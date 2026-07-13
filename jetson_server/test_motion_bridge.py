import importlib.util
import json
import unittest

from jetson_server import motion_bridge


class MotionBridgeModuleTest(unittest.TestCase):
    def test_motion_bridge_module_exists(self):
        spec = importlib.util.find_spec("jetson_server.motion_bridge")

        self.assertIsNotNone(spec)


class MotionProtocolTest(unittest.TestCase):
    def test_direction_maps_to_expected_twist(self):
        twist_values = self.required("twist_values")

        self.assertEqual((0.12, 0.0, 0.0), twist_values("front", 0.12, 0.8))
        self.assertEqual((-0.12, 0.0, 0.0), twist_values("back", 0.12, 0.8))
        self.assertEqual((0.0, 0.12, 0.0), twist_values("left", 0.12, 0.8))
        self.assertEqual((0.0, -0.12, 0.0), twist_values("right", 0.12, 0.8))
        self.assertEqual((0.0, 0.0, 0.8), twist_values("turn_left", 0.12, 0.8))
        self.assertEqual((0.0, 0.0, -0.8), twist_values("turn_right", 0.12, 0.8))
        self.assertEqual((0.0, 0.0, 0.0), twist_values("stop", 0.12, 0.8))

    def test_decode_command_rejects_unknown_direction(self):
        raw = json.dumps({"sequence": 7, "direction": "jump", "speed": 0.1, "turn": 0.5})

        decode_command = self.required("decode_command")

        with self.assertRaisesRegex(ValueError, "unknown direction"):
            decode_command(raw)

    def test_decode_command_normalizes_non_negative_limits(self):
        raw = json.dumps({"sequence": 9, "direction": "front", "speed": -0.3, "turn": -2.5})

        decode_command = self.required("decode_command")
        command = decode_command(raw)

        self.assertEqual(9, command.sequence)
        self.assertEqual("front", command.direction)
        self.assertEqual(0.3, command.speed)
        self.assertEqual(2.5, command.turn)

    def required(self, name):
        self.assertTrue(hasattr(motion_bridge, name), f"motion_bridge is missing {name}")
        return getattr(motion_bridge, name)


class MotionWatchdogTest(unittest.TestCase):
    def test_watchdog_expires_once_after_motion_refresh_stops(self):
        watchdog_type = self.required("MotionWatchdog")
        watchdog = watchdog_type(timeout_seconds=0.35)

        watchdog.update(direction="front", now=10.0)

        self.assertFalse(watchdog.consume_expiry(now=10.34))
        self.assertTrue(watchdog.consume_expiry(now=10.36))
        self.assertFalse(watchdog.consume_expiry(now=10.50))

    def test_stop_disarms_watchdog(self):
        watchdog_type = self.required("MotionWatchdog")
        watchdog = watchdog_type(timeout_seconds=0.35)
        watchdog.update(direction="front", now=10.0)

        watchdog.update(direction="stop", now=10.1)

        self.assertFalse(watchdog.consume_expiry(now=11.0))

    def test_disabled_watchdog_keeps_motion_until_explicit_stop(self):
        watchdog_type = self.required("MotionWatchdog")
        watchdog = watchdog_type(timeout_seconds=0.0)

        watchdog.update(direction="front", now=10.0)

        self.assertFalse(watchdog.consume_expiry(now=100.0))

    def required(self, name):
        self.assertTrue(hasattr(motion_bridge, name), f"motion_bridge is missing {name}")
        return getattr(motion_bridge, name)


class MotionBridgeRuntimeTest(unittest.TestCase):
    def test_odometry_integrates_measured_linear_velocity(self):
        tracker = motion_bridge.OdometryTracker(clock=lambda: 0.0)

        tracker.update(0.2, 0.0, 0.0, now=10.0)
        tracker.update(0.2, 0.0, 0.0, now=12.5)

        self.assertAlmostEqual(0.5, tracker.snapshot()["distance_m"], places=3)

    def test_handle_line_publishes_immediately_and_acknowledges_sequence(self):
        runtime_type = self.required("MotionBridgeRuntime")
        published = []
        clock = iter([20.0, 20.004]).__next__
        runtime = runtime_type(published.append, watchdog_seconds=0.35, clock=clock)
        raw = json.dumps({"sequence": 12, "direction": "front", "speed": 0.1, "turn": 0.6})

        acknowledgement = runtime.handle_line(raw)

        self.assertEqual([(0.1, 0.0, 0.0)], published)
        self.assertEqual(12, acknowledgement["sequence"])
        self.assertEqual("front", acknowledgement["direction"])
        self.assertEqual(4.0, acknowledgement["bridge_latency_ms"])
        self.assertIn("distance_m", acknowledgement)

    def test_poll_watchdog_publishes_zero_velocity(self):
        runtime_type = self.required("MotionBridgeRuntime")
        published = []
        runtime = runtime_type(published.append, watchdog_seconds=0.35, clock=lambda: 30.0)
        runtime.handle_line(json.dumps({"sequence": 1, "direction": "front", "speed": 0.1, "turn": 0.6}))

        expired = runtime.poll_watchdog(now=30.36)

        self.assertTrue(expired)
        self.assertEqual((0.0, 0.0, 0.0), published[-1])

    def test_disabled_watchdog_does_not_interrupt_continuous_motion(self):
        runtime_type = self.required("MotionBridgeRuntime")
        published = []
        runtime = runtime_type(published.append, watchdog_seconds=0.0, clock=lambda: 30.0)
        runtime.handle_line(json.dumps({"sequence": 1, "direction": "front", "speed": 0.1, "turn": 0.6}))

        expired = runtime.poll_watchdog(now=300.0)

        self.assertFalse(expired)
        self.assertEqual([(0.1, 0.0, 0.0)], published)

    def test_shutdown_always_publishes_zero_velocity(self):
        runtime_type = self.required("MotionBridgeRuntime")
        published = []
        runtime = runtime_type(published.append, watchdog_seconds=0.35, clock=lambda: 40.0)

        runtime.shutdown()

        self.assertEqual([(0.0, 0.0, 0.0)], published)

    def required(self, name):
        self.assertTrue(hasattr(motion_bridge, name), f"motion_bridge is missing {name}")
        return getattr(motion_bridge, name)


if __name__ == "__main__":
    unittest.main()
