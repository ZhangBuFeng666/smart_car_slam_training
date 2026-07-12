# Jarvis Phase One Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Jetson-hosted Jarvis patrol agent that uses DeepSeek to plan safe missions, accepts simulated vision events, drives the existing car-control API through a whitelist, and exposes a dedicated Android mission experience.

**Architecture:** A new Python FastAPI service on port `8100` owns planning, validation, mission state, event handling, SQLite persistence, and reports. The Android app talks only to this service for Jarvis features while retaining direct access to the existing port `8000` emergency-stop endpoint. The first release uses a simulator behind the same event contract that a future YOLO adapter will use.

**Tech Stack:** Python 3.8+, FastAPI, Pydantic 2, HTTPX, SQLite, pytest, Android Kotlin, `HttpURLConnection`, `org.json`, JUnit 4.

---

## Scope And Parallelization

This plan implements design phase one only: simulated vision-event patrol. It intentionally excludes YOLO training, live video analysis, cloud deployment, direct wheel-speed control, and autonomous navigation target changes.

After Task 1 fixes the API contract, work may run in three lanes:

- Backend lane: Tasks 2-8.
- Android lane: Tasks 9-11.
- Verification lane: Task 12 after the backend and Android contracts are stable.

The repository currently contains untracked Android project files. Before implementation, review `git status --short` and create a baseline commit containing only the existing project files after user approval. This is required so Jarvis commits do not silently absorb unrelated pre-existing work.

## File Map

Backend files to create:

- `jarvis_agent/pyproject.toml`: Python package and test dependencies.
- `jarvis_agent/src/jarvis_agent/config.py`: environment-backed configuration.
- `jarvis_agent/src/jarvis_agent/models.py`: shared API and domain models.
- `jarvis_agent/src/jarvis_agent/validator.py`: action and task whitelist validation.
- `jarvis_agent/src/jarvis_agent/repository.py`: SQLite persistence.
- `jarvis_agent/src/jarvis_agent/deepseek.py`: DeepSeek transport and structured planner.
- `jarvis_agent/src/jarvis_agent/control_client.py`: adapter for the existing port `8000` service.
- `jarvis_agent/src/jarvis_agent/mission_engine.py`: state transitions and execution.
- `jarvis_agent/src/jarvis_agent/event_adapter.py`: event thresholding and deduplication.
- `jarvis_agent/src/jarvis_agent/reporting.py`: Markdown report generation.
- `jarvis_agent/src/jarvis_agent/api.py`: FastAPI routes and dependency wiring.
- `jarvis_agent/src/jarvis_agent/simulator.py`: repeatable simulated events.
- `jarvis_agent/deploy/jarvis-agent.service`: systemd unit.
- `jarvis_agent/.env.example`: non-secret deployment configuration.

Android files to create or modify:

- `app/src/main/java/com/example/icarcontroller/JarvisModels.kt`: API models and JSON parsing.
- `app/src/main/java/com/example/icarcontroller/JarvisApi.kt`: authenticated HTTP client.
- `app/src/main/java/com/example/icarcontroller/JarvisState.kt`: testable view-state reduction.
- `app/src/main/java/com/example/icarcontroller/JarvisCredentials.kt`: Android Keystore token storage.
- `app/src/main/java/com/example/icarcontroller/MainActivity.kt`: dedicated Jarvis page and polling lifecycle.
- `app/src/main/java/com/example/icarcontroller/FeatureCatalog.kt`: Jarvis copy and examples.
- `app/src/test/java/com/example/icarcontroller/JarvisModelsTest.java`: contract parsing tests.
- `app/src/test/java/com/example/icarcontroller/JarvisStateTest.java`: UI state tests.

## Task 1: Freeze The API Contract

**Files:**
- Create: `contracts/jarvis-api-v1.json`
- Create: `jarvis_agent/tests/fixtures/mission.json`
- Create: `jarvis_agent/tests/fixtures/vision_event.json`

