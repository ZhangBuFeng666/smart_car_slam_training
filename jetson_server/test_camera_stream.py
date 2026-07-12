import dataclasses
import threading
import time
import unittest

from jetson_server.camera_stream import CameraCaptureService, CameraSnapshot


class FakeFrame:
    def __init__(self, width=640, height=480):
        self.shape = (height, width, 3)


class FakeCapture:
    def __init__(self, opened=True, reads=None):
        self.opened = opened
        self.reads = iter(reads or [])
        self.released = False
        self.settings = []

    def isOpened(self):
        return self.opened

    def read(self):
        try:
            item = next(self.reads)
        except StopIteration:
            return False, None
        if isinstance(item, tuple):
            return item
        return True, item

    def set(self, property_id, value):
        self.settings.append((property_id, value))
        return True

    def release(self):
        self.released = True


class CameraSnapshotTest(unittest.TestCase):
    def test_snapshot_is_frozen_and_has_the_public_status_fields(self):
        snapshot = CameraSnapshot("idle", None, 0, 0.0, 640, 480, 0, None)

        self.assertEqual(
            ["state", "device", "clients", "fps", "width", "height", "sequence", "error"],
            [field.name for field in dataclasses.fields(snapshot)],
        )
        with self.assertRaises(dataclasses.FrozenInstanceError):
            snapshot.state = "live"


