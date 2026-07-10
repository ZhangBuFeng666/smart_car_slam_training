# Jetson HTTP Control Service

This service runs on the Jetson host and exposes HTTP endpoints for the Android APP.

## Start

```bash
cd ~/icar_app_server
python3 server.py --container 8b98 --host 0.0.0.0 --port 8000
```

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
