#!/usr/bin/env python3
"""Persistent ROS 2 map, pose, goal and path subscriber for the HTTP service."""

import json
import math
import select
import signal
import sys
import threading
import time


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
    if follow_waypoints_ready:
        return "follow_waypoints"
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
    from nav2_msgs.action import FollowWaypoints, NavigateToPose
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
    waypoint_client = ActionClient(node, FollowWaypoints, "/follow_waypoints")
    navigate_client = ActionClient(node, NavigateToPose, "/navigate_to_pose")
    waypoint_goal_handle = None
    pending_waypoint_submission = None
    sequential_points = []
    sequential_index = -1
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

    def waypoint_feedback(feedback_message):
        store.set_waypoint_status(
            "active",
            current_index=feedback_message.feedback.current_waypoint,
            message="巡逻执行中",
            backend="follow_waypoints",
        )

    def waypoint_result_done(future):
        nonlocal waypoint_goal_handle
        try:
            wrapped = future.result()
            missed = list(getattr(wrapped.result, "missed_waypoints", []))
            if wrapped.status == GoalStatus.STATUS_SUCCEEDED and not missed:
                state = "completed"
                message = "全部目标点已完成"
            elif wrapped.status == GoalStatus.STATUS_CANCELED:
                state = "canceled"
                message = "多点导航已取消"
            else:
                state = "failed"
                message = "部分目标点未完成" if missed else "多点导航失败"
            store.set_waypoint_status(state, missed=missed, message=message)
        except Exception as error:
            store.set_waypoint_status("failed", message=str(error))
        waypoint_goal_handle = None

    def waypoint_goal_response(future):
        nonlocal waypoint_goal_handle
        try:
            goal_handle = future.result()
            if not goal_handle.accepted:
                store.set_waypoint_status("rejected", message="Nav2 拒绝多点任务")
                return
            waypoint_goal_handle = goal_handle
            store.set_waypoint_status("active", current_index=0, message="巡逻执行中")
            goal_handle.get_result_async().add_done_callback(waypoint_result_done)
        except Exception as error:
            store.set_waypoint_status("failed", message=str(error))

    def submit_waypoints(points):
        if store.waypoints["state"] in ("submitting", "active", "canceling"):
            raise ValueError("a waypoint mission is already active")
        backend = select_waypoint_backend(
            waypoint_client.wait_for_server(timeout_sec=0.3),
            navigate_client.wait_for_server(timeout_sec=1.5),
        )
        if backend == "navigate_to_pose":
            start_sequential_navigation(points)
            return
        if backend is None:
            raise RuntimeError(
                "Nav2 /follow_waypoints and /navigate_to_pose actions are unavailable"
            )
        goal = FollowWaypoints.Goal()
        now = node.get_clock().now().to_msg()
        for point in points:
            pose = PoseStamped()
            pose.header.frame_id = "map"
            pose.header.stamp = now
            pose.pose.position.x = float(point["x"])
            pose.pose.position.y = float(point["y"])
            yaw = float(point.get("yaw", 0.0))
            pose.pose.orientation.z = math.sin(yaw / 2.0)
            pose.pose.orientation.w = math.cos(yaw / 2.0)
            goal.poses.append(pose)
        store.set_waypoint_status(
            "submitting",
            total=len(points),
            current_index=0,
            missed=[],
            message="正在提交多点任务",
            backend="follow_waypoints",
        )
        waypoint_client.send_goal_async(
            goal,
            feedback_callback=waypoint_feedback,
        ).add_done_callback(waypoint_goal_response)

    def sequential_result_done(future):
        nonlocal waypoint_goal_handle, sequential_index, sequential_points
        try:
            wrapped = future.result()
            if wrapped.status == GoalStatus.STATUS_SUCCEEDED:
                next_index = sequential_index + 1
                waypoint_goal_handle = None
                if next_index < len(sequential_points):
                    send_sequential_goal(next_index)
                else:
                    total = len(sequential_points)
                    sequential_points = []
                    sequential_index = -1
                    store.set_waypoint_status(
                        "completed",
                        current_index=max(0, total - 1),
                        message="全部目标点已完成",
                        backend="navigate_to_pose",
                    )
            elif wrapped.status == GoalStatus.STATUS_CANCELED:
                sequential_points = []
                sequential_index = -1
                waypoint_goal_handle = None
                store.set_waypoint_status(
                    "canceled",
                    message="多点导航已取消",
                    backend="navigate_to_pose",
                )
            else:
                missed = list(range(max(0, sequential_index), len(sequential_points)))
                sequential_points = []
                sequential_index = -1
                waypoint_goal_handle = None
                store.set_waypoint_status(
                    "failed",
                    missed=missed,
                    message="当前目标点导航失败",
                    backend="navigate_to_pose",
                )
        except Exception as error:
            waypoint_goal_handle = None
            sequential_points = []
            sequential_index = -1
            store.set_waypoint_status(
                "failed", message=str(error), backend="navigate_to_pose"
            )

    def sequential_goal_response(future):
        nonlocal waypoint_goal_handle, sequential_points, sequential_index
        try:
            goal_handle = future.result()
            if not goal_handle.accepted:
                missed = list(range(max(0, sequential_index), len(sequential_points)))
                sequential_points = []
                sequential_index = -1
                store.set_waypoint_status(
                    "rejected",
                    missed=missed,
                    message="Nav2 拒绝当前目标点",
                    backend="navigate_to_pose",
                )
                return
            waypoint_goal_handle = goal_handle
            store.set_waypoint_status(
                "active",
                current_index=sequential_index,
                message="按顺序执行目标点",
                backend="navigate_to_pose",
            )
            goal_handle.get_result_async().add_done_callback(sequential_result_done)
        except Exception as error:
            waypoint_goal_handle = None
            sequential_points = []
            sequential_index = -1
            store.set_waypoint_status(
                "failed", message=str(error), backend="navigate_to_pose"
            )

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
            message=f"正在提交第 {index + 1} 个目标点",
            backend="navigate_to_pose",
        )
        navigate_client.send_goal_async(goal).add_done_callback(sequential_goal_response)

    def start_sequential_navigation(points):
        nonlocal sequential_points, sequential_index
        sequential_points = list(points)
        sequential_index = -1
        store.set_waypoint_status(
            "submitting",
            total=len(sequential_points),
            current_index=0,
            missed=[],
            message="Waypoint Follower 不可用，切换为顺序目标导航",
            backend="navigate_to_pose",
        )
        send_sequential_goal(0)

    def queue_waypoints(start, points):
        nonlocal pending_waypoint_submission
        if store.waypoints["state"] in (
            "preparing", "submitting", "active", "canceling"
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
        pending_waypoint_submission = (time.monotonic() + 0.6, list(points))

    def cancel_waypoints():
        nonlocal pending_waypoint_submission, sequential_points, sequential_index
        if pending_waypoint_submission is not None:
            pending_waypoint_submission = None
            store.set_waypoint_status(
                "canceled", current_index=-1, message="待执行多点任务已取消"
            )
            return
        if waypoint_goal_handle is None:
            store.set_waypoint_status("idle", current_index=-1, message="当前没有多点任务")
            return
        sequential_points = []
        sequential_index = -1
        store.set_waypoint_status("canceling", message="正在取消多点任务")
        waypoint_goal_handle.cancel_goal_async()

    def request_stop(_signum, _frame):
        stopping.set()

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    try:
        while not stopping.is_set():
            rclpy.spin_once(node, timeout_sec=0.0)
            now = time.monotonic()
            if pending_waypoint_submission is not None and now >= pending_waypoint_submission[0]:
                _, points = pending_waypoint_submission
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
                    break
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
        waypoint_client.destroy()
        navigate_client.destroy()
        del tf_listener
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
