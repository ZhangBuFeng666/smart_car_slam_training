#!/usr/bin/env python3
import argparse
import errno
import json
import math
import os
import queue
import re
import shlex
import signal
import socket
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

try:
    from .camera_stream import CameraCaptureService
except ImportError:
    from camera_stream import CameraCaptureService


USER_LOCAL_BIN = str(Path.home() / ".local" / "bin")
if USER_LOCAL_BIN not in os.environ.get("PATH", "").split(os.pathsep):
    os.environ["PATH"] = USER_LOCAL_BIN + os.pathsep + os.environ.get("PATH", "")


ROS_DOMAIN_ID = 32
ROBOT_TYPE = "x3"
RPLIDAR_TYPE = "a1"
ROS_SETUP = (
    f"export ROS_DOMAIN_ID={ROS_DOMAIN_ID}; "
    f"export ROBOT_TYPE={ROBOT_TYPE}; "
    f"export RPLIDAR_TYPE={RPLIDAR_TYPE}; "
    "source /opt/ros/foxy/setup.bash; "
    "if [ -f /root/yahboomcar_ros2_ws/yahboomcar_ws/install/setup.bash ]; then "
    "source /root/yahboomcar_ros2_ws/yahboomcar_ws/install/setup.bash; fi; "
    "if [ -f /root/icar_ros2_ws/icar_ws/install/setup.bash ]; then "
    "source /root/icar_ros2_ws/icar_ws/install/setup.bash; fi"
)

DEFAULT_CONTAINER = "8b98"
NAVIGATION_PACKAGES = ("icar_nav", "yahboomcar_nav")
ICAR_MAP_PATH = "/root/icar_ros2_ws/icar_ws/src/icar_nav/maps/icar.yaml"
NAVIGATION_LAUNCH_FILES = {
    "map_gmapping": "map_gmapping_launch.py",
    "map_display": "display_map_launch.py",
    "map_save": "save_map_launch.py",
    "nav_laser": "laser_bringup_launch.py",
    "nav_display": "display_nav_launch.py",
    "nav_dwa": "navigation_dwa_launch.py",
    "nav_teb": "navigation_teb_launch.py",
    "nav_astar_rpp": "navigation_rpp_launch.py",
}
NAVIGATION_TASK_KEYS = frozenset(NAVIGATION_LAUNCH_FILES)

COMMON_TASK_COMMANDS = {
    "base": "ros2 run icar_bringup Mcnamu_driver_X3",
    "lidar": "ros2 launch sllidar_ros2 sllidar_launch.py",
    "avoidance": "ros2 run icar_laser laser_Avoidance_a1_X3",
    "follow": "ros2 run icar_laser laser_Tracker_a1_X3",
    "warning": "ros2 run icar_laser laser_Warning_a1_X3",
    "camera": "ros2 launch astra_camera astra.launch.xml",
    "hsv": "ros2 run icar_astra colorHSV",
    "color_track": "ros2 run icar_astra colorTracker",
}


def build_navigation_commands(package="icar_nav", n5_command=None):
    commands = {
        task: f"ros2 launch {package} {launch_file}"
        for task, launch_file in NAVIGATION_LAUNCH_FILES.items()
    }
    if n5_command:
        commands["nav_astar_rpp"] = n5_command
    elif package == "icar_nav":
        # save_map_launch.py writes icar.yaml into the source package. Pass the
        # same file explicitly because the vendor navigation launches still
        # default to the unrelated yahboomcar.yaml name.
        for task in ("nav_dwa", "nav_teb", "nav_astar_rpp"):
            commands[task] += f" map:={ICAR_MAP_PATH}"
    return commands


TASK_COMMANDS = {
    **COMMON_TASK_COMMANDS,
    **build_navigation_commands(),
}
TASK_PATTERNS = {
    "base": "Mcnamu_driver_X3",
    "lidar": "sllidar_launch.py",
    "avoidance": "laser_Avoidance_a1_X3",
    "follow": "laser_Tracker_a1_X3",
    "warning": "laser_Warning_a1_X3",
    "camera": "astra_camera",
    "hsv": "colorHSV",
    "color_track": "colorTracker",
    "map_gmapping": "map_gmapping_launch.py",
    "map_display": "display_map_launch.py",
    "map_save": "save_map_launch.py",
    "nav_laser": "laser_bringup_launch.py",
    "nav_display": "display_nav_launch.py",
    "nav_dwa": "navigation_dwa_launch.py",
    "nav_teb": "navigation_teb_launch.py",
    "nav_astar_rpp": "navigation_rpp_launch.py",
}

# Launch parents can exit without reaping their ROS children. These additional
# patterns let stop endpoints remove orphaned nodes as well as the launch file.
TASK_CLEANUP_PATTERNS = {
    "map_gmapping": ("slam_gmapping",),
    "map_display": ("rviz2",),
    "nav_laser": (
        "sllidar_node",
        "Mcnamu_driver_X3",
        "robot_state_publisher",
        "joint_state_publisher",
    ),
    "nav_display": ("rviz2",),
    "nav_dwa": (
        "map_server",
        "amcl",
        "controller_server",
        "planner_server",
        "recoveries_server",
        "bt_navigator",
        "waypoint_follower",
        "lifecycle_manager",
    ),
    "nav_teb": (
        "map_server",
        "amcl",
        "controller_server",
        "planner_server",
        "recoveries_server",
        "bt_navigator",
        "waypoint_follower",
        "lifecycle_manager",
    ),
    "nav_astar_rpp": (
        "map_server",
        "amcl",
        "controller_server",
        "planner_server",
        "recoveries_server",
        "bt_navigator",
        "waypoint_follower",
        "lifecycle_manager",
    ),
}

ONE_SHOT_TASKS = {"map_save"}
MAPPING_TASKS = ("map_gmapping", "map_display")
NAVIGATION_SHARED_TASKS = ("nav_laser", "nav_display")
NAVIGATION_ALGORITHMS = {
    "dwa": "nav_dwa",
    "teb": "nav_teb",
    "astar_rpp": "nav_astar_rpp",
}
CONTAINER_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
RUNNING = {}
LOG_HANDLES = {}
SERVER_CONFIG = {
    "container": DEFAULT_CONTAINER,
    "dry_run": False,
    "navigation_package": "icar_nav",
    "navigation_tasks_ready": True,
    "missing_navigation_launches": {},
    "n5_command_override": None,
}
MOTION_BRIDGE = None
NAVIGATION_BRIDGE = None
CAMERA_STREAM = None
AUTOMATION_LOCK = threading.Lock()
BASE_START_LOCK = threading.Lock()
STREAM_WRITE_TIMEOUT = 2.0
STREAM_DISCONNECT_ERRNOS = {
    errno.ECONNABORTED,
    errno.ECONNRESET,
    errno.EPIPE,
    errno.ETIMEDOUT,
}
USB_AUDIO_SINK = "alsa_output.usb-C-Media_Electronics_Inc._USB_Audio_Device-00.analog-stereo"
DEFAULT_TTS_VOICE = os.environ.get("ICAR_TTS_VOICE", "zh-CN-XiaoxiaoNeural")
DEFAULT_TTS_RATE = os.environ.get("ICAR_TTS_RATE", "+0%")
DEFAULT_TTS_PITCH = os.environ.get("ICAR_TTS_PITCH", "+0Hz")
NOTIFICATION_MESSAGES = {
    "connected": "小车服务已连接。",
    "drive_ready": "驾驶模式已就绪。",
    "patrol_start": "开始停车场巡逻，请注意避让。",
    "patrol_stop": "停车场巡逻已停止。",
    "task_start": "实训功能已启动。",
    "task_stop": "实训功能已停止。",
    "camera_ready": "摄像头画面已打开。",
    "emergency_stop": "急停已触发，小车已停止。",
    "all_stop": "全部功能已停止。",
}


