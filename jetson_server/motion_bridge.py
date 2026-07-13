#!/usr/bin/env python3
"""Persistent ROS 2 motion publisher used by the Jetson HTTP service."""

import argparse
import json
import math
import select
import signal
import sys
import threading
import time
from dataclasses import dataclass
from typing import Callable, Dict, Optional, Tuple


VALID_DIRECTIONS = {
    "front",
    "back",
    "left",
    "right",
    "turn_left",
    "turn_right",
    "stop",
}


@dataclass(frozen=True)
class MotionCommand:
    sequence: int
    direction: str
    speed: float
    turn: float


def twist_values(direction: str, speed: float, turn: float) -> Tuple[float, float, float]:
    if direction not in VALID_DIRECTIONS:
        raise ValueError(f"unknown direction: {direction}")

    speed = abs(float(speed))
    turn = abs(float(turn))
    if direction == "front":
        return speed, 0.0, 0.0
    if direction == "back":
        return -speed, 0.0, 0.0
    if direction == "left":
        return 0.0, speed, 0.0
    if direction == "right":
        return 0.0, -speed, 0.0
    if direction == "turn_left":
        return 0.0, 0.0, turn
    if direction == "turn_right":
        return 0.0, 0.0, -turn
    return 0.0, 0.0, 0.0


def decode_command(raw: str) -> MotionCommand:
    payload = json.loads(raw)
    direction = str(payload["direction"])
    if direction not in VALID_DIRECTIONS:
        raise ValueError(f"unknown direction: {direction}")
    return MotionCommand(
        sequence=int(payload["sequence"]),
        direction=direction,
        speed=abs(float(payload.get("speed", 0.12))),
        turn=abs(float(payload.get("turn", 0.8))),
    )


class MotionWatchdog:
    def __init__(self, timeout_seconds: float):
        self.timeout_seconds = float(timeout_seconds)
        self.deadline: Optional[float] = None

    def update(self, direction: str, now: float) -> None:
        if direction == "stop" or self.timeout_seconds <= 0:
            self.deadline = None
        else:
            self.deadline = float(now) + self.timeout_seconds

    def consume_expiry(self, now: float) -> bool:
        if self.deadline is None or float(now) < self.deadline:
            return False
        self.deadline = None
        return True


class OdometryTracker:
    def __init__(self, clock: Callable[[], float] = time.perf_counter):
        self.clock = clock
        self.distance_m = 0.0
        self.last_update = None
        self.linear_x = 0.0
        self.linear_y = 0.0
        self.angular_z = 0.0
        self.lock = threading.Lock()

    def update(self, linear_x, linear_y, angular_z, now=None) -> None:
        timestamp = self.clock() if now is None else float(now)
        with self.lock:
            if self.last_update is not None:
                elapsed = max(0.0, timestamp - self.last_update)
                self.distance_m += math.hypot(self.linear_x, self.linear_y) * elapsed
            self.last_update = timestamp
            self.linear_x = float(linear_x)
            self.linear_y = float(linear_y)
            self.angular_z = float(angular_z)

    def snapshot(self) -> Dict[str, float]:
        with self.lock:
            return {
                "distance_m": round(self.distance_m, 4),
                "measured_linear_x": self.linear_x,
                "measured_linear_y": self.linear_y,
                "measured_angular_z": self.angular_z,
            }


class MotionBridgeRuntime:
    def __init__(
        self,
        publish_twist: Callable[[Tuple[float, float, float]], None],
        watchdog_seconds: float,
        clock: Callable[[], float] = time.perf_counter,
    ):
        self.publish_twist = publish_twist
        self.clock = clock
        self.watchdog = MotionWatchdog(watchdog_seconds)
        self.odometry = OdometryTracker()

    def update_odometry(self, linear_x, linear_y, angular_z, now=None) -> None:
        self.odometry.update(linear_x, linear_y, angular_z, now=now)

    def handle_line(self, raw: str) -> Dict[str, object]:
        started_at = self.clock()
        command = decode_command(raw)
        values = twist_values(command.direction, command.speed, command.turn)
        self.publish_twist(values)
        self.watchdog.update(command.direction, started_at)
        elapsed_ms = round((self.clock() - started_at) * 1000.0, 3)
        response = {
            "ok": True,
            "sequence": command.sequence,
            "direction": command.direction,
            "linear_x": values[0],
            "linear_y": values[1],
            "angular_z": values[2],
            "bridge_latency_ms": elapsed_ms,
        }
        response.update(self.odometry.snapshot())
        return response

    def poll_watchdog(self, now: Optional[float] = None) -> bool:
        current = self.clock() if now is None else float(now)
        if not self.watchdog.consume_expiry(current):
            return False
        self.publish_twist((0.0, 0.0, 0.0))
        return True

    def shutdown(self) -> None:
        self.watchdog.update("stop", self.clock())
        self.publish_twist((0.0, 0.0, 0.0))


def main() -> None:
    parser = argparse.ArgumentParser(description="Persistent ROS 2 /cmd_vel publisher")
    parser.add_argument("--watchdog-ms", type=int, default=0)
    args = parser.parse_args()

    import rclpy
    from geometry_msgs.msg import Twist

    rclpy.init(args=None)
    node = rclpy.create_node("icar_http_motion_bridge")
    publisher = node.create_publisher(Twist, "/cmd_vel", 10)
    stopping = threading.Event()

    def publish(values: Tuple[float, float, float]) -> None:
        message = Twist()
        message.linear.x = values[0]
        message.linear.y = values[1]
        message.angular.z = values[2]
        publisher.publish(message)
        rclpy.spin_once(node, timeout_sec=0.0)

    runtime = MotionBridgeRuntime(
        publish_twist=publish,
        watchdog_seconds=max(args.watchdog_ms, 0) / 1000.0,
    )

    def measured_velocity(message: Twist) -> None:
        runtime.update_odometry(
            message.linear.x,
            message.linear.y,
            message.angular.z,
        )

    node.create_subscription(Twist, "/vel_raw", measured_velocity, 10)

    def request_stop(_signum, _frame) -> None:
        stopping.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    try:
        while not stopping.is_set():
            rclpy.spin_once(node, timeout_sec=0.0)
            readable, _, _ = select.select([sys.stdin], [], [], 0.02)
            if readable:
                raw = sys.stdin.readline()
                if not raw:
                    break
                try:
                    response = runtime.handle_line(raw)
                except Exception as error:
                    sequence = None
                    try:
                        sequence = json.loads(raw).get("sequence")
                    except Exception:
                        pass
                    response = {
                        "ok": False,
                        "sequence": sequence,
                        "error": str(error),
                    }
                sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
                sys.stdout.flush()

            if runtime.poll_watchdog():
                sys.stderr.write("motion watchdog published zero velocity\n")
                sys.stderr.flush()
    finally:
        runtime.shutdown()
        for _ in range(3):
            rclpy.spin_once(node, timeout_sec=0.02)
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
