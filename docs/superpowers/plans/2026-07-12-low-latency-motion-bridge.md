# Low-Latency Motion Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace per-command Docker/ROS process startup with a persistent ROS 2 motion publisher while preserving the Android HTTP contract.

**Architecture:** A persistent Python `rclpy` process runs inside the existing container and exchanges newline-delimited JSON with the host server over one long-lived `docker exec -i` subprocess. The bridge owns the safety watchdog; the host server owns lifecycle, retry, HTTP routing, and health reporting.

**Tech Stack:** Python 3.8, ROS 2 Foxy `rclpy`, Docker CLI, Python `ThreadingHTTPServer`, Kotlin/Android `HttpURLConnection`, `unittest`, JUnit.

---

### Task 1: Motion protocol and watchdog

**Files:**
- Create: `jetson_server/motion_bridge.py`
- Create: `jetson_server/test_motion_bridge.py`

- [ ] Write failing tests for direction-to-twist conversion, command validation, and watchdog expiry.
- [ ] Run `python -m unittest jetson_server.test_motion_bridge -v` and verify failures are caused by missing bridge code.
- [ ] Implement pure protocol helpers and watchdog state without importing ROS at module import time.
- [ ] Re-run the focused tests and confirm they pass.

### Task 2: Persistent host bridge manager

**Files:**
- Modify: `jetson_server/server.py`
- Modify: `jetson_server/test_server_config.py`

- [ ] Write failing tests proving movement routes use a reusable bridge rather than `run_once`.
- [ ] Write failing tests for bridge restart after process failure and emergency-stop routing.
- [ ] Implement `MotionBridgeClient` with copy, startup, serialized request/response, one retry, and shutdown.
- [ ] Preserve all existing task, mapping, navigation, and HTTP paths.
- [ ] Run all Jetson server tests.

### Task 3: Android refresh interval

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/InteractionSpec.kt`
- Modify: `app/src/test/java/com/example/icarcontroller/InteractionSpecTest.java`

- [ ] Tighten the JUnit expectation to require an 80-150 millisecond repeat interval and run it red.
- [ ] Change the repeat interval to 120 milliseconds.
- [ ] Re-run the Android unit test green.

### Task 4: Documentation and verification

**Files:**
- Modify: `jetson_server/README.md`
- Modify: `README.md`

- [ ] Document bridge startup, health fields, watchdog behavior, deployment, and latency test command.
- [ ] Run Python unit tests, Android unit tests, `lintDebug`, and `assembleDebug`.
- [ ] Run a local fake-process latency regression test.

### Task 5: Device deployment

**Files:**
- Deploy: `jetson_server/server.py` to `~/icar_app_server/server.py`
- Deploy: `jetson_server/motion_bridge.py` to `~/icar_app_server/motion_bridge.py`

- [ ] Stop the old HTTP service and deploy both files without recreating container `8b98`.
- [ ] Start the server and confirm `/health` reports `motion_bridge_ready: true`.
- [ ] Measure 50 warm stop requests and calculate average and P95.
- [ ] Install the updated APK and verify short, slow movement plus release-to-stop.
- [ ] Verify bridge watchdog stop by interrupting command refresh in a safe area.
