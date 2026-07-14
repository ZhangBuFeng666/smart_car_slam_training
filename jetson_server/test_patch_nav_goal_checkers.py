import tempfile
import unittest
from pathlib import Path

from jetson_server.patch_nav_goal_checkers import patch_file, patch_text


class PatchNavGoalCheckersTest(unittest.TestCase):
    def test_adds_missing_goal_checker_to_dwa_or_rpp(self):
        source = '''controller_server:
  ros__parameters:
    controller_plugins: ["FollowPath"]
    FollowPath:
      plugin: "example"
'''
        patched = patch_text(source)
        self.assertIn('goal_checker_plugin: "goal_checker"', patched)
        self.assertIn("yaw_goal_tolerance: 0.174533", patched)

    def test_updates_existing_teb_tolerance_without_duplicate_block(self):
        source = '''controller_server:
  ros__parameters:
    goal_checker_plugin: "goal_checker"
    controller_plugins: ["FollowPath"]
    goal_checker:
      plugin: "nav2_controller::SimpleGoalChecker"
      yaw_goal_tolerance: 0.25
'''
        patched = patch_text(source)
        self.assertEqual(1, patched.count("goal_checker_plugin"))
        self.assertIn("yaw_goal_tolerance: 0.174533", patched)

    def test_limits_controller_and_recovery_rotation(self):
        source = '''controller_server:
  ros__parameters:
    goal_checker_plugin: "goal_checker"
    controller_plugins: ["FollowPath"]
    goal_checker:
      plugin: "nav2_controller::SimpleGoalChecker"
      yaw_goal_tolerance: 0.25
    FollowPath:
      max_vel_theta: 1.0
      acc_lim_theta: 3.2
      rotate_to_heading_angular_vel: 0.45
recoveries_server:
  ros__parameters:
    max_rotational_vel: 1.0
    min_rotational_vel: 0.4
    rotational_acc_lim: 3.2
'''
        patched = patch_text(source)
        self.assertIn("max_vel_theta: 0.5", patched)
        self.assertIn("acc_lim_theta: 0.5", patched)
        self.assertIn("rotate_to_heading_angular_vel: 0.35", patched)
        self.assertIn("max_rotational_vel: 0.35", patched)
        self.assertIn("min_rotational_vel: 0.10", patched)
        self.assertIn("rotational_acc_lim: 0.5", patched)

    def test_file_patch_is_idempotent_and_keeps_backup(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "dwa_nav_params.yaml"
            path.write_text(
                '    controller_plugins: ["FollowPath"]\n', encoding="utf-8"
            )
            self.assertTrue(patch_file(path))
            self.assertFalse(patch_file(path))
            self.assertTrue(
                path.with_name(path.name + ".before-yaw-goal-checker").is_file()
            )


if __name__ == "__main__":
    unittest.main()