- [ ] **Step 1: Add a machine-readable contract fixture**

Create `contracts/jarvis-api-v1.json` with the exact action and state enums:

```json
{
  "version": "v1",
  "actions": ["CHECK_STATUS", "START_TASK", "STOP_TASK", "STOP_ALL", "RECORD_EVENT", "ASK_USER", "GENERATE_REPORT"],
  "mission_states": ["DRAFT", "WAITING_CONFIRMATION", "RUNNING", "PAUSED", "FAILED", "CANCELLED", "COMPLETED"],
  "decision_types": ["continue", "ignore", "pause", "finish"],
  "allowed_tasks": ["base", "lidar", "avoidance", "follow", "warning", "camera", "hsv", "color_track"]
}
```

- [ ] **Step 2: Add representative JSON fixtures**

Create `mission.json` with a `CHECK_STATUS`, `START_TASK camera`, and `START_TASK avoidance` plan. Create `vision_event.json` with `mission_id`, `event_type`, `label`, `confidence`, `position`, `track_id`, `image_path`, `timestamp`, and `metadata` fields matching the design spec.

- [ ] **Step 3: Validate fixture syntax**

Run:

```powershell
Get-Content contracts/jarvis-api-v1.json | ConvertFrom-Json | Out-Null
Get-Content jarvis_agent/tests/fixtures/mission.json | ConvertFrom-Json | Out-Null
Get-Content jarvis_agent/tests/fixtures/vision_event.json | ConvertFrom-Json | Out-Null
```

Expected: all commands exit successfully with no output.

- [ ] **Step 4: Commit the contract**

```bash
git add contracts jarvis_agent/tests/fixtures
git commit -m "test: define Jarvis API contract"
```

## Task 2: Scaffold The Jetson Service And Configuration

**Files:**
- Create: `jarvis_agent/pyproject.toml`
- Create: `jarvis_agent/src/jarvis_agent/__init__.py`
- Create: `jarvis_agent/src/jarvis_agent/config.py`
- Test: `jarvis_agent/tests/test_config.py`

- [ ] **Step 1: Write the failing configuration test**

```python
from jarvis_agent.config import Settings


def test_settings_use_safe_defaults(monkeypatch):
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    settings = Settings()
    assert settings.jarvis_port == 8100
    assert settings.control_base_url == "http://127.0.0.1:8000"
    assert settings.deepseek_configured is False
```

- [ ] **Step 2: Add package metadata and install dependencies**

Use a `pyproject.toml` with `fastapi`, `uvicorn`, `httpx`, and `pydantic-settings`; add a `test` extra containing `pytest`, `pytest-asyncio`, and `respx`. Configure pytest with `pythonpath = ["src"]` and `testpaths = ["tests"]`.

Run:

```powershell
python -m venv jarvis_agent/.venv
jarvis_agent/.venv/Scripts/python -m pip install -e "jarvis_agent[test]"
jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_config.py -v
```

Expected before implementation: FAIL with `ModuleNotFoundError: jarvis_agent.config`.

- [ ] **Step 3: Implement environment-backed settings**

Implement `Settings(BaseSettings)` with these fields and defaults:

```python
jarvis_host: str = "0.0.0.0"
jarvis_port: int = 8100
jarvis_app_token: str = ""
control_base_url: str = "http://127.0.0.1:8000"
deepseek_api_key: str = ""
deepseek_base_url: str = "https://api.deepseek.com"
deepseek_model: str = "deepseek-chat"
database_path: str = "data/jarvis.db"
request_timeout_seconds: float = 20.0
event_cooldown_seconds: int = 15
```

Add a `deepseek_configured` property returning `bool(self.deepseek_api_key.strip())` and use `env_file=".env"` with `extra="ignore"`.

