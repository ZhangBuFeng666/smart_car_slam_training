import argparse
import errno
import http.client
import io
import json
import queue
import socket
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import server


class ServerConfigTest(unittest.TestCase):
    def test_slam_and_navigation_tasks_match_training_manual(self):
        self.assertIn("ros2 launch icar_nav map_gmapping_launch.py", server.TASK_COMMANDS["map_gmapping"])
        self.assertIn("ros2 launch icar_nav display_map_launch.py", server.TASK_COMMANDS["map_display"])
        self.assertIn("ros2 launch icar_nav save_map_launch.py", server.TASK_COMMANDS["map_save"])
        self.assertIn("ros2 launch icar_nav laser_bringup_launch.py", server.TASK_COMMANDS["nav_laser"])
        self.assertIn("ros2 launch icar_nav display_nav_launch.py", server.TASK_COMMANDS["nav_display"])
        self.assertIn("ros2 launch icar_nav navigation_dwa_launch.py", server.TASK_COMMANDS["nav_dwa"])
        self.assertIn("ros2 launch icar_nav navigation_teb_launch.py", server.TASK_COMMANDS["nav_teb"])
        self.assertIn(
            "ros2 launch icar_nav navigation_rpp_launch.py",
            server.TASK_COMMANDS["nav_astar_rpp"],
        )
        for task in ("nav_dwa", "nav_teb", "nav_astar_rpp"):
            self.assertIn(f"map:={server.ICAR_MAP_PATH}", server.TASK_COMMANDS[task])

    def test_default_container_is_8b98(self):
        self.assertEqual("8b98", server.DEFAULT_CONTAINER)

    def test_systemd_unit_uses_8b98_and_single_server_path(self):
        unit = Path(__file__).with_name("icar-control.service").read_text()

        self.assertIn("After=network-online.target docker.service", unit)
        self.assertIn("WorkingDirectory=/home/jetson/icar_app_server", unit)
        self.assertIn("server.py --container 8b98", unit)

    def test_automatic_workflow_steps_match_manual_shortcuts(self):
        self.assertEqual(("map_gmapping", "map_display"), server.MAPPING_TASKS)
        self.assertEqual(("nav_laser", "nav_display"), server.NAVIGATION_SHARED_TASKS)
        self.assertEqual(
            {"dwa": "nav_dwa", "teb": "nav_teb", "astar_rpp": "nav_astar_rpp"},
            server.NAVIGATION_ALGORITHMS,
        )

    def test_pose_publish_commands_target_nav2_topics(self):
        initial = server.build_initial_pose_command(1.2, -0.5, 1.57)
        goal = server.build_goal_pose_command(2.0, 3.45, 0.0)

        self.assertIn("/initialpose", initial)
        self.assertIn("geometry_msgs/msg/PoseWithCovarianceStamped", initial)
        self.assertIn("/goal_pose", goal)
        self.assertIn("geometry_msgs/msg/PoseStamped", goal)
        self.assertIn("0.706", initial)
        self.assertIn("w: 1", goal)

    def test_waypoint_payload_preserves_order_and_validates_numbers(self):
        points = server.validated_waypoints({
            "points": [
                {"x": 1, "y": 2, "yaw": 0.5},
                {"x": 3.25, "y": -1},
            ]
        })

        self.assertEqual(
            [
                {"x": 1.0, "y": 2.0, "yaw": 0.5},
                {"x": 3.25, "y": -1.0, "yaw": 0.0},
            ],
            points,
        )

        with self.assertRaisesRegex(ValueError, "non-empty"):
            server.validated_waypoints({"points": []})

        self.assertEqual(
            {"x": 0.5, "y": -1.25, "yaw": 1.57},
            server.validated_navigation_pose(
                {"x": 0.5, "y": -1.25, "yaw": 1.57}, "start"
            ),
        )
        with self.assertRaisesRegex(ValueError, "start must be an object"):
            server.validated_navigation_pose(None, "start")

    def test_lidar_avoidance_tasks_match_training_manual(self):
        self.assertEqual(
            "ros2 launch sllidar_ros2 sllidar_launch.py",
            server.TASK_COMMANDS["lidar"],
        )
        self.assertEqual(
            "ros2 run icar_laser laser_Avoidance_a1_X3",
            server.TASK_COMMANDS["avoidance"],
        )

    def test_follow_warning_and_color_tracking_match_training_manual(self):
        self.assertIn("follow", server.TASK_COMMANDS)
        self.assertIn("warning", server.TASK_COMMANDS)
        self.assertIn("color_track", server.TASK_COMMANDS)
        self.assertEqual(
            "ros2 run icar_laser laser_Tracker_a1_X3",
            server.TASK_COMMANDS["follow"],
        )
        self.assertEqual(
            "ros2 run icar_laser laser_Warning_a1_X3",
            server.TASK_COMMANDS["warning"],
        )
        self.assertEqual(
            "ros2 run icar_astra colorTracker",
            server.TASK_COMMANDS["color_track"],
        )

    def test_bridge_factory_uses_repo_motion_script(self):
        self.assertTrue(hasattr(server, "create_motion_bridge"), "server is missing create_motion_bridge")

        bridge = server.create_motion_bridge("8b98")

        self.assertEqual("8b98", bridge.container)
        self.assertEqual("motion_bridge.py", bridge.script_path.name)
        self.assertTrue(bridge.script_path.is_file())

    def test_navigation_bridge_factory_uses_repo_navigation_script(self):
        bridge = server.create_navigation_bridge("ed97")

        self.assertEqual("ed97", bridge.container)
        self.assertEqual("navigation_bridge.py", bridge.script_path.name)
        self.assertTrue(bridge.script_path.is_file())

    def test_motion_limits_preserve_safe_remote_control_ranges(self):
        self.assertEqual(0.18, server.clamp_float("bad", 0.18, 0.05, 0.35))
        self.assertEqual(0.05, server.clamp_float("0.01", 0.18, 0.05, 0.35))
        self.assertEqual(0.35, server.clamp_float("0.8", 0.18, 0.05, 0.35))
        self.assertEqual(0.8, server.clamp_float("0.8", 0.8, 0.2, 1.2))

    def test_every_task_has_a_process_detection_pattern(self):
        self.assertTrue(hasattr(server, "TASK_PATTERNS"), "server is missing TASK_PATTERNS")
        self.assertEqual(set(server.TASK_COMMANDS), set(server.TASK_PATTERNS))

    def test_camera_factory_preserves_local_device_and_stream_settings(self):
        camera = server.create_camera_stream(
            device="/dev/video13",
            width=640,
            height=480,
            fps=18,
            quality=70,
        )

        self.assertEqual("/dev/video13", camera.configured_device)
        self.assertEqual(640, camera.width)
        self.assertEqual(480, camera.height)
        self.assertEqual(18, camera.target_fps)
        self.assertEqual(70, camera.jpeg_quality)

    def test_positive_int_accepts_positive_values(self):
        self.assertEqual(1, server.positive_int("1"))
        self.assertEqual(640, server.positive_int("640"))

    def test_positive_int_rejects_zero_negative_and_non_integer_values(self):
        for value in ("0", "-1", "1.5", "invalid"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                server.positive_int(value)

    def test_jpeg_quality_accepts_bounds(self):
        self.assertEqual(1, server.jpeg_quality("1"))
        self.assertEqual(100, server.jpeg_quality("100"))

    def test_jpeg_quality_rejects_values_outside_bounds(self):
        for value in ("0", "101", "invalid"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                server.jpeg_quality(value)

    def test_port_number_accepts_valid_bounds(self):
        self.assertEqual(1, server.port_number("1"))
        self.assertEqual(65535, server.port_number("65535"))

    def test_port_number_rejects_values_outside_valid_range(self):
        for value in ("0", "65536", "-1", "1.5", "invalid"):
            with self.subTest(value=value), self.assertRaises(argparse.ArgumentTypeError):
                server.port_number(value)


class NavigationProfileTest(unittest.TestCase):
    def setUp(self):
        self.original_config = dict(server.SERVER_CONFIG)
        self.original_commands = dict(server.TASK_COMMANDS)

    def tearDown(self):
        server.SERVER_CONFIG.clear()
        server.SERVER_CONFIG.update(self.original_config)
        server.TASK_COMMANDS.clear()
        server.TASK_COMMANDS.update(self.original_commands)

    def test_prefers_complete_icar_nav_profile(self):
        with patch.object(server, "inspect_navigation_package", return_value=[]) as inspect:
            profile = server.detect_navigation_profile("8b98")

        self.assertTrue(profile["ready"])
        self.assertEqual("icar_nav", profile["package"])
        inspect.assert_called_once_with("8b98", "icar_nav")

    def test_falls_back_to_complete_yahboom_profile(self):
        required = list(server.NAVIGATION_LAUNCH_FILES.values())
        with patch.object(
            server,
            "inspect_navigation_package",
            side_effect=[required, []],
        ):
            profile = server.detect_navigation_profile("ed97")

        self.assertTrue(profile["ready"])
        self.assertEqual("yahboomcar_nav", profile["package"])

    def test_reports_both_incomplete_profiles(self):
        required = list(server.NAVIGATION_LAUNCH_FILES.values())
        with patch.object(server, "inspect_navigation_package", return_value=required):
            profile = server.detect_navigation_profile("empty")

        self.assertFalse(profile["ready"])
        self.assertIsNone(profile["package"])
        self.assertEqual(
            {"icar_nav": required, "yahboomcar_nav": required},
            profile["missing_navigation_launches"],
        )

    def test_incomplete_profile_disables_navigation_but_keeps_base(self):
        profile = {
            "package": None,
            "ready": False,
            "missing_navigation_launches": {"icar_nav": ["navigation_rpp_launch.py"]},
        }
        server.SERVER_CONFIG["dry_run"] = True
        server.apply_navigation_profile(profile)

        base_status, _ = server.start_task("base")
        navigation_status, body = server.start_task("map_gmapping")

        self.assertEqual(200, base_status)
        self.assertEqual(503, navigation_status)
        self.assertFalse(body["navigation_tasks_ready"])
        self.assertEqual("map_gmapping", body["task"])

        with patch.object(server, "start_task") as start:
            workflow_status, workflow_body = server.start_automatic_mapping()
        self.assertEqual(503, workflow_status)
        self.assertFalse(workflow_body["navigation_tasks_ready"])
        start.assert_not_called()

    def test_apply_profile_updates_all_navigation_commands_and_n5_override(self):
        profile = {
            "package": "yahboomcar_nav",
            "ready": True,
            "missing_navigation_launches": {},
        }
        override = "ros2 launch custom_pkg custom_rpp_launch.py"

        server.apply_navigation_profile(profile, override)

        self.assertEqual(
            "ros2 launch yahboomcar_nav map_gmapping_launch.py",
            server.TASK_COMMANDS["map_gmapping"],
        )
        self.assertEqual(override, server.TASK_COMMANDS["nav_astar_rpp"])

    def test_container_switch_applies_detected_navigation_profile(self):
        class Bridge:
            def shutdown(self, *args, **kwargs):
                pass

        original_motion = getattr(server, "MOTION_BRIDGE", None)
        original_navigation = getattr(server, "NAVIGATION_BRIDGE", None)
        self.addCleanup(setattr, server, "MOTION_BRIDGE", original_motion)
        self.addCleanup(setattr, server, "NAVIGATION_BRIDGE", original_navigation)
        server.MOTION_BRIDGE = Bridge()
        server.NAVIGATION_BRIDGE = Bridge()
        server.SERVER_CONFIG["container"] = "8b98"
        server.SERVER_CONFIG["dry_run"] = True
        profile = {
            "package": "yahboomcar_nav",
            "ready": True,
            "missing_navigation_launches": {},
        }

        with patch.object(server, "configured_container_is_running", return_value=True), patch.object(
            server, "detect_navigation_profile", return_value=profile
        ), patch.object(server, "create_motion_bridge", return_value=Bridge()), patch.object(
            server, "create_navigation_bridge", return_value=Bridge()
        ):
            status, body = server.select_container("ed97")

        self.assertEqual(200, status)
        self.assertTrue(body["changed"])
        self.assertEqual("yahboomcar_nav", body["navigation_package"])
        self.assertEqual(
            "ros2 launch yahboomcar_nav navigation_dwa_launch.py",
            server.TASK_COMMANDS["nav_dwa"],
        )

    def test_main_rejects_stopped_container_before_creating_bridges(self):
        with patch("sys.argv", ["server.py", "--container", "8b98"]), patch(
            "sys.stderr", new=io.StringIO()
        ), patch.object(
            server, "configured_container_is_running", return_value=False
        ), patch.object(server, "create_motion_bridge") as create_motion, self.assertRaises(
            SystemExit
        ):
            server.main()

        create_motion.assert_not_called()


class AutomationWorkflowTest(unittest.TestCase):
    def test_process_snapshot_keeps_complete_launch_process_list(self):
        output = (
            "100 ros2 launch icar_nav navigation_dwa_launch.py\n"
            + "x" * 5000
        )
        with patch.object(
            server,
            "run_once",
            return_value={"returncode": 0, "stdout": output, "stderr": ""},
        ) as run_once:
            snapshot = server.task_process_snapshot()

        run_once.assert_called_once_with(
            "ps -eo pid=,args=", timeout=3, output_limit=None
        )
        self.assertIn("navigation_dwa_launch.py", snapshot["nav_dwa"])

    def test_container_id_validation_accepts_docker_names_and_rejects_shell_text(self):
        self.assertTrue(server.valid_container_id("8b98"))
        self.assertTrue(server.valid_container_id("icar-foxy_1.0"))
        self.assertFalse(server.valid_container_id(""))
        self.assertFalse(server.valid_container_id("8b98; rm -rf /"))

    def test_container_selection_rejects_a_container_that_is_not_running(self):
        with patch.object(server, "configured_container_is_running", return_value=False):
            status, body = server.select_container("missing-container")

        self.assertEqual(404, status)
        self.assertEqual("container is not running", body["error"])

    def test_mapping_stops_navigation_then_starts_m1_and_m2(self):
        calls = []

        def fake_stop_task(task):
            calls.append(("stop", task))
            return 200, {"task": task, "status": "stopped"}

        def fake_stop_tasks(tasks):
            results = []
            for task in reversed(tuple(tasks)):
                calls.append(("stop", task))
                results.append({"task": task, "status": "stopped"})
            return results

        def fake_start(task):
            calls.append(("start", task))
            return 200, {"task": task, "status": "started"}

        with patch.object(server, "stop_task", side_effect=fake_stop_task), patch.object(
            server, "stop_tasks", side_effect=fake_stop_tasks
        ), patch.object(
            server, "start_task", side_effect=fake_start
        ):
            status, body = server.start_automatic_mapping()

        self.assertEqual(200, status)
        self.assertEqual("running", body["status"])
        self.assertEqual(
            [("start", "map_gmapping"), ("start", "map_display")],
            [call for call in calls if call[0] == "start"],
        )
        self.assertEqual(["m1", "m2"], body["steps"])
        self.assertIn(("stop", "base"), calls)
        self.assertLess(calls.index(("stop", "base")), calls.index(("start", "map_gmapping")))
        self.assertLess(calls.index(("stop", "nav_dwa")), calls.index(("start", "map_gmapping")))
        self.assertIn(("stop", "map_gmapping"), calls)
        self.assertLess(calls.index(("stop", "map_gmapping")), calls.index(("start", "map_gmapping")))

    def test_navigation_starts_n1_n2_then_selected_algorithm(self):
        started = []

        def fake_start(task):
            started.append(task)
            return 200, {"task": task, "status": "started"}

        with patch.object(server, "stop_task", return_value=(200, {"status": "stopped"})), patch.object(
            server, "stop_tasks", return_value=[]
        ), patch.object(
            server, "start_task", side_effect=fake_start
        ):
            status, body = server.start_automatic_navigation("teb")

        self.assertEqual(200, status)
        self.assertEqual(["nav_laser", "nav_display", "nav_teb"], started)
        self.assertEqual(["n1", "n2", "n4"], body["steps"])
        self.assertEqual("teb", body["algorithm"])
        self.assertEqual("stopped", body["stopped_base"]["status"])

    def test_navigation_rejects_unknown_algorithm(self):
        status, body = server.start_automatic_navigation("astar")

        self.assertEqual(400, status)
        self.assertEqual("algorithm must be dwa, teb or astar_rpp", body["error"])

    def test_n5_starts_astar_and_rpp_profile(self):
        started = []

        def fake_start(task):
            started.append(task)
            return 200, {"task": task, "status": "started"}

        with patch.object(server, "stop_task", return_value=(200, {"status": "stopped"})), patch.object(
            server, "stop_tasks", return_value=[]
        ), patch.object(
            server, "start_task", side_effect=fake_start
        ):
            status, body = server.start_automatic_navigation("astar_rpp")

        self.assertEqual(200, status)
        self.assertEqual(["nav_laser", "nav_display", "nav_astar_rpp"], started)
        self.assertEqual(["n1", "n2", "n5"], body["steps"])

    def test_save_map_runs_m4_before_stopping_mapping_nodes(self):
        calls = []

        def fake_start(task):
            calls.append(("start", task))
            return 200, {"task": task, "status": "finished", "result": {"returncode": 0}}

        def fake_stop_tasks(tasks):
            results = []
            for task in reversed(tuple(tasks)):
                calls.append(("stop", task))
                results.append({"task": task, "status": "stopped"})
            return results

        with patch.object(server, "start_task", side_effect=fake_start), patch.object(
            server, "stop_tasks", side_effect=fake_stop_tasks
        ):
            status, body = server.save_automatic_mapping()

        self.assertEqual(200, status)
        self.assertEqual("saved", body["status"])
        self.assertEqual(("start", "map_save"), calls[0])
        self.assertEqual([("stop", "map_display"), ("stop", "map_gmapping")], calls[1:])


class FakeMotionBridge:
    def __init__(self, ready=True):
        self.ready = ready
        self.calls = []

    def is_running(self):
        return self.ready

    def send(self, direction, speed, turn):
        self.calls.append((direction, speed, turn))
        return {
            "ok": True,
            "sequence": len(self.calls),
            "direction": direction,
            "bridge_latency_ms": 2.5,
        }


class FailingMotionBridge(FakeMotionBridge):
    def send(self, direction, speed, turn):
        self.calls.append((direction, speed, turn))
        raise RuntimeError("bridge offline")


class FakeNavigationBridge:
    def __init__(self, ready=True):
        self.ready = ready
        self.calls = []

    def is_running(self):
        return self.ready

    def snapshot(self, map_generation=-1):
        self.calls.append(map_generation)
        return {
            "map_generation": 4,
            "map": None,
            "pose": {"x": 1.0, "y": 2.0, "yaw": 0.5},
            "goal": None,
            "path": [],
            "updated_at": 10.0,
        }

    def follow_waypoints(self, points, start=None):
        self.calls.append(("follow", points, start))
        return {"state": "submitting", "total": len(points), "current_index": 0}

    def cancel_waypoints(self):
        self.calls.append(("cancel",))
        return {"state": "canceling"}

    def reset_map(self):
        self.calls.append(("reset_map",))
        return {"ok": True, "map_generation": 5}


class FakeCameraService:
    def __init__(self, state="idle"):
        self.state = state
        self.restart_calls = 0

    def status(self):
        return {
            "state": self.state,
            "device": "/dev/video13",
            "clients": 0,
            "fps": 18.0,
            "width": 640,
            "height": 480,
            "sequence": 12,
            "error": None,
        }

    def restart(self):
        self.restart_calls += 1
        self.state = "connecting"
        return self.status()


class ServerMainLifecycleTest(unittest.TestCase):
    def test_main_shuts_down_camera_before_closing_server(self):
        events = []

        class LifecycleCamera:
            def shutdown(self):
                events.append("camera.shutdown")

        class LifecycleBridge:
            def __init__(self, name):
                self.name = name

            def shutdown(self):
                events.append(f"{self.name}.shutdown")

        class LifecycleServer:
            def __init__(self, address, handler):
                self.address = address
                self.handler = handler

            def serve_forever(self):
                events.append("serve_forever")
                raise KeyboardInterrupt

            def server_close(self):
                events.append("server.close")

        def restore_global(name, existed, value):
            if existed:
                setattr(server, name, value)
            elif hasattr(server, name):
                delattr(server, name)

        camera_existed = hasattr(server, "CAMERA_STREAM")
        bridge_existed = hasattr(server, "MOTION_BRIDGE")
        original_camera = getattr(server, "CAMERA_STREAM", None)
        original_bridge = getattr(server, "MOTION_BRIDGE", None)
        navigation_existed = hasattr(server, "NAVIGATION_BRIDGE")
        original_navigation = getattr(server, "NAVIGATION_BRIDGE", None)
        self.addCleanup(restore_global, "CAMERA_STREAM", camera_existed, original_camera)
        self.addCleanup(restore_global, "MOTION_BRIDGE", bridge_existed, original_bridge)
        self.addCleanup(restore_global, "NAVIGATION_BRIDGE", navigation_existed, original_navigation)

        with patch("sys.argv", ["server.py", "--dry-run"]), patch.object(
            server, "create_camera_stream", return_value=LifecycleCamera()
        ), patch.object(
            server, "create_motion_bridge", return_value=LifecycleBridge("motion")
        ), patch.object(
            server, "create_navigation_bridge", return_value=LifecycleBridge("navigation")
        ), patch.object(
            server, "ThreadingHTTPServer", LifecycleServer
        ), self.assertRaises(KeyboardInterrupt):
            server.main()

        self.assertEqual(
            ["serve_forever", "camera.shutdown", "server.close", "motion.shutdown", "navigation.shutdown"],
            events,
        )

    def test_main_shuts_down_services_when_server_bind_fails(self):
        events = []

        class LifecycleCamera:
            def shutdown(self):
                events.append("camera.shutdown")

        class LifecycleBridge:
            def __init__(self, name):
                self.name = name

            def shutdown(self):
                events.append(f"{self.name}.shutdown")

        def failing_server_factory(address, handler):
            events.append("server.bind")
            raise OSError("address already in use")

        camera_existed = hasattr(server, "CAMERA_STREAM")
        bridge_existed = hasattr(server, "MOTION_BRIDGE")
        original_camera = getattr(server, "CAMERA_STREAM", None)
        original_bridge = getattr(server, "MOTION_BRIDGE", None)
        navigation_existed = hasattr(server, "NAVIGATION_BRIDGE")
        original_navigation = getattr(server, "NAVIGATION_BRIDGE", None)

        def restore_global(name, existed, value):
            if existed:
                setattr(server, name, value)
            elif hasattr(server, name):
                delattr(server, name)

        self.addCleanup(restore_global, "CAMERA_STREAM", camera_existed, original_camera)
        self.addCleanup(restore_global, "MOTION_BRIDGE", bridge_existed, original_bridge)
        self.addCleanup(restore_global, "NAVIGATION_BRIDGE", navigation_existed, original_navigation)

        with patch("sys.argv", ["server.py", "--dry-run"]), patch.object(
            server, "create_camera_stream", return_value=LifecycleCamera()
        ), patch.object(
            server, "create_motion_bridge", return_value=LifecycleBridge("motion")
        ), patch.object(
            server, "create_navigation_bridge", return_value=LifecycleBridge("navigation")
        ), patch.object(
            server, "ThreadingHTTPServer", side_effect=failing_server_factory
        ), self.assertRaisesRegex(OSError, "address already in use"):
            server.main()

        self.assertEqual(
            ["server.bind", "camera.shutdown", "motion.shutdown", "navigation.shutdown"],
            events,
        )


class MotionRouteTest(unittest.TestCase):
    def setUp(self):
        self.original_bridge = getattr(server, "MOTION_BRIDGE", None)
        self.original_camera = getattr(server, "CAMERA_STREAM", None)
        self.original_navigation_bridge = getattr(server, "NAVIGATION_BRIDGE", None)
        self.original_dry_run = server.SERVER_CONFIG["dry_run"]
        self.original_container_running = getattr(server, "container_is_running", None)
        self.original_task_processes = getattr(server, "task_processes", None)
        server.SERVER_CONFIG["dry_run"] = True
        server.RUNNING.clear()
        server.NAVIGATION_BRIDGE = FakeNavigationBridge()
        self.handler = object.__new__(server.Handler)

    def tearDown(self):
        server.SERVER_CONFIG["dry_run"] = self.original_dry_run
        if self.original_bridge is None:
            if hasattr(server, "MOTION_BRIDGE"):
                delattr(server, "MOTION_BRIDGE")
        else:
            server.MOTION_BRIDGE = self.original_bridge
        if self.original_camera is None:
            if hasattr(server, "CAMERA_STREAM"):
                delattr(server, "CAMERA_STREAM")
        else:
            server.CAMERA_STREAM = self.original_camera
        server.NAVIGATION_BRIDGE = self.original_navigation_bridge
        if self.original_container_running is not None:
            server.container_is_running = self.original_container_running
        if self.original_task_processes is not None:
            server.task_processes = self.original_task_processes

    def test_move_route_uses_persistent_bridge(self):
        bridge = FakeMotionBridge()
        server.MOTION_BRIDGE = bridge

        status, body = self.handler.route(
            "move/front",
            {"speed": ["0.12"], "turn": ["0.7"]},
        )

        self.assertEqual(200, status)
        self.assertEqual([("front", 0.12, 0.7)], bridge.calls)
        self.assertTrue(body["ok"])
        self.assertEqual(2.5, body["bridge_latency_ms"])
        self.assertEqual("dry_run", body["base"])

    def test_stop_route_does_not_start_base(self):
        bridge = FakeMotionBridge()
        server.MOTION_BRIDGE = bridge

        with patch.object(server, "ensure_base_for_manual_motion") as ensure_base:
            status, body = self.handler.route("move/stop", {})

        self.assertEqual(200, status)
        ensure_base.assert_not_called()
        self.assertEqual([("stop", 0.18, 0.8)], bridge.calls)

    def test_move_route_clamps_speed_and_turn_to_safe_ranges(self):
        bridge = FakeMotionBridge()
        server.MOTION_BRIDGE = bridge

        self.handler.route(
            "move/front",
            {"speed": ["9.0"], "turn": ["0.01"]},
        )

        self.assertEqual([("front", 0.35, 0.2)], bridge.calls)

    def test_health_reports_motion_bridge_readiness(self):
        server.MOTION_BRIDGE = FakeMotionBridge(ready=True)
        self.assertTrue(hasattr(server, "container_is_running"), "server is missing container_is_running")
        server.container_is_running = lambda: True

        status, body = self.handler.route("health", {})

        self.assertEqual(200, status)
        self.assertIn("motion_bridge_ready", body)
        self.assertTrue(body["motion_bridge_ready"])
        self.assertTrue(body["container_running"])
        self.assertTrue(body["navigation_bridge_ready"])
        self.assertIn("navigation_package", body)
        self.assertIn("navigation_tasks_ready", body)
        self.assertIn("missing_navigation_launches", body)

    def test_navigation_state_returns_live_bridge_snapshot(self):
        bridge = FakeNavigationBridge()
        server.NAVIGATION_BRIDGE = bridge

        status, body = self.handler.route("navigation/state", {"map_generation": ["3"]})

        self.assertEqual(200, status)
        self.assertEqual([3], bridge.calls)
        self.assertEqual(4, body["map_generation"])
        self.assertEqual(1.0, body["pose"]["x"])

    def test_status_uses_actual_container_process_discovery(self):
        self.assertTrue(hasattr(server, "task_process_snapshot"), "server is missing task_process_snapshot")
        server.MOTION_BRIDGE = FakeMotionBridge(ready=True)
        snapshot = {
            task: "42 process" if task == "base" else ""
            for task in server.TASK_COMMANDS
        }

        with patch.object(server, "task_process_snapshot", return_value=snapshot):
            status, body = self.handler.route("status", {})

        self.assertEqual(200, status)
        self.assertTrue(body["base"]["running"])
        self.assertEqual("42 process", body["base"]["process"])
        self.assertFalse(body["lidar"]["running"])

    def test_camera_status_route_returns_capture_snapshot(self):
        server.CAMERA_STREAM = FakeCameraService(state="live")

        status, body = self.handler.route("camera/status", {})

        self.assertEqual(200, status)
        self.assertEqual("live", body["state"])
        self.assertEqual("/dev/video13", body["device"])
        self.assertEqual(640, body["width"])

    def test_camera_restart_route_reopens_local_device(self):
        camera = FakeCameraService(state="busy")
        server.CAMERA_STREAM = camera

        status, body = self.handler.route("camera/restart", {})

        self.assertEqual(200, status)
        self.assertEqual(1, camera.restart_calls)
        self.assertEqual("connecting", body["state"])

    def test_camera_status_route_returns_503_when_service_is_unavailable(self):
        server.CAMERA_STREAM = None

        status, body = self.handler.route("camera/status", {})

        self.assertEqual(503, status)
        self.assertEqual(
            {"state": "unavailable", "error": "camera service unavailable"},
            body,
        )

    def test_camera_restart_route_returns_503_when_service_is_unavailable(self):
        server.CAMERA_STREAM = None

        status, body = self.handler.route("camera/restart", {})

        self.assertEqual(503, status)
        self.assertEqual(
            {"state": "unavailable", "error": "camera service unavailable"},
            body,
        )

    def test_notify_route_speaks_known_parking_event(self):
        calls = []

        with patch.object(server, "speak_text", side_effect=lambda text: {"ok": True, "text": text}) as speak_mock:
            status, body = self.handler.route("notify/patrol_start", {})

        self.assertEqual(200, status)
        self.assertTrue(body["ok"])
        self.assertEqual("patrol_start", body["event"])
        speak_mock.assert_called_once_with("开始停车场巡逻，请注意避让。")

    def test_speak_route_accepts_json_body_text(self):
        handler = object.__new__(server.Handler)
        handler.path = "/speak"
        payload = '{"text":"开始巡逻"}'.encode("utf-8")
        handler.headers = {"Content-Length": str(len(payload))}
        handler.rfile = FakeRequestBody(payload)
        captured = {}
        handler.reply = lambda status, body: captured.update({"status": status, "body": body})

        with patch.object(server, "speak_text", return_value={"ok": True, "text": "开始巡逻"}):
            handler.do_POST()

        self.assertEqual(200, captured["status"])
        self.assertTrue(captured["body"]["ok"])
        self.assertEqual("开始巡逻", captured["body"]["text"])

    def test_speak_route_accepts_voice_rate_and_pitch(self):
        handler = object.__new__(server.Handler)
        handler.path = "/speak"
        payload = json.dumps({
            "text": "开始巡逻",
            "voice": "zh-CN-YunyangNeural",
            "rate": "+8%",
            "pitch": "+2Hz",
        }).encode("utf-8")
        handler.headers = {"Content-Length": str(len(payload))}
        handler.rfile = FakeRequestBody(payload)
        captured = {}
        handler.reply = lambda status, body: captured.update({"status": status, "body": body})

        with patch.object(server, "speak_text", return_value={"ok": True, "text": "开始巡逻"}) as speak_mock:
            handler.do_POST()

        self.assertEqual(200, captured["status"])
        speak_mock.assert_called_once_with(
            "开始巡逻",
            voice="zh-CN-YunyangNeural",
            rate="+8%",
            pitch="+2Hz",
        )

    def test_speak_text_prefers_edge_tts_with_selected_voice(self):
        server.SERVER_CONFIG["dry_run"] = False
        run_calls = []

        def fake_run(command, **_kwargs):
            run_calls.append(command)
            return FakeCompletedProcess(returncode=0)

        with patch.object(server, "command_exists", return_value=True), patch.object(
            server.subprocess, "run", side_effect=fake_run
        ):
            result = server.speak_text(
                "开始巡逻",
                voice="zh-CN-YunyangNeural",
                rate="+8%",
                pitch="+2Hz",
            )

        self.assertTrue(result["ok"])
        self.assertEqual("edge-tts", result["engine"])
        edge_calls = [command for command in run_calls if command and command[0] == "edge-tts"]
        self.assertEqual(1, len(edge_calls))
        self.assertIn("zh-CN-YunyangNeural", edge_calls[0])
        self.assertIn("+8%", edge_calls[0])
        self.assertIn("+2Hz", edge_calls[0])
        ffplay_calls = [command for command in run_calls if command and command[0] == "ffplay"]
        self.assertEqual(1, len(ffplay_calls))
        self.assertTrue(ffplay_calls[0][-1].endswith(".mp3"))

    def test_speak_text_falls_back_to_generated_wav_file_when_edge_tts_is_unavailable(self):
        server.SERVER_CONFIG["dry_run"] = False
        run_calls = []

        def fake_run(command, **_kwargs):
            run_calls.append(command)
            return FakeCompletedProcess(returncode=0)

        def fake_command_exists(command):
            return command in {"espeak-ng", "paplay"}

        with patch.object(server, "command_exists", side_effect=fake_command_exists), patch.object(
            server.subprocess, "run", side_effect=fake_run
        ):
            result = server.speak_text("开始巡逻")

        self.assertTrue(result["ok"])
        self.assertEqual("espeak-ng", result["engine"])
        paplay_calls = [command for command in run_calls if command and command[0] == "paplay"]
        self.assertEqual(1, len(paplay_calls))
        self.assertNotEqual("-", paplay_calls[0][-1])
        self.assertTrue(paplay_calls[0][-1].endswith(".wav"))

    def test_emergency_stop_uses_persistent_bridge(self):
        bridge = FakeMotionBridge()
        server.MOTION_BRIDGE = bridge

        status, body = self.handler.route("emergency_stop", {})

        self.assertEqual(200, status)
        self.assertEqual([("stop", 0.0, 0.0)], bridge.calls)
        self.assertEqual("emergency_stop", body["status"])

    def test_emergency_stop_falls_back_and_still_stops_tasks_when_bridge_fails(self):
        bridge = FailingMotionBridge()
        server.MOTION_BRIDGE = bridge
        fallback = {"returncode": 0, "stdout": "", "stderr": ""}

        with patch.object(server, "run_once", return_value=fallback) as run_once_mock, patch.object(
            server, "stop_task", return_value=(200, {"stopped": []})
        ) as stop_task_mock:
            status, body = self.handler.route("emergency_stop", {})

        self.assertEqual(200, status)
        self.assertEqual("fallback", body["result"]["mode"])
        self.assertEqual("bridge offline", body["result"]["bridge_error"])
        run_once_mock.assert_called_once_with(
            server.build_twist_command("stop", 0.0, 0.0),
            timeout=3,
        )
        stop_task_mock.assert_called_once_with("all")

    def test_stop_all_publishes_zero_velocity_before_stopping_tasks(self):
        bridge = FakeMotionBridge()
        server.MOTION_BRIDGE = bridge

        status, _body = self.handler.route("stop/all", {})

        self.assertEqual(200, status)
        self.assertEqual([("stop", 0.0, 0.0)], bridge.calls)


class FakeProcessOutput:
    def __init__(self):
        self.lines = queue.Queue()

    def readline(self):
        return self.lines.get(timeout=1.0)


class FakeRequestBody:
    def __init__(self, payload):
        self.payload = payload

    def read(self, size=-1):
        if size < 0:
            return self.payload
        return self.payload[:size]


class FakeCompletedProcess:
    def __init__(self, returncode=0, stdout="", stderr=b""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class FakeProcessInput:
    def __init__(self, process, malformed=False):
        self.process = process
        self.malformed = malformed
        self.writes = []

    def write(self, raw):
        self.writes.append(raw)
        command = json.loads(raw)
        if self.malformed:
            self.malformed = False
            self.process.stdout.lines.put("not-json\n")
        else:
            self.process.stdout.lines.put(json.dumps({
                "ok": True,
                "sequence": command["sequence"],
                "direction": command["direction"],
                "bridge_latency_ms": 1.5,
            }) + "\n")

    def flush(self):
        pass

    def close(self):
        pass


class FakeProcess:
    def __init__(self, malformed=False):
        self.returncode = None
        self.stdout = FakeProcessOutput()
        self.stdin = FakeProcessInput(self, malformed=malformed)

    def poll(self):
        return self.returncode

    def terminate(self):
        self.returncode = 0
        self.stdout.lines.put("")

    def wait(self, timeout=None):
        return self.returncode

    def kill(self):
        self.terminate()


class FakeProcessFactory:
    def __init__(self, malformed_first=False):
        self.malformed_first = malformed_first
        self.processes = []
        self.commands = []

    def __call__(self, command, **_kwargs):
        malformed = self.malformed_first and not self.processes
        process = FakeProcess(malformed=malformed)
        self.processes.append(process)
        self.commands.append(command)
        return process


class FakeCopyRunner:
    def __init__(self):
        self.commands = []

    def __call__(self, command, **_kwargs):
        self.commands.append(command)


class MotionBridgeClientTest(unittest.TestCase):
    def test_bridge_process_uses_robot_ros_domain(self):
        client_type = self.required_client()
        process_factory = FakeProcessFactory()
        client = client_type(
            container="8b98",
            script_path=Path(__file__).with_name("motion_bridge.py"),
            process_factory=process_factory,
            copy_runner=FakeCopyRunner(),
            response_timeout=0.1,
        )

        client.send("stop", 0.0, 0.0)

        shell_command = process_factory.commands[0][-1]
        self.assertIn("export ROS_DOMAIN_ID=32", shell_command)
        self.assertIn("export ROBOT_TYPE=x3", shell_command)
        self.assertIn("export RPLIDAR_TYPE=a1", shell_command)
        client.shutdown(send_stop=False)

    def test_multiple_commands_reuse_one_persistent_process(self):
        client_type = self.required_client()
        process_factory = FakeProcessFactory()
        copy_runner = FakeCopyRunner()
        client = client_type(
            container="8b98",
            script_path=Path(__file__).with_name("motion_bridge.py"),
            process_factory=process_factory,
            copy_runner=copy_runner,
            response_timeout=0.1,
        )

        first = client.send("front", 0.1, 0.6)
        second = client.send("stop", 0.0, 0.0)

        self.assertEqual(1, len(process_factory.processes))
        self.assertEqual(2, len(copy_runner.commands))
        self.assertEqual(
            "pkill -f '[i]car_motion_bridge.py' || true",
            copy_runner.commands[1][-1],
        )
        self.assertEqual("cp", copy_runner.commands[0][1])
        self.assertEqual("exec", copy_runner.commands[1][1])
        self.assertEqual(1, first["sequence"])
        self.assertEqual(2, second["sequence"])
        client.shutdown(send_stop=False)

    def test_malformed_response_restarts_bridge_once(self):
        client_type = self.required_client()
        process_factory = FakeProcessFactory(malformed_first=True)
        client = client_type(
            container="8b98",
            script_path=Path(__file__).with_name("motion_bridge.py"),
            process_factory=process_factory,
            copy_runner=FakeCopyRunner(),
            response_timeout=0.1,
        )

        result = client.send("front", 0.1, 0.6)

        self.assertTrue(result["ok"])
        self.assertEqual(2, len(process_factory.processes))
        client.shutdown(send_stop=False)

    def test_shutdown_sends_stop_before_terminating_process(self):
        client_type = self.required_client()
        process_factory = FakeProcessFactory()
        client = client_type(
            container="8b98",
            script_path=Path(__file__).with_name("motion_bridge.py"),
            process_factory=process_factory,
            copy_runner=FakeCopyRunner(),
            response_timeout=0.1,
        )
        client.send("front", 0.1, 0.6)

        client.shutdown()

        directions = [json.loads(raw)["direction"] for raw in process_factory.processes[0].stdin.writes]
        self.assertEqual(["front", "stop"], directions)

    def required_client(self):
        self.assertTrue(hasattr(server, "MotionBridgeClient"), "server is missing MotionBridgeClient")
        return server.MotionBridgeClient


class CameraStreamHttpTest(unittest.TestCase):
    def test_mjpeg_chunk_has_boundary_length_and_jpeg(self):
        jpeg = b"\xff\xd8frame\xff\xd9"

        chunk = server.build_mjpeg_chunk(jpeg)

        self.assertTrue(chunk.startswith(b"--frame\r\n"))
        self.assertIn(b"Content-Type: image/jpeg\r\n", chunk)
        self.assertIn(b"Content-Length: 9\r\n", chunk)
        self.assertTrue(chunk.endswith(jpeg + b"\r\n"))

    def test_stream_writes_frame_and_releases_client_after_disconnect(self):
        camera = FakeStreamingCamera(ready=True)
        handler = self.streaming_handler(disconnect_on_write=True)
        original_camera = getattr(server, "CAMERA_STREAM", None)
        server.CAMERA_STREAM = camera
        try:
            handler.stream_camera()
        finally:
            server.CAMERA_STREAM = original_camera

        self.assertEqual(200, handler.response_code)
        self.assertEqual(
            "multipart/x-mixed-replace; boundary=frame",
            handler.headers["Content-Type"],
        )
        self.assertIn(b"\xff\xd8frame\xff\xd9", handler.wfile.payload)
        self.assertEqual(1, camera.acquire_calls)
        self.assertEqual(1, camera.release_calls)

    def test_stream_returns_503_and_releases_when_camera_is_busy(self):
        camera = FakeStreamingCamera(ready=False, state="busy")
        handler = self.streaming_handler(disconnect_on_write=False)
        original_camera = getattr(server, "CAMERA_STREAM", None)
        server.CAMERA_STREAM = camera
        try:
            handler.stream_camera()
        finally:
            server.CAMERA_STREAM = original_camera

        self.assertEqual(503, handler.response_code)
        self.assertIn(b"camera busy", handler.wfile.payload)
        self.assertEqual(1, camera.release_calls)

    def test_stream_ends_and_releases_when_live_camera_disconnects(self):
        camera = FakeStreamingCamera(ready=True, state="disconnected", frame=None)
        handler = self.streaming_handler(disconnect_on_write=False)
        original_camera = getattr(server, "CAMERA_STREAM", None)
        server.CAMERA_STREAM = camera
        try:
            handler.stream_camera()
        finally:
            server.CAMERA_STREAM = original_camera

        self.assertEqual(1, camera.wait_calls)
        self.assertEqual(1, camera.release_calls)

    def test_stream_write_timeout_releases_camera_and_restores_socket_timeout(self):
        camera = FakeStreamingCamera(ready=True)
        connection = FakeConnection(timeout=None)
        handler = self.streaming_handler(
            disconnect_on_write=False,
            write_error=socket.timeout("slow client"),
            connection=connection,
        )
        original_camera = getattr(server, "CAMERA_STREAM", None)
        server.CAMERA_STREAM = camera
        try:
            handler.stream_camera()
        finally:
            server.CAMERA_STREAM = original_camera

        self.assertEqual([2.0, None], connection.timeouts)
        self.assertEqual(1, camera.release_calls)

    def test_stream_disconnect_oserror_releases_camera(self):
        camera = FakeStreamingCamera(ready=True)
        handler = self.streaming_handler(
            disconnect_on_write=False,
            write_error=OSError(errno.ECONNRESET, "connection reset"),
        )
        original_camera = getattr(server, "CAMERA_STREAM", None)
        server.CAMERA_STREAM = camera
        try:
            handler.stream_camera()
        finally:
            server.CAMERA_STREAM = original_camera

        self.assertEqual(1, camera.release_calls)

    def test_actual_camera_status_unavailable_response_has_json_headers(self):
        original_camera = getattr(server, "CAMERA_STREAM", None)
        server.CAMERA_STREAM = None
        http_server = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        thread = threading.Thread(target=http_server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = http.client.HTTPConnection(
                "127.0.0.1",
                http_server.server_address[1],
                timeout=2.0,
            )
            connection.request("GET", "/camera/status")
            response = connection.getresponse()
            payload = json.loads(response.read())
            connection.close()
        finally:
            http_server.shutdown()
            http_server.server_close()
            thread.join(timeout=2.0)
            server.CAMERA_STREAM = original_camera

        self.assertFalse(thread.is_alive())
        self.assertEqual(503, response.status)
        self.assertEqual("application/json; charset=utf-8", response.getheader("Content-Type"))
        self.assertEqual("*", response.getheader("Access-Control-Allow-Origin"))
        self.assertEqual(
            {"state": "unavailable", "error": "camera service unavailable"},
            payload,
        )

    def test_actual_camera_stream_has_no_cache_headers_and_releases_client(self):
        camera = OneFrameStreamingCamera()
        original_camera = getattr(server, "CAMERA_STREAM", None)
        server.CAMERA_STREAM = camera
        http_server = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        thread = threading.Thread(target=http_server.serve_forever, daemon=True)
        thread.start()
        try:
            connection = http.client.HTTPConnection(
                "127.0.0.1",
                http_server.server_address[1],
                timeout=2.0,
            )
            connection.request("GET", "/camera/stream")
            response = connection.getresponse()
            payload = response.read()
            connection.close()
        finally:
            http_server.shutdown()
            http_server.server_close()
            thread.join(timeout=2.0)
            server.CAMERA_STREAM = original_camera

        self.assertFalse(thread.is_alive())
        self.assertEqual(200, response.status)
        self.assertEqual("no-store, no-cache, must-revalidate", response.getheader("Cache-Control"))
        self.assertEqual("no-cache", response.getheader("Pragma"))
        self.assertIn(b"\xff\xd8frame\xff\xd9", payload)
        self.assertEqual(1, camera.acquire_calls)
        self.assertEqual(1, camera.release_calls)

    @staticmethod
    def streaming_handler(disconnect_on_write, write_error=None, connection=None):
        handler = object.__new__(server.Handler)
        handler.response_code = None
        handler.headers = {}
        handler.wfile = DisconnectingOutput(disconnect_on_write, write_error=write_error)
        if connection is not None:
            handler.connection = connection
        handler.send_response = lambda code: setattr(handler, "response_code", code)
        handler.send_header = lambda key, value: handler.headers.__setitem__(key, value)
        handler.end_headers = lambda: None
        return handler


class FakeStreamingCamera:
    def __init__(self, ready, state="live", frame=b"\xff\xd8frame\xff\xd9"):
        self.ready = ready
        self.state = state
        self.frame = frame
        self.acquire_calls = 0
        self.release_calls = 0
        self.wait_calls = 0

    def acquire_client(self):
        self.acquire_calls += 1

    def release_client(self):
        self.release_calls += 1

    def wait_until_ready(self, timeout):
        return self.ready

    def wait_for_frame(self, after_sequence, timeout):
        self.wait_calls += 1
        if self.wait_calls > 1:
            raise AssertionError("stream loop continued after terminal camera state")
        return 1, self.frame

    def status(self):
        return {
            "state": self.state,
            "error": f"camera {self.state}",
        }


class OneFrameStreamingCamera(FakeStreamingCamera):
    def __init__(self):
        super().__init__(ready=True)

    def wait_for_frame(self, after_sequence, timeout):
        self.wait_calls += 1
        if self.wait_calls == 1:
            return 1, self.frame
        self.state = "disconnected"
        return 1, None


class FakeConnection:
    def __init__(self, timeout):
        self.timeout = timeout
        self.timeouts = []

    def gettimeout(self):
        return self.timeout

    def settimeout(self, timeout):
        self.timeout = timeout
        self.timeouts.append(timeout)


class DisconnectingOutput:
    def __init__(self, disconnect_on_write, write_error=None):
        self.payload = b""
        self.disconnect_on_write = disconnect_on_write
        self.write_error = write_error

    def write(self, value):
        self.payload += value
        if self.write_error is not None:
            raise self.write_error
        if self.disconnect_on_write:
            raise BrokenPipeError("client disconnected")

    def flush(self):
        pass


if __name__ == "__main__":
    unittest.main()
