import json
import math
import unittest

from jetson_server.navigation_bridge import (
    NavigationSnapshotStore,
    YawStabilityTracker,
    downsample_points,
    feedback_is_fresh,
    heading_angular_velocity,
    normalize_angle,
    quaternion_to_yaw,
    rle_encode,
    select_waypoint_backend,
    waypoint_yaw_error,
)


class NavigationBridgeTest(unittest.TestCase):
    def test_waypoint_backend_requires_navigate_to_pose_for_heading_checks(self):
        self.assertEqual(
            "navigate_to_pose", select_waypoint_backend(True, True)
        )
        self.assertEqual(
            "navigate_to_pose", select_waypoint_backend(False, True)
        )
        self.assertIsNone(select_waypoint_backend(True, False))
        self.assertIsNone(select_waypoint_backend(False, False))

    def test_yaw_error_uses_shortest_turn_across_pi_boundary(self):
        error = waypoint_yaw_error(math.radians(-179), math.radians(179))
        self.assertAlmostEqual(math.radians(2), error)
        self.assertAlmostEqual(math.pi, abs(normalize_angle(math.pi)))

    def test_yaw_stability_requires_three_consecutive_samples(self):
        tracker = YawStabilityTracker(0.0)
        self.assertFalse(tracker.observe(math.radians(9)))
        self.assertFalse(tracker.observe(math.radians(11)))
        self.assertFalse(tracker.observe(math.radians(8)))
        self.assertFalse(tracker.observe(math.radians(7)))
        self.assertTrue(tracker.observe(math.radians(6)))

    def test_heading_controller_stops_inside_tolerance(self):
        self.assertEqual(0.0, heading_angular_velocity(math.radians(9.0)))

    def test_heading_controller_uses_shortest_bounded_turn(self):
        self.assertAlmostEqual(0.25, heading_angular_velocity(math.radians(90)))
        self.assertAlmostEqual(-0.25, heading_angular_velocity(math.radians(-90)))
        self.assertLess(heading_angular_velocity(math.radians(10.1)), 0.10)

    def test_robot_feedback_requires_fresh_imu_and_velocity(self):
        self.assertTrue(feedback_is_fresh(9.4, 9.2, 10.0, timeout=1.0))
        self.assertFalse(feedback_is_fresh(None, 9.9, 10.0, timeout=1.0))
        self.assertFalse(feedback_is_fresh(8.9, 9.9, 10.0, timeout=1.0))

    def test_rle_encode_compacts_occupancy_values(self):
        self.assertEqual([0, 3, 100, 2, -1, 1], rle_encode([0, 0, 0, 100, 100, -1]))

    def test_quaternion_to_yaw_recovers_planar_angle(self):
        yaw = 1.2
        self.assertAlmostEqual(
            yaw,
            quaternion_to_yaw(0.0, 0.0, math.sin(yaw / 2.0), math.cos(yaw / 2.0)),
        )

    def test_snapshot_only_repeats_map_when_generation_changes(self):
        store = NavigationSnapshotStore(clock=lambda: 123.0)
        store.set_map(2, 2, 0.05, -1.0, -2.0, 0.0, [0, 0, 100, -1])

        first = store.snapshot(-1)
        unchanged = store.snapshot(first["map_generation"])

        self.assertIsNotNone(first["map"])
        self.assertIsNone(unchanged["map"])
        self.assertEqual([0, 2, 100, 1, -1, 1], first["map"]["data_rle"])

    def test_reset_map_clears_previous_slam_session(self):
        store = NavigationSnapshotStore(clock=lambda: 123.0)
        store.set_map(2, 2, 0.05, 0.0, 0.0, 0.0, [0, 0, 0, 0])
        old_generation = store.map_generation

        store.reset_map()
        snapshot = store.snapshot(old_generation)

        self.assertGreater(snapshot["map_generation"], old_generation)
        self.assertTrue(snapshot["map_reset"])
        self.assertIsNone(snapshot["map"])
        self.assertIsNone(snapshot["pose"])

    def test_protocol_preserves_sequence_pose_goal_and_path(self):
        store = NavigationSnapshotStore(clock=lambda: 456.0)
        store.set_pose(1.0, 2.0, 0.5)
        store.set_goal(3.0, 4.0, 1.0)
        store.set_path([(0.0, 0.0), (1.0, 1.0)])

        response = store.handle_line(json.dumps({"sequence": 7, "map_generation": 0}))

        self.assertEqual(7, response["sequence"])
        self.assertEqual({"x": 1.0, "y": 2.0, "yaw": 0.5}, response["snapshot"]["pose"])
        self.assertEqual(2, len(response["snapshot"]["path"]))

    def test_path_downsampling_keeps_last_point(self):
        points = [(float(index), 0.0) for index in range(1000)]
        sampled = downsample_points(points, limit=100)
        self.assertLessEqual(len(sampled), 101)
        self.assertEqual(points[-1], sampled[-1])

    def test_snapshot_reports_ordered_waypoint_progress(self):
        store = NavigationSnapshotStore(clock=lambda: 789.0)
        store.set_waypoint_status(
            "verifying",
            total=5,
            current_index=2,
            missed=[1],
            message="正在确认方向",
            phase="verifying",
            target_yaw=1.0,
            actual_yaw=0.8,
            yaw_error_deg=11.46,
            retry_count=1,
        )

        status = store.snapshot()["waypoints"]

        self.assertEqual("verifying", status["state"])
        self.assertEqual(5, status["total"])
        self.assertEqual(2, status["current_index"])
        self.assertEqual([1], status["missed"])
        self.assertEqual("verifying", status["phase"])
        self.assertEqual(1.0, status["target_yaw"])
        self.assertEqual(0.8, status["actual_yaw"])
        self.assertEqual(11.46, status["yaw_error_deg"])
        self.assertEqual(1, status["retry_count"])


if __name__ == "__main__":
    unittest.main()
