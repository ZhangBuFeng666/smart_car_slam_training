import unittest

from arrow_turn_demo import (
    ArrowTurnController,
    ControllerConfig,
    Phase,
    advance_duration_s,
    in_trigger_band,
    pick_arrow_target,
    sample_depth_meters,
    turn_duration_s,
    twist_to_move_path,
    yaw_sign,
)


class HelpersTest(unittest.TestCase):
    def test_trigger_band(self):
        self.assertTrue(in_trigger_band(1.05, 1.0, 0.2))
        self.assertFalse(in_trigger_band(1.5, 1.0, 0.2))

    def test_durations(self):
        self.assertAlmostEqual(advance_duration_s(0.5, 0.15), 0.5 / 0.15)
        self.assertGreater(turn_duration_s(90.0, 0.4), 3.0)

    def test_yaw_sign(self):
        self.assertEqual(yaw_sign("turn_left"), 1.0)
        self.assertEqual(yaw_sign("turn_right"), -1.0)

    def test_pick_prefers_stable_nearer_arrow(self):
        snapshot = {
            "state": "live",
            "detections": [
                {
                    "label": "direction_arrow",
                    "direction": "turn_right",
                    "confidence": 0.9,
                    "box": {"left": 0.1, "top": 0.1, "right": 0.2, "bottom": 0.4},
                },
                {
                    "label": "direction_arrow",
                    "stable_direction": "turn_left",
                    "direction_confidence": 0.8,
                    "box": {"left": 0.3, "top": 0.4, "right": 0.5, "bottom": 0.9},
                },
            ],
        }
        chosen = pick_arrow_target(snapshot)
        self.assertEqual(chosen["stable_direction"], "turn_left")

    def test_sample_depth_median(self):
        # 640x480-ish tiny grid: centerish patch = 1000 mm
        depth = [[0] * 20 for _ in range(20)]
        for y in range(12, 18):
            for x in range(8, 14):
                depth[y][x] = 1000
        meters = sample_depth_meters(
            depth,
            {"left": 0.4, "top": 0.5, "right": 0.6, "bottom": 0.85},
            patch=5,
        )
        self.assertAlmostEqual(meters, 1.0)

    def test_twist_to_move_path(self):
        self.assertEqual(twist_to_move_path(0.0, 0.0), "/move/stop")
        self.assertEqual(twist_to_move_path(0.08, 0.0), "/move/front?speed=0.080")
        self.assertEqual(twist_to_move_path(0.0, -0.30), "/move/turn_right?turn=0.300&speed=0.10")
        self.assertEqual(twist_to_move_path(0.0, 0.30), "/move/turn_left?turn=0.300&speed=0.10")


class ControllerStateMachineTest(unittest.TestCase):
    def test_seek_advance_turn_stop_left(self):
        cfg = ControllerConfig(
            trigger_distance_m=1.0,
            trigger_tolerance_m=0.2,
            extra_forward_m=0.5,
            linear_speed_m_s=0.5,
            angular_speed_rad_s=1.57079632679,  # 90 deg / second
            turn_angle_deg=90.0,
            confirm_frames=2,
        )
        controller = ArrowTurnController(cfg)
        now = 100.0

        vx, yaw, note = controller.on_observation(None, None, now=now)
        self.assertEqual(controller.phase, Phase.SEEK)
        self.assertAlmostEqual(vx, cfg.seek_speed_m_s)
        self.assertIn("seeking_creep", note)

        vx, yaw, note = controller.on_observation("turn_left", 1.0, now=now)
        self.assertEqual(controller.phase, Phase.SEEK)
        self.assertAlmostEqual(vx, cfg.seek_speed_m_s)
        self.assertIn("confirming", note)

        vx, yaw, note = controller.on_observation("turn_left", 0.98, now=now + 0.1)
        self.assertEqual(controller.phase, Phase.ADVANCE)
        self.assertAlmostEqual(vx, 0.5)
        self.assertEqual(yaw, 0.0)

        # advance 0.5 m at 0.5 m/s => 1.0 s
        vx, yaw, note = controller.on_observation(None, None, now=now + 0.5)
        self.assertEqual(controller.phase, Phase.ADVANCE)
        self.assertAlmostEqual(vx, 0.5)

        vx, yaw, note = controller.on_observation(None, None, now=now + 1.2)
        self.assertEqual(controller.phase, Phase.TURN)
        self.assertEqual(vx, 0.0)
        self.assertGreater(yaw, 0.0)

        vx, yaw, note = controller.on_observation(None, None, now=now + 2.3)
        self.assertEqual(controller.phase, Phase.DONE)
        self.assertEqual(vx, 0.0)
        self.assertEqual(yaw, 0.0)
        self.assertEqual(note, "turn_complete_stop")

    def test_right_turn_negative_yaw(self):
        cfg = ControllerConfig(confirm_frames=1, linear_speed_m_s=0.5, angular_speed_rad_s=1.0)
        controller = ArrowTurnController(cfg)
        controller.on_observation("turn_right", 1.0, now=0.0)
        self.assertEqual(controller.phase, Phase.ADVANCE)
        # finish advance instantly with large elapsed via new phase start + enough time
        controller._phase_started = 0.0
        controller._advance_seconds = 0.0
        vx, yaw, note = controller.on_observation(None, None, now=0.01)
        self.assertEqual(controller.phase, Phase.TURN)
        self.assertLess(yaw, 0.0)


if __name__ == "__main__":
    unittest.main()
