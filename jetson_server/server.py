#!/usr/bin/env python3
import argparse
import errno
import json
import math
import os
import queue
import shlex
import signal
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlparse

try:
    from .camera_stream import CameraCaptureService
except ImportError:
    from camera_stream import CameraCaptureService


ROS_DOMAIN_ID = 32
ROS_SETUP = (
    f"export ROS_DOMAIN_ID={ROS_DOMAIN_ID}; "
    "source /opt/ros/foxy/setup.bash; "
    "[ -f /root/yahboomcar_ros2_ws/yahboomcar_ws/install/setup.bash ] && "
    "source /root/yahboomcar_ros2_ws/yahboomcar_ws/install/setup.bash; "
    "[ -f /root/icar_ros2_ws/icar_ws/install/setup.bash ] && "
    "source /root/icar_ros2_ws/icar_ws/install/setup.bash"
)

TASK_COMMANDS = {
    "base": "ros2 run icar_bringup Mcnamu_driver_X3",
    "lidar": "ros2 launch sllidar_ros2 sllidar_launch.py",
    "avoidance": "ros2 run icar_laser laser_Avoidance_a1_X3",
    "follow": "ros2 run icar_laser laser_Tracker_a1_X3",
    "warning": "ros2 run icar_laser laser_Warning_a1_X3",
    "camera": "ros2 launch astra_camera astra.launch.xml",
    "hsv": "ros2 run icar_astra colorHSV",
    "color_track": "ros2 run icar_astra colorTracker",
    "map_gmapping": "ros2 launch yahboomcar_nav map_gmapping_launch.py",
    "map_display": "ros2 launch yahboomcar_nav display_map_launch.py",
    "map_save": "ros2 launch yahboomcar_nav save_map_launch.py",
    "nav_laser": "ros2 launch yahboomcar_nav laser_bringup_launch.py",
    "nav_display": "ros2 launch yahboomcar_nav display_nav_launch.py",
    "nav_dwa": "ros2 launch yahboomcar_nav navigation_dwa_launch.py",
    "nav_teb": "ros2 launch yahboomcar_nav navigation_teb_launch.py",
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
}

