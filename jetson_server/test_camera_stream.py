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
        self.release_calls = 0
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
        self.release_calls += 1
        self.released = True


class HoldingCapture(FakeCapture):
    def __init__(self, reads):
        super().__init__(opened=True, reads=reads)
        self.read_blocked = threading.Event()
        self.release_requested = threading.Event()

    def read(self):
        try:
            item = next(self.reads)
        except StopIteration:
            self.read_blocked.set()
            self.release_requested.wait()
            return False, None
        if isinstance(item, tuple):
            return item
        return True, item

    def release(self):
        super().release()
        self.release_requested.set()


class UninterruptibleCapture(FakeCapture):
    def __init__(self):
        super().__init__(opened=True)
        self.read_started = threading.Event()
        self.allow_read_to_finish = threading.Event()

    def read(self):
        self.read_started.set()
        self.allow_read_to_finish.wait()
        return False, None


class BlockingReleaseCapture(UninterruptibleCapture):
    def __init__(self):
        super().__init__()
        self.release_started = threading.Event()
        self.allow_release_to_finish = threading.Event()

    def release(self):
        self.release_calls += 1
        self.release_started.set()
        self.allow_release_to_finish.wait(2.0)
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
        self.assertIn("close other camera applications", snapshot["error"])
        self.assertTrue(capture.released)
        self.assertIsNone(service._capture)

    def test_terminal_capture_loop_releases_backend_exactly_once(self):
        capture = FakeCapture(opened=True, reads=[(False, None)])
        service = self.make_service(capture_factory=lambda path: capture)

        service.acquire_client()
        self.wait_for_state(service, "busy")
        deadline = time.monotonic() + 0.5
        while capture.release_calls == 0 and time.monotonic() < deadline:
            time.sleep(0.001)

        self.assertEqual(1, capture.release_calls)

    def test_normal_client_shutdown_releases_backend_exactly_once(self):
        capture = HoldingCapture([FakeFrame()])
        service = self.make_service(capture_factory=lambda path: capture)
        service.acquire_client()
        self.assertTrue(capture.read_blocked.wait(0.5))

        service.release_client()

        self.assertEqual(1, capture.release_calls)

    def test_first_client_starts_one_thread_and_last_client_releases_camera(self):
        capture = HoldingCapture([FakeFrame()])
        captures = [capture]

        service = self.make_service(capture_factory=lambda path: capture)

        service.acquire_client()
        service.acquire_client()
        self.assertTrue(captures[0].read_blocked.wait(0.5))

        self.assertEqual(2, service.status()["clients"])
        self.assertEqual(1, len(captures))
        service.release_client()
        self.assertFalse(captures[0].released)
        service.release_client()
        self.assertTrue(captures[0].released)
        self.assertEqual("idle", service.status()["state"])
        self.assertEqual(0, service.status()["clients"])

    def test_capture_thread_is_daemon_and_shutdown_join_is_bounded(self):
        first_capture = UninterruptibleCapture()
        captures = []

        def capture_factory(path):
            capture = first_capture if not captures else FakeCapture(
                opened=True, reads=[(False, None)])
            captures.append(capture)
            return capture

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        self.assertTrue(first_capture.read_started.wait(0.5))

        with service._condition:
            self.assertTrue(service._thread.daemon)

        started_at = time.monotonic()
        service.release_client()
        elapsed = time.monotonic() - started_at

        self.assertLess(elapsed, 1.2)
        self.assertEqual("stopping", service.status()["state"])
        service.acquire_client()
        self.assertEqual(1, len(captures))
        self.assertEqual("stopping", service.status()["state"])

        first_capture.allow_read_to_finish.set()
        self.wait_for_state(service, "busy", timeout=1.0)

        self.assertEqual(2, len(captures))
        self.assertTrue(first_capture.released)

    def test_reaper_does_not_replace_a_newer_capture_thread(self):
        first_capture = UninterruptibleCapture()
        captures = []

        def capture_factory(path):
            capture = first_capture if not captures else FakeCapture(
                opened=True, reads=[(False, None)]
            )
            captures.append(capture)
            return capture

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        self.assertTrue(first_capture.read_started.wait(0.5))

        reaper_entered = threading.Event()
        allow_reaper = threading.Event()
        original_reap = service._reap_capture_thread

        def paused_reap(thread, capture_release):
            reaper_entered.set()
            allow_reaper.wait(0.5)
            original_reap(thread, capture_release)

        service._reap_capture_thread = paused_reap
        service.release_client()
        self.assertTrue(reaper_entered.wait(0.5))
        first_capture.allow_read_to_finish.set()
        with service._condition:
            old_thread = service._thread
        old_thread.join(0.5)

        service.acquire_client()
        self.assertEqual("stopping", service.status()["state"])
        self.assertEqual(1, len(captures))
        with service._condition:
            reaper_thread = service._reaper_thread
        allow_reaper.set()
        reaper_thread.join(0.5)

        self.assertFalse(reaper_thread.is_alive())
        self.wait_for_state(service, "busy")
        self.assertEqual(2, len(captures))

    def test_acquire_during_last_client_shutdown_starts_a_fresh_capture(self):
        captures = []

        def capture_factory(path):
            capture = FakeCapture(opened=True, reads=[(False, None)])
            captures.append(capture)
            return capture

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        self.wait_for_state(service, "busy")

        shutdown_entered = threading.Event()
        allow_shutdown = threading.Event()
        original_shutdown = service._shutdown_capture

        def paused_shutdown():
            shutdown_entered.set()
            allow_shutdown.wait(0.5)
            original_shutdown()

        service._shutdown_capture = paused_shutdown
        release_thread = threading.Thread(target=service.release_client)
        release_thread.start()
        self.assertTrue(shutdown_entered.wait(0.5))

        acquire_finished = threading.Event()
        acquire_thread = threading.Thread(
            target=lambda: (service.acquire_client(), acquire_finished.set())
        )
        acquire_thread.start()
        self.assertFalse(acquire_finished.wait(0.05))
        allow_shutdown.set()
        release_thread.join(0.5)
        acquire_thread.join(0.5)

        self.assertFalse(release_thread.is_alive())
        self.assertFalse(acquire_thread.is_alive())
        self.assertTrue(acquire_finished.is_set())
        snapshot = self.wait_for_state(service, "busy")
        self.assertEqual(1, snapshot["clients"])
        self.assertEqual(2, len(captures))
        self.assertTrue(captures[0].released)

    def test_stale_capture_generation_cannot_publish_after_shutdown(self):
        capture = FakeCapture(opened=True, reads=[FakeFrame()])
        service = self.make_service(capture_factory=lambda path: capture)
        dimensions_entered = threading.Event()
        allow_dimensions = threading.Event()

        def paused_dimensions(frame):
            dimensions_entered.set()
            allow_dimensions.wait(0.5)
            return 640, 480

        service._frame_dimensions = paused_dimensions
        service.acquire_client()
        self.assertTrue(dimensions_entered.wait(0.5))
        with service._condition:
            capture_thread = service._thread

        release_finished = threading.Event()
        release_thread = threading.Thread(
            target=lambda: (service.release_client(), release_finished.set())
        )
        release_thread.start()
        self.assertFalse(release_finished.wait(0.05))
        allow_dimensions.set()
        release_thread.join(0.5)
        capture_thread.join(0.5)

        snapshot = service.status()
        self.assertEqual("idle", snapshot["state"])
        self.assertEqual(0, snapshot["sequence"])

    def test_reconnect_does_not_return_cached_frame_from_previous_generation(self):
        first_capture = HoldingCapture([FakeFrame()])
        second_capture = HoldingCapture([])
        captures = iter([first_capture, second_capture])
        service = self.make_service(capture_factory=lambda path: next(captures))
        service.acquire_client()
        self.assertTrue(service.wait_until_ready(0.5))
        old_sequence = service.status()["sequence"]
        service.release_client()

        service.acquire_client()
        self.assertTrue(second_capture.read_blocked.wait(0.5))
        sequence, jpeg = service.wait_for_frame(after_sequence=0, timeout=0.02)

        self.assertEqual(0, sequence)
        self.assertIsNone(jpeg)
        self.assertEqual(old_sequence, service.status()["sequence"])

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
        self.assertTrue(capture.released)
        self.assertIsNone(service._capture)

    def test_successful_frame_resets_consecutive_read_failures(self):
        reads = (
            [FakeFrame()]
            + [(False, None)] * 4
            + [FakeFrame()]
            + [(False, None)] * 4
        )
        capture = HoldingCapture(reads)
        service = self.make_service(capture_factory=lambda path: capture)

        service.acquire_client()
        self.assertTrue(capture.read_blocked.wait(0.5))
        snapshot = service.status()

        self.assertEqual("live", snapshot["state"])
        self.assertEqual(2, snapshot["sequence"])

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

    def test_restart_reuses_requested_dimensions_after_reporting_actual_frame_size(self):
        captures = [
            HoldingCapture([FakeFrame(width=320, height=240)]),
            FakeCapture(opened=True, reads=[(False, None)]),
        ]
        service = self.make_service(
            width=800,
            height=600,
            capture_factory=lambda path: captures.pop(0),
        )
        first_capture = captures[0]
        second_capture = captures[1]
        service.acquire_client()
        self.assertTrue(service.wait_until_ready(0.5))
        self.assertEqual((320, 240), (
            service.status()["width"], service.status()["height"]
        ))

        service.restart()
        self.wait_for_state(service, "busy")

        self.assertTrue(first_capture.released)
        self.assertEqual([(3, 800), (4, 600), (5, 18)], second_capture.settings)

    def test_permission_error_reports_busy_with_device_context(self):
        def capture_factory(path):
            raise PermissionError("permission denied")

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        snapshot = self.wait_for_state(service, "busy")

        self.assertIn("/dev/video0", snapshot["error"])
        self.assertIn("permission denied", snapshot["error"])

    def test_resource_busy_open_error_reports_busy(self):
        def capture_factory(path):
            raise OSError("device or resource busy")

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        snapshot = self.wait_for_state(service, "busy")

        self.assertIn("resource busy", snapshot["error"])

    def test_non_absence_open_error_is_preserved_as_disconnected(self):
        def capture_factory(path):
            raise RuntimeError("backend initialization failed")

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        snapshot = self.wait_for_state(service, "disconnected")

        self.assertIn("backend initialization failed", snapshot["error"])

    def test_restart_without_clients_stays_idle(self):
        attempts = []
        service = self.make_service(
            capture_factory=lambda path: attempts.append(path) or FakeCapture(opened=False)
        )

        service.restart()

        self.assertEqual([], attempts)
        self.assertEqual("idle", service.status()["state"])

    def test_shutdown_releases_capture_clears_clients_and_rejects_new_acquires(self):
        capture = HoldingCapture([FakeFrame()])
        attempts = []

        def capture_factory(path):
            attempts.append(path)
            return capture

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        service.acquire_client()
        self.assertTrue(capture.read_blocked.wait(0.5))
        published_sequence = service.status()["sequence"]

        service.shutdown()
        snapshot = service.status()

        self.assertTrue(capture.released)
        self.assertEqual("idle", snapshot["state"])
        self.assertEqual(0, snapshot["clients"])
        self.assertEqual((0, None), service.wait_for_frame(0, 0.01))
        self.assertGreaterEqual(snapshot["sequence"], published_sequence)
        service.acquire_client()
        self.assertEqual(1, len(attempts))
        self.assertEqual(0, service.status()["clients"])
        service.publish_encoded_frame(b"late", 640, 480, time.monotonic())
        service.restart()
        self.assertEqual("idle", service.status()["state"])
        self.assertEqual((0, None), service.wait_for_frame(0, 0.01))
        self.assertEqual(1, len(attempts))

    def test_shutdown_keeps_stopping_until_blocked_thread_exits(self):
        capture = UninterruptibleCapture()
        service = self.make_service(capture_factory=lambda path: capture)
        service.acquire_client()
        self.assertTrue(capture.read_started.wait(0.5))

        service.shutdown()

        self.assertEqual("stopping", service.status()["state"])
        self.assertEqual(0, service.status()["clients"])
        capture.allow_read_to_finish.set()
        self.wait_for_state(service, "idle", timeout=1.0)

    def test_shutdown_is_bounded_when_backend_release_blocks(self):
        capture = BlockingReleaseCapture()
        service = self.make_service(capture_factory=lambda path: capture)
        service.acquire_client()
        self.assertTrue(capture.read_started.wait(0.5))

        started_at = time.monotonic()
        service.shutdown()
        elapsed = time.monotonic() - started_at

        self.assertTrue(capture.release_started.is_set())
        self.assertLess(elapsed, 1.3)
        self.assertEqual("stopping", service.status()["state"])
        self.assertEqual(1, capture.release_calls)
        capture.allow_release_to_finish.set()
        capture.allow_read_to_finish.set()
        self.wait_for_state(service, "idle", timeout=1.0)
        self.assertEqual(1, capture.release_calls)

    def test_reconnect_waits_for_blocking_backend_release_to_finish(self):
        first_capture = BlockingReleaseCapture()
        captures = []

        def capture_factory(path):
            capture = first_capture if not captures else FakeCapture(
                opened=True, reads=[(False, None)]
            )
            captures.append(capture)
            return capture

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        self.assertTrue(first_capture.read_started.wait(0.5))
        service.release_client()
        with service._condition:
            old_thread = service._thread

        first_capture.allow_read_to_finish.set()
        old_thread.join(0.5)
        service.acquire_client()

        self.assertEqual(1, len(captures))
        self.assertEqual("stopping", service.status()["state"])
        first_capture.allow_release_to_finish.set()
        self.wait_for_state(service, "busy", timeout=1.0)
        self.assertEqual(2, len(captures))

    def test_repeated_restart_waits_for_existing_release_reaper(self):
        first_capture = BlockingReleaseCapture()
        captures = []

        def capture_factory(path):
            capture = first_capture if not captures else FakeCapture(
                opened=True, reads=[(False, None)]
            )
            captures.append(capture)
            return capture

        service = self.make_service(capture_factory=capture_factory)
        service.acquire_client()
        self.assertTrue(first_capture.read_started.wait(0.5))

        service.restart()
        self.assertEqual("stopping", service.status()["state"])
        first_capture.allow_read_to_finish.set()
        with service._condition:
            old_thread = service._thread
        old_thread.join(0.5)

        started_at = time.monotonic()
        service.restart()
        second_restart_elapsed = time.monotonic() - started_at

        self.assertLess(second_restart_elapsed, 0.2)
        self.assertEqual("stopping", service.status()["state"])
        self.assertEqual(1, len(captures))
        first_capture.allow_release_to_finish.set()
        self.wait_for_state(service, "busy", timeout=1.0)
        self.assertEqual(2, len(captures))


if __name__ == "__main__":
    unittest.main()