- [ ] **Step 4: Run the configuration test**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_config.py -v`.

Expected: `1 passed`.

- [ ] **Step 5: Commit the scaffold**

```bash
git add jarvis_agent/pyproject.toml jarvis_agent/src/jarvis_agent jarvis_agent/tests/test_config.py
git commit -m "feat: scaffold Jarvis service configuration"
```

## Task 3: Define Domain Models And Safety Validation

**Files:**
- Create: `jarvis_agent/src/jarvis_agent/models.py`
- Create: `jarvis_agent/src/jarvis_agent/validator.py`
- Test: `jarvis_agent/tests/test_validator.py`

- [ ] **Step 1: Write failing validator tests**

```python
import pytest
from jarvis_agent.models import Action, MissionPlan, MissionStep
from jarvis_agent.validator import PlanValidationError, validate_plan


def test_accepts_whitelisted_task():
    plan = MissionPlan(summary="巡检", steps=[MissionStep(action=Action.START_TASK, arguments={"task": "camera"})])
    assert validate_plan(plan).steps[0].arguments["task"] == "camera"


def test_rejects_move_or_unknown_task():
    plan = MissionPlan(summary="危险动作", steps=[MissionStep(action=Action.START_TASK, arguments={"task": "move/front"})])
    with pytest.raises(PlanValidationError):
        validate_plan(plan)
```

- [ ] **Step 2: Run tests and confirm failure**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_validator.py -v`.

Expected: FAIL because the model and validator modules do not exist.

- [ ] **Step 3: Implement typed models**

Define string enums `Action`, `MissionState`, and `DecisionType`. Define Pydantic models `MissionStep`, `MissionPlan`, `ChatRequest`, `ChatResponse`, `MissionCreateRequest`, `DecisionRequest`, `VisionEvent`, `TimelineEntry`, `MissionView`, and `ReportView`. Require `confidence` to be between `0.0` and `1.0` and require timezone-aware ISO timestamps.

- [ ] **Step 4: Implement whitelist validation**

Use immutable sets matching `contracts/jarvis-api-v1.json`. Reject unknown task names, arguments on argument-free actions, URLs, strings containing shell separators, and plans with zero or more than 12 steps. Force `requires_confirmation=True` whenever a plan contains `START_TASK`, `STOP_TASK`, or `STOP_ALL`.

- [ ] **Step 5: Run tests**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_validator.py -v`.

Expected: all validator tests pass.

- [ ] **Step 6: Commit domain safety**

```bash
git add jarvis_agent/src/jarvis_agent/models.py jarvis_agent/src/jarvis_agent/validator.py jarvis_agent/tests/test_validator.py
git commit -m "feat: validate Jarvis mission plans"
```

## Task 4: Add SQLite Mission Persistence

**Files:**
- Create: `jarvis_agent/src/jarvis_agent/repository.py`
- Test: `jarvis_agent/tests/test_repository.py`

- [ ] **Step 1: Write failing persistence tests**

Test that `Repository.initialize()` creates `missions`, `timeline`, `vision_events`, `decisions`, and `reports`; that a mission round-trips with its plan; and that two timeline entries return in insertion order.

```python
def test_mission_round_trip(tmp_path, sample_plan):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan)
    loaded = repo.get_mission(mission.id)
    assert loaded.plan == sample_plan
    assert loaded.state is MissionState.WAITING_CONFIRMATION
```

- [ ] **Step 2: Run and confirm failure**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_repository.py -v`.

Expected: FAIL because `Repository` is missing.

- [ ] **Step 3: Implement the repository**

Use Python `sqlite3`, JSON text columns for plans and metadata, UTC ISO timestamps, foreign keys, and transactions. Expose focused methods: `initialize`, `create_mission`, `get_mission`, `set_mission_state`, `append_timeline`, `list_timeline`, `save_event`, `find_recent_event`, `save_decision`, `save_report`, and `get_report`.

