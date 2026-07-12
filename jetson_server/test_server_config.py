import json
import queue
import unittest
from pathlib import Path
from unittest.mock import patch

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

    def test_motion_limits_preserve_safe_remote_control_ranges(self):
        self.assertEqual(0.18, server.clamp_float("bad", 0.18, 0.05, 0.35))
        self.assertEqual(0.05, server.clamp_float("0.01", 0.18, 0.05, 0.35))
        self.assertEqual(0.35, server.clamp_float("0.8", 0.18, 0.05, 0.35))
        self.assertEqual(0.8, server.clamp_float("0.8", 0.8, 0.2, 1.2))

    def test_every_task_has_a_process_detection_pattern(self):
        self.assertTrue(hasattr(server, "TASK_PATTERNS"), "server is missing TASK_PATTERNS")
        self.assertEqual(set(server.TASK_COMMANDS), set(server.TASK_PATTERNS))


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


class MotionRouteTest(unittest.TestCase):
    def setUp(self):
        self.original_bridge = getattr(server, "MOTION_BRIDGE", None)
        self.original_dry_run = server.SERVER_CONFIG["dry_run"]
        self.original_container_running = getattr(server, "container_is_running", None)
        self.original_task_processes = getattr(server, "task_processes", None)
        server.SERVER_CONFIG["dry_run"] = True
        server.RUNNING.clear()
        self.handler = object.__new__(server.Handler)

    def tearDown(self):
        server.SERVER_CONFIG["dry_run"] = self.original_dry_run
        if self.original_bridge is None:
            if hasattr(server, "MOTION_BRIDGE"):
                delattr(server, "MOTION_BRIDGE")
        else:
            server.MOTION_BRIDGE = self.original_bridge
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

    def test_status_uses_actual_container_process_discovery(self):
        self.assertTrue(hasattr(server, "task_processes"), "server is missing task_processes")
        server.MOTION_BRIDGE = FakeMotionBridge(ready=True)
        server.task_processes = lambda task: "42 process" if task == "base" else ""

        status, body = self.handler.route("status", {})

        self.assertEqual(200, status)
        self.assertTrue(body["base"]["running"])
        self.assertEqual("42 process", body["base"]["process"])
        self.assertFalse(body["lidar"]["running"])

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
        self.assertEqual(1, len(copy_runner.commands))
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


if __name__ == "__main__":
    unittest.main()