class MotionBridgeClient:
    REMOTE_SCRIPT = "/tmp/icar_motion_bridge.py"

    def __init__(
        self,
        container,
        script_path,
        process_factory=subprocess.Popen,
        copy_runner=subprocess.run,
        response_timeout=5.0,
        watchdog_ms=0,
        clock=time.perf_counter,
    ):
        self.container = container
        self.script_path = Path(script_path)
        self.process_factory = process_factory
        self.copy_runner = copy_runner
        self.response_timeout = float(response_timeout)
        self.watchdog_ms = int(watchdog_ms)
        self.clock = clock
        self.lock = threading.Lock()
        self.process = None
        self.responses = queue.Queue()
        self.reader_thread = None
        self.sequence = 0

    def is_running(self):
        return self.process is not None and self.process.poll() is None

    def start(self):
        with self.lock:
            self._ensure_started()

    def send(self, direction, speed, turn):
        with self.lock:
            last_error = None
            for _attempt in range(2):
                try:
                    return self._send_once(direction, speed, turn)
                except Exception as error:
                    last_error = error
                    self._terminate_process()
            raise RuntimeError(f"motion bridge unavailable: {last_error}")

    def shutdown(self, send_stop=True):
        with self.lock:
            if send_stop and self.is_running():
                try:
                    self._send_once("stop", 0.0, 0.0)
                except Exception:
                    pass
            self._terminate_process()

    def _ensure_started(self):
        if self.is_running():
            return
        if not self.script_path.is_file():
            raise FileNotFoundError(f"motion bridge script not found: {self.script_path}")

        self.copy_runner(
            ["docker", "cp", str(self.script_path), f"{self.container}:{self.REMOTE_SCRIPT}"],
            check=True,
            capture_output=True,
            text=True,
        )
        self.copy_runner(
            [
                "docker",
                "exec",
                self.container,
                "bash",
                "-lc",
                "pkill -f '[i]car_motion_bridge.py' || true",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        command = [
            "docker",
            "exec",
            "-i",
            self.container,
            "bash",
            "-lc",
            f"{ROS_SETUP}; exec python3 -u {self.REMOTE_SCRIPT} --watchdog-ms {self.watchdog_ms}",
        ]
        self.responses = queue.Queue()
        self.process = self.process_factory(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
        response_queue = self.responses
        process = self.process
        self.reader_thread = threading.Thread(
            target=self._read_responses,
            args=(process, response_queue),
            name="motion-bridge-reader",
            daemon=True,
        )
        self.reader_thread.start()

    @staticmethod
    def _read_responses(process, response_queue):
        while True:
            line = process.stdout.readline()
            if not line:
                break
            response_queue.put(line)

    def _send_once(self, direction, speed, turn):
        self._ensure_started()
        self.sequence += 1
        sequence = self.sequence
        command = {
            "sequence": sequence,
            "direction": direction,
            "speed": float(speed),
            "turn": float(turn),
        }
        started_at = self.clock()
        self.process.stdin.write(json.dumps(command, separators=(",", ":")) + "\n")
        self.process.stdin.flush()
        raw_response = self.responses.get(timeout=self.response_timeout)
        response = json.loads(raw_response)
        if response.get("sequence") != sequence:
            raise RuntimeError(
                f"motion bridge sequence mismatch: expected {sequence}, got {response.get('sequence')}"
            )
        if not response.get("ok"):
            raise RuntimeError(response.get("error", "motion bridge rejected command"))
        response["round_trip_ms"] = round((self.clock() - started_at) * 1000.0, 3)
        return response

    def _terminate_process(self):
        process = self.process
        self.process = None
        if process is None:
            return
        try:
            if process.stdin:
                process.stdin.close()
        except Exception:
            pass
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=1.0)
            except subprocess.TimeoutExpired:
                process.kill()


class NavigationBridgeClient:
    REMOTE_SCRIPT = "/tmp/icar_navigation_bridge.py"

    def __init__(
        self,
        container,
        script_path,
        process_factory=subprocess.Popen,
        copy_runner=subprocess.run,
        response_timeout=5.0,
    ):
        self.container = container
        self.script_path = Path(script_path)
        self.process_factory = process_factory
        self.copy_runner = copy_runner
        self.response_timeout = float(response_timeout)
        self.lock = threading.Lock()
        self.process = None
        self.responses = queue.Queue()
        self.reader_thread = None
        self.sequence = 0

    def is_running(self):
        return self.process is not None and self.process.poll() is None

    def snapshot(self, map_generation=-1):
        response = self.request(
            {"operation": "snapshot", "map_generation": int(map_generation)}
        )
        return response["snapshot"]

    def follow_waypoints(self, points, start=None):
        payload = {"operation": "follow_waypoints", "points": points}
        if start is not None:
            payload["start"] = start
        return self.request(payload)[
            "waypoints"
        ]

    def cancel_waypoints(self):
        return self.request({"operation": "cancel_waypoints"})["waypoints"]

    def reset_map(self):
        return self.request({"operation": "reset_map"})

    def request(self, payload):
        with self.lock:
            last_error = None
            for _attempt in range(2):
                try:
                    return self._request_once(payload)
                except Exception as error:
                    last_error = error
                    self._terminate_process()
            raise RuntimeError(f"navigation bridge unavailable: {last_error}")

    def shutdown(self):
        with self.lock:
            self._terminate_process()

    def _ensure_started(self):
        if self.is_running():
            return
        if not self.script_path.is_file():
            raise FileNotFoundError(f"navigation bridge script not found: {self.script_path}")
        self.copy_runner(
            ["docker", "cp", str(self.script_path), f"{self.container}:{self.REMOTE_SCRIPT}"],
            check=True,
            capture_output=True,
            text=True,
        )
        self.copy_runner(
            [
                "docker",
                "exec",
                self.container,
                "bash",
                "-lc",
                "pkill -f '[i]car_navigation_bridge.py' || true",
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        command = [
            "docker",
            "exec",
            "-i",
            self.container,
            "bash",
            "-lc",
            f"{ROS_SETUP}; exec python3 -u {self.REMOTE_SCRIPT}",
        ]
        self.responses = queue.Queue()
        self.process = self.process_factory(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            bufsize=1,
        )
        process = self.process
        response_queue = self.responses
        self.reader_thread = threading.Thread(
            target=MotionBridgeClient._read_responses,
            args=(process, response_queue),
            name="navigation-bridge-reader",
            daemon=True,
        )
        self.reader_thread.start()

    def _request_once(self, payload):
        self._ensure_started()
        self.sequence += 1
        sequence = self.sequence
        command = {"sequence": sequence, **payload}
        self.process.stdin.write(json.dumps(command, separators=(",", ":")) + "\n")
        self.process.stdin.flush()
        response = json.loads(self.responses.get(timeout=self.response_timeout))
        if response.get("sequence") != sequence:
            raise RuntimeError(
                f"navigation bridge sequence mismatch: expected {sequence}, got {response.get('sequence')}"
            )
        if not response.get("ok"):
            raise RuntimeError(response.get("error", "navigation bridge rejected request"))
        return response

    def _terminate_process(self):
        process = self.process
        self.process = None
        if process is None:
            return
        try:
            if process.stdin:
                process.stdin.close()
        except Exception:
            pass
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=1.0)
            except subprocess.TimeoutExpired:
                process.kill()


def create_motion_bridge(container):
    return MotionBridgeClient(
        container=container,
        script_path=Path(__file__).with_name("motion_bridge.py"),
    )


def create_navigation_bridge(container):
    return NavigationBridgeClient(
        container=container,
        script_path=Path(__file__).with_name("navigation_bridge.py"),
    )


def create_camera_stream(device, width, height, fps, quality):
    return CameraCaptureService(
        configured_device=device,
        width=width,
        height=height,
        fps=fps,
        jpeg_quality=quality,
    )


def positive_int(value):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError("must be a positive integer")
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def jpeg_quality(value):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError("must be an integer from 1 to 100")
    if not 1 <= parsed <= 100:
        raise argparse.ArgumentTypeError("must be an integer from 1 to 100")
    return parsed


def port_number(value):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        raise argparse.ArgumentTypeError("must be an integer from 1 to 65535")
    if not 1 <= parsed <= 65535:
        raise argparse.ArgumentTypeError("must be an integer from 1 to 65535")
    return parsed


def is_stream_disconnect(error):
    if isinstance(
        error,
        (socket.timeout, TimeoutError, BrokenPipeError, ConnectionResetError, ConnectionAbortedError),
    ):
        return True
    return isinstance(error, OSError) and error.errno in STREAM_DISCONNECT_ERRNOS


def build_mjpeg_chunk(jpeg):
    return (
        b"--frame\r\n"
        b"Content-Type: image/jpeg\r\n"
        + f"Content-Length: {len(jpeg)}\r\n\r\n".encode("ascii")
        + jpeg
        + b"\r\n"
    )


def clamp_float(value, default, min_value, max_value):
    try:
        parsed = float(value)
    except (TypeError, ValueError):
        return default
    return max(min_value, min(max_value, parsed))


def build_initial_pose_command(x, y, yaw):
    qz, qw = yaw_to_quaternion_z_w(yaw)
    covariance = ", ".join(
        [
            "0.25", "0.0", "0.0", "0.0", "0.0", "0.0",
            "0.0", "0.25", "0.0", "0.0", "0.0", "0.0",
            "0.0", "0.0", "0.0", "0.0", "0.0", "0.0",
            "0.0", "0.0", "0.0", "0.0", "0.0", "0.0",
            "0.0", "0.0", "0.0", "0.0", "0.0", "0.0",
            "0.0", "0.0", "0.0", "0.0", "0.0", "0.0685",
        ]
    )
    message = (
        "{header: {frame_id: 'map'}, "
        f"pose: {{pose: {{position: {{x: {format_num(x)}, y: {format_num(y)}, z: 0.0}}, "
        f"orientation: {{z: {format_num(qz)}, w: {format_num(qw)}}}}}, "
        f"covariance: [{covariance}]}}}}"
    )
    return f"ros2 topic pub --once /initialpose geometry_msgs/msg/PoseWithCovarianceStamped \"{message}\""


def build_goal_pose_command(x, y, yaw):
    qz, qw = yaw_to_quaternion_z_w(yaw)
    message = (
        "{header: {frame_id: 'map'}, "
        f"pose: {{position: {{x: {format_num(x)}, y: {format_num(y)}, z: 0.0}}, "
        f"orientation: {{z: {format_num(qz)}, w: {format_num(qw)}}}}}"
        "}"
    )
    return f"ros2 topic pub --once /goal_pose geometry_msgs/msg/PoseStamped \"{message}\""


def build_twist_command(direction, speed, turn):
    x, y, z = 0.0, 0.0, 0.0
    if direction == "front":
        x = speed
    elif direction == "back":
        x = -speed
    elif direction == "left":
        y = speed
    elif direction == "right":
        y = -speed
    elif direction == "turn_left":
        z = turn
    elif direction == "turn_right":
        z = -turn
    elif direction == "stop":
        pass
    else:
        raise ValueError(f"unknown direction: {direction}")

    message = (
        "{linear: "
        f"{{x: {format_num(x)}, y: {format_num(y)}, z: 0.0}}, "
        "angular: "
        f"{{x: 0.0, y: 0.0, z: {format_num(z)}}}"
        "}"
    )
    return f"ros2 topic pub --once /cmd_vel geometry_msgs/msg/Twist \"{message}\""


def yaw_to_quaternion_z_w(yaw):
    return math.sin(yaw / 2.0), math.cos(yaw / 2.0)


def format_num(value):
    return f"{float(value):.4f}".rstrip("0").rstrip(".")


def docker_command_for(container, ros_command):
    return [
        "docker",
        "exec",
        "-i",
        container,
        "bash",
        "-lc",
        f"{ROS_SETUP}; {ros_command}",
    ]


def docker_command(ros_command):
    return docker_command_for(SERVER_CONFIG["container"], ros_command)


def run_host(command, timeout=8, output_limit=2000):
    if SERVER_CONFIG["dry_run"]:
        return {"dry_run": True, "command": command, "returncode": 0, "stdout": "", "stderr": ""}
    completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    stdout = completed.stdout
    stderr = completed.stderr
    if output_limit is not None:
        stdout = stdout[-output_limit:]
        stderr = stderr[-output_limit:]
    return {
        "returncode": completed.returncode,
        "stdout": stdout,
        "stderr": stderr,
    }


def run_once(ros_command, timeout=8, output_limit=2000):
    command = docker_command(ros_command)
    return run_host(command, timeout=timeout, output_limit=output_limit)


def command_exists(command):
    return subprocess.run(
        ["bash", "-lc", f"command -v {shlex.quote(command)}"],
        capture_output=True,
        text=True,
        timeout=3,
    ).returncode == 0


def speak_text(text, voice=None, rate=None, pitch=None):
    message = str(text or "").strip()
    if not message:
        return {"ok": False, "error": "text is required"}
    if len(message) > 500:
        message = message[:500]
    selected_voice = str(voice or DEFAULT_TTS_VOICE).strip() or DEFAULT_TTS_VOICE
    selected_rate = str(rate or DEFAULT_TTS_RATE).strip() or DEFAULT_TTS_RATE
    selected_pitch = str(pitch or DEFAULT_TTS_PITCH).strip() or DEFAULT_TTS_PITCH
    if SERVER_CONFIG["dry_run"]:
        return {
            "ok": True,
            "dry_run": True,
            "text": message,
            "engine": "edge-tts",
            "voice": selected_voice,
        }

    prepare_audio_output()
    if command_exists("edge-tts") and command_exists("ffplay"):
        edge_result = speak_with_edge_tts(message, selected_voice, selected_rate, selected_pitch)
        if edge_result.get("ok"):
            return edge_result
    else:
        edge_result = {"ok": False, "error": "edge-tts or ffplay is not installed"}
    fallback = speak_with_espeak(message)
    if fallback.get("ok"):
        fallback["fallback_from"] = edge_result.get("error", "edge-tts failed")
    return fallback


def prepare_audio_output():
    subprocess.run(
        ["pactl", "set-default-sink", USB_AUDIO_SINK],
        capture_output=True,
        text=True,
        timeout=3,
    )
    subprocess.run(
        ["pactl", "set-sink-volume", USB_AUDIO_SINK, "85%"],
        capture_output=True,
        text=True,
        timeout=3,
    )


def speak_with_edge_tts(message, voice, rate, pitch):
    mp3_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="icar_speech_", suffix=".mp3", delete=False) as mp3_file:
            mp3_path = mp3_file.name
        tts = subprocess.run(
            [
                "edge-tts",
                "--voice",
                voice,
                "--rate",
                rate,
                "--pitch",
                pitch,
                "--text",
                message,
                "--write-media",
                mp3_path,
            ],
            capture_output=True,
            timeout=25,
        )
        if tts.returncode != 0:
            return {"ok": False, "text": message, "engine": "edge-tts", "error": decode_process_error(tts.stderr)}
        player = subprocess.run(
            ["ffplay", "-nodisp", "-autoexit", "-loglevel", "error", mp3_path],
            capture_output=True,
            timeout=25,
        )
        if player.returncode != 0:
            return {"ok": False, "text": message, "engine": "edge-tts", "error": decode_process_error(player.stderr)}
        return {"ok": True, "text": message, "engine": "edge-tts", "voice": voice}
    except Exception as error:
        return {"ok": False, "text": message, "engine": "edge-tts", "error": str(error)}
    finally:
        if mp3_path:
            try:
                os.unlink(mp3_path)
            except OSError:
                pass


def speak_with_espeak(message):
    if not command_exists("espeak-ng"):
        return {"ok": False, "text": message, "engine": "espeak-ng", "error": "espeak-ng is not installed"}
    wav_path = None
    try:
        with tempfile.NamedTemporaryFile(prefix="icar_speech_", suffix=".wav", delete=False) as wav_file:
            wav_path = wav_file.name
            tts = subprocess.run(
                ["espeak-ng", "-v", "zh", "-s", "155", "--stdout", message],
                stdout=wav_file,
                stderr=subprocess.PIPE,
                timeout=8,
            )
        if tts.returncode != 0:
            return {
                "ok": False,
                "text": message,
                "engine": "espeak-ng",
                "error": decode_process_error(tts.stderr),
            }
        player = subprocess.run(
            ["paplay", f"--device={USB_AUDIO_SINK}", wav_path],
            capture_output=True,
            timeout=12,
        )
        if player.returncode != 0:
            return {
                "ok": False,
                "text": message,
                "engine": "espeak-ng",
                "error": decode_process_error(player.stderr),
            }
        return {"ok": True, "text": message, "engine": "espeak-ng"}
    except Exception as error:
        return {"ok": False, "text": message, "engine": "espeak-ng", "error": str(error)}
    finally:
        if wav_path:
            try:
                os.unlink(wav_path)
            except OSError:
                pass


def decode_process_error(value):
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")[-300:]
    return str(value or "")[-300:]


def container_is_running():
    return configured_container_is_running(SERVER_CONFIG["container"])


def configured_container_is_running(container):
    result = run_host(
        ["docker", "inspect", "-f", "{{.State.Running}}", container],
        timeout=3,
    )
    return result["returncode"] == 0 and result["stdout"].strip().lower() == "true"


def inspect_navigation_package(container, package):
    if SERVER_CONFIG["dry_run"]:
        return []
    required = tuple(NAVIGATION_LAUNCH_FILES.values())
    checks = " ".join(shlex.quote(item) for item in required)
    script = (
        f"prefix=$(ros2 pkg prefix {shlex.quote(package)} 2>/dev/null) || exit 2; "
        f"for launch_file in {checks}; do "
        f"if [ ! -f \"$prefix/share/{package}/launch/$launch_file\" ]; then "
        "printf '%s\\n' \"$launch_file\"; fi; done; exit 0"
    )
    result = run_host(docker_command_for(container, script), timeout=8)
    if result.get("returncode") != 0:
        return list(required)
    found_missing = {
        line.strip() for line in result.get("stdout", "").splitlines() if line.strip()
    }
    return [item for item in required if item in found_missing]


def detect_navigation_profile(container):
    missing_by_package = {}
    for package in NAVIGATION_PACKAGES:
        missing = inspect_navigation_package(container, package)
        if not missing:
            return {
                "package": package,
                "ready": True,
                "missing_navigation_launches": {},
            }
        missing_by_package[package] = missing
    return {
        "package": None,
        "ready": False,
        "missing_navigation_launches": missing_by_package,
    }


def apply_navigation_profile(profile, n5_command=None):
    package = profile.get("package")
    SERVER_CONFIG["navigation_package"] = package
    SERVER_CONFIG["navigation_tasks_ready"] = bool(profile.get("ready"))
    SERVER_CONFIG["missing_navigation_launches"] = profile.get(
        "missing_navigation_launches", {}
    )
    SERVER_CONFIG["n5_command_override"] = n5_command
    selected_package = package or "icar_nav"
    TASK_COMMANDS.update(build_navigation_commands(selected_package, n5_command))


def navigation_profile_status():
    return {
        "navigation_package": SERVER_CONFIG.get("navigation_package"),
        "navigation_tasks_ready": bool(SERVER_CONFIG.get("navigation_tasks_ready")),
        "missing_navigation_launches": SERVER_CONFIG.get(
            "missing_navigation_launches", {}
        ),
    }


def navigation_unavailable(task=None):
    body = {
        "error": "navigation launch profile is incomplete",
        **navigation_profile_status(),
    }
    if task:
        body["task"] = task
    return 503, body


def safe_process_pattern(pattern):
    return f"[{pattern[0]}]{pattern[1:]}" if pattern else pattern


def task_processes(task):
    if task not in TASK_PATTERNS:
        return ""
    pattern = safe_process_pattern(TASK_PATTERNS[task])
    result = run_once(f"pgrep -af {shlex.quote(pattern)} || true", timeout=3)
    return result.get("stdout", "").strip()


def task_process_snapshot():
    """Read all container processes once instead of one docker call per task."""
    # Process listings are commonly larger than the normal 2 KB command-output
    # limit. Truncating the front removes the long-lived ros2 launch parents and
    # makes a running DWA/TEB/RPP profile look idle.
    result = run_once("ps -eo pid=,args=", timeout=3, output_limit=None)
    lines = result.get("stdout", "").splitlines()
    return {
        task: "\n".join(line for line in lines if pattern in line).strip()
        for task, pattern in TASK_PATTERNS.items()
    }


def close_task_log(task):
    handle = LOG_HANDLES.pop(task, None)
    if handle is not None:
        handle.close()


def start_task(task):
    if task not in TASK_COMMANDS:
        return 404, {"error": f"unknown task: {task}"}
    if task in NAVIGATION_TASK_KEYS and not SERVER_CONFIG["navigation_tasks_ready"]:
        return navigation_unavailable(task)
    if task in ONE_SHOT_TASKS:
        return 200, {"task": task, "status": "finished", "result": run_once(TASK_COMMANDS[task], timeout=30)}

    command = docker_command(TASK_COMMANDS[task])
    if SERVER_CONFIG["dry_run"]:
        return 200, {"task": task, "status": "dry_run", "command": command}

    existing = task_processes(task)
    if existing:
        return 200, {"task": task, "status": "already_running", "process": existing}

    log_directory = Path(__file__).with_name("logs")
    log_directory.mkdir(parents=True, exist_ok=True)
    log_path = log_directory / f"{task}.log"
    close_task_log(task)
    log_handle = open(log_path, "ab", buffering=0)
    process = subprocess.Popen(command, stdout=log_handle, stderr=subprocess.STDOUT)
    RUNNING[task] = process
    LOG_HANDLES[task] = log_handle
    time.sleep(0.8)
    discovered = task_processes(task)
    status = "started" if discovered else "start_failed"
    return 200, {
        "task": task,
        "status": status,
        "ok": bool(discovered),
        "pid": process.pid,
        "process": discovered,
        "log": os.path.relpath(log_path, Path(__file__).parent),
    }


def stop_task_batch(tasks):
    ordered = list(dict.fromkeys(reversed(tuple(tasks))))
    if SERVER_CONFIG["dry_run"]:
        results = []
        for task in ordered:
            RUNNING.pop(task, None)
            close_task_log(task)
            results.append({"task": task, "status": "dry_run"})
        return results

    patterns = []
    for task in ordered:
        patterns.append(TASK_PATTERNS[task])
        patterns.extend(TASK_CLEANUP_PATTERNS.get(task, ()))
    combined = "|".join(
        safe_process_pattern(pattern) for pattern in dict.fromkeys(patterns)
    )
    # ros2 launch gives each docker-exec command its own process group. Kill
    # the complete group so Nav2, lidar and RViz children cannot remain as
    # orphaned duplicate nodes after their launch parent exits.
    stop_script = (
        f"pgids=$(pgrep -f {shlex.quote(combined)} 2>/dev/null | "
        "while read pid; do ps -o pgid= -p \"$pid\"; done | tr -d ' ' | sort -u); "
        "for pgid in $pgids; do kill -INT -- -$pgid 2>/dev/null || true; done; "
        "sleep 0.15; "
        f"pgids=$(pgrep -f {shlex.quote(combined)} 2>/dev/null | "
        "while read pid; do ps -o pgid= -p \"$pid\"; done | tr -d ' ' | sort -u); "
        "for pgid in $pgids; do kill -TERM -- -$pgid 2>/dev/null || true; done; "
        "sleep 0.15; "
        f"pgids=$(pgrep -f {shlex.quote(combined)} 2>/dev/null | "
        "while read pid; do ps -o pgid= -p \"$pid\"; done | tr -d ' ' | sort -u); "
        "for pgid in $pgids; do kill -KILL -- -$pgid 2>/dev/null || true; done; true"
    )
    result = run_once(stop_script, timeout=2)
    results = []
    for task in ordered:
        process = RUNNING.get(task)
        if process is not None and process.poll() is None:
            process.send_signal(signal.SIGTERM)
            try:
                process.wait(timeout=0.4)
            except subprocess.TimeoutExpired:
                process.kill()
        RUNNING.pop(task, None)
        close_task_log(task)
        results.append(
            {"task": task, "status": "stopped", "output": result.get("stdout", "")}
        )
    return results


def stop_task(task):
    if task == "all":
        return 200, {"stopped": stop_task_batch(TASK_COMMANDS)}
    if task not in TASK_COMMANDS:
        return 404, {"error": f"unknown task: {task}"}
    return 200, stop_task_batch((task,))[0]


def stop_motion_safely():
    try:
        result = MOTION_BRIDGE.send("stop", 0.0, 0.0)
        result["mode"] = "bridge"
        return result
    except Exception as error:
        fallback = run_once(build_twist_command("stop", 0.0, 0.0), timeout=3)
        return {
            "ok": fallback.get("returncode") == 0,
            "mode": "fallback",
            "bridge_error": str(error),
            "fallback": fallback,
        }


def cancel_waypoints_safely():
    if NAVIGATION_BRIDGE is None:
        return {"state": "unavailable"}
    try:
        return NAVIGATION_BRIDGE.cancel_waypoints()
    except Exception as error:
        return {"state": "cancel_failed", "message": str(error)}


def reset_navigation_map_safely():
    if NAVIGATION_BRIDGE is None:
        return {"ok": False, "error": "navigation bridge unavailable"}
    try:
        return NAVIGATION_BRIDGE.reset_map()
    except Exception as error:
        return {"ok": False, "error": str(error)}


def ensure_base_for_manual_motion():
    """Start the standalone base driver on the first manual movement request."""
    with BASE_START_LOCK:
        existing = task_processes("base")
        if existing:
            return {"task": "base", "status": "already_running", "process": existing}
        status, result = start_task("base")
        if status >= 400 or result.get("status") == "start_failed":
            raise RuntimeError(f"base driver unavailable: {result}")
        # Give ROS discovery a short window before the first velocity publish.
        if not SERVER_CONFIG["dry_run"]:
            time.sleep(0.7)
        return result


def task_state(task, snapshot=None):
    process = snapshot.get(task, "") if snapshot is not None else task_processes(task)
    return {"running": bool(process), "process": process}


def automation_status():
    snapshot = task_process_snapshot()
    mapping = {task: task_state(task, snapshot) for task in MAPPING_TASKS}
    navigation_tasks = NAVIGATION_SHARED_TASKS + tuple(NAVIGATION_ALGORITHMS.values())
    navigation = {task: task_state(task, snapshot) for task in navigation_tasks}
    active_algorithm = next(
        (name for name, task in NAVIGATION_ALGORITHMS.items() if navigation[task]["running"]),
        None,
    )
    return {
        "mode": (
            "navigation"
            if any(item["running"] for item in navigation.values())
            else "mapping"
            if any(item["running"] for item in mapping.values())
            else "idle"
        ),
        "mapping": mapping,
        "navigation": navigation,
        "algorithm": active_algorithm,
        **navigation_profile_status(),
    }


def stop_tasks(tasks):
    return stop_task_batch(tasks)


def start_tasks(tasks):
    results = []
    for task in tasks:
        status, body = start_task(task)
        results.append(body)
        if status >= 400 or body.get("status") == "start_failed":
            return False, results
    return True, results


def start_automatic_mapping():
    if not SERVER_CONFIG["navigation_tasks_ready"]:
        return navigation_unavailable("automatic_mapping")
    with AUTOMATION_LOCK:
        navigation_tasks = NAVIGATION_SHARED_TASKS + tuple(NAVIGATION_ALGORITHMS.values())
        stopped = stop_tasks(navigation_tasks)
        _, stopped_base = stop_task("base")
        # A second press must start a fresh SLAM session. Otherwise the
        # already-running gmapping node continues the previous occupancy grid,
        # which looks like two mapping runs drawn on top of each other.
        stopped_previous_mapping = stop_tasks(MAPPING_TASKS)
        map_reset = reset_navigation_map_safely()
        # The icar/yahboom mapping launch includes the robot bringup and base
        # driver. Starting `base` separately opens /dev/myserial twice.
        tasks = MAPPING_TASKS
        ok, started = start_tasks(tasks)
        return (200 if ok else 503), {
            "workflow": "mapping",
            "status": "running" if ok else "failed",
            "steps": ["m1", "m2"],
            "stopped_base": stopped_base,
            "stopped": stopped,
            "stopped_previous_mapping": stopped_previous_mapping,
            "map_reset": map_reset,
            "started": started,
        }


def save_automatic_mapping():
    if not SERVER_CONFIG["navigation_tasks_ready"]:
        return navigation_unavailable("save_map")
    with AUTOMATION_LOCK:
        status, saved = start_task("map_save")
        if status >= 400 or saved.get("result", {}).get("returncode", 0) != 0:
            return 503, {"workflow": "mapping", "status": "save_failed", "saved": saved}
        stopped = stop_tasks(MAPPING_TASKS)
        return 200, {
            "workflow": "mapping",
            "status": "saved",
            "step": "m4",
            "saved": saved,
            "stopped": stopped,
        }


def start_automatic_navigation(algorithm):
    normalized = algorithm.strip().lower()
    if normalized not in NAVIGATION_ALGORITHMS:
        return 400, {"error": "algorithm must be dwa, teb or astar_rpp"}
    if not SERVER_CONFIG["navigation_tasks_ready"]:
        return navigation_unavailable("automatic_navigation")
    with AUTOMATION_LOCK:
        stopped_mapping = stop_tasks(MAPPING_TASKS)
        _, stopped_base = stop_task("base")
        other_algorithms = [
            task for name, task in NAVIGATION_ALGORITHMS.items() if name != normalized
        ]
        stopped_algorithms = stop_tasks(other_algorithms)
        # n1 includes robot bringup and owns the base serial device.
        tasks = NAVIGATION_SHARED_TASKS + (NAVIGATION_ALGORITHMS[normalized],)
        ok, started = start_tasks(tasks)
        return (200 if ok else 503), {
            "workflow": "navigation",
            "algorithm": normalized,
            "status": "running" if ok else "failed",
            "steps": [
                "n1",
                "n2",
                {"dwa": "n3", "teb": "n4", "astar_rpp": "n5"}[normalized],
            ],
            "stopped_mapping": stopped_mapping,
            "stopped_base": stopped_base,
            "stopped_algorithms": stopped_algorithms,
            "started": started,
        }


def stop_automatic_navigation():
    with AUTOMATION_LOCK:
        tasks = NAVIGATION_SHARED_TASKS + tuple(NAVIGATION_ALGORITHMS.values())
        motion = stop_motion_safely()
        waypoints = cancel_waypoints_safely()
        stopped = stop_tasks(tasks)
        return 200, {
            "workflow": "navigation",
            "status": "stopped",
            "motion": motion,
            "waypoints": waypoints,
            "stopped": stopped,
        }


def valid_container_id(container):
    return bool(CONTAINER_ID_PATTERN.fullmatch(container.strip()))


def select_container(container):
    global MOTION_BRIDGE, NAVIGATION_BRIDGE
    selected = container.strip()
    if not valid_container_id(selected):
        return 400, {"error": "container id contains unsupported characters"}
    with AUTOMATION_LOCK:
        current = SERVER_CONFIG["container"]
        if selected == current:
            if not configured_container_is_running(selected):
                return 404, {"error": "container is not running", "container": selected}
            profile = detect_navigation_profile(selected)
            apply_navigation_profile(profile, SERVER_CONFIG.get("n5_command_override"))
            return 200, {
                "status": "selected",
                "container": selected,
                "changed": False,
                **navigation_profile_status(),
            }
        if not configured_container_is_running(selected):
            return 404, {"error": "container is not running", "container": selected}

        profile = detect_navigation_profile(selected)

        automatic_tasks = MAPPING_TASKS + NAVIGATION_SHARED_TASKS + tuple(NAVIGATION_ALGORITHMS.values())
        stopped = stop_tasks(automatic_tasks)
        old_motion_bridge = MOTION_BRIDGE
        old_navigation_bridge = NAVIGATION_BRIDGE
        replacement_motion = create_motion_bridge(selected)
        replacement_navigation = create_navigation_bridge(selected)
        if not SERVER_CONFIG["dry_run"]:
            try:
                replacement_motion.send("stop", 0.0, 0.0)
                replacement_navigation.snapshot(-1)
            except Exception as error:
                replacement_motion.shutdown(send_stop=False)
                replacement_navigation.shutdown()
                return 503, {
                    "error": "container ROS bridge check failed",
                    "container": selected,
                    "detail": str(error),
                }
        if old_motion_bridge is not None:
            old_motion_bridge.shutdown()
        if old_navigation_bridge is not None:
            old_navigation_bridge.shutdown()
        SERVER_CONFIG["container"] = selected
        apply_navigation_profile(profile, SERVER_CONFIG.get("n5_command_override"))
        MOTION_BRIDGE = replacement_motion
        NAVIGATION_BRIDGE = replacement_navigation
        return 200, {
            "status": "selected",
            "container": selected,
            "previous_container": current,
            "changed": True,
            "stopped": stopped,
            **navigation_profile_status(),
        }


def query_float(query, name, default=0.0):
    raw = query.get(name, [str(default)])[0]
    try:
        return float(raw)
    except ValueError:
        raise ValueError(f"{name} must be a number")


def validated_waypoints(data):
    raw_points = data.get("points")
    if not isinstance(raw_points, list) or not raw_points:
        raise ValueError("points must be a non-empty list")
    if len(raw_points) > 100:
        raise ValueError("at most 100 waypoints are allowed")
    points = []
    for index, raw in enumerate(raw_points):
        if not isinstance(raw, dict):
            raise ValueError(f"point {index + 1} must be an object")
        try:
            x = float(raw["x"])
            y = float(raw["y"])
            yaw = float(raw.get("yaw", 0.0))
        except (KeyError, TypeError, ValueError):
            raise ValueError(f"point {index + 1} requires numeric x, y and yaw")
        if not all(math.isfinite(value) for value in (x, y, yaw)):
            raise ValueError(f"point {index + 1} contains a non-finite value")
        points.append({"x": x, "y": y, "yaw": yaw})
    return points


def validated_navigation_pose(raw, name="start"):
    if not isinstance(raw, dict):
        raise ValueError(f"{name} must be an object")
    try:
        x = float(raw["x"])
        y = float(raw["y"])
        yaw = float(raw.get("yaw", 0.0))
    except (KeyError, TypeError, ValueError):
        raise ValueError(f"{name} requires numeric x, y and yaw")
    if not all(math.isfinite(value) for value in (x, y, yaw)):
        raise ValueError(f"{name} contains a non-finite value")
    return {"x": x, "y": y, "yaw": yaw}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.strip("/")
        query = parse_qs(parsed.query)

        if path == "camera/stream":
            self.stream_camera()
            return

        try:
            status, body = self.route(path, query)
        except ValueError as error:
            status, body = 400, {"error": str(error)}
        except Exception as error:
            status, body = 500, {"error": str(error)}
        try:
            self.reply(status, body)
        except OSError as error:
            if is_stream_disconnect(error):
                return
            raise

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.strip("/")
        try:
            if path == "speak":
                status, body = self.handle_speak_post()
            elif path == "navigation/waypoints":
                status, body = self.handle_waypoints_post()
            elif path.startswith("notify/"):
                status, body = self.route(path, {})
            else:
                status, body = 404, {"error": f"unknown endpoint: /{path}"}
        except ValueError as error:
            status, body = 400, {"error": str(error)}
        except Exception as error:
            status, body = 500, {"error": str(error)}
        self.reply(status, body)

    def handle_speak_post(self):
        data = self.read_json_body(max_bytes=2048)
        text = data.get("text", "")
        result = speak_text(
            text,
            voice=data.get("voice"),
            rate=data.get("rate"),
            pitch=data.get("pitch"),
        )
        return (200 if result.get("ok") else 503), result

    def read_json_body(self, max_bytes):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            raise ValueError("Content-Length must be an integer")
        if length <= 0:
            raise ValueError("request body is required")
        if length > max_bytes:
            raise ValueError("request body is too large")
        payload = self.rfile.read(length).decode("utf-8")
        data = json.loads(payload)
        if not isinstance(data, dict):
            raise ValueError("JSON body must be an object")
        return data

    def handle_waypoints_post(self):
        if NAVIGATION_BRIDGE is None:
            return 503, {"error": "navigation bridge unavailable"}
        data = self.read_json_body(max_bytes=65536)
        start = validated_navigation_pose(data.get("start"), "start")
        points = validated_waypoints(data)
        active_algorithm = automation_status().get("algorithm")
        if active_algorithm is None and not SERVER_CONFIG["dry_run"]:
            return 409, {
                "error": "请先启动 DWA、TEB 或 A*+RPP 导航算法",
                "status": "algorithm_required",
            }
        # The persistent bridge publishes /initialpose and queues FollowWaypoints
        # asynchronously. The HTTP request returns immediately instead of
        # waiting for ROS discovery and being reported as a phone-side timeout.
        result = NAVIGATION_BRIDGE.follow_waypoints(points, start=start)
        return 202, {
            "status": "route_submitted",
            "algorithm": active_algorithm,
            "start": start,
            "waypoints": result,
        }

    def stream_camera(self):
        camera_stream = CAMERA_STREAM
        if camera_stream is None:
            self.reply(503, {"state": "unavailable", "error": "camera service unavailable"})
            return

        camera_stream.acquire_client()
        connection = getattr(self, "connection", None)
        previous_timeout = None
        timeout_changed = False
        try:
            if not camera_stream.wait_until_ready(timeout=2.0):
                snapshot = camera_stream.status()
                state = snapshot.get("state", "unavailable")
                error = snapshot.get("error") or f"camera {state}"
                self.reply(503, {"state": state, "error": error})
                return

            if connection is not None:
                previous_timeout = connection.gettimeout()
                connection.settimeout(STREAM_WRITE_TIMEOUT)
                timeout_changed = True

            try:
                self.send_response(200)
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
                self.send_header("Pragma", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()
            except OSError as error:
                if is_stream_disconnect(error):
                    return
                raise

            sequence = 0
            while True:
                next_sequence, jpeg = camera_stream.wait_for_frame(sequence, timeout=1.0)
                if jpeg is None:
                    if camera_stream.status().get("state") in {
                        "busy",
                        "disconnected",
                        "idle",
                        "missing",
                    }:
                        break
                    continue
                if next_sequence <= sequence:
                    continue
                sequence = next_sequence
                try:
                    self.wfile.write(build_mjpeg_chunk(jpeg))
                    self.wfile.flush()
                except OSError as error:
                    if is_stream_disconnect(error):
                        break
                    raise
        finally:
            if timeout_changed:
                try:
                    connection.settimeout(previous_timeout)
                except OSError:
                    pass
            camera_stream.release_client()

    def route(self, path, query):
        if path == "camera/status":
            if CAMERA_STREAM is None:
                return 503, {"state": "unavailable", "error": "camera service unavailable"}
            return 200, CAMERA_STREAM.status()
        if path == "camera/restart":
            if CAMERA_STREAM is None:
                return 503, {"state": "unavailable", "error": "camera service unavailable"}
            return 200, CAMERA_STREAM.restart()
        if path.startswith("notify/"):
            event = unquote(path.split("/", 1)[1])
            text = NOTIFICATION_MESSAGES.get(event)
            if text is None:
                return 404, {"error": f"unknown notification: {event}"}
            result = speak_text(text)
            result["event"] = event
            return (200 if result.get("ok") else 503), result
        if path == "health":
            return 200, {
                "ok": True,
                "container": SERVER_CONFIG["container"],
                "container_running": container_is_running(),
                "motion_bridge_ready": bool(MOTION_BRIDGE and MOTION_BRIDGE.is_running()),
                "navigation_bridge_ready": bool(NAVIGATION_BRIDGE and NAVIGATION_BRIDGE.is_running()),
                **navigation_profile_status(),
            }
        if path == "status":
            snapshot = task_process_snapshot()
            states = {
                task: {"running": bool(process), "process": process}
                for task, process in snapshot.items()
            }
            return 200, states
        if path == "container/status":
            return 200, {
                "container": SERVER_CONFIG["container"],
                "running": container_is_running(),
                **navigation_profile_status(),
            }
        if path == "container/select":
            return select_container(query.get("id", [""])[0])
        if path == "automation/status":
            return 200, automation_status()
        if path == "automation/mapping/start":
            return start_automatic_mapping()
        if path == "automation/mapping/save":
            return save_automatic_mapping()
        if path == "automation/navigation/start":
            algorithm = query.get("algorithm", ["dwa"])[0]
            return start_automatic_navigation(algorithm)
        if path == "automation/navigation/stop":
            return stop_automatic_navigation()
        if path == "stop/all":
            motion_result = stop_motion_safely()
            waypoint_result = cancel_waypoints_safely()
            status, body = stop_task("all")
            body["motion"] = motion_result
            body["waypoints"] = waypoint_result
            return status, body
        if path.startswith("start/"):
            return start_task(unquote(path.split("/", 1)[1]))
        if path.startswith("stop/"):
            return stop_task(unquote(path.split("/", 1)[1]))
        if path == "emergency_stop":
            result = stop_motion_safely()
            cancel_waypoints_safely()
            stop_task("all")
            return 200, {"status": "emergency_stop", "result": result}
        if path.startswith("move/"):
            direction = unquote(path.split("/", 1)[1])
            speed_raw = query.get("speed", ["0.18"])[0]
            turn_raw = query.get("turn", ["0.8"])[0]
            speed = clamp_float(speed_raw, 0.18, 0.05, 0.35)
            turn = clamp_float(turn_raw, 0.8, 0.2, 1.2)
            base = None
            if direction != "stop":
                base = ensure_base_for_manual_motion()
            result = MOTION_BRIDGE.send(direction, speed, turn)
            if base is not None:
                result["base"] = base["status"]
            return 200, result
        if path == "navigation/initial_pose":
            x = query_float(query, "x")
            y = query_float(query, "y")
            yaw = query_float(query, "yaw")
            return 200, {"status": "published", "result": run_once(build_initial_pose_command(x, y, yaw))}
        if path == "navigation/goal":
            x = query_float(query, "x")
            y = query_float(query, "y")
            yaw = query_float(query, "yaw")
            return 200, {"status": "published", "result": run_once(build_goal_pose_command(x, y, yaw))}
        if path == "navigation/waypoints/cancel":
            if NAVIGATION_BRIDGE is None:
                return 503, {"error": "navigation bridge unavailable"}
            return 200, {
                "status": "cancel_requested",
                "waypoints": NAVIGATION_BRIDGE.cancel_waypoints(),
            }
        if path == "navigation/state":
            if NAVIGATION_BRIDGE is None:
                return 503, {"state": "unavailable", "error": "navigation bridge unavailable"}
            map_generation = int(query.get("map_generation", ["-1"])[0])
            return 200, NAVIGATION_BRIDGE.snapshot(map_generation)
        return 404, {"error": f"unknown endpoint: /{path}"}

    def reply(self, status, body):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main():
    global CAMERA_STREAM, MOTION_BRIDGE, NAVIGATION_BRIDGE
    parser = argparse.ArgumentParser(description="HTTP bridge for smart car ROS2 commands")
    parser.add_argument("--container", default=DEFAULT_CONTAINER)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=port_number, default=8000)
    parser.add_argument(
        "--n5-command",
        default=None,
        help="optional full ROS 2 launch command overriding the detected RPP profile",
    )
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--camera-device", default=None)
    parser.add_argument("--camera-width", type=positive_int, default=640)
    parser.add_argument("--camera-height", type=positive_int, default=480)
    parser.add_argument("--camera-fps", type=positive_int, default=18)
    parser.add_argument("--camera-quality", type=jpeg_quality, default=70)
    args = parser.parse_args()
    SERVER_CONFIG["container"] = args.container
    SERVER_CONFIG["dry_run"] = args.dry_run
    if not args.dry_run and not configured_container_is_running(args.container):
        parser.error(f"Docker container {args.container!r} is not running")
    profile = detect_navigation_profile(args.container)
    apply_navigation_profile(profile, args.n5_command)
    if profile["ready"]:
        print(f"Navigation profile ready: {profile['package']}")
    else:
        print(
            "Navigation profile unavailable; mapping/navigation endpoints disabled: "
            + json.dumps(profile["missing_navigation_launches"], ensure_ascii=False)
        )
    MOTION_BRIDGE = create_motion_bridge(args.container)
    NAVIGATION_BRIDGE = create_navigation_bridge(args.container)
    CAMERA_STREAM = create_camera_stream(
        device=args.camera_device,
        width=args.camera_width,
        height=args.camera_height,
        fps=args.camera_fps,
        quality=args.camera_quality,
    )

    if not args.dry_run:
        try:
            warmup = MOTION_BRIDGE.send("stop", 0.0, 0.0)
            print(
                "Motion bridge ready, "
                f"round_trip_ms={warmup.get('round_trip_ms')}, "
                f"bridge_latency_ms={warmup.get('bridge_latency_ms')}"
            )
        except Exception as error:
            print(f"Motion bridge warm-up failed: {error}")
        try:
            NAVIGATION_BRIDGE.snapshot(-1)
            print("Navigation bridge ready")
        except Exception as error:
            print(f"Navigation bridge warm-up failed: {error}")

    server = None
    try:
        server = ThreadingHTTPServer((args.host, args.port), Handler)
        print(f"Smart car HTTP server on {args.host}:{args.port}, container={args.container}")
        server.serve_forever()
    finally:
        try:
            if CAMERA_STREAM is not None:
                CAMERA_STREAM.shutdown()
        finally:
            try:
                if server is not None:
                    server.server_close()
            finally:
                if MOTION_BRIDGE is not None:
                    try:
                        MOTION_BRIDGE.shutdown()
                    finally:
                        if NAVIGATION_BRIDGE is not None:
                            NAVIGATION_BRIDGE.shutdown()


if __name__ == "__main__":
    main()
