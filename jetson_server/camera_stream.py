#!/usr/bin/env python3
from dataclasses import asdict, dataclass
import glob
import threading
import time
from typing import Callable, Iterable, Optional


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


class CameraCaptureService:
    _READY_STATES = frozenset(("live", "missing", "busy", "disconnected"))
    _CAP_PROP_FRAME_WIDTH = 3
    _CAP_PROP_FRAME_HEIGHT = 4
    _CAP_PROP_FPS = 5

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
        self.width = int(width)
        self.height = int(height)
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
        self._thread = None
        self._stop_requested = False
        self._generation = 0

    def acquire_client(self):
        with self._lifecycle_lock:
            with self._condition:
                previous_clients = self._clients
                self._clients += 1
                if previous_clients == 0:
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
                has_clients = self._clients > 0
            self._shutdown_capture()
            if has_clients:
                with self._condition:
                    self._start_capture_locked()
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
            while self._sequence <= after_sequence:
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
        self._measured_fps = 0.0
        self._last_captured_at = None
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
            self._thread = None
            self._capture = None
            self._condition.notify_all()

        self._release_capture(capture)
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=1.0)

        with self._condition:
            self._state = "idle"
            self._device = None
            self._error = None
            self._measured_fps = 0.0
            self._last_captured_at = None
            self._condition.notify_all()

    def _capture_loop(self, generation):
        capture = None
        try:
            capture, device = self._open_capture(generation)
            if capture is None:
                return
            if not self._set_active_capture(generation, capture, device):
                self._release_capture(capture)
                return

            self._configure_capture(capture)
            success, frame = capture.read()
            if not success or frame is None:
                self._set_state(
                    generation,
                    "busy",
                    f"camera {device} opened but did not return a frame",
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

    def _open_capture(self, generation):
        for device in self._ordered_devices():
            if self._should_stop(generation):
                return None, None
            try:
                capture = self.capture_factory(device)
            except Exception:
                continue
            try:
                if capture is not None and capture.isOpened():
                    return capture, device
            except Exception:
                pass
            self._release_capture(capture)

        self._set_state(generation, "missing", "no camera device could be opened")
        return None, None

    def _ordered_devices(self):
        configured = [] if self.configured_device is None else [self.configured_device]
        candidates = sorted(set(self.device_candidates()))
        return configured + [device for device in candidates if device not in configured]

    def _set_active_capture(self, generation, capture, device):
        with self._condition:
            if self._generation != generation or self._stop_requested:
                return False
            self._capture = capture
            self._device = str(device)
            return True

    def _configure_capture(self, capture):
        setter = getattr(capture, "set", None)
        if setter is None:
            return
        for property_id, value in (
            (self._CAP_PROP_FRAME_WIDTH, self.width),
            (self._CAP_PROP_FRAME_HEIGHT, self.height),
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