- [ ] **Step 4: Run persistence tests**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_repository.py -v`.

Expected: all repository tests pass.

- [ ] **Step 5: Commit persistence**

```bash
git add jarvis_agent/src/jarvis_agent/repository.py jarvis_agent/tests/test_repository.py
git commit -m "feat: persist Jarvis missions and events"
```

## Task 5: Integrate DeepSeek Through A Structured Planner

**Files:**
- Create: `jarvis_agent/src/jarvis_agent/deepseek.py`
- Test: `jarvis_agent/tests/test_deepseek.py`

- [ ] **Step 1: Write failing transport and planner tests**

Use `respx` to verify a POST to `/chat/completions` with Bearer authentication, `model`, `messages`, `temperature: 0.1`, and `response_format: {"type": "json_object"}`. Add tests for one retry after timeout, JSON inside Markdown fences, invalid actions, and missing API key.

```python
@pytest.mark.asyncio
async def test_planner_returns_validated_plan(settings, respx_mock):
    respx_mock.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(200, json={"choices": [{"message": {"content": VALID_PLAN_JSON}}]})
    )
    plan = await DeepSeekPlanner(settings).plan("检查停车场", {"control": "online"})
    assert plan.steps[0].action is Action.CHECK_STATUS
```

- [ ] **Step 2: Run and confirm failure**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_deepseek.py -v`.

Expected: FAIL because `DeepSeekPlanner` is missing.

- [ ] **Step 3: Implement the planner**

Create an async HTTPX client. Build a system prompt that lists the exact action enum, allowed task names, safety rules, and required JSON keys. Strip optional Markdown fences, parse JSON, validate with `MissionPlan.model_validate`, then call `validate_plan`. Define typed exceptions `ModelUnavailableError`, `ModelTimeoutError`, and `ModelResponseError`.

- [ ] **Step 4: Run planner tests**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_deepseek.py -v`.

Expected: all DeepSeek tests pass without calling the real API.

- [ ] **Step 5: Commit the planner**

```bash
git add jarvis_agent/src/jarvis_agent/deepseek.py jarvis_agent/tests/test_deepseek.py
git commit -m "feat: add structured DeepSeek planning"
```

## Task 6: Add The Existing Control-Service Adapter And Mission Engine

**Files:**
- Create: `jarvis_agent/src/jarvis_agent/control_client.py`
- Create: `jarvis_agent/src/jarvis_agent/mission_engine.py`
- Test: `jarvis_agent/tests/test_mission_engine.py`

- [ ] **Step 1: Write failing mission tests**

Create fake repository and control-client fixtures. Verify that confirmation performs `CHECK_STATUS` before `START_TASK`, duplicate confirmation does not replay actions, an HTTP failure moves the mission to `PAUSED`, cancellation calls `/stop/all`, and `GENERATE_REPORT` is deferred until a `finish` decision.

```python
@pytest.mark.asyncio
async def test_confirmation_executes_each_control_step_once(engine, mission, fake_control):
    result = await engine.confirm(mission.id)
    assert fake_control.calls == [("status", None), ("start", "camera")]
    assert result.state is MissionState.RUNNING
    await engine.confirm(mission.id)
    assert fake_control.calls == [("status", None), ("start", "camera")]
```

- [ ] **Step 2: Run and confirm failure**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_mission_engine.py -v`.

Expected: FAIL because the client and engine do not exist.

- [ ] **Step 3: Implement `ControlClient`**

Expose async `health`, `status`, `start_task`, `stop_task`, `stop_all`, and `emergency_stop`. Use URL encoding for task names, a short timeout, `raise_for_status`, and a `ControlServiceError` that never exposes raw response bodies to the App.

- [ ] **Step 4: Implement mission execution**

Make `confirm` atomically transition `WAITING_CONFIRMATION` to `RUNNING` before executing. Record every start, success, pause, failure, cancellation, and decision in the timeline. Do not implement `/move`. Treat `RECORD_EVENT` and `ASK_USER` as engine-internal steps. Defer `GENERATE_REPORT` until a `finish` decision.

