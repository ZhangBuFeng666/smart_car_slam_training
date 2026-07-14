#!/usr/bin/env python3
"""Persistent ROS 2 map, pose, goal and path subscriber for the HTTP service."""

import json
import math
import select
import signal
import sys
import threading
import time


WAYPOINT_YAW_TOLERANCE_RAD = math.radians(10.0)
WAYPOINT_YAW_REQUIRED_SAMPLES = 3
WAYPOINT_YAW_VERIFICATION_TIMEOUT_SEC = 2.0
WAYPOINT_YAW_MAX_CORRECTIONS = 1


def normalize_angle(angle):
    return math.atan2(math.sin(angle), math.cos(angle))


def waypoint_yaw_error(target_yaw, actual_yaw):
    return normalize_angle(float(target_yaw) - float(actual_yaw))


class YawStabilityTracker:
    def __init__(
        self,
        target_yaw,
        tolerance=WAYPOINT_YAW_TOLERANCE_RAD,
        required_samples=WAYPOINT_YAW_REQUIRED_SAMPLES,
    ):
        self.target_yaw = float(target_yaw)
        self.tolerance = float(tolerance)
        self.required_samples = int(required_samples)
        self.samples = 0
        self.consecutive_matches = 0
        self.last_actual_yaw = None
        self.last_error = None

    def observe(self, actual_yaw):
        self.samples += 1
        self.last_actual_yaw = float(actual_yaw)
        self.last_error = waypoint_yaw_error(self.target_yaw, actual_yaw)
        if abs(self.last_error) <= self.tolerance:
            self.consecutive_matches += 1
        else:
            self.consecutive_matches = 0
        return self.consecutive_matches >= self.required_samples


def quaternion_to_yaw(x, y, z, w):
    siny_cosp = 2.0 * (w * z + x * y)
    cosy_cosp = 1.0 - 2.0 * (y * y + z * z)
    return math.atan2(siny_cosp, cosy_cosp)


def rle_encode(values):
    encoded = []
    current = None
    count = 0
    for raw in values:
        value = int(raw)
        if count and value != current:
            encoded.extend((current, count))
            count = 0
        current = value
        count += 1
    if count:
        encoded.extend((current, count))
    return encoded


def downsample_points(points, limit=240):
    points = list(points)
    if len(points) <= limit:
        return points
    stride = max(1, math.ceil(len(points) / limit))
    sampled = points[::stride]
    if sampled[-1] != points[-1]:
        sampled.append(points[-1])
    return sampled


def select_waypoint_backend(follow_waypoints_ready, navigate_to_pose_ready):
    # Multi-point routes must pause between goals so the bridge can verify the
    # robot's real heading before advancing. FollowWaypoints can move to the
    # next pose before that verification is possible.
    if navigate_to_pose_ready:
        return "navigate_to_pose"
    return None


