# Drive Camera Stream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show the Jetson-local Astra camera in the Android drive page, with a video-first portrait layout and immersive landscape glass controls.

**Architecture:** A lazily opened Jetson capture service shares the latest JPEG frame through an MJPEG endpoint. Android reads the multipart stream on a worker thread, renders frames in a lifecycle-aware custom view, and reparents that view into a landscape full-screen overlay so the camera is opened only once.

**Tech Stack:** Python 3.8, OpenCV/V4L2, `ThreadingHTTPServer`, Kotlin, Android Views, `HttpURLConnection`, JUnit 4, Python `unittest`.

---

## File Map

- Create `jetson_server/camera_stream.py`: camera probing, capture lifecycle, JPEG encoding, frame fan-out, and status snapshots.
- Create `jetson_server/test_camera_stream.py`: pure service tests with fake capture and encoder dependencies.
- Modify `jetson_server/server.py`: camera CLI options and HTTP status, restart, and MJPEG stream endpoints.
- Modify `jetson_server/test_server_config.py`: route and stream-response regression tests.
- Create `app/src/main/java/com/example/icarcontroller/MjpegFrameReader.kt`: dependency-free multipart JPEG frame parser.
- Create `app/src/test/java/com/example/icarcontroller/MjpegFrameReaderTest.java`: parser tests.
- Create `app/src/main/java/com/example/icarcontroller/MjpegStreamView.kt`: lifecycle-aware network reader and bitmap renderer.
- Create `app/src/main/java/com/example/icarcontroller/DriveCameraPanel.kt`: portrait camera surface and camera-state overlay.
- Create `app/src/main/java/com/example/icarcontroller/FullscreenDriveOverlay.kt`: landscape HUD and transparent glass controls.
- Modify `app/src/main/java/com/example/icarcontroller/CarApi.kt`: camera URLs.
- Modify `app/src/test/java/com/example/icarcontroller/CarApiTest.java`: camera URL tests.
- Modify `app/src/main/java/com/example/icarcontroller/InteractionSpec.kt`: stream and layout constants.
- Modify `app/src/test/java/com/example/icarcontroller/InteractionSpecTest.java`: camera interaction requirements.
- Modify `app/src/main/java/com/example/icarcontroller/MainActivity.kt`: drive-page integration and lifecycle.
- Modify `app/src/main/AndroidManifest.xml`: preserve the activity across orientation changes.
- Modify `jetson_server/README.md` and root `README.md`: deployment and camera-conflict guidance.

### Task 1: Jetson Camera Capture Core

**Files:**
- Create: `jetson_server/camera_stream.py`
- Create: `jetson_server/test_camera_stream.py`

- [ ] **Step 1: Write failing tests for device probing, client accounting, and frame publication**

```python
class FakeCapture:
    def __init__(self, opened=True, frames=None):
        self.opened = opened
        self.frames = iter(frames or [])
        self.released = False

    def isOpened(self):
        return self.opened

    def read(self):
        try:
            return True, next(self.frames)
        except StopIteration:
            return False, None

    def release(self):
        self.released = True


def test_explicit_camera_device_is_tried_first():
    attempts = []
    service = CameraCaptureService(
        configured_device="/dev/video13",
        device_candidates=lambda: ["/dev/video0", "/dev/video13"],
        capture_factory=lambda path: attempts.append(path) or FakeCapture(opened=False),
        jpeg_encoder=lambda frame, quality: b"jpeg",
    )
    service.open_camera()
    assert attempts[0] == "/dev/video13"


def test_last_client_releases_camera():
    capture = FakeCapture(opened=True)
    service = CameraCaptureService(
        capture_factory=lambda _path: capture,
        jpeg_encoder=lambda frame, quality: b"jpeg",
    )
    service.acquire_client()
    service.release_client()
    assert capture.released
    assert service.status()["clients"] == 0


def test_wait_for_frame_returns_only_new_sequence():
    service = CameraCaptureService(jpeg_encoder=lambda frame, quality: b"jpeg")
    service.publish_encoded_frame(b"first", width=640, height=480, captured_at=10.0)
    sequence, frame = service.wait_for_frame(after_sequence=0, timeout=0.01)
    assert sequence == 1
    assert frame == b"first"
```