- [ ] **Step 5: Run mission tests**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_mission_engine.py -v`.

Expected: all mission-engine tests pass.

- [ ] **Step 6: Commit execution logic**

```bash
git add jarvis_agent/src/jarvis_agent/control_client.py jarvis_agent/src/jarvis_agent/mission_engine.py jarvis_agent/tests/test_mission_engine.py
git commit -m "feat: execute validated Jarvis missions"
```

## Task 7: Add Vision Events, Explanations, Reports, And Simulator

**Files:**
- Create: `jarvis_agent/src/jarvis_agent/event_adapter.py`
- Create: `jarvis_agent/src/jarvis_agent/reporting.py`
- Create: `jarvis_agent/src/jarvis_agent/simulator.py`
- Test: `jarvis_agent/tests/test_events_and_reports.py`

- [ ] **Step 1: Write failing event tests**

Verify confidence below `0.60` is ignored, the same `mission_id + track_id + label` inside 15 seconds is deduplicated, obstacle and standing-water events create a pending user decision, and a different track ID is accepted.

- [ ] **Step 2: Write a failing report test**

```python
def test_report_contains_timeline_events_and_decisions(report_service, mission_id):
    markdown = report_service.build_fallback_report(mission_id)
    assert "# 贾维斯巡检报告" in markdown
    assert "纸箱" in markdown
    assert "用户决定：继续" in markdown
```

- [ ] **Step 3: Run and confirm failure**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_events_and_reports.py -v`.

Expected: FAIL because the event and report services do not exist.

- [ ] **Step 4: Implement event processing**

Use deterministic severity defaults: `standing_water=HIGH`, `obstacle=MEDIUM`, `illegal_parking=MEDIUM`, and unknown types `LOW`. Store every accepted event. Ask DeepSeek for a concise explanation only after local thresholding and deduplication; use a deterministic Chinese fallback when the model is unavailable.

- [ ] **Step 5: Implement report generation**

Build a complete Markdown fallback from persisted data first. Optionally ask DeepSeek to improve the summary and risk ranking, but always retain factual event values and evidence paths from SQLite. Save the final report before transitioning the mission to `COMPLETED`.

- [ ] **Step 6: Implement the simulator**

Provide a CLI accepting `--base-url`, `--token`, `--mission-id`, and `--scenario`. Define a `parking-lot` scenario that posts a paper-box obstacle followed by standing water using fixed track IDs and valid timezone-aware timestamps.

- [ ] **Step 7: Run tests and commit**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_events_and_reports.py -v`.

Expected: all tests pass.

```bash
git add jarvis_agent/src/jarvis_agent/event_adapter.py jarvis_agent/src/jarvis_agent/reporting.py jarvis_agent/src/jarvis_agent/simulator.py jarvis_agent/tests/test_events_and_reports.py
git commit -m "feat: process patrol events and reports"
```

## Task 8: Expose The FastAPI Surface And Jetson Deployment

**Files:**
- Create: `jarvis_agent/src/jarvis_agent/api.py`
- Create: `jarvis_agent/tests/test_api.py`
- Create: `jarvis_agent/.env.example`
- Create: `jarvis_agent/deploy/jarvis-agent.service`
- Modify: `.gitignore`
- Modify: `README.md`

- [ ] **Step 1: Write failing API tests**

Use FastAPI `TestClient` with injected fake planner and control service. Cover `/health`, missing or wrong Bearer token, `/api/v1/chat`, mission creation, confirmation, polling, vision events, decisions, cancellation, and reports.

```python
def test_protected_route_requires_token(client):
    response = client.post("/api/v1/chat", json={"message": "检查停车场"})
    assert response.status_code == 401


def test_health_remains_public(client):
    response = client.get("/health")
    assert response.status_code == 200
