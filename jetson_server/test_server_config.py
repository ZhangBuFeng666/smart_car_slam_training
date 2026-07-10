import unittest

import server


class ServerConfigTest(unittest.TestCase):
    def test_slam_and_navigation_tasks_match_training_manual(self):
        self.assertIn("ros2 launch yahboomcar_nav map_gmapping_launch.py", server.TASK_COMMANDS["map_gmapping"])
        self.assertIn("ros2 launch yahboomcar_nav display_map_launch.py", server.TASK_COMMANDS["map_display"])
        self.assertIn("ros2 launch yahboomcar_nav save_map_launch.py", server.TASK_COMMANDS["map_save"])
        self.assertIn("ros2 launch yahboomcar_nav laser_bringup_launch.py", server.TASK_COMMANDS["nav_laser"])
        self.assertIn("ros2 launch yahboomcar_nav display_nav_launch.py", server.TASK_COMMANDS["nav_display"])
        self.assertIn("ros2 launch yahboomcar_nav navigation_dwa_launch.py", server.TASK_COMMANDS["nav_dwa"])
        self.assertIn("ros2 launch yahboomcar_nav navigation_teb_launch.py", server.TASK_COMMANDS["nav_teb"])

    def test_pose_publish_commands_target_nav2_topics(self):
        initial = server.build_initial_pose_command(1.2, -0.5, 1.57)
        goal = server.build_goal_pose_command(2.0, 3.45, 0.0)

        self.assertIn("/initialpose", initial)
        self.assertIn("geometry_msgs/msg/PoseWithCovarianceStamped", initial)
        self.assertIn("/goal_pose", goal)
        self.assertIn("geometry_msgs/msg/PoseStamped", goal)
        self.assertIn("0.706", initial)
        self.assertIn("w: 1", goal)

    def test_lidar_avoidance_tasks_match_training_manual(self):
        self.assertEqual(
            "ros2 launch sllidar_ros2 sllidar_launch.py",
            server.TASK_COMMANDS["lidar"],
        )
        self.assertEqual(
            "ros2 run icar_laser laser_Avoidance_a1_X3",
            server.TASK_COMMANDS["avoidance"],
        )


if __name__ == "__main__":
    unittest.main()
