#!/usr/bin/env python3
"""Arrow-guided turn demo for the parking patrol car.

Behavior
--------
1. Start creeping forward at a low linear speed.
2. Watch /vision/detections for a ground arrow with stable turn_left / turn_right.
3. Sample /camera/depth/image_raw under the arrow box (16UC1 millimeters).
4. When distance is about 1.0 m, drive straight another 0.5 m.
5. Yaw ±90 degrees at a fixed angular rate, then publish zero velocity immediately.

Run on the Jetson (ROS topics must be visible in that shell):

    source /opt/ros/foxy/setup.bash
    cd /home/jetson/icar_vision
    python3 arrow_turn_demo.py --dry-run          # print only
    python3 arrow_turn_demo.py                   # real /cmd_vel

Safety: Ctrl+C always stops. Keep a physical e-stop ready.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from enum import Enum
from typing import Any, Dict, List, Optional, Sequence, Tuple


TURN_DIRECTIONS = ("turn_left", "turn_right")


class Phase(str, Enum):
    SEEK = "seek"
    ADVANCE = "advance"
    TURN = "turn"
    DONE = "done"


@dataclass(frozen=True)
class ArrowTarget:
    direction: str
    distance_m: float
    confidence: float
    track_id: Optional[int]
    box: Dict[str, float]


@dataclass
class ControllerConfig:
    vision_url: str = "http://127.0.0.1:8200/vision/detections"
    depth_topic: str = "/camera/depth/image_raw"
    cmd_vel_topic: str = "/cmd_vel"
    # Prefer HTTP control service (/move/*). Direct /cmd_vel often fails when
    # the icar motion bridge owns the velocity channel the driver obeys.
    motion_url: str = "http://127.0.0.1:8000"
    trigger_distance_m: float = 1.0
    trigger_tolerance_m: float = 0.2
    extra_forward_m: float = 0.5
    seek_speed_m_s: float = 0.05
    linear_speed_m_s: float = 0.08
    angular_speed_rad_s: float = 0.30
    turn_angle_deg: float = 90.0
    confirm_frames: int = 3
    poll_hz: float = 5.0
    depth_min_m: float = 0.6
    depth_max_m: float = 8.0
    depth_patch: int = 7
    dry_run: bool = False
    simulate_distance_m: Optional[float] = None


def fetch_detections(url: str, timeout: float = 2.0) -> Dict[str, Any]:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def twist_to_move_path(vx: float, yaw: float) -> str:
    """Map Twist-like (vx, yaw) to icar-control /move/* path+query."""
    eps = 1e-3
    if abs(vx) < eps and abs(yaw) < eps:
        return "/move/stop"
    if abs(yaw) >= abs(vx) and abs(yaw) >= eps:
        direction = "turn_left" if yaw > 0 else "turn_right"
        # server clamps turn to [0.2, 1.2]
        turn = max(0.2, min(1.2, abs(float(yaw))))
        return "/move/%s?turn=%.3f&speed=0.10" % (direction, turn)
    direction = "front" if vx > 0 else "back"
    # server clamps speed to [0.05, 0.35]
    speed = max(0.05, min(0.35, abs(float(vx))))
    return "/move/%s?speed=%.3f" % (direction, speed)


def publish_http_motion(base_url: str, vx: float, yaw: float, timeout: float = 1.0) -> None:
    url = base_url.rstrip("/") + twist_to_move_path(vx, yaw)
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        response.read()


def pick_arrow_target(snapshot: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Prefer stable left/right arrows; fall back to single-frame direction."""
    if snapshot.get("state") != "live":
        return None
    candidates = []
    for item in snapshot.get("detections") or []:
        if item.get("label") != "direction_arrow":
            continue
        direction = item.get("stable_direction") or item.get("direction")
        if direction not in TURN_DIRECTIONS:
            continue
        box = item.get("box") or {}
        if not box:
            continue
        candidates.append(item)
    if not candidates:
        return None

    def sort_key(item: Dict[str, Any]) -> Tuple[int, float, float]:
        stable = 1 if item.get("stable_direction") in TURN_DIRECTIONS else 0
        conf = float(item.get("direction_confidence") or item.get("confidence") or 0.0)
        bottom = float((item.get("box") or {}).get("bottom") or 1.0)
        # Prefer stable, higher confidence, and lower on the image (usually nearer ground mark).
        return (stable, conf, bottom)

    return max(candidates, key=sort_key)


def sample_depth_meters(
    depth_mm: Any,
    box: Dict[str, float],
    patch: int = 7,
    min_m: float = 0.6,
    max_m: float = 8.0,
) -> Optional[float]:
    """Median depth under the arrow box bottom-center. depth_mm is HxW 16UC1."""
    if depth_mm is None:
        return None
    try:
        import numpy as np

        image = np.asarray(depth_mm)
        if image.ndim != 2:
            return None
        height, width = int(image.shape[0]), int(image.shape[1])
    except Exception:
        if not depth_mm:
            return None
        height = len(depth_mm)
        width = len(depth_mm[0]) if height else 0
        image = depth_mm
    if height <= 0 or width <= 0:
        return None

    left = float(box.get("left", 0.0))
    right = float(box.get("right", 1.0))
    top = float(box.get("top", 0.0))
    bottom = float(box.get("bottom", 1.0))
    cx = int(round(((left + right) * 0.5) * (width - 1)))
    # Ground arrows: sample slightly above the bottom edge.
    cy = int(round((top * 0.25 + bottom * 0.75) * (height - 1)))
    half = max(1, int(patch) // 2)

    samples: List[float] = []
    for y in range(max(0, cy - half), min(height, cy + half + 1)):
        row = image[y]
        for x in range(max(0, cx - half), min(width, cx + half + 1)):
            raw = int(row[x])
            if raw <= 0:
                continue
            meters = raw / 1000.0
            if min_m <= meters <= max_m:
                samples.append(meters)
    if not samples:
        return None
    samples.sort()
    return samples[len(samples) // 2]


def in_trigger_band(distance_m: float, target_m: float, tolerance_m: float) -> bool:
    return abs(float(distance_m) - float(target_m)) <= float(tolerance_m)


def advance_duration_s(distance_m: float, speed_m_s: float) -> float:
    speed = max(1e-3, abs(float(speed_m_s)))
    return abs(float(distance_m)) / speed


def turn_duration_s(angle_deg: float, angular_speed_rad_s: float) -> float:
    omega = max(1e-3, abs(float(angular_speed_rad_s)))
    return math.radians(abs(float(angle_deg))) / omega


def yaw_sign(direction: str) -> float:
    if direction == "turn_left":
        return 1.0
    if direction == "turn_right":
        return -1.0
    raise ValueError("unsupported direction: %s" % direction)


class ArrowTurnController:
    def __init__(self, config: ControllerConfig) -> None:
        self.config = config
        self.phase = Phase.SEEK
        self._confirm_hits = 0
        self._locked: Optional[ArrowTarget] = None
        self._phase_started = 0.0
        self._advance_seconds = 0.0
        self._turn_seconds = 0.0

    def reset(self) -> None:
        self.phase = Phase.SEEK
        self._confirm_hits = 0
        self._locked = None
        self._phase_started = 0.0
        self._advance_seconds = 0.0
        self._turn_seconds = 0.0

    def on_observation(
        self,
        direction: Optional[str],
        distance_m: Optional[float],
        confidence: float = 0.0,
        track_id: Optional[int] = None,
        now: Optional[float] = None,
    ) -> Tuple[float, float, str]:
        """Return (vx, yaw_rate, note)."""
        now = time.monotonic() if now is None else now
        cfg = self.config

        if self.phase == Phase.DONE:
            return 0.0, 0.0, "done"

        if self.phase == Phase.ADVANCE:
            elapsed = now - self._phase_started
            if elapsed >= self._advance_seconds:
                self.phase = Phase.TURN
                self._phase_started = now
                self._turn_seconds = turn_duration_s(cfg.turn_angle_deg, cfg.angular_speed_rad_s)
                assert self._locked is not None
                return (
                    0.0,
                    yaw_sign(self._locked.direction) * cfg.angular_speed_rad_s,
                    "start_turn_%s" % self._locked.direction,
                )
            return cfg.linear_speed_m_s, 0.0, "advance_%.2fm" % cfg.extra_forward_m

        if self.phase == Phase.TURN:
            elapsed = now - self._phase_started
            if elapsed >= self._turn_seconds:
                self.phase = Phase.DONE
                return 0.0, 0.0, "turn_complete_stop"
            assert self._locked is not None
            return (
                0.0,
                yaw_sign(self._locked.direction) * cfg.angular_speed_rad_s,
                "turning_%s" % self._locked.direction,
            )

        # SEEK: creep forward until an arrow is confirmed near the trigger distance.
        seek_speed = abs(float(cfg.seek_speed_m_s))
        if (
            direction in TURN_DIRECTIONS
            and distance_m is not None
            and in_trigger_band(distance_m, cfg.trigger_distance_m, cfg.trigger_tolerance_m)
        ):
            self._confirm_hits += 1
            if self._confirm_hits >= cfg.confirm_frames:
                self._locked = ArrowTarget(
                    direction=direction,
                    distance_m=float(distance_m),
                    confidence=float(confidence),
                    track_id=track_id,
                    box={},
                )
                self.phase = Phase.ADVANCE
                self._phase_started = now
                self._advance_seconds = advance_duration_s(
                    cfg.extra_forward_m, cfg.linear_speed_m_s
                )
                return (
                    cfg.linear_speed_m_s,
                    0.0,
                    "locked_%s@%.2fm_then_advance" % (direction, distance_m),
                )
            return (
                seek_speed,
                0.0,
                "confirming_%d/%d_creep" % (self._confirm_hits, cfg.confirm_frames),
            )

        self._confirm_hits = 0
        if direction in TURN_DIRECTIONS and distance_m is not None:
            return (
                seek_speed,
                0.0,
                "seeking_arrow_%s@%.2fm" % (direction, distance_m),
            )
        if direction in TURN_DIRECTIONS:
            return seek_speed, 0.0, "seeking_arrow_%s_no_depth" % direction
        return seek_speed, 0.0, "seeking_creep"


def _depth_array_from_ros_image(message):
    import numpy as np

    if message.encoding not in ("16UC1", "mono16"):
        raise RuntimeError("unsupported depth encoding: %s" % message.encoding)
    row = max(1, int(message.step) // 2)
    data = np.frombuffer(message.data, dtype=np.uint16)
    if data.size < message.height * row:
        raise RuntimeError(
            "depth buffer too small: size=%d height=%d step_u16=%d"
            % (data.size, message.height, row)
        )
    image = data.reshape(message.height, row)[:, : message.width]
    return np.ascontiguousarray(image)


class RosBridge:
    def __init__(self, config: ControllerConfig) -> None:
        import rclpy
        from geometry_msgs.msg import Twist
        from rclpy.qos import QoSProfile, qos_profile_sensor_data
        from sensor_msgs.msg import Image

        try:
            from rclpy.qos import ReliabilityPolicy, HistoryPolicy

            best_effort = QoSProfile(
                reliability=ReliabilityPolicy.BEST_EFFORT,
                history=HistoryPolicy.KEEP_LAST,
                depth=10,
            )
        except Exception:  # pragma: no cover - older rclpy naming
            best_effort = qos_profile_sensor_data

        self.config = config
        self._rclpy = rclpy
        rclpy.init(args=None)
        self.node = rclpy.create_node("arrow_turn_demo")
        self._twist_type = Twist
        self.publisher = self.node.create_publisher(Twist, config.cmd_vel_topic, 10)
        self._latest_depth = None
        self._depth_stamp = 0.0
        self._depth_frames = 0
        # Foxy rclpy is not reliable when spinning from a background thread.
        # Pump on the main thread instead (see spin_once / wait_for_depth).
        # Subscribe twice: sensor_data + explicit best_effort (same callback).
        for qos in (qos_profile_sensor_data, best_effort):
            self.node.create_subscription(
                Image,
                config.depth_topic,
                self._on_depth,
                qos,
            )
        if self.wait_for_depth(5.0):
            print(
                "depth stream ready on %s (frames=%d)"
                % (config.depth_topic, self._depth_frames),
                flush=True,
            )
        else:
            print(
                "WARNING: no depth yet on %s after 5s. "
                "In another shell: ros2 topic echo %s --qos-reliability best_effort"
                % (config.depth_topic, config.depth_topic),
                flush=True,
            )

    def _on_depth(self, message) -> None:
        try:
            image = _depth_array_from_ros_image(message)
            self._latest_depth = image
            self._depth_stamp = time.monotonic()
            self._depth_frames += 1
            if self._depth_frames == 1:
                print(
                    "first depth frame: %dx%d %s"
                    % (message.width, message.height, message.encoding),
                    flush=True,
                )
        except Exception as error:  # pragma: no cover - runtime ROS path
            print("depth decode failed: %s" % error, flush=True)

    def spin_once(self, timeout_sec: float = 0.1) -> None:
        """Drain DDS callbacks on the main thread for up to timeout_sec."""
        deadline = time.monotonic() + max(0.02, float(timeout_sec))
        while time.monotonic() < deadline and self._rclpy.ok():
            self._rclpy.spin_once(self.node, timeout_sec=0.01)

    def wait_for_depth(self, timeout_sec: float = 5.0) -> bool:
        deadline = time.monotonic() + float(timeout_sec)
        while time.monotonic() < deadline:
            self.spin_once(0.1)
            if self.latest_depth() is not None:
                return True
        return False

    def latest_depth(self):
        if self._latest_depth is None:
            return None
        # Hold last frame briefly; Astra / DDS can hiccup under load.
        if time.monotonic() - self._depth_stamp > 5.0:
            return None
        return self._latest_depth

    def publish_cmd(self, vx: float, yaw: float) -> None:
        message = self._twist_type()
        message.linear.x = float(vx)
        message.angular.z = float(yaw)
        if not self.config.dry_run:
            self.publisher.publish(message)

    def shutdown(self) -> None:
        try:
            self.publish_cmd(0.0, 0.0)
            time.sleep(0.05)
            self.publish_cmd(0.0, 0.0)
        finally:
            self.node.destroy_node()
            if self._rclpy.ok():
                self._rclpy.shutdown()


def write_status_file(
    path: str,
    phase: str,
    note: str,
    direction: Optional[str],
    distance_m: Optional[float],
    track_id: Optional[int],
    vx: float,
    yaw: float,
) -> None:
    payload = {
        "phase": phase,
        "note": note,
        "direction": direction,
        "distance_m": None if distance_m is None else round(float(distance_m), 3),
        "track_id": track_id,
        "vx": round(float(vx), 3),
        "yaw": round(float(yaw), 3),
        "updated_at": time.time(),
    }
    try:
        directory = os.path.dirname(path)
        if directory:
            os.makedirs(directory, exist_ok=True)
        tmp_path = path + ".tmp"
        with open(tmp_path, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False)
        os.replace(tmp_path, path)
    except OSError as error:
        print("status write failed: %s" % error, flush=True)


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Detect arrow at ~1m, advance 0.5m, turn 90°, stop.")
    parser.add_argument("--vision-url", default="http://127.0.0.1:8200/vision/detections")
    parser.add_argument("--depth-topic", default="/camera/depth/image_raw")
    parser.add_argument("--cmd-vel-topic", default="/cmd_vel")
    parser.add_argument(
        "--motion-url",
        default="http://127.0.0.1:8000",
        help="icar-control base URL for /move/* (set empty to publish ROS /cmd_vel instead)",
    )
    parser.add_argument(
        "--status-file",
        default="/tmp/arrow_turn_status.json",
        help="JSON status path readable by icar-control /arrow_turn/status",
    )
    parser.add_argument("--trigger-distance", type=float, default=1.0)
    parser.add_argument("--trigger-tolerance", type=float, default=0.2)
    parser.add_argument("--extra-forward", type=float, default=0.5)
    parser.add_argument(
        "--seek-speed",
        type=float,
        default=0.05,
        help="Creep forward speed while searching for the 1 m trigger (m/s)",
    )
    parser.add_argument(
        "--linear-speed",
        type=float,
        default=0.08,
        help="Forward speed for the extra 0.5 m after trigger (m/s)",
    )
    parser.add_argument(
        "--angular-speed",
        type=float,
        default=0.30,
        help="Yaw rate while turning 90 deg (rad/s)",
    )
    parser.add_argument("--turn-angle", type=float, default=90.0)
    parser.add_argument("--confirm-frames", type=int, default=3)
    parser.add_argument("--poll-hz", type=float, default=5.0)
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Do not send motion (neither HTTP /move nor ROS /cmd_vel)",
    )
    parser.add_argument(
        "--simulate-distance",
        type=float,
        default=None,
        help="Bypass depth topic and pretend every arrow is this many meters away",
    )
    parser.add_argument("--max-runtime", type=float, default=120.0)
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_arg_parser().parse_args(argv)
    motion_url = (args.motion_url or "").strip()
    config = ControllerConfig(
        vision_url=args.vision_url,
        depth_topic=args.depth_topic,
        cmd_vel_topic=args.cmd_vel_topic,
        motion_url=motion_url,
        trigger_distance_m=args.trigger_distance,
        trigger_tolerance_m=args.trigger_tolerance,
        extra_forward_m=args.extra_forward,
        seek_speed_m_s=args.seek_speed,
        linear_speed_m_s=args.linear_speed,
        angular_speed_rad_s=args.angular_speed,
        turn_angle_deg=args.turn_angle,
        confirm_frames=args.confirm_frames,
        poll_hz=args.poll_hz,
        dry_run=bool(args.dry_run),
        simulate_distance_m=args.simulate_distance,
    )
    controller = ArrowTurnController(config)
    bridge = None
    started = time.monotonic()
    period = 1.0 / max(0.5, config.poll_hz)
    use_http_motion = bool(config.motion_url) and not config.dry_run

    print(
        "arrow_turn_demo starting dry_run=%s seek=%.2fm/s trigger=%.2fm extra=%.2fm turn=%.0fdeg motion=%s"
        % (
            config.dry_run,
            config.seek_speed_m_s,
            config.trigger_distance_m,
            config.extra_forward_m,
            config.turn_angle_deg,
            config.motion_url if use_http_motion else ("ros:%s" % config.cmd_vel_topic),
        ),
        flush=True,
    )
    if config.simulate_distance_m is not None:
        print(
            "using simulated distance_m=%.2f (no ROS depth)" % config.simulate_distance_m,
            flush=True,
        )

    # Motion via HTTP does not need ROS /cmd_vel. Depth still needs ROS unless simulated.
    needs_ros_for_cmd = (not config.dry_run) and (not use_http_motion)
    needs_ros_for_depth = config.simulate_distance_m is None
    if needs_ros_for_cmd or needs_ros_for_depth:
        try:
            bridge = RosBridge(config)
        except ImportError as error:
            if config.dry_run or use_http_motion:
                print(
                    "rclpy not available (%s). Continuing without ROS depth/cmd_vel.\n"
                    "Tip: python3 arrow_turn_demo.py --dry-run --simulate-distance 1.0\n"
                    "Or enable ROS first: source /opt/ros/foxy/setup.bash "
                    "(or docker exec into the ROS container)."
                    % error,
                    flush=True,
                )
                bridge = None
                if needs_ros_for_depth and config.simulate_distance_m is None:
                    print(
                        "ERROR: depth requires rclpy (or pass --simulate-distance).",
                        flush=True,
                    )
                    return 2
            else:
                print(
                    "ERROR: rclpy not found. On the Jetson host run:\n"
                    "  source /opt/ros/foxy/setup.bash\n"
                    "or enter the ROS container first, e.g.\n"
                    "  docker exec -it fd7ba18044cd bash\n"
                    "  source /opt/ros/foxy/setup.bash\n"
                    "Then re-run this script.",
                    flush=True,
                )
                return 2

    def emit_cmd(vx: float, yaw: float) -> None:
        if config.dry_run:
            return
        if use_http_motion:
            try:
                publish_http_motion(config.motion_url, vx, yaw)
            except (urllib.error.URLError, TimeoutError, OSError) as error:
                print("motion http failed: %s" % error, flush=True)
            return
        if bridge is not None:
            bridge.publish_cmd(vx, yaw)

    try:
        while time.monotonic() - started < float(args.max_runtime):
            loop_started = time.monotonic()
            if bridge is not None:
                # Main-thread DDS pump; keep long enough to catch BEST_EFFORT frames.
                bridge.spin_once(0.15)

            direction = None
            distance_m = None
            confidence = 0.0
            track_id = None
            note_prefix = ""

            if controller.phase == Phase.SEEK:
                try:
                    snapshot = fetch_detections(config.vision_url)
                except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError) as error:
                    print("vision fetch failed: %s" % error, flush=True)
                    emit_cmd(0.0, 0.0)
                    time.sleep(period)
                    continue

                arrow = pick_arrow_target(snapshot)
                if arrow is not None:
                    direction = arrow.get("stable_direction") or arrow.get("direction")
                    confidence = float(
                        arrow.get("direction_confidence") or arrow.get("confidence") or 0.0
                    )
                    track_id = arrow.get("track_id")
                    if config.simulate_distance_m is not None:
                        distance_m = float(config.simulate_distance_m)
                    elif bridge is not None:
                        depth = bridge.latest_depth()
                        if depth is not None:
                            distance_m = sample_depth_meters(
                                depth,
                                arrow.get("box") or {},
                                patch=config.depth_patch,
                                min_m=config.depth_min_m,
                                max_m=config.depth_max_m,
                            )
                        else:
                            note_prefix = "waiting_depth;"
                    else:
                        note_prefix = "no_depth;"

            vx, yaw, note = controller.on_observation(
                direction=direction,
                distance_m=distance_m,
                confidence=confidence,
                track_id=track_id if isinstance(track_id, int) else None,
            )
            emit_cmd(vx, yaw)
            composed_note = "%s%s" % (note_prefix, note)
            write_status_file(
                args.status_file,
                phase=controller.phase.value,
                note=composed_note,
                direction=direction if isinstance(direction, str) else None,
                distance_m=distance_m,
                track_id=track_id if isinstance(track_id, int) else None,
                vx=vx,
                yaw=yaw,
            )
            print(
                "phase=%s vx=%.2f yaw=%.2f dir=%s dist=%s track=%s %s"
                % (
                    controller.phase.value,
                    vx,
                    yaw,
                    direction,
                    None if distance_m is None else "%.2f" % distance_m,
                    track_id,
                    composed_note,
                ),
                flush=True,
            )
            if controller.phase == Phase.DONE:
                emit_cmd(0.0, 0.0)
                write_status_file(
                    args.status_file,
                    phase=Phase.DONE.value,
                    note="turn_complete_stop",
                    direction=None,
                    distance_m=None,
                    track_id=None,
                    vx=0.0,
                    yaw=0.0,
                )
                print("sequence complete; robot stopped", flush=True)
                return 0

            elapsed = time.monotonic() - loop_started
            time.sleep(max(0.0, period - elapsed))

        print("max runtime reached; stopping", flush=True)
        return 1
    except KeyboardInterrupt:
        print("interrupted; stopping", flush=True)
        return 130
    finally:
        try:
            emit_cmd(0.0, 0.0)
        except Exception:
            pass
        try:
            write_status_file(
                args.status_file,
                phase="stopped",
                note="stopped",
                direction=None,
                distance_m=None,
                track_id=None,
                vx=0.0,
                yaw=0.0,
            )
        except Exception:
            pass
        if bridge is not None:
            bridge.shutdown()


if __name__ == "__main__":
    sys.exit(main())