- [ ] **Step 2: Run tests and verify RED**

Run: `python -m unittest jetson_server.test_camera_stream -v`

Expected: import failure because `jetson_server.camera_stream` does not exist.

- [ ] **Step 3: Implement the capture service with injected dependencies**

Implement this status value in `camera_stream.py`:

```python
@dataclass(frozen=True)
class CameraSnapshot:
    state: str
    device: Optional[str]
    clients: int
    fps: float
    width: int
    height: int
    sequence: int
    error: Optional[str]


```

Implement `CameraCaptureService.__init__` with the exact arguments used in the
tests: `configured_device`, `width`, `height`, `fps`, `jpeg_quality`,
`device_candidates`, `capture_factory`, `jpeg_encoder`, and `clock`. Expose
`acquire_client()`, `release_client()`, `restart()`, `status()`,
`wait_until_ready(timeout)`, `wait_for_frame(after_sequence, timeout)`, and the
test seam `publish_encoded_frame(frame, width, height, captured_at)`.

Use a `threading.Condition` to protect client count, sequence, latest frame,
status, and shutdown state. `acquire_client()` increments the count and starts
one daemon capture thread when the previous count was zero. `release_client()`
decrements without going below zero; the zero transition requests capture
shutdown, joins the thread for at most one second, releases the capture, and
sets state to `idle`. `restart()` performs the same shutdown and starts capture
again only when clients are present. `wait_for_frame()` blocks until sequence
is greater than `after_sequence`, returns `(sequence, bytes)`, and returns
`(after_sequence, None)` on timeout.

Import `cv2` only inside default capture/encoder functions so unit tests run on
the Windows development machine without OpenCV. Probe the configured device
first, then sorted `/dev/video*` candidates. Set state to `busy` when a device
opens but cannot return a frame and `missing` when no candidate opens. A valid
frame transitions to `live`; five consecutive read failures transition to
`disconnected` and end the capture thread.

- [ ] **Step 4: Run camera core tests and verify GREEN**

Run: `python -m unittest jetson_server.test_camera_stream -v`

Expected: all camera service tests pass without camera hardware.

- [ ] **Step 5: Commit the camera core**

```bash
git add jetson_server/camera_stream.py jetson_server/test_camera_stream.py
git commit -m "feat: add shared Jetson camera capture service"
```

### Task 2: MJPEG HTTP Endpoints

**Files:**
- Modify: `jetson_server/server.py`
- Modify: `jetson_server/test_server_config.py`

- [ ] **Step 1: Write failing route and stream tests**

Add a fake camera service that records `acquire_client`, `release_client`, and
`restart` calls. Assert:

```python
def test_camera_status_route_returns_capture_snapshot(self):
    server.CAMERA_STREAM = FakeCameraService(state="live")
    status, body = self.handler.route("camera/status", {})
    self.assertEqual(200, status)
    self.assertEqual("live", body["state"])


def test_camera_restart_route_reopens_local_device(self):
    camera = FakeCameraService(state="busy")
    server.CAMERA_STREAM = camera
    status, body = self.handler.route("camera/restart", {})
    self.assertEqual(200, status)
    self.assertEqual(1, camera.restart_calls)


def test_mjpeg_chunk_has_boundary_length_and_jpeg(self):
    chunk = server.build_mjpeg_chunk(b"\xff\xd8frame\xff\xd9")
    self.assertIn(b"--frame\r\n", chunk)
    self.assertIn(b"Content-Type: image/jpeg\r\n", chunk)
    self.assertIn(b"Content-Length: 9\r\n", chunk)
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: `python -m unittest test_server_config.MotionRouteTest.test_camera_status_route_returns_capture_snapshot test_server_config.CameraStreamHttpTest -v`

Expected: failures for missing `CAMERA_STREAM` and `build_mjpeg_chunk`.

- [ ] **Step 3: Add camera initialization and endpoints**

Add CLI options:

```python
parser.add_argument("--camera-device", default=None)
parser.add_argument("--camera-width", type=int, default=640)
parser.add_argument("--camera-height", type=int, default=480)
parser.add_argument("--camera-fps", type=int, default=18)
parser.add_argument("--camera-quality", type=int, default=70)
```

Create `CAMERA_STREAM` in `main()`. Add `/camera/status` and
`/camera/restart` to `route()`. Handle `/camera/stream` before normal JSON
routing in `do_GET()` because it owns a long-lived multipart response.

`stream_camera()` must:

1. Acquire one camera client.
2. Wait up to 2 seconds for a valid frame.
3. Return 503 JSON when state is busy or missing.
4. Send `multipart/x-mixed-replace; boundary=frame`, `Cache-Control: no-store`,
   and `Connection: close` headers.
5. Write only newly sequenced frames.
6. Treat `BrokenPipeError` and `ConnectionResetError` as normal disconnects.
7. Release the camera client in `finally`.

- [ ] **Step 4: Run all Python tests**

Run:

```powershell
python -m unittest test_server_config -v
cd ..
python -m unittest jetson_server.test_motion_bridge jetson_server.test_camera_stream -v
```

Expected: all existing motion tests and new camera tests pass.

- [ ] **Step 5: Commit HTTP camera support**

```bash
git add jetson_server/server.py jetson_server/test_server_config.py
git commit -m "feat: expose Jetson MJPEG camera endpoints"
```

### Task 3: Android Camera URLs and MJPEG Parser

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/CarApi.kt`
- Modify: `app/src/test/java/com/example/icarcontroller/CarApiTest.java`
- Create: `app/src/main/java/com/example/icarcontroller/MjpegFrameReader.kt`
- Create: `app/src/test/java/com/example/icarcontroller/MjpegFrameReaderTest.java`

- [ ] **Step 1: Write failing URL and parser tests**

```java
@Test public void cameraUrlsUseTheConfiguredJetson() {
    CarApi api = new CarApi("10.161.57.230", 8000);
    assertEquals("http://10.161.57.230:8000/camera/stream", api.cameraStreamUrl());
    assertEquals("http://10.161.57.230:8000/camera/status", api.cameraStatusUrl());
    assertEquals("http://10.161.57.230:8000/camera/restart", api.cameraRestartUrl());
}

@Test public void readerExtractsConsecutiveJpegFrames() throws Exception {
    byte[] first = new byte[] {(byte)0xff, (byte)0xd8, 1, (byte)0xff, (byte)0xd9};
    byte[] second = new byte[] {(byte)0xff, (byte)0xd8, 2, (byte)0xff, (byte)0xd9};
    byte[] stream = concat("noise".getBytes(), first, "boundary".getBytes(), second);
    MjpegFrameReader reader = new MjpegFrameReader(new ByteArrayInputStream(stream), 2_000_000);
    assertArrayEquals(first, reader.nextFrame());
    assertArrayEquals(second, reader.nextFrame());
}
```

Also test EOF, bytes before SOI, and rejection of frames larger than the
configured maximum.

- [ ] **Step 2: Run targeted Android tests and verify RED**

Run:

```powershell
& 'E:\android-tools\gradle-8.7\bin\gradle.bat' testDebugUnitTest --tests '*CarApiTest' --tests '*MjpegFrameReaderTest' --no-daemon --console=plain
```

Expected: compilation failures for missing camera URL methods and reader.

- [ ] **Step 3: Implement URL methods and a bounded SOI/EOI parser**

Add to `CarApi`:

```kotlin
fun cameraStreamUrl(): String = "$baseUrl/camera/stream"
fun cameraStatusUrl(): String = "$baseUrl/camera/status"
fun cameraRestartUrl(): String = "$baseUrl/camera/restart"
```