class NavigationSnapshotStore:
    def __init__(self, clock=time.time):
        self.clock = clock
        self.map_generation = 0
        self.map_payload = None
        self.pose = None
        self.goal = None
        self.path = []
        self.waypoints = {
            "state": "idle",
            "total": 0,
            "current_index": -1,
            "missed": [],
            "message": "",
            "backend": "",
            "phase": "idle",
            "target_yaw": None,
            "actual_yaw": None,
            "yaw_error_deg": None,
            "retry_count": 0,
        }
        self.updated_at = None

    def set_map(self, width, height, resolution, origin_x, origin_y, origin_yaw, data):
        self.map_generation += 1
        self.updated_at = self.clock()
        self.map_payload = {
            "generation": self.map_generation,
            "width": int(width),
            "height": int(height),
            "resolution": float(resolution),
            "origin": {
                "x": float(origin_x),
                "y": float(origin_y),
                "yaw": float(origin_yaw),
            },
            "data_rle": rle_encode(data),
            "updated_at": self.updated_at,
        }

    def reset_map(self):
        self.map_generation += 1
        self.map_payload = None
        self.pose = None
        self.goal = None
        self.path = []
        self.updated_at = self.clock()

    def set_pose(self, x, y, yaw):
        self.pose = {"x": float(x), "y": float(y), "yaw": float(yaw)}
        self.updated_at = self.clock()

    def set_goal(self, x, y, yaw):
        self.goal = {"x": float(x), "y": float(y), "yaw": float(yaw)}
        self.updated_at = self.clock()

    def set_path(self, points):
        self.path = [
            {"x": float(point[0]), "y": float(point[1])}
            for point in downsample_points(points)
        ]
        self.updated_at = self.clock()

    def set_waypoint_status(
        self,
        state,
        total=None,
        current_index=None,
        missed=None,
        message=None,
        backend=None,
        phase=None,
        target_yaw=None,
        actual_yaw=None,
        yaw_error_deg=None,
        retry_count=None,
    ):
        self.waypoints["state"] = str(state)
        if total is not None:
            self.waypoints["total"] = int(total)
        if current_index is not None:
            self.waypoints["current_index"] = int(current_index)
        if missed is not None:
            self.waypoints["missed"] = [int(item) for item in missed]
        if message is not None:
            self.waypoints["message"] = str(message)
        if backend is not None:
            self.waypoints["backend"] = str(backend)
        if phase is not None:
            self.waypoints["phase"] = str(phase)
        if target_yaw is not None:
            self.waypoints["target_yaw"] = float(target_yaw)
        if actual_yaw is not None:
            self.waypoints["actual_yaw"] = float(actual_yaw)
        if yaw_error_deg is not None:
            self.waypoints["yaw_error_deg"] = float(yaw_error_deg)
        if retry_count is not None:
            self.waypoints["retry_count"] = int(retry_count)
        self.updated_at = self.clock()

    def snapshot(self, known_map_generation=-1):
        include_map = self.map_payload is not None and int(known_map_generation) != self.map_generation
        return {
            "map_generation": self.map_generation,
            "map_reset": self.map_payload is None and int(known_map_generation) != self.map_generation,
            "map": self.map_payload if include_map else None,
            "pose": self.pose,
            "goal": self.goal,
            "path": self.path,
            "waypoints": dict(self.waypoints),
            "updated_at": self.updated_at,
        }

    def handle_line(self, raw):
        payload = json.loads(raw)
        sequence = int(payload["sequence"])
        return {
            "ok": True,
            "sequence": sequence,
            "snapshot": self.snapshot(payload.get("map_generation", -1)),
        }


