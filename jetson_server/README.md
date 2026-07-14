# Jetson HTTP Control Service

This service runs on the Jetson host and exposes HTTP endpoints for the Android APP.

Motion and navigation commands use persistent ROS 2 bridges. The server copies
the bridge scripts into the selected container, keeps their `rclpy` nodes
alive, and reuses them for HTTP requests. This avoids the multi-second overhead
of starting `docker exec` and ROS command processes for every operation.

The server also copies `navigation_bridge.py` into the selected container and
keeps a ROS subscriber alive. It sends the occupancy map, robot pose, goal and
global path to the Android route page. The map payload is run-length encoded
and is only repeated when its generation changes.

## Start

```bash
cd ~/icar_app_server
python3 server.py --container 8b98 --host 0.0.0.0 --port 8000
```

All five files must be present in `~/icar_app_server`:

```text
server.py
motion_bridge.py
navigation_bridge.py
camera_stream.py
icar-control.service
```

At startup the server publishes one zero-velocity command to warm the bridge.
The startup output should contain `Motion bridge ready`.

The default `8b98` container uses `icar_nav`. On startup and container switch,
the service verifies all required launch files in the ROS install space. It
prefers a complete `icar_nav` package and falls back to `yahboomcar_nav`.

```bash
python3 server.py --container <autodrive_container_id> --host 0.0.0.0 --port 8000
```

## Manual Commands Mapped By APP

Mapping:

- `map_gmapping`: `ros2 launch icar_nav map_gmapping_launch.py`
- `map_display`: `ros2 launch icar_nav display_map_launch.py`
- `map_save`: `ros2 launch icar_nav save_map_launch.py`

Navigation:

- `nav_laser`: `ros2 launch icar_nav laser_bringup_launch.py`
- `nav_display`: `ros2 launch icar_nav display_nav_launch.py`
- `nav_dwa`: `ros2 launch icar_nav navigation_dwa_launch.py`
- `nav_teb`: `ros2 launch icar_nav navigation_teb_launch.py`
- `nav_astar_rpp`: `ros2 launch icar_nav navigation_rpp_launch.py`

Pose and goal:

- `GET /navigation/initial_pose?x=0&y=0&yaw=0`
- `GET /navigation/goal?x=1&y=0&yaw=0`
- `GET /navigation/state?map_generation=-1`
- `POST /navigation/waypoints` with JSON `{"start":{"x":0,"y":0,"yaw":0},"points":[{"x":1,"y":2,"yaw":0}]}`
- `GET /navigation/waypoints/cancel`

The waypoint endpoint requires an explicit route start. The server publishes
that start on `/initialpose` before submitting the ordered points to Nav2
`/follow_waypoints`. If the vendor Nav2 build has no `/follow_waypoints` action
server, the navigation bridge automatically executes the same ordered route as
consecutive `/navigate_to_pose` goals. Starting automatic mapping always stops the previous
Gmapping/RViz processes and resets the cached map snapshot, so a second run is
a new SLAM session rather than a continuation of the first one.

The initial pose endpoint publishes `/initialpose`.
The goal endpoint publishes `/goal_pose`.
The state endpoint reads `/map`, `/goal_pose`, `/plan` and `/global_plan`.
It also reports Nav2 `FollowWaypoints` progress so Android can show the active
point, completion and missed waypoint indexes.
Robot pose is resolved from `map` to `base_footprint`/`base_link` TF, with
`/amcl_pose` as an additional source. Android sends its last map generation so
unchanged map cells are not retransmitted on each poll.

The selected container must provide the normal ROS 2 Python packages used by
the Yahboom navigation stack: `rclpy`, `nav_msgs`, `geometry_msgs`, and
`tf2_ros`. Verify them without rebuilding the image:

```bash
docker exec -it 8b98 bash
source /opt/ros/foxy/setup.bash
python3 -c "import rclpy, tf2_ros; from nav_msgs.msg import OccupancyGrid, Path; from geometry_msgs.msg import PoseStamped"
ros2 topic list | grep -E '/map|/amcl_pose|/plan|/global_plan|/goal_pose'
```

The navigation bridge also supports waypoint execution, cancellation, and
state polling through `/navigation/waypoints/*` and `/navigation/state`.

## Safety

Keep physical emergency stop access during navigation tests. The APP endpoint `/emergency_stop` stops tracked processes and publishes zero velocity on `/cmd_vel`.

The persistent bridge keeps the latest manual velocity until Android sends an
explicit stop. Android sends that stop when the control is released, the user
leaves the drive page, the stop button is pressed, or emergency stop is used.
Bridge shutdown and standard-input closure also publish zero velocity.

## Health And Latency

Check bridge readiness:

```bash
curl http://127.0.0.1:8000/health
```

The response includes `motion_bridge_ready`, `navigation_bridge_ready`,
`navigation_package`, `navigation_tasks_ready`, and
`missing_navigation_launches`.
Warm motion responses include
both `bridge_latency_ms` and end-to-end server `round_trip_ms`.

Example safe latency request:

```bash
curl 'http://127.0.0.1:8000/move/stop?speed=0&turn=0'
```

The practical target on the local Wi-Fi network is 20-120 ms after warm-up.

## systemd

Install the provided service so only one HTTP server owns port 8000:

```bash
sudo cp icar-control.service /etc/systemd/system/icar-control.service
sudo systemctl daemon-reload
sudo systemctl enable --now icar-control.service
systemctl status icar-control.service --no-pager
```

The unit starts `/home/jetson/icar_app_server/server.py` with container `8b98`.
Stop any foreground `server.py` before starting the unit.