`MjpegFrameReader.nextFrame()` scans for JPEG SOI `FF D8`, writes bytes into a
bounded `ByteArrayOutputStream`, and returns when it sees EOI `FF D9`. It
returns `null` on clean EOF and throws `IOException` when a frame exceeds the
limit. It must not depend on Android classes so local JVM tests cover it.

- [ ] **Step 4: Run targeted tests and verify GREEN**

Run the same Gradle command. Expected: all targeted tests pass.

- [ ] **Step 5: Commit parser and URL support**

```bash
git add app/src/main/java/com/example/icarcontroller/CarApi.kt app/src/main/java/com/example/icarcontroller/MjpegFrameReader.kt app/src/test/java/com/example/icarcontroller/CarApiTest.java app/src/test/java/com/example/icarcontroller/MjpegFrameReaderTest.java
git commit -m "feat: add Android MJPEG stream parser"
```

### Task 4: Lifecycle-Aware Android Stream View

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/MjpegStreamView.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/InteractionSpec.kt`
- Modify: `app/src/test/java/com/example/icarcontroller/InteractionSpecTest.java`

- [ ] **Step 1: Add failing interaction specification tests**

```java
@Test public void cameraStreamUsesBoundedProductSettings() {
    assertEquals(2_000_000, requiredSpec("cameraMaxFrameBytes"));
    assertEquals(640, requiredSpec("cameraTargetWidth"));
    assertEquals(480, requiredSpec("cameraTargetHeight"));
    assertEquals(300, requiredSpec("cameraLatencyTargetMillis"));
    assertArrayEquals(new int[] {1000, 2000, 4000, 5000},
        (int[]) requiredSpec("cameraReconnectDelaysMillis"));
}
```

- [ ] **Step 2: Run the interaction test and verify RED**

Run: `gradle testDebugUnitTest --tests '*InteractionSpecTest'`

Expected: missing camera specification methods.

- [ ] **Step 3: Implement `MjpegStreamView`**

The view owns one single-thread executor and exposes:

```kotlin
enum class CameraViewState { IDLE, CONNECTING, LIVE, BUSY, MISSING, DISCONNECTED }

data class CameraViewSnapshot(
    val state: CameraViewState,
    val fps: Int = 0,
    val error: String? = null
)

fun start(url: String, listener: (CameraViewSnapshot) -> Unit)
fun stop()
fun reconnect()
```

Use `HttpURLConnection` with 2-second connect timeout and 5-second read
timeout. Decode each frame using `BitmapFactory.decodeByteArray`, atomically
replace the displayed bitmap, and draw center-cropped in `onDraw`. Post state
callbacks and invalidation to the main thread. Retry only while `start()` is
active, using the reconnect delays from `InteractionSpec`. `stop()` disconnects
the current connection, cancels retries, clears the bitmap, and prevents stale
worker callbacks from changing state.

- [ ] **Step 4: Run unit tests and compile the app**

Run: `gradle testDebugUnitTest assembleDebug --no-daemon --console=plain`

Expected: tests pass and the new Android view compiles.

- [ ] **Step 5: Commit the stream view**

```bash
git add app/src/main/java/com/example/icarcontroller/MjpegStreamView.kt app/src/main/java/com/example/icarcontroller/InteractionSpec.kt app/src/test/java/com/example/icarcontroller/InteractionSpecTest.java
git commit -m "feat: add lifecycle-aware MJPEG view"
```

### Task 5: Portrait Video-First Drive Page

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/DriveCameraPanel.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`

- [ ] **Step 1: Add a testable camera-state presentation mapping**

Put a pure companion mapping in `DriveCameraPanel` or a separate top-level
object and test these exact strings and retry visibility:

```kotlin
fun cameraStatePresentation(snapshot: CameraViewSnapshot): CameraPresentation = when (snapshot.state) {
    CameraViewState.CONNECTING -> CameraPresentation("正在连接", false)
    CameraViewState.LIVE -> CameraPresentation("实时 · ${snapshot.fps} FPS", false)
    CameraViewState.BUSY -> CameraPresentation("摄像头正在被其他任务使用", true)
    CameraViewState.MISSING -> CameraPresentation("未检测到小车摄像头", true)
    CameraViewState.DISCONNECTED -> CameraPresentation("连接中断", true)
    CameraViewState.IDLE -> CameraPresentation("视频未启动", true)
}
```

- [ ] **Step 2: Verify the presentation test fails before implementation**

Run: `gradle testDebugUnitTest --tests '*DriveCameraPanelTest'`

Expected: missing presentation mapping.

- [ ] **Step 3: Build the portrait panel and integrate it above telemetry**

`DriveCameraPanel` is a `FrameLayout` with a stable 16:9 height, an
`MjpegStreamView`, a top-left status glass chip, top-right full-screen icon,
and centered error/retry state. It receives palette colors and callbacks
instead of directly controlling the activity.

In `MainActivity.renderDrive()` insert the panel immediately after the page
header and before `parkingDriveStatus()`. Store it in a field and call:

```kotlin
driveCameraPanel?.start(api().cameraStreamUrl())
driveCameraPanel?.stop()
```

Stop it from `renderPage()` before removing views, `onPause()`, and
`onDestroy()`. Restart it from `onResume()` only when `selectedPage == "drive"`.

- [ ] **Step 4: Build and install the portrait version**

Run:

```powershell
& 'E:\android-tools\gradle-8.7\bin\gradle.bat' testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: build succeeds and the drive page shows deterministic loading/error
states even before the Jetson endpoint is deployed.

- [ ] **Step 5: Commit portrait integration**

```bash
git add app/src/main/java/com/example/icarcontroller/DriveCameraPanel.kt app/src/main/java/com/example/icarcontroller/MainActivity.kt app/src/test/java/com/example/icarcontroller/DriveCameraPanelTest.java
git commit -m "feat: add live video to portrait drive page"
```

### Task 6: Immersive Landscape Glass Controls

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/FullscreenDriveOverlay.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Add pure layout-contract tests**

Extend `InteractionSpec` tests to require landscape mode, no group panel, and
low glass opacity:

```java
assertEquals("landscape", requiredSpec("cameraFullscreenOrientation"));
assertEquals("edge_floating", requiredSpec("cameraFullscreenControlLayout"));
assertFalse((Boolean) requiredSpec("cameraFullscreenUsesControlPanels"));
assertTrue((Float) requiredSpec("cameraGlassButtonAlpha") <= 0.18f);
```

- [ ] **Step 2: Run the contract test and verify RED**

Run: `gradle testDebugUnitTest --tests '*InteractionSpecTest'`

- [ ] **Step 3: Implement full-screen overlay and safe control callbacks**

`FullscreenDriveOverlay` fills the decor view. It accepts the existing
`MjpegStreamView`, callbacks for each direction, stop, speed changes, and exit,
plus current speed/action/latency providers. It reparents the stream view into
the full-screen container and restores it to `DriveCameraPanel` on exit.

Use fixed-size transparent edge buttons with familiar arrows. Keep the center
of the camera unobstructed. Apply only a translucent red fill to stop. Direction
buttons use the same press-and-hold behavior as portrait controls: `ACTION_DOWN`
starts the repeating movement command and `ACTION_UP`/`ACTION_CANCEL` sends
stop. Exiting full screen calls `forceStopForExit` before changing orientation.

Set:

```xml
android:configChanges="orientation|screenSize"
```

on `MainActivity`. Entering sets `requestedOrientation` to landscape and hides
status/navigation bars; exit restores portrait and system UI.

- [ ] **Step 4: Verify build and run orientation safety tests**

Run: `gradle testDebugUnitTest lintDebug assembleDebug --no-daemon --console=plain`

Expected: no lifecycle or manifest lint errors.

- [ ] **Step 5: Commit full-screen driving**

```bash
git add app/src/main/java/com/example/icarcontroller/FullscreenDriveOverlay.kt app/src/main/java/com/example/icarcontroller/MainActivity.kt app/src/main/AndroidManifest.xml app/src/main/java/com/example/icarcontroller/InteractionSpec.kt app/src/test/java/com/example/icarcontroller/InteractionSpecTest.java
git commit -m "feat: add immersive glass drive controls"
```

### Task 7: Documentation, Deployment, and End-to-End Verification

**Files:**
- Modify: `jetson_server/README.md`
- Modify: `README.md`

- [ ] **Step 1: Document the local-camera requirement and startup options**

Document this production command:

```bash
python3 -u server.py \
  --container 8b98 \
  --host 0.0.0.0 \
  --port 8000 \
  --camera-device /dev/video13 \
  --camera-width 640 \
  --camera-height 480 \
  --camera-fps 18 \
  --camera-quality 70
