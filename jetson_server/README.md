# Jetson HTTP Control Service

This service runs on the Jetson host and exposes HTTP endpoints for the Android APP.

Motion commands use a persistent ROS 2 bridge. The server copies
`motion_bridge.py` into the selected container once, keeps one `rclpy`
publisher alive, and reuses it for every `/move/*` request. This avoids the
multi-second overhead of starting `docker exec` and `ros2 topic pub` for every
touch event.

## Start

```bash
cd ~/icar_app_server
python3 server.py --container 8b98 --host 0.0.0.0 --port 8000
```

Both files must be present in `~/icar_app_server`:

```text
server.py
motion_bridge.py
```

At startup the server publishes one zero-velocity command to warm the bridge.
The startup output should contain `Motion bridge ready`.

If SLAM and navigation packages are in another auto-drive container, replace `8b98` with that container id:

```bash
python3 server.py --container <autodrive_container_id> --host 0.0.0.0 --port 8000
```

## Manual Commands Mapped By APP

Mapping:

- `map_gmapping`: `ros2 launch yahboomcar_nav map_gmapping_launch.py`
- `map_display`: `ros2 launch yahboomcar_nav display_map_launch.py`
- `map_save`: `ros2 launch yahboomcar_nav save_map_launch.py`

Navigation:

- `nav_laser`: `ros2 launch yahboomcar_nav laser_bringup_launch.py`
- `nav_display`: `ros2 launch yahboomcar_nav display_nav_launch.py`
- `nav_dwa`: `ros2 launch yahboomcar_nav navigation_dwa_launch.py`
- `nav_teb`: `ros2 launch yahboomcar_nav navigation_teb_launch.py`

Pose and goal:

- `GET /navigation/initial_pose?x=0&y=0&yaw=0`
- `GET /navigation/goal?x=1&y=0&yaw=0`

The initial pose endpoint publishes `/initialpose`.
The goal endpoint publishes `/goal_pose`.

## Safety

Keep physical emergency stop access during navigation tests. The APP endpoint `/emergency_stop` stops tracked processes and publishes zero velocity on `/cmd_vel`.

The persistent bridge also has a 350 ms watchdog. A held Android control
refreshes its command every 120 ms. If the phone disconnects, the HTTP service
fails, or command refresh stops, the bridge publishes zero velocity after the
watchdog deadline. Bridge shutdown and standard-input closure also publish
zero velocity.

## Health And Latency

Check bridge readiness:

```bash
curl http://127.0.0.1:8000/health
```

The response includes `motion_bridge_ready`. Warm motion responses include
both `bridge_latency_ms` and end-to-end server `round_trip_ms`.

Example safe latency request:

```bash
curl 'http://127.0.0.1:8000/move/stop?speed=0&turn=0'
```

The practical target on the local Wi-Fi network is 20-120 ms after warm-up.
