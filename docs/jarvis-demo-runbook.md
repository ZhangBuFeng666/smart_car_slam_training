# Jarvis Simulated Patrol Demo Runbook

This runbook demonstrates the phase-one Jarvis patrol workflow with simulated
vision events. It does not require a trained YOLO model. The simulator posts the
same event contract that the future detector will produce.

## Prerequisites

- Existing car control service is running on Jetson port `8000`.
- Jarvis agent is installed and configured on Jetson port `8100`.
- Android phone and Jetson are on the same network.
- `.env` contains a non-empty `JARVIS_APP_TOKEN`.
- `DEEPSEEK_API_KEY` is set for live planning, or the API can be tested with a
  fake planner in automated tests.

## Start Services

Start the existing control service:

```bash
cd jetson_server
python3 server.py --container 8b98 --host 0.0.0.0 --port 8000
```

Start Jarvis in the foreground:

```bash
cd /opt/jarvis-agent
.venv/bin/python -m uvicorn jarvis_agent.api:create_app --factory --host 0.0.0.0 --port 8100
```

Check health:

```bash
curl http://127.0.0.1:8100/health
```

Expected response:

```json
{"status":"ok"}
```

## App Connection

1. Open the Android app.
2. Set the car host to the Jetson IP address.
3. Open the `贾维斯` page.
4. Enter the same token as `JARVIS_APP_TOKEN`.
5. Enter a mission prompt such as:

```text
巡检 B2 东区车位，发现障碍物和积水后提醒我
```

6. Tap `生成计划`.
7. Review the generated plan.
8. Tap `确认执行`.

Emergency stop remains direct to the existing port `8000` endpoint and does not
depend on Jarvis being healthy.

## Simulator Flow

Create a mission through the app or API, then run:

```bash
.venv/bin/python -m jarvis_agent.simulator \
  --base-url http://127.0.0.1:8100 \
  --token "$JARVIS_APP_TOKEN" \
  --mission-id "$MISSION_ID" \
  --scenario parking-lot
```

The `parking-lot` scenario posts:

- paper-box obstacle
- standing-water event

Use the app to submit decisions:

- `continue` for the paper box
- `finish` for standing water

Then open the report. The report should include both events, evidence paths,
and the user decisions.

## Failure Demonstrations

Missing DeepSeek key:

- Clear `DEEPSEEK_API_KEY`.
- Restart Jarvis.
- Tap `生成计划`.
- Expected: protected API stays up, but planning returns a model unavailable
  error.

Stopped control service:

- Stop the port `8000` control service.
- Confirm a mission.
- Expected: Jarvis moves the mission to `PAUSED` and records a failure in the
  timeline.

Rejected illegal action:

- Send a plan containing `MOVE`, `/move`, a URL, shell separators, or an unknown
  task.
- Expected: Jarvis rejects the plan before any control-service request is sent.

## Verification Commands

Backend:

```powershell
build\jarvis-venv\Scripts\python -m pytest jarvis_agent\tests -q
```

Android:

```powershell
.\build_app.ps1
```

Expected:

- Backend tests pass.
- Android unit tests pass.
- `app/build/outputs/apk/debug/app-debug.apk` exists.