```

State that Rosmaster APP and camera ROS experiments must be closed before the
Android preview opens.

- [ ] **Step 2: Run all automated verification**

Run:

```powershell
python -m unittest test_server_config -v
cd ..
python -m unittest jetson_server.test_motion_bridge jetson_server.test_camera_stream -v
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME='E:\android-gradle-cache'
& 'E:\android-tools\gradle-8.7\bin\gradle.bat' testDebugUnitTest lintDebug assembleDebug --rerun-tasks --no-daemon --console=plain
git diff --check
```

Expected: all commands exit 0.

The existing `MotionWatchdogTest.test_watchdog_expires_once_after_motion_refresh_stops`
must remain green, preserving the 350 ms automatic stop independently of
camera connection, decoding, orientation changes, and full-screen mode.

- [ ] **Step 3: Identify and validate the Jetson-local capture device**

On the Jetson host run:

```bash
v4l2-ctl --list-devices
python3 - <<'PY'
import cv2, glob
for path in sorted(glob.glob('/dev/video*')):
    cap = cv2.VideoCapture(path, cv2.CAP_V4L2)
    ok, frame = cap.read()
    print(path, 'opened=', cap.isOpened(), 'frame=', ok,
          None if frame is None else frame.shape)
    cap.release()
PY
```

Expected: at least one local `/dev/video*` returns a non-empty color frame.

- [ ] **Step 4: Deploy the Jetson files and verify MJPEG**

Back up and replace `server.py`, `camera_stream.py`, and `motion_bridge.py`,
then restart the service with the validated device. Verify:

```bash
curl http://127.0.0.1:8000/camera/status
timeout 3 curl -s http://127.0.0.1:8000/camera/stream -o /tmp/camera.mjpeg
test -s /tmp/camera.mjpeg
```

Expected: status is `live`, dimensions are 640x480, FPS reaches at least 15,
and the captured stream file is non-empty.

- [ ] **Step 5: Install and verify on the connected phone**

Install the APK, open the drive page, and verify both themes. Capture portrait
and landscape screenshots with `adb exec-out screencap -p`. Confirm:

- Video appears before movement testing.
- Portrait controls remain below the video and do not resize while frames load.
- Full screen rotates to landscape and the center view is unobstructed.
- Glass buttons move the car with the existing low-latency behavior.
- Releasing any button stops the car promptly.
- Closing Rosmaster resolves `camera busy` after retry.
- Leaving the drive page reduces `/camera/status` clients to zero.
- Camera-to-screen latency is below 300 ms and frame rate is at least 15 FPS.

- [ ] **Step 6: Commit final documentation after device verification**

```bash
git add README.md jetson_server/README.md
git commit -m "docs: document live drive camera deployment"
```

- [ ] **Step 7: Do not push until the user confirms the real camera and controls**

After confirmation, push the complete commit series to `origin/main` and
record measured FPS, video latency, movement latency, selected camera device,
and the final commit hash in the completion response.
