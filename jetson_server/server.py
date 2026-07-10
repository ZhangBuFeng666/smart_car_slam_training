#!/usr/bin/env python3
import argparse
import json
import math
import signal
import subprocess
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse


ROS_SETUP = (
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
    "camera": "ros2 launch astra_camera astra.launch.xml",
    "hsv": "ros2 run icar_astra colorHSV",
    "map_gmapping": "ros2 launch yahboomcar_nav map_gmapping_launch.py",
    "map_display": "ros2 launch yahboomcar_nav display_map_launch.py",
    "map_save": "ros2 launch yahboomcar_nav save_map_launch.py",
    "nav_laser": "ros2 launch yahboomcar_nav laser_bringup_launch.py",
    "nav_display": "ros2 launch yahboomcar_nav display_nav_launch.py",
    "nav_dwa": "ros2 launch yahboomcar_nav navigation_dwa_launch.py",
    "nav_teb": "ros2 launch yahboomcar_nav navigation_teb_launch.py",
}

ONE_SHOT_TASKS = {"map_save"}
RUNNING = {}
SERVER_CONFIG = {
    "container": "8b98",
    "dry_run": False,
}


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


def run_once(ros_command, timeout=8):
    command = docker_command(ros_command)
    if SERVER_CONFIG["dry_run"]:
        return {"dry_run": True, "command": command}
    completed = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    return {
        "returncode": completed.returncode,
        "stdout": completed.stdout[-2000:],
        "stderr": completed.stderr[-2000:],
    }


def start_task(task):
    if task not in TASK_COMMANDS:
        return 404, {"error": f"unknown task: {task}"}
    if task in RUNNING and RUNNING[task].poll() is None:
        return 200, {"task": task, "status": "already_running"}
    if task in ONE_SHOT_TASKS:
        return 200, {"task": task, "status": "finished", "result": run_once(TASK_COMMANDS[task], timeout=30)}

    command = docker_command(TASK_COMMANDS[task])
    if SERVER_CONFIG["dry_run"]:
        return 200, {"task": task, "status": "dry_run", "command": command}
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    RUNNING[task] = process
    return 200, {"task": task, "status": "started", "pid": process.pid}


def stop_task(task):
    if task == "all":
        stopped = []
        for key in list(RUNNING.keys()):
            _, body = stop_task(key)
            stopped.append(body)
        return 200, {"stopped": stopped}
    process = RUNNING.get(task)
    if process is None or process.poll() is not None:
        RUNNING.pop(task, None)
        return 200, {"task": task, "status": "not_running"}
    process.send_signal(signal.SIGTERM)
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        process.kill()
    RUNNING.pop(task, None)
    return 200, {"task": task, "status": "stopped"}


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

        try:
            status, body = self.route(path, query)
        except ValueError as error:
            status, body = 400, {"error": str(error)}
        except Exception as error:
            status, body = 500, {"error": str(error)}
        self.reply(status, body)

    def route(self, path, query):
        if path == "health":
            return 200, {"ok": True, "container": SERVER_CONFIG["container"]}
        if path == "status":
            return 200, {
                "running": {
                    task: process.poll() is None
                    for task, process in RUNNING.items()
                },
                "tasks": sorted(TASK_COMMANDS.keys()),
            }
        if path == "stop/all":
            return stop_task("all")
        if path.startswith("start/"):
            return start_task(unquote(path.split("/", 1)[1]))
        if path.startswith("stop/"):
            return stop_task(unquote(path.split("/", 1)[1]))
        if path == "emergency_stop":
            stop_task("all")
            return 200, {"status": "emergency_stop", "result": run_once(build_twist_command("stop", 0.0, 0.0))}
        if path.startswith("move/"):
            direction = unquote(path.split("/", 1)[1])
            speed = query_float(query, "speed", 0.12)
            turn = query_float(query, "turn", 0.8)
            return 200, {"direction": direction, "result": run_once(build_twist_command(direction, speed, turn), timeout=3)}
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
    parser = argparse.ArgumentParser(description="HTTP bridge for smart car ROS2 commands")
    parser.add_argument("--container", default="8b98")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    SERVER_CONFIG["container"] = args.container
    SERVER_CONFIG["dry_run"] = args.dry_run

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Smart car HTTP server on {args.host}:{args.port}, container={args.container}")
    server.serve_forever()


if __name__ == "__main__":
    main()
