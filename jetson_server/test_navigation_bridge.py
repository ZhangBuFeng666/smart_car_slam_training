import json
import math
import unittest

from jetson_server.navigation_bridge import (
    NavigationSnapshotStore,
    downsample_points,
    quaternion_to_yaw,
    rle_encode,
    select_waypoint_backend,
)


class NavigationBridgeTest(unittest.TestCase):
    def test_waypoint_backend_falls_back_to_navigate_to_pose(self):
        self.assertEqual(
            "follow_waypoints", select_waypoint_backend(True, True)
        )
        self.assertEqual(
            "navigate_to_pose", select_waypoint_backend(False, True)
        )
        self.assertIsNone(select_waypoint_backend(False, False))

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
            "active", total=5, current_index=2, missed=[1], message="巡逻执行中"
        )

        status = store.snapshot()["waypoints"]

        self.assertEqual("active", status["state"])
        self.assertEqual(5, status["total"])
        self.assertEqual(2, status["current_index"])
        self.assertEqual([1], status["missed"])


if __name__ == "__main__":
    unittest.main()
