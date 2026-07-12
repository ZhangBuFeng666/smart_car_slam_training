# Low-Latency Motion Bridge Design

## Goal

Reduce Android-to-robot motion latency from the measured 4.2-4.6 seconds to a practical LAN target of 20-120 milliseconds without changing the public `/move/*` HTTP API.

## Root Cause

The current server handles every motion request by running `docker exec`, starting a login shell, sourcing ROS, creating a temporary `ros2 topic pub` process, publishing once, and exiting. Process startup dominates the request time.

## Architecture

- `motion_bridge.py` runs persistently inside container `8b98`.
- It creates one `rclpy` node and one `/cmd_vel` publisher.
- It receives newline-delimited JSON commands over its persistent standard input and writes one JSON acknowledgement per command to standard output.
- The host HTTP server owns one long-running `docker exec -i` subprocess and serializes command/acknowledgement exchange with a lock.
- Existing `/move/front`, `/move/back`, `/move/left`, `/move/right`, `/move/turn_left`, `/move/turn_right`, and `/move/stop` routes remain unchanged.

## Safety

- A motion command expires unless refreshed before the watchdog deadline.
- Watchdog expiry publishes zero velocity.
- EOF, SIGINT, SIGTERM, bridge restart, emergency stop, and server shutdown publish zero velocity.
- Stop commands are never routed through the slow one-shot ROS CLI path.
- Stale acknowledgements cannot overwrite newer commands because bridge requests are serialized and carry sequence identifiers.

## Recovery

- The host copies the bridge script into the running container when starting the bridge.
- If the bridge exits or returns malformed data, the server restarts it once and retries the command once.
- `/health` reports bridge readiness.
- Non-motion ROS lifecycle commands continue using the existing Docker execution path.

## Android Behavior

- The first `ACTION_DOWN` command is sent immediately.
- Held controls refresh movement every 120 milliseconds.
- `ACTION_UP` and `ACTION_CANCEL` continue to dispatch stop through both urgent and move-barrier lanes.

## Acceptance Criteria

- Unit tests cover direction mapping, malformed commands, watchdog expiry, bridge process reuse, restart-on-failure, and stop routing.
- Android unit tests require a repeat interval of 80-150 milliseconds.
- Fifty LAN stop requests have P95 latency below 120 milliseconds after warm-up.
- Physical press-to-motion and release-to-stop are each below 150 milliseconds in a safe test area.
- Network loss causes automatic zero velocity within 400 milliseconds.