```

- [ ] **Step 2: Run and confirm failure**

Run `jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests/test_api.py -v`.

Expected: FAIL because `create_app` is missing.

- [ ] **Step 3: Implement dependency wiring and routes**

Expose `create_app(settings, planner=None, control=None, repository=None)`. Initialize SQLite during app lifespan. Return stable JSON error bodies shaped as `{"error": {"code": "MODEL_TIMEOUT", "message": "..."}}`. Ensure all protected routes compare the Bearer token with `secrets.compare_digest`.

- [ ] **Step 4: Add deployable configuration**

The `.env.example` must list non-secret defaults and blank secret values. The systemd unit must run `uvicorn jarvis_agent.api:create_app --factory --host 0.0.0.0 --port 8100`, restart on failure, use `EnvironmentFile=/opt/jarvis-agent/.env`, and set `WorkingDirectory=/opt/jarvis-agent`.

Append these exact generated-file rules to `.gitignore`:

```gitignore
# Jarvis local runtime
jarvis_agent/.env
jarvis_agent/.venv/
jarvis_agent/data/
jarvis_agent/**/*.db
jarvis_agent/**/*.db-shm
jarvis_agent/**/*.db-wal
```

- [ ] **Step 5: Document Jetson installation**

Add exact commands to README for Python venv creation, editable install, `.env` setup, foreground run, systemd installation, health check, and simulator invocation. Explicitly state that real secrets must never be committed.

- [ ] **Step 6: Run the full backend suite**

Run:

```powershell
jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests -v
```

Expected: all backend tests pass.

- [ ] **Step 7: Commit the API and deployment**

```bash
git add .gitignore jarvis_agent/src/jarvis_agent/api.py jarvis_agent/tests/test_api.py jarvis_agent/.env.example jarvis_agent/deploy README.md
git commit -m "feat: expose and deploy Jarvis API"
```

## Task 9: Add Android Contract Models And HTTP Client

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/JarvisModels.kt`
- Create: `app/src/main/java/com/example/icarcontroller/JarvisApi.kt`
- Test: `app/src/test/java/com/example/icarcontroller/JarvisModelsTest.java`

- [ ] **Step 1: Write failing JSON parsing tests**

Load representative inline JSON matching the shared fixtures. Assert mission ID, state, plan summary, steps, timeline messages, pending decisions, and report Markdown. Add a malformed-response test that expects `JarvisProtocolException`.

```java
@Test
public void parsesMissionView() {
    JarvisMission mission = JarvisJson.parseMission(MISSION_JSON);
    assertEquals("mission-001", mission.getId());
    assertEquals(JarvisMissionState.WAITING_CONFIRMATION, mission.getState());
    assertEquals("巡检停车场", mission.getPlan().getSummary());
}
```

- [ ] **Step 2: Run and confirm failure**

Run `.\build_app.ps1`.

Expected: unit-test compilation fails because Jarvis model classes do not exist.

- [ ] **Step 3: Implement immutable Kotlin models and parser**

Use data classes and enums matching the contract exactly. Parse with `org.json`; reject missing IDs, unknown mission states, unknown actions, and invalid confidence values. Keep JSON parsing out of `MainActivity`.

- [ ] **Step 4: Implement `JarvisApi`**

Build URLs from the current car host and agent port `8100`. Add `Authorization: Bearer <token>` and `Content-Type: application/json`. Implement `chat`, `createMission`, `confirmMission`, `cancelMission`, `submitDecision`, `getMission`, `getTimeline`, `postVisionEvent`, and `getReport`. Use explicit connect/read timeouts and close streams in `finally` blocks.

- [ ] **Step 5: Run Android unit tests**

Run `.\build_app.ps1`.

Expected: all existing and new unit tests pass and the debug APK builds.

- [ ] **Step 6: Commit the Android client**

```bash
git add app/src/main/java/com/example/icarcontroller/JarvisModels.kt app/src/main/java/com/example/icarcontroller/JarvisApi.kt app/src/test/java/com/example/icarcontroller/JarvisModelsTest.java
git commit -m "feat: add Android Jarvis API client"
```