class CameraCaptureServiceTest(unittest.TestCase):
    def setUp(self):
        self.services = []

    def tearDown(self):
        for service in self.services:
            while service.status()["clients"]:
                service.release_client()

    def make_service(self, **kwargs):
        kwargs.setdefault("device_candidates", lambda: ["/dev/video0"])
        kwargs.setdefault("jpeg_encoder", lambda frame, quality: b"jpeg")
        service = CameraCaptureService(**kwargs)
        self.services.append(service)
        return service

    def wait_for_state(self, service, expected, timeout=0.5):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            snapshot = service.status()
            if snapshot["state"] == expected:
                return snapshot
            time.sleep(0.001)
        self.fail(f"camera did not enter {expected!r}; last status was {service.status()!r}")

    def test_defaults_match_stream_configuration(self):
        service = self.make_service(capture_factory=lambda path: FakeCapture(opened=False))

        snapshot = service.status()

        self.assertIsInstance(snapshot, dict)
        self.assertEqual("idle", snapshot["state"])
        self.assertEqual(640, snapshot["width"])
        self.assertEqual(480, snapshot["height"])
        self.assertEqual(0.0, snapshot["fps"])

    def test_explicit_device_is_tried_first_then_candidates_are_sorted(self):
        attempts = []
        service = self.make_service(
            configured_device="/dev/video13",
            device_candidates=lambda: ["/dev/video2", "/dev/video13", "/dev/video0"],
            capture_factory=lambda path: attempts.append(path) or FakeCapture(opened=False),
        )

        service.acquire_client()
        self.wait_for_state(service, "missing")

        self.assertEqual(["/dev/video13", "/dev/video0", "/dev/video2"], attempts)

    def test_no_open_candidate_reports_missing_and_releases_attempts(self):
        captures = []

        def capture_factory(path):
            capture = FakeCapture(opened=False)
            captures.append(capture)
            return capture

        service = self.make_service(capture_factory=capture_factory)

        service.acquire_client()
        ready = service.wait_until_ready(0.5)
        snapshot = service.status()

        self.assertFalse(ready)
        self.assertEqual("missing", snapshot["state"])
        self.assertTrue(all(capture.released for capture in captures))
        self.assertIn("no camera device", snapshot["error"])

    def test_opened_camera_without_a_frame_reports_busy(self):
        capture = FakeCapture(opened=True, reads=[(False, None)])
        service = self.make_service(capture_factory=lambda path: capture)

        service.acquire_client()
        ready = service.wait_until_ready(0.5)
        snapshot = service.status()

        self.assertFalse(ready)
        self.assertEqual("busy", snapshot["state"])
        self.assertEqual("/dev/video0", snapshot["device"])
        self.assertIn("did not return a frame", snapshot["error"])

    def test_first_client_starts_one_thread_and_last_client_releases_camera(self):
        captures = []

        def capture_factory(path):
            capture = FakeCapture(opened=True, reads=[(False, None)])
            captures.append(capture)
            return capture

        service = self.make_service(capture_factory=capture_factory)

        service.acquire_client()
        service.acquire_client()
        self.wait_for_state(service, "busy")

        self.assertEqual(2, service.status()["clients"])
        self.assertEqual(1, len(captures))
        service.release_client()
        self.assertFalse(captures[0].released)
        service.release_client()
        self.assertTrue(captures[0].released)
        self.assertEqual("idle", service.status()["state"])
        self.assertEqual(0, service.status()["clients"])

    def test_release_client_does_not_decrement_below_zero(self):
        service = self.make_service(capture_factory=lambda path: FakeCapture(opened=False))

        service.release_client()

        self.assertEqual(0, service.status()["clients"])
        self.assertEqual("idle", service.status()["state"])

    def test_valid_frame_configures_capture_encodes_quality_and_becomes_live(self):
        capture = FakeCapture(opened=True, reads=[FakeFrame(width=320, height=240)])
        encoded = []
        service = self.make_service(
            width=800,
            height=600,
            fps=18,
            jpeg_quality=67,
            capture_factory=lambda path: capture,
            jpeg_encoder=lambda frame, quality: encoded.append((frame, quality)) or b"frame-jpeg",
        )

        service.acquire_client()
        self.assertTrue(service.wait_until_ready(0.5))
        snapshot = self.wait_for_state(service, "live")
        sequence, frame = service.wait_for_frame(0, 0.1)

        self.assertEqual([(3, 800), (4, 600), (5, 18)], capture.settings)
        self.assertEqual(67, encoded[0][1])
        self.assertEqual((1, b"frame-jpeg"), (sequence, frame))
        self.assertEqual((320, 240), (snapshot["width"], snapshot["height"]))
        self.assertIsNone(snapshot["error"])

    def test_five_read_failures_after_live_report_disconnected(self):
        reads = [FakeFrame()] + [(False, None)] * 5
        capture = FakeCapture(opened=True, reads=reads)
        service = self.make_service(capture_factory=lambda path: capture)

        service.acquire_client()
        snapshot = self.wait_for_state(service, "disconnected")

        self.assertEqual(1, snapshot["sequence"])
        self.assertIn("five consecutive", snapshot["error"])

    def test_publish_and_wait_for_frame_only_return_new_sequences(self):
        service = self.make_service(capture_factory=lambda path: FakeCapture(opened=False))

        service.publish_encoded_frame(b"first", width=640, height=480, captured_at=10.0)
        first = service.wait_for_frame(after_sequence=0, timeout=0.01)
        timed_out = service.wait_for_frame(after_sequence=1, timeout=0.01)
        service.publish_encoded_frame(b"second", width=640, height=480, captured_at=10.05)
        second = service.wait_for_frame(after_sequence=1, timeout=0.01)

        self.assertEqual((1, b"first"), first)
        self.assertEqual((1, None), timed_out)
        self.assertEqual((2, b"second"), second)
        self.assertAlmostEqual(20.0, service.status()["fps"])

    def test_wait_for_frame_unblocks_when_another_thread_publishes(self):
        service = self.make_service(capture_factory=lambda path: FakeCapture(opened=False))
        result = []
        waiter = threading.Thread(target=lambda: result.append(service.wait_for_frame(0, 0.5)))
        waiter.start()

        service.publish_encoded_frame(b"new", 640, 480, 1.0)
        waiter.join(0.5)

        self.assertFalse(waiter.is_alive())
        self.assertEqual([(1, b"new")], result)

    def test_restart_releases_active_capture_and_reopens_for_existing_clients(self):
        captures = []

        def capture_factory(path):
            capture = FakeCapture(opened=True, reads=[(False, None)])
            captures.append(capture)
            return capture

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        self.wait_for_state(service, "busy")

        service.restart()
        self.wait_for_state(service, "busy")

        self.assertEqual(2, len(captures))
        self.assertTrue(captures[0].released)
        self.assertEqual(1, service.status()["clients"])

    def test_restart_without_clients_stays_idle(self):
        attempts = []
        service = self.make_service(
            capture_factory=lambda path: attempts.append(path) or FakeCapture(opened=False)
        )

        service.restart()

        self.assertEqual([], attempts)
        self.assertEqual("idle", service.status()["state"])


if __name__ == "__main__":
    unittest.main()
