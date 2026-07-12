# Drive Camera Stream Design

## Goal

Add a live view from the Astra camera connected to the Jetson to the Android
drive page. The first version provides a stable camera preview only. It does
not run object detection, color tracking, or other vision workloads at the
same time.

## User Experience

### Portrait drive page

The existing drive page keeps its current light and dark themes. Its content
order becomes:

1. Drive page title and connection state.
2. A large 16:9 live camera window.
3. Speed, current motion, and control latency metrics.
4. Speed limits, explicit direction buttons, chassis start, and emergency
   stop controls.

The camera window displays one of four states: connecting, live with measured
FPS, camera busy, or disconnected with a retry action. Video failure never
disables the movement or emergency-stop controls.

### Full-screen drive mode

Tapping the full-screen icon switches the activity to immersive landscape
mode and fills the screen with video. The center of the image remains clear.

- Translation controls float along the left edge.
- Rotation and stop controls float along the right edge.
- A compact speed-limit control sits at the bottom center.
- Live state, speed, current motion, latency, and exit are shown in a minimal
  top HUD.
- Direction controls use low-opacity glass styling with no outer group panel.
- Pressed controls temporarily increase brightness and border contrast.
- Stop remains translucent red so it is easy to locate.

Exiting full screen restores portrait orientation and the standard drive
page without restarting the entire application.

## Video Architecture

The video source is always the Astra camera physically connected to the
Jetson. The phone camera and the development PC are not part of the stream.

```text
Jetson Astra camera
  -> one local capture thread
  -> resize and JPEG encode latest frame
  -> HTTP multipart MJPEG endpoint
  -> Android MJPEG stream view
```

The Jetson HTTP service owns one shared camera capture service. It opens the
camera lazily when the first video client connects and releases it after the
last client disconnects. All connected clients receive the latest encoded
frame rather than opening the camera independently.

Initial stream settings are 640x480, 15-20 FPS, and JPEG quality 70. These
settings prioritize useful driving visibility and predictable latency over
recording quality.

## Camera Selection

The server accepts an explicit camera device argument for reliable deployment.
When no device is configured, it probes local `/dev/video*` devices and selects
the first capture-capable device that returns a valid color frame. Failed
devices are skipped and included in diagnostics.

The existing Rosmaster desktop APP and camera-based ROS experiments may hold
the camera exclusively. Users must close those programs before opening the
Android live view. The Android page reports this condition as `camera busy`
instead of hiding it behind a generic connection error.

## HTTP Interface

The existing control API remains unchanged. The service adds:

- `GET /camera/stream`: multipart MJPEG stream.
- `GET /camera/status`: camera device, state, clients, measured FPS, frame
  dimensions, and latest error.
- `POST` or `GET /camera/restart`: release and reopen the configured camera.

The stream response disables caching. A camera-open failure returns HTTP 503
with a concise error body. The status and restart endpoints remain available
when streaming fails.

## Android Components

`CarApi` builds camera status, stream, and restart URLs from the same host and
port used by movement control.

A dedicated `MjpegStreamView` owns the stream connection and frame decoder. It
runs network reads and bitmap decoding off the main thread, renders with
center-crop scaling, reports FPS and state changes, and closes its connection
when stopped.

The activity starts the stream when the drive page is visible and resumed. It
stops the stream on page changes, app pause, and activity destruction. Full
screen reparents the same live view rather than opening a second camera stream.

## Failure Handling

- Initial connection failures show a retry action.
- Unexpected disconnects retry after 1, 2, and 4 seconds, capped at 5 seconds.
- Camera busy and camera missing are displayed as distinct states.
- Malformed JPEG frames are dropped without stopping movement controls.
- Leaving the page cancels pending reconnects.
- The existing 350 ms motion watchdog remains independent of video state.

## Verification

Automated tests cover camera URL construction, multipart frame parsing,
camera-state mapping, capture-service client accounting, camera release, and
failure responses. Existing movement and safety tests must remain green.

Jetson verification checks that the selected local device returns frames,
`/camera/status` reports the correct dimensions and FPS, and the stream stops
cleanly when the Android client disconnects.

Device verification covers portrait layout in both themes, full-screen
landscape transitions, controls while video is unavailable, retry behavior,
frame continuity during movement, and release of the camera after leaving the
drive page. The first-version target is 15 FPS or better and less than 300 ms
camera-to-screen latency on the shared hotspot.

## Out Of Scope

- Object detection overlays and confidence labels.
- Sharing the camera concurrently with color tracking or model inference.
- Video recording, cloud relay, audio, WebRTC, and operation outside the local
  network.