def main():
    import rclpy
    from action_msgs.msg import GoalStatus
    from nav2_msgs.action import NavigateToPose
    from rclpy.action import ActionClient
    from rclpy.time import Time
    from geometry_msgs.msg import PoseStamped, PoseWithCovarianceStamped
    from nav_msgs.msg import OccupancyGrid, Path
    from rclpy.qos import DurabilityPolicy, QoSProfile, ReliabilityPolicy
    from tf2_ros import Buffer, TransformListener

    rclpy.init(args=None)
    node = rclpy.create_node("icar_http_navigation_bridge")
    store = NavigationSnapshotStore()
    stopping = threading.Event()
    tf_buffer = Buffer()
    tf_listener = TransformListener(tf_buffer, node)
    last_tf_lookup = 0.0
    navigate_client = ActionClient(node, NavigateToPose, "/navigate_to_pose")
    waypoint_goal_handle = None
    pending_waypoint_submission = None
    sequential_points = []
    sequential_index = -1
    sequential_correction_count = 0
    orientation_verification = None
    initial_pose_publisher = node.create_publisher(
        PoseWithCovarianceStamped, "/initialpose", 10
    )

    map_qos = QoSProfile(depth=1)
    map_qos.durability = DurabilityPolicy.TRANSIENT_LOCAL
    map_qos.reliability = ReliabilityPolicy.RELIABLE

    def map_callback(message):
        origin = message.info.origin
        store.set_map(
            message.info.width,
            message.info.height,
            message.info.resolution,
            origin.position.x,
            origin.position.y,
            quaternion_to_yaw(
                origin.orientation.x,
                origin.orientation.y,
                origin.orientation.z,
                origin.orientation.w,
            ),
            message.data,
        )

    def pose_callback(message):
        pose = message.pose.pose
        store.set_pose(
            pose.position.x,
            pose.position.y,
            quaternion_to_yaw(
                pose.orientation.x,
                pose.orientation.y,
                pose.orientation.z,
                pose.orientation.w,
            ),
        )

    def goal_callback(message):
        pose = message.pose
        store.set_goal(
            pose.position.x,
            pose.position.y,
            quaternion_to_yaw(
                pose.orientation.x,
                pose.orientation.y,
                pose.orientation.z,
                pose.orientation.w,
            ),
        )

    def path_callback(message):
        store.set_path((pose.pose.position.x, pose.pose.position.y) for pose in message.poses)

    node.create_subscription(OccupancyGrid, "/map", map_callback, map_qos)
    node.create_subscription(PoseWithCovarianceStamped, "/amcl_pose", pose_callback, 10)
    node.create_subscription(PoseStamped, "/goal_pose", goal_callback, 10)
    node.create_subscription(Path, "/plan", path_callback, 10)
    node.create_subscription(Path, "/global_plan", path_callback, 10)

    def submit_waypoints(points):
        if store.waypoints["state"] in (
            "submitting", "active", "aligning", "verifying", "retrying", "canceling"
        ):
            raise ValueError("a waypoint mission is already active")
        if select_waypoint_backend(False, navigate_client.wait_for_server(timeout_sec=1.5)) is None:
            raise RuntimeError("Nav2 /navigate_to_pose action is unavailable")
        start_sequential_navigation(points)

    def localization_is_ready():
        for base_frame in ("base_footprint", "base_link"):
            try:
                tf_buffer.lookup_transform("map", base_frame, Time())
                return True
            except Exception:
                continue
        return False

    def fail_sequential(state, message):
        nonlocal waypoint_goal_handle, sequential_index, sequential_points
        nonlocal sequential_correction_count, orientation_verification
        missed = list(range(max(0, sequential_index), len(sequential_points)))
        waypoint_goal_handle = None
        sequential_points = []
        sequential_index = -1
        sequential_correction_count = 0
        orientation_verification = None
        store.set_waypoint_status(
            state,
            missed=missed,
            message=message,
            backend="navigate_to_pose",
            phase="failed",
        )

    def advance_after_orientation_verified():
        nonlocal waypoint_goal_handle, sequential_index, sequential_points
        nonlocal sequential_correction_count, orientation_verification
        next_index = sequential_index + 1
        waypoint_goal_handle = None
        orientation_verification = None
        sequential_correction_count = 0
        if next_index < len(sequential_points):
            send_sequential_goal(next_index)
            return
        total = len(sequential_points)
        sequential_points = []
        sequential_index = -1
        store.set_waypoint_status(
            "completed",
            current_index=max(0, total - 1),
            message="全部目标点的位置与方向均已完成",
            backend="navigate_to_pose",
            phase="completed",
            retry_count=0,
        )

    def retry_orientation_or_fail(reason):
        nonlocal sequential_correction_count, orientation_verification
        if sequential_correction_count < WAYPOINT_YAW_MAX_CORRECTIONS:
            sequential_correction_count += 1
            orientation_verification = None
            store.set_waypoint_status(
                "retrying",
                current_index=sequential_index,
                message=f"第 {sequential_index + 1} 个目标点方向未对准，正在校正",
                backend="navigate_to_pose",
                phase="retrying",
                retry_count=sequential_correction_count,
            )
            send_sequential_goal(sequential_index)
            return
        fail_sequential(
            "failed",
            f"第 {sequential_index + 1} 个目标点方向校正失败：{reason}",
        )

    def begin_orientation_verification():
        nonlocal orientation_verification
        point = sequential_points[sequential_index]
        target_yaw = float(point.get("yaw", 0.0))
        orientation_verification = {
            "tracker": YawStabilityTracker(target_yaw),
            "deadline": time.monotonic() + WAYPOINT_YAW_VERIFICATION_TIMEOUT_SEC,
        }
        store.set_waypoint_status(
            "verifying",
            current_index=sequential_index,
            message=f"第 {sequential_index + 1} 个目标点位置已到达，正在确认方向",
            backend="navigate_to_pose",
            phase="verifying",
            target_yaw=target_yaw,
            retry_count=sequential_correction_count,
        )

    def observe_orientation(actual_yaw):
        verification = orientation_verification
        if verification is None:
            return
        tracker = verification["tracker"]
        verified = tracker.observe(actual_yaw)
        error_degrees = math.degrees(tracker.last_error)
        store.set_waypoint_status(
            "verifying",
            current_index=sequential_index,
            message=(
                f"第 {sequential_index + 1} 个目标点方向已对准，正在稳定确认"
                if abs(tracker.last_error) <= tracker.tolerance
                else f"第 {sequential_index + 1} 个目标点方向还差 {abs(error_degrees):.1f}°"
            ),
            backend="navigate_to_pose",
            phase="verifying",
            target_yaw=tracker.target_yaw,
            actual_yaw=actual_yaw,
            yaw_error_deg=error_degrees,
            retry_count=sequential_correction_count,
        )
        if verified:
            advance_after_orientation_verified()
        elif (
            tracker.samples >= WAYPOINT_YAW_REQUIRED_SAMPLES
            and tracker.consecutive_matches == 0
        ):
            retry_orientation_or_fail(f"仍相差 {abs(error_degrees):.1f}°")

    def check_orientation_verification_timeout(now):
        verification = orientation_verification
        if verification is None or now < verification["deadline"]:
            return
        tracker = verification["tracker"]
        if tracker.last_error is None:
            retry_orientation_or_fail("无法读取小车实时方向")
        else:
            retry_orientation_or_fail(f"仍相差 {abs(math.degrees(tracker.last_error)):.1f}°")

    def sequential_result_done(future):
        nonlocal waypoint_goal_handle
        try:
            wrapped = future.result()
            if wrapped.status == GoalStatus.STATUS_SUCCEEDED:
                waypoint_goal_handle = None
                begin_orientation_verification()
            elif wrapped.status == GoalStatus.STATUS_CANCELED:
                fail_sequential("canceled", "多点导航已取消")
            else:
                fail_sequential("failed", "当前目标点导航失败")
        except Exception as error:
            fail_sequential("failed", str(error))

    def sequential_goal_response(future):
        nonlocal waypoint_goal_handle
        try:
            goal_handle = future.result()
            if not goal_handle.accepted:
                fail_sequential("rejected", "Nav2 拒绝当前目标点")
                return
            waypoint_goal_handle = goal_handle
            correcting = sequential_correction_count > 0
            store.set_waypoint_status(
                "aligning" if correcting else "active",
                current_index=sequential_index,
                message=(
                    f"正在校正第 {sequential_index + 1} 个目标点方向"
                    if correcting
                    else f"正在前往第 {sequential_index + 1} 个目标点"
                ),
                backend="navigate_to_pose",
                phase="aligning" if correcting else "navigating",
                retry_count=sequential_correction_count,
            )
            goal_handle.get_result_async().add_done_callback(sequential_result_done)
        except Exception as error:
            fail_sequential("failed", str(error))

    def send_sequential_goal(index):
        nonlocal sequential_index
        sequential_index = index
        point = sequential_points[index]
        goal = NavigateToPose.Goal()
        goal.pose.header.frame_id = "map"
        goal.pose.header.stamp = node.get_clock().now().to_msg()
        goal.pose.pose.position.x = float(point["x"])
        goal.pose.pose.position.y = float(point["y"])
        yaw = float(point.get("yaw", 0.0))
        goal.pose.pose.orientation.z = math.sin(yaw / 2.0)
        goal.pose.pose.orientation.w = math.cos(yaw / 2.0)
        store.set_waypoint_status(
            "submitting",
            current_index=index,
            message=(
                f"正在提交第 {index + 1} 个目标点方向校正"
                if sequential_correction_count > 0
                else f"正在提交第 {index + 1} 个目标点"
            ),
            backend="navigate_to_pose",
            phase="submitting",
            target_yaw=yaw,
            retry_count=sequential_correction_count,
        )
        navigate_client.send_goal_async(goal).add_done_callback(sequential_goal_response)

    def start_sequential_navigation(points):
        nonlocal sequential_points, sequential_index, sequential_correction_count
        nonlocal orientation_verification
        sequential_points = list(points)
        sequential_index = -1
        sequential_correction_count = 0
        orientation_verification = None
        store.set_waypoint_status(
            "submitting",
            total=len(sequential_points),
            current_index=0,
            missed=[],
            message="使用逐点导航并校验每个目标点方向",
            backend="navigate_to_pose",
            phase="submitting",
            retry_count=0,
        )
        send_sequential_goal(0)

    def queue_waypoints(start, points):
        nonlocal pending_waypoint_submission
        if store.waypoints["state"] in (
            "preparing", "submitting", "active", "aligning", "verifying",
            "retrying", "canceling"
        ):
            raise ValueError("a waypoint mission is already active")
        pose = PoseWithCovarianceStamped()
        pose.header.frame_id = "map"
        pose.header.stamp = node.get_clock().now().to_msg()
        pose.pose.pose.position.x = float(start["x"])
        pose.pose.pose.position.y = float(start["y"])
        yaw = float(start.get("yaw", 0.0))
        pose.pose.pose.orientation.z = math.sin(yaw / 2.0)
        pose.pose.pose.orientation.w = math.cos(yaw / 2.0)
        pose.pose.covariance[0] = 0.25
        pose.pose.covariance[7] = 0.25
        pose.pose.covariance[35] = 0.0685
        initial_pose_publisher.publish(pose)
        store.set_waypoint_status(
            "preparing",
            total=len(points),
            current_index=0,
            missed=[],
            message="起点与目标点已提交，正在等待导航算法",
        )
        now = time.monotonic()
        # AMCL needs the initial pose before Nav2 can finish activating its
        # planner and navigator action servers.  On the Jetson this normally
        # takes a couple of seconds, so do not fail the mission after a single
        # early discovery check.
        pending_waypoint_submission = (now + 0.6, now + 15.0, list(points))

    def cancel_waypoints():
        nonlocal pending_waypoint_submission, sequential_points, sequential_index
        nonlocal sequential_correction_count, orientation_verification
        if pending_waypoint_submission is not None:
            pending_waypoint_submission = None
            store.set_waypoint_status(
                "canceled", current_index=-1, message="待执行多点任务已取消"
            )
            return
        if waypoint_goal_handle is None and not sequential_points and orientation_verification is None:
            store.set_waypoint_status("idle", current_index=-1, message="当前没有多点任务")
            return
        sequential_points = []
        sequential_index = -1
        sequential_correction_count = 0
        orientation_verification = None
        store.set_waypoint_status("canceling", message="正在取消多点任务")
        if waypoint_goal_handle is not None:
            waypoint_goal_handle.cancel_goal_async()
        else:
            store.set_waypoint_status(
                "canceled", current_index=-1, message="多点导航已取消", phase="canceled"
            )

    def request_stop(_signum, _frame):
        stopping.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    try:
        while not stopping.is_set():
            rclpy.spin_once(node, timeout_sec=0.0)
            now = time.monotonic()
            if pending_waypoint_submission is not None and now >= pending_waypoint_submission[0]:
                _, deadline, points = pending_waypoint_submission
                actions_ready = navigate_client.wait_for_server(timeout_sec=0.05)
                localization_ready = localization_is_ready()
                if (not actions_ready or not localization_ready) and now < deadline:
                    pending_waypoint_submission = (now + 0.5, deadline, points)
                    store.set_waypoint_status(
                        "preparing",
                        message=(
                            "正在等待 Nav2 定位完成"
                            if not localization_ready
                            else "正在等待 Nav2 导航服务就绪"
                        ),
                    )
                elif not localization_ready:
                    pending_waypoint_submission = None
                    store.set_waypoint_status(
                        "failed",
                        message="Nav2 定位未就绪，请确认起点位于地图空闲区域",
                    )
                elif not actions_ready:
                    pending_waypoint_submission = None
                    store.set_waypoint_status(
                        "failed",
                        message="Nav2 导航服务启动超时",
                    )
                else:
                    pending_waypoint_submission = None
                    try:
                        submit_waypoints(points)
                    except Exception as error:
                        store.set_waypoint_status("failed", message=str(error))
            if now - last_tf_lookup >= 0.2:
                last_tf_lookup = now
                for base_frame in ("base_footprint", "base_link"):
                    try:
                        transform = tf_buffer.lookup_transform("map", base_frame, Time())
                    except Exception:
                        continue
                    translation = transform.transform.translation
                    rotation = transform.transform.rotation
                    store.set_pose(
                        translation.x,
                        translation.y,
                        quaternion_to_yaw(rotation.x, rotation.y, rotation.z, rotation.w),
                    )
                    observe_orientation(store.pose["yaw"])
                    break
            check_orientation_verification_timeout(now)
            readable, _, _ = select.select([sys.stdin], [], [], 0.03)
            if not readable:
                continue
            raw = sys.stdin.readline()
            if not raw:
                break
            try:
                payload = json.loads(raw)
                sequence = int(payload["sequence"])
                operation = payload.get("operation", "snapshot")
                if operation == "snapshot":
                    response = store.handle_line(raw)
                elif operation == "follow_waypoints":
                    points = payload.get("points", [])
                    if not points:
                        raise ValueError("at least one waypoint is required")
                    start = payload.get("start")
                    if not isinstance(start, dict):
                        raise ValueError("route start is required")
                    queue_waypoints(start, points)
                    response = {
                        "ok": True,
                        "sequence": sequence,
                        "waypoints": dict(store.waypoints),
                    }
                elif operation == "cancel_waypoints":
                    cancel_waypoints()
                    response = {
                        "ok": True,
                        "sequence": sequence,
                        "waypoints": dict(store.waypoints),
                    }
                elif operation == "reset_map":
                    store.reset_map()
                    response = {
                        "ok": True,
                        "sequence": sequence,
                        "map_generation": store.map_generation,
                    }
                else:
                    raise ValueError(f"unknown navigation operation: {operation}")
            except Exception as error:
                sequence = None
                try:
                    sequence = json.loads(raw).get("sequence")
                except Exception:
                    pass
                response = {"ok": False, "sequence": sequence, "error": str(error)}
            sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
            sys.stdout.flush()
    finally:
        navigate_client.destroy()
        del tf_listener
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