ONE_SHOT_TASKS = {"map_save"}
RUNNING = {}
LOG_HANDLES = {}
SERVER_CONFIG = {
    "container": "8b98",
    "dry_run": False,
}
MOTION_BRIDGE = None
CAMERA_STREAM = None
STREAM_WRITE_TIMEOUT = 2.0
STREAM_DISCONNECT_ERRNOS = {
    errno.ECONNABORTED,
    errno.ECONNRESET,
    errno.EPIPE,
    errno.ETIMEDOUT,
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
        watchdog_ms=350,
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


def create_motion_bridge(container):
    return MotionBridgeClient(
        container=container,
        script_path=Path(__file__).with_name("motion_bridge.py"),
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


def docker_command(ros_command):
    return [
        "docker",
        "exec",
        "-i",
        SERVER_CONFIG["container"],
        "bash",
        "-lc",
        f"{ROS_SETUP}; {ros_command}",
    ]


def run_host(command, timeout=8):
    if SERVER_CONFIG["dry_run"]:
        return {"dry_run": True, "command": command, "returncode": 0, "stdout": "", "stderr": ""}
    completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    return {
        "returncode": completed.returncode,
        "stdout": completed.stdout[-2000:],
        "stderr": completed.stderr[-2000:],
    }


def run_once(ros_command, timeout=8):
    command = docker_command(ros_command)
    return run_host(command, timeout=timeout)


def container_is_running():
    result = run_host(
        ["docker", "inspect", "-f", "{{.State.Running}}", SERVER_CONFIG["container"]],
        timeout=3,
    )
    return result["returncode"] == 0 and result["stdout"].strip().lower() == "true"


def safe_process_pattern(pattern):
    return f"[{pattern[0]}]{pattern[1:]}" if pattern else pattern


def task_processes(task):
    if task not in TASK_PATTERNS:
        return ""
    pattern = safe_process_pattern(TASK_PATTERNS[task])
    result = run_once(f"pgrep -af {shlex.quote(pattern)} || true", timeout=3)
    return result.get("stdout", "").strip()


def close_task_log(task):
    handle = LOG_HANDLES.pop(task, None)
    if handle is not None:
        handle.close()


def start_task(task):
    if task not in TASK_COMMANDS:
        return 404, {"error": f"unknown task: {task}"}
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


def stop_task(task):
    if task == "all":
        stopped = []
        for key in reversed(TASK_COMMANDS):
            _, body = stop_task(key)
            stopped.append(body)
        return 200, {"stopped": stopped}
    if task not in TASK_COMMANDS:
        return 404, {"error": f"unknown task: {task}"}

    if SERVER_CONFIG["dry_run"]:
        RUNNING.pop(task, None)
        close_task_log(task)
        return 200, {"task": task, "status": "dry_run"}

    pattern = safe_process_pattern(TASK_PATTERNS[task])
    result = run_once(f"pkill -f {shlex.quote(pattern)} || true", timeout=3)
    process = RUNNING.get(task)
    if process is not None and process.poll() is None:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            process.kill()
    RUNNING.pop(task, None)
    close_task_log(task)
    return 200, {"task": task, "status": "stopped", "output": result.get("stdout", "")}


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


def query_float(query, name, default=0.0):
    raw = query.get(name, [str(default)])[0]
    try:
        return float(raw)
    except ValueError:
        raise ValueError(f"{name} must be a number")


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
        self.reply(status, body)

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
        if path == "health":
            return 200, {
                "ok": True,
                "container": SERVER_CONFIG["container"],
                "container_running": container_is_running(),
                "motion_bridge_ready": bool(MOTION_BRIDGE and MOTION_BRIDGE.is_running()),
            }
        if path == "status":
            states = {}
            for task in TASK_COMMANDS:
                process = task_processes(task)
                states[task] = {"running": bool(process), "process": process}
            return 200, states
        if path == "stop/all":
            motion_result = stop_motion_safely()
            status, body = stop_task("all")
            body["motion"] = motion_result
            return status, body
        if path.startswith("start/"):
            return start_task(unquote(path.split("/", 1)[1]))
        if path.startswith("stop/"):
            return stop_task(unquote(path.split("/", 1)[1]))
        if path == "emergency_stop":
            result = stop_motion_safely()
            stop_task("all")
            return 200, {"status": "emergency_stop", "result": result}
        if path.startswith("move/"):
            direction = unquote(path.split("/", 1)[1])
            speed_raw = query.get("speed", ["0.18"])[0]
            turn_raw = query.get("turn", ["0.8"])[0]
            speed = clamp_float(speed_raw, 0.18, 0.05, 0.35)
            turn = clamp_float(turn_raw, 0.8, 0.2, 1.2)
            return 200, MOTION_BRIDGE.send(direction, speed, turn)
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
    global CAMERA_STREAM, MOTION_BRIDGE
    parser = argparse.ArgumentParser(description="HTTP bridge for smart car ROS2 commands")
    parser.add_argument("--container", default="8b98")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=port_number, default=8000)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--camera-device", default=None)
    parser.add_argument("--camera-width", type=positive_int, default=640)
    parser.add_argument("--camera-height", type=positive_int, default=480)
    parser.add_argument("--camera-fps", type=positive_int, default=18)
    parser.add_argument("--camera-quality", type=jpeg_quality, default=70)
    args = parser.parse_args()
    SERVER_CONFIG["container"] = args.container
    SERVER_CONFIG["dry_run"] = args.dry_run
    MOTION_BRIDGE = create_motion_bridge(args.container)
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
                    MOTION_BRIDGE.shutdown()


if __name__ == "__main__":
    main()