## Task 10: Add Testable Android Mission State And Secure Token Storage

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/JarvisState.kt`
- Create: `app/src/main/java/com/example/icarcontroller/JarvisCredentials.kt`
- Test: `app/src/test/java/com/example/icarcontroller/JarvisStateTest.java`

- [ ] **Step 1: Write failing state tests**

Test initial idle state, chat loading, plan-ready state, confirmation loading, running mission, pending decision, report-ready state, and recoverable network error. Verify an error never removes the emergency-stop availability flag.

```java
@Test
public void networkErrorKeepsEmergencyStopAvailable() {
    JarvisViewState state = JarvisReducer.reduce(JarvisViewState.initial(), new JarvisEvent.NetworkFailed("超时"));
    assertTrue(state.isEmergencyStopAvailable());
    assertEquals("超时", state.getErrorMessage());
}
```

- [ ] **Step 2: Run and confirm failure**

Run `.\build_app.ps1`.

Expected: unit-test compilation fails because state classes do not exist.

- [ ] **Step 3: Implement pure state reduction**

Keep `JarvisViewState`, `JarvisEvent`, and `JarvisReducer.reduce` free of Android framework types so JVM tests can cover state transitions. Store current mission ID, plan, timeline, pending decision, report, loading flag, error text, and emergency-stop availability.

- [ ] **Step 4: Implement Keystore-backed credentials**

Create an AES/GCM key named `jarvis_app_token_key` in `AndroidKeyStore`. Encrypt the token with a fresh 12-byte IV and store Base64 IV plus ciphertext in private SharedPreferences. Expose only `saveToken`, `loadToken`, and `clearToken`; never log the token.

- [ ] **Step 5: Run tests and build**

Run `.\build_app.ps1`.

Expected: all JVM tests pass and APK assembly succeeds. Keystore behavior is verified later on a device because the local JVM test runtime does not provide AndroidKeyStore.

- [ ] **Step 6: Commit Android state and credentials**

```bash
git add app/src/main/java/com/example/icarcontroller/JarvisState.kt app/src/main/java/com/example/icarcontroller/JarvisCredentials.kt app/src/test/java/com/example/icarcontroller/JarvisStateTest.java
git commit -m "feat: manage Jarvis Android state securely"
```

## Task 11: Replace The Placeholder Sheet With A Dedicated Jarvis Page

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/FeatureCatalog.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/drawable/jarvis_timeline_bg.xml`
- Create: `app/src/main/res/drawable/jarvis_event_bg.xml`

- [ ] **Step 1: Add a failing navigation assertion**

Extend `FeatureCatalogTest.java` to assert that the AI action title is `贾维斯`, status is `可用`, and its page key is `ai`. Run `.\build_app.ps1` and confirm the assertion fails against the current `AI 智能助手`/`规划中` copy.

- [ ] **Step 2: Update navigation behavior**

Change every existing AI shortcut from `showAiAssistantSheet()` to `renderPage("ai")`. Make the `"ai"` branch call a new `renderJarvis()` method. Remove the obsolete dialog builder after the dedicated page works.

- [ ] **Step 3: Build the dedicated page**

Using the existing view-builder style, render these full-width sections without nesting cards:

- compact status header with model, car, and mission state;
- conversation transcript;
- plan steps with confirm, modify, and cancel commands;
- stable-height timeline list;
- evidence event cards with continue, ignore, and pause actions;
- report preview;
- bottom task input and send command;
- always-visible emergency-stop command that calls the existing port `8000` API directly.

Use headings sized for compact panels, 8px-or-less card radii, stable button heights, and existing project colors. Do not add a sixth bottom-navigation item.

- [ ] **Step 4: Wire API calls and polling**

Use a dedicated executor for Jarvis calls. Start one-second polling only while `selectedPage == "ai"` and a mission is active. Cancel callbacks on page change and `onDestroy`. Render loading and error states from `JarvisReducer`; never block the UI thread.

