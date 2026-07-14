#!/usr/bin/env python3
"""Apply the shared waypoint heading tolerance to icar_nav parameter files."""

import argparse
import re
import shutil
from pathlib import Path


YAW_TOLERANCE = "0.174533"
GOAL_CHECKER_BLOCK = '''    progress_checker_plugin: "progress_checker"
    goal_checker_plugin: "goal_checker"
    progress_checker:
      plugin: "nav2_controller::SimpleProgressChecker"
      required_movement_radius: 0.5
      movement_time_allowance: 10.0
    goal_checker:
      plugin: "nav2_controller::SimpleGoalChecker"
      xy_goal_tolerance: 0.25
      yaw_goal_tolerance: 0.174533
      stateful: True
'''


def patch_text(text):
    if 'goal_checker_plugin: "goal_checker"' not in text:
        marker = '    controller_plugins: ["FollowPath"]\n'
        if marker not in text:
            raise ValueError("controller_plugins marker was not found")
        text = text.replace(marker, marker + GOAL_CHECKER_BLOCK, 1)
    text, replacements = re.subn(
        r"(^\s*yaw_goal_tolerance:\s*)[^#\s]+",
        rf"\g<1>{YAW_TOLERANCE}",
        text,
        flags=re.MULTILINE,
    )
    if replacements != 1:
        raise ValueError(
            "expected exactly one yaw_goal_tolerance after patching, found %d"
            % replacements
        )
    return text


def patch_file(path):
    original = path.read_text(encoding="utf-8")
    patched = patch_text(original)
    if patched == original:
        return False
    backup = path.with_name(path.name + ".before-yaw-goal-checker")
    if not backup.exists():
        shutil.copy2(path, backup)
    path.write_text(patched, encoding="utf-8")
    return True


def parameter_files(workspace):
    relative = Path("icar_nav") / "params"
    roots = (
        workspace / "src" / relative,
        workspace / "install" / "icar_nav" / "share" / relative,
    )
    for root in roots:
        for algorithm in ("dwa", "teb", "rpp"):
            yield root / (algorithm + "_nav_params.yaml")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--workspace",
        type=Path,
        default=Path("/root/icar_ros2_ws/icar_ws"),
    )
    args = parser.parse_args()
    changed = 0
    for path in parameter_files(args.workspace):
        if not path.is_file():
            raise SystemExit("missing navigation parameter file: %s" % path)
        was_changed = patch_file(path)
        changed += int(was_changed)
        print(("patched" if was_changed else "ready") + ": " + str(path))
    print("navigation goal checker files changed: %d" % changed)


if __name__ == "__main__":
    main()
