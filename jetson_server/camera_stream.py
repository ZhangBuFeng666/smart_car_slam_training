#!/usr/bin/env python3
from dataclasses import asdict, dataclass
import glob
import threading
import time
from typing import Iterable, Optional


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


def _default_device_candidates() -> Iterable[str]:
    return glob.glob("/dev/video*")


def _default_capture_factory(device):
    import cv2

    return cv2.VideoCapture(device)


def _default_jpeg_encoder(frame, quality):
    import cv2

    success, encoded = cv2.imencode(
        ".jpg", frame, [int(cv2.IMWRITE_JPEG_QUALITY), int(quality)]
    )
    if not success:
        raise RuntimeError("JPEG encoding failed")
    return encoded.tobytes()


class _CaptureRelease:
    def __init__(self, capture):
        self.capture = capture
        self._lock = threading.Lock()
        self._claimed = False
        self._completed = threading.Event()

    def release_once(self):
        with self._lock:
            if self._claimed:
                return False
            self._claimed = True
        try:
            self.capture.release()
        except Exception:
            pass
        finally:
            self._completed.set()
        return True

    def wait(self, timeout=None):
        return self._completed.wait(timeout)


class CameraCaptureService:
    _READY_STATES = frozenset(("live", "missing", "busy", "disconnected"))
    _CAP_PROP_FRAME_WIDTH = 3
    _CAP_PROP_FRAME_HEIGHT = 4
    _CAP_PROP_FPS = 5
    _RELEASE_WAIT_SECONDS = 0.05
    _CAPTURE_JOIN_SECONDS = 1.0

    def __init__(
        self,
        configured_device=None,
        width=640,
        height=480,
        fps=18,
        jpeg_quality=70,
        device_candidates=_default_device_candidates,
        capture_factory=_default_capture_factory,
        jpeg_encoder=_default_jpeg_encoder,
        clock=time.monotonic,
    ):
        self.configured_device = configured_device
        self.requested_width = int(width)
        self.requested_height = int(height)
        self.width = self.requested_width
        self.height = self.requested_height
        self.target_fps = int(fps)
        self.jpeg_quality = int(jpeg_quality)
        self.device_candidates = device_candidates
        self.capture_factory = capture_factory
        self.jpeg_encoder = jpeg_encoder
        self.clock = clock

        self._lifecycle_lock = threading.Lock()
        self._condition = threading.Condition()
        self._clients = 0
        self._latest_frame = None
        self._sequence = 0
        self._state = "idle"
        self._device = None
        self._measured_fps = 0.0
        self._error = None
        self._last_captured_at = None
        self._capture = None
        self._capture_generation = None
        self._capture_release = None
        self._thread = None
        self._reaper_thread = None
        self._stop_requested = False
        self._closed = False
        self._generation = 0

    def acquire_client(self):
        with self._lifecycle_lock:
            with self._condition:
                if self._closed:
                    return self._status_locked()
                previous_clients = self._clients
                self._clients += 1
                if previous_clients == 0:
                    capture_stopping = (
                        self._thread is not None and self._thread.is_alive()
                    ) or (
                        self._reaper_thread is not None
                        and self._reaper_thread.is_alive()
                    )
                    if capture_stopping:
                        self._state = "stopping"
                    else:
                        self._thread = None
                        self._start_capture_locked()
                return self._status_locked()

    def release_client(self):
        with self._lifecycle_lock:
            with self._condition:
                if self._clients == 0:
                    return self._status_locked()
                self._clients -= 1
                should_stop = self._clients == 0

            if should_stop:
                self._shutdown_capture()
            return self.status()

    def restart(self):
        with self._lifecycle_lock:
            with self._condition:
                if self._closed:
                    return self._status_locked()
                has_clients = self._clients > 0
            stopped = self._shutdown_capture()
            if has_clients and stopped:
                with self._condition:
                    self._start_capture_locked()
            return self.status()

    def shutdown(self):
        with self._lifecycle_lock:
            with self._condition:
                self._closed = True
                self._clients = 0
            self._shutdown_capture()
            return self.status()

    def status(self):
        with self._condition:
            return self._status_locked()

    def wait_until_ready(self, timeout):
        deadline = time.monotonic() + max(0.0, float(timeout))
        with self._condition:
            while self._state not in self._READY_STATES:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self._condition.wait(remaining)
            return self._state == "live"

    def wait_for_frame(self, after_sequence, timeout):
        deadline = time.monotonic() + max(0.0, float(timeout))
        with self._condition:
            while self._latest_frame is None or self._sequence <= after_sequence:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return after_sequence, None
                self._condition.wait(remaining)
            return self._sequence, self._latest_frame

    def publish_encoded_frame(self, frame, width, height, captured_at):
        return self._publish_encoded_frame(frame, width, height, captured_at)

    def _publish_encoded_frame(
        self, frame, width, height, captured_at, generation=None
    ):
        with self._condition:
            if self._closed:
                return None
            if generation is not None and (
                self._generation != generation or self._stop_requested
            ):
                return None
            if self._last_captured_at is not None and captured_at > self._last_captured_at:
                self._measured_fps = 1.0 / (captured_at - self._last_captured_at)
            self._last_captured_at = captured_at
            self._latest_frame = bytes(frame)
            self._sequence += 1
            self._state = "live"
            self.width = int(width)
            self.height = int(height)
            self._error = None
            self._condition.notify_all()
            return self._sequence

    def _start_capture_locked(self):
        self._generation += 1
        generation = self._generation
        self._stop_requested = False
        self._state = "connecting"
        self._device = None
        self._error = None
        self._clear_frame_visibility_locked()
        thread = threading.Thread(
            target=self._capture_loop,
            args=(generation,),
            name="camera-capture",
            daemon=True,
        )
        self._thread = thread
        thread.start()
        self._condition.notify_all()

    def _shutdown_capture(self):
        with self._condition:
            self._generation += 1
            self._stop_requested = True
            thread = self._thread
            capture = self._capture
            capture_release = self._capture_release
            self._clear_frame_visibility_locked()
            self._condition.notify_all()

        release_complete = self._request_capture_release(capture_release)
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=self._CAPTURE_JOIN_SECONDS)

        with self._condition:
            if (
                thread is not None and thread.is_alive()
            ) or not release_complete:
                self._state = "stopping"
                self._error = "camera capture thread is still stopping"
                self._start_reaper_locked(thread, capture_release)
                self._condition.notify_all()
                return False
            if self._thread is thread:
                self._thread = None
            if self._capture is capture:
                self._capture = None
                self._capture_generation = None
                self._capture_release = None
            self._set_idle_locked()
            self._condition.notify_all()
            return True

    def _capture_loop(self, generation):
        capture = None
        capture_release = None
        try:
            capture, device = self._open_capture(generation)
            if capture is None:
                return
            capture_release = _CaptureRelease(capture)
            if not self._set_active_capture(
                generation, capture, capture_release, device
            ):
                return

            self._configure_capture(capture)
            success, frame = capture.read()
            if not success or frame is None:
                self._set_state(
                    generation,
                    "busy",
                    (
                        f"camera {device} opened but did not return a frame; "
                        "close other camera applications and check permissions"
                    ),
                )
                return

            if not self._encode_and_publish(generation, frame):
                return

            consecutive_failures = 0
            while not self._should_stop(generation):
                success, frame = capture.read()
                if success and frame is not None:
                    consecutive_failures = 0
                    if not self._encode_and_publish(generation, frame):
                        return
                    continue

                consecutive_failures += 1
                if consecutive_failures >= 5:
                    self._set_state(
                        generation,
                        "disconnected",
                        "camera disconnected after five consecutive read failures",
                    )
                    return
                with self._condition:
                    if self._generation == generation and not self._stop_requested:
                        self._condition.wait(0.01)
        except Exception as error:
            self._set_state(generation, "disconnected", str(error))
        finally:
            if capture_release is not None:
                capture_release.release_once()
            with self._condition:
                if (
                    self._capture is capture
                    and self._capture_generation == generation
                ):
                    self._capture = None
                    self._capture_generation = None
                    self._capture_release = None
                self._condition.notify_all()

    def _open_capture(self, generation):
        for device in self._ordered_devices():
            if self._should_stop(generation):
                return None, None
            try:
                capture = self.capture_factory(device)
            except Exception as error:
                state = self._classify_open_error(error)
                if state == "missing":
                    continue
                self._set_state(
                    generation,
                    state,
                    self._open_error_message(device, error, state),
                )
                return None, None
            try:
                if capture is not None and capture.isOpened():
                    return capture, device
            except Exception as error:
                self._release_capture(capture)
                state = self._classify_open_error(error)
                if state == "missing":
                    continue
                self._set_state(
                    generation,
                    state,
                    self._open_error_message(device, error, state),
                )
                return None, None
            self._release_capture(capture)

        self._set_state(generation, "missing", "no camera device could be opened")
        return None, None

    def _ordered_devices(self):
        configured = [] if self.configured_device is None else [self.configured_device]
        candidates = sorted(set(self.device_candidates()))
        return configured + [device for device in candidates if device not in configured]

    def _set_active_capture(self, generation, capture, capture_release, device):
        with self._condition:
            if self._generation != generation or self._stop_requested:
                return False
            self._capture = capture
            self._capture_generation = generation
            self._capture_release = capture_release
            self._device = str(device)
            return True

    def _configure_capture(self, capture):
        setter = getattr(capture, "set", None)
        if setter is None:
            return
        for property_id, value in (
            (self._CAP_PROP_FRAME_WIDTH, self.requested_width),
            (self._CAP_PROP_FRAME_HEIGHT, self.requested_height),
            (self._CAP_PROP_FPS, self.target_fps),
        ):
            try:
                setter(property_id, value)
            except Exception:
                pass

    def _encode_and_publish(self, generation, frame):
        try:
            encoded = self.jpeg_encoder(frame, self.jpeg_quality)
        except Exception as error:
            self._set_state(generation, "disconnected", str(error))
            return False
        if self._should_stop(generation):
            return False
        width, height = self._frame_dimensions(frame)
        sequence = self._publish_encoded_frame(
            encoded, width, height, self.clock(), generation=generation
        )
        return sequence is not None

    def _frame_dimensions(self, frame):
        shape = getattr(frame, "shape", None)
        if shape is not None and len(shape) >= 2:
            return int(shape[1]), int(shape[0])
        return self.width, self.height

    def _start_reaper_locked(self, thread, capture_release):
        if self._reaper_thread is not None and self._reaper_thread.is_alive():
            return
        reaper = threading.Thread(
            target=self._reap_capture_thread,
            args=(thread, capture_release),
            name="camera-capture-reaper",
            daemon=True,
        )
        self._reaper_thread = reaper
        reaper.start()

    def _reap_capture_thread(self, capture_thread, capture_release):
        if capture_thread is not None:
            capture_thread.join()
        if capture_release is not None:
            capture_release.wait()
        with self._lifecycle_lock:
            with self._condition:
                if self._reaper_thread is threading.current_thread():
                    self._reaper_thread = None
                if self._thread is not capture_thread:
                    return
                self._thread = None
                if self._clients > 0 and not self._closed:
                    self._start_capture_locked()
                    return
                self._set_idle_locked()
                self._condition.notify_all()

    def _clear_frame_visibility_locked(self):
        self._latest_frame = None
        self._measured_fps = 0.0
        self._last_captured_at = None
        self.width = self.requested_width
        self.height = self.requested_height

    def _set_idle_locked(self):
        self._state = "idle"
        self._device = None
        self._error = None
        self._clear_frame_visibility_locked()

    @staticmethod
    def _classify_open_error(error):
        if isinstance(error, FileNotFoundError):
            return "missing"
        message = str(error).lower()
        if isinstance(error, PermissionError) or any(
            marker in message
            for marker in ("busy", "resource", "permission", "access denied")
        ):
            return "busy"
        return "disconnected"

    @staticmethod
    def _open_error_message(device, error, state):
        message = f"camera {device} could not be opened: {error}"
        if state == "busy":
            return f"{message}; close other camera applications and check permissions"
        return message

    def _request_capture_release(self, capture_release):
        if capture_release is None:
            return True
        if capture_release.wait(0):
            return True

        def release_capture():
            capture_release.release_once()

        release_thread = threading.Thread(
            target=release_capture,
            name="camera-release",
            daemon=True,
        )
        release_thread.start()
        return capture_release.wait(self._RELEASE_WAIT_SECONDS)

    def _should_stop(self, generation):
        with self._condition:
            return self._generation != generation or self._stop_requested

    def _set_state(self, generation, state, error):
        with self._condition:
            if self._generation != generation or self._stop_requested:
                return
            self._state = state
            self._error = error
            self._condition.notify_all()

    def _snapshot_locked(self):
        return CameraSnapshot(
            state=self._state,
            device=self._device,
            clients=self._clients,
            fps=self._measured_fps,
            width=self.width,
            height=self.height,
            sequence=self._sequence,
            error=self._error,
        )

    def _status_locked(self):
        return asdict(self._snapshot_locked())

    @staticmethod
    def _release_capture(capture):
        if capture is None:
            return
        try:
            capture.release()
        except Exception:
            pass