- [ ] **Step 5: Add connection settings**

Reuse the current host, default agent port to `8100`, and add a token field whose value is loaded and saved through `JarvisCredentials`. Display only a masked token. Do not place a real token in resources or defaults.

- [ ] **Step 6: Build and run unit tests**

Run `.\build_app.ps1`.

Expected: all unit tests pass and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 7: Commit the Jarvis page**

```bash
git add app/src/main/java/com/example/icarcontroller/MainActivity.kt app/src/main/java/com/example/icarcontroller/FeatureCatalog.kt app/src/main/res app/src/test/java/com/example/icarcontroller/FeatureCatalogTest.java
git commit -m "feat: add dedicated Jarvis mission page"
```

## Task 12: Verify The End-To-End Simulated Patrol

**Files:**
- Create: `jarvis_agent/tests/test_end_to_end.py`
- Create: `docs/jarvis-demo-runbook.md`
- Modify: `README.md`

- [ ] **Step 1: Write the end-to-end automated test**

Use a fake planner and fake control service with the real FastAPI routes, repository, mission engine, event adapter, and reporter. Execute this exact sequence: chat, create mission, confirm, post paper-box event, submit `continue`, post standing-water event, submit `finish`, fetch report. Assert the report contains both events and the mission is `COMPLETED`.

- [ ] **Step 2: Run the complete backend suite**

Run:

```powershell
jarvis_agent/.venv/Scripts/python -m pytest jarvis_agent/tests -v
```

Expected: all tests pass, including `test_end_to_end.py`.

- [ ] **Step 3: Run the complete Android build**

Run `.\build_app.ps1`.

Expected: all Android unit tests pass and the debug APK is produced.

- [ ] **Step 4: Perform the local HTTP smoke test**

Start the service with test configuration, call `/health`, create a mission through the API, capture the returned mission ID, and run:

```powershell
$headers = @{ Authorization = 'Bearer test-token' }
$body = @{ plan = (Get-Content -Raw jarvis_agent/tests/fixtures/mission.json | ConvertFrom-Json) } | ConvertTo-Json -Depth 8
$mission = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8100/api/v1/missions -Headers $headers -ContentType 'application/json' -Body $body
$missionId = $mission.id
jarvis_agent/.venv/Scripts/python -m jarvis_agent.simulator --base-url http://127.0.0.1:8100 --token test-token --mission-id $missionId --scenario parking-lot
```

Expected: `$missionId` is non-empty and two accepted vision events appear in the mission timeline.

- [ ] **Step 5: Write the demo runbook**

Document Jetson service startup, App connection, DeepSeek configuration, mission prompt, plan confirmation, simulator invocation, event decisions, report display, emergency stop, and the three failure demonstrations: missing DeepSeek key, stopped control service, and rejected illegal action.

- [ ] **Step 6: Verify the working tree and commit**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and only intentional files remain modified.

```bash
git add jarvis_agent/tests/test_end_to_end.py docs/jarvis-demo-runbook.md README.md
git commit -m "test: verify Jarvis patrol workflow"
```

## Final Acceptance Checklist

- [ ] DeepSeek receives only structured mission context and meaningful events, not raw continuous video.
- [ ] Every executable action passes the server-side whitelist.
- [ ] No LLM path can invoke `/move` or wheel-speed control.
- [ ] Mission confirmation is idempotent and cannot replay commands.
- [ ] App loss does not erase the Jetson mission; control-service loss pauses it.
- [ ] Emergency stop remains direct to port `8000` and works when Jarvis is unavailable.
- [ ] Simulated events use the same contract reserved for future YOLO output.
- [ ] The final report is generated from persisted facts and survives service restart.
- [ ] Backend tests and Android tests pass from a clean invocation.
- [ ] No API keys or shared tokens appear in Git, APK resources, logs, or screenshots.
