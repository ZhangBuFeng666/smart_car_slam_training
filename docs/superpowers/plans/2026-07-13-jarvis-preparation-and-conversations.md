# Jarvis Preparation And Conversations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make control tasks prepare dependencies before asking for Start and add durable, multi-conversation Jarvis chat history with a sliding history drawer.

**Architecture:** The backend separates preparation from hazardous execution with explicit task states and endpoints. Android stores complete typed chat items in native SQLite, keeps conversation lifetime outside the page View, and renders a header-driven animated history drawer.

**Tech Stack:** Python 3, FastAPI, pytest, Kotlin, Android native Views, SQLiteOpenHelper, JUnit 4.

---

### Task 1: Backend Preparation State Machine

**Files:**
- Modify: `jarvis_agent/src/jarvis_agent/models.py`
- Modify: `jarvis_agent/src/jarvis_agent/api.py`
- Test: `jarvis_agent/tests/test_api.py`

- [ ] **Step 1: Write failing task-state tests**

Add tests asserting that chat returns `PREPARING`, preparation calls health/dependency operations but not the requested feature or movement, successful preparation becomes `READY`, and `/start` rejects tasks outside `READY`.

```python
response = client.post("/api/v1/chat", headers=auth(), json={"message": "启动自动避障"})
task_id = response.json()["control_task"]["id"]
status = wait_for_state(client, task_id, "READY")
assert status["current_message"] == "准备就绪，等待启动。"
assert not any(call[:2] == ("start", "avoidance") for call in control.calls)
```

- [ ] **Step 2: Run focused tests and verify RED**

Run: bundled Python `-m pytest tests/test_api.py -k preparation -q`

Expected: failure because `PREPARING`, `READY`, and automatic preparation do not exist.

- [ ] **Step 3: Implement preparation and start separation**

Add `PREPARING`, `READY`, and `PREPARATION_FAILED`. Create a preparation thread when a control task is created. Preparation checks health and starts only dependencies. Keep requested movement or feature in the execution spec until `/start` is called from `READY`.

```python
class ControlTaskState(str, Enum):
    PREPARING = "PREPARING"
    READY = "READY"
    PREPARATION_FAILED = "PREPARATION_FAILED"
```

- [ ] **Step 4: Add retry and cancellation tests, then implementation**

Test `/prepare` from `PREPARATION_FAILED`, stopping during preparation, and exact failed-step reporting. Add `POST /api/v1/control-tasks/{id}/prepare`; Stop sets cancellation and prevents later transition to `READY`.

- [ ] **Step 5: Run all Jarvis tests**

Run: bundled Python `-m pytest -q`

Expected: all tests pass.

- [ ] **Step 6: Commit backend state-machine changes**

Commit message: `feat: prepare Jarvis control tasks before start`

### Task 2: Android Models And API

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisModels.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisApi.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisChatPage.kt`
- Test: `app/src/test/java/com/example/icarcontroller/JarvisApiContractTest.kt`

- [ ] **Step 1: Write failing parsing and button-state tests**

Assert JSON parsing for the three new states and that only `READY` enables Start while `PREPARATION_FAILED` exposes Retry.

```kotlin
assertEquals(JarvisControlTaskState.READY, parseControlTask(json).state)
assertEquals(JarvisTaskAction.START, JarvisTaskActions.primaryFor(JarvisControlTaskState.READY))
```

- [ ] **Step 2: Run focused Android tests and verify RED**

Run: `build_app.ps1` with the new test selected through Gradle.

Expected: compilation failure because the new states/actions are absent.

- [ ] **Step 3: Implement new models and API method**

Add the states, `prepareControlTask(id)`, and a pure `JarvisTaskActions` mapper. Render preparation progress immediately; Start is disabled until `READY`; Retry calls `/prepare`.

- [ ] **Step 4: Run Android unit tests**

Run: `build_app.ps1`

Expected: `BUILD SUCCESSFUL`.

### Task 3: SQLite Conversation Store

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/JarvisConversationStore.kt`
- Create: `app/src/main/java/com/example/icarcontroller/JarvisChatItemCodec.kt`
- Test: `app/src/test/java/com/example/icarcontroller/JarvisChatItemCodecTest.kt`
- Test: `app/src/androidTest/java/com/example/icarcontroller/JarvisConversationStoreTest.kt`

- [ ] **Step 1: Write failing codec tests**

Round-trip user, assistant, system, error, plan, control-task, progress, and report items through `{type, payload_json}`. Verify unknown payloads become a recoverable system item.

- [ ] **Step 2: Run codec tests and verify RED**

Expected: compilation failure because `JarvisChatItemCodec` does not exist.

- [ ] **Step 3: Implement the typed JSON codec**

Use `org.json` and existing model parsers. Never serialize the Jarvis token. Preserve stable item IDs and timestamps in a stored item wrapper.

- [ ] **Step 4: Write failing store tests**

Test create/list/load, first-message title, explicit rename precedence, archive/restore, item replacement, and cascading delete with an in-memory or instrumentation database.

- [ ] **Step 5: Implement SQLiteOpenHelper store**

Create `conversations` and `conversation_items`, schema version 1, indexes, foreign keys, transaction-backed append/replace/delete, and ordering by `updated_at DESC`.

- [ ] **Step 6: Run store and codec tests**

Run JVM tests and connected instrumentation tests when a device is connected.

- [ ] **Step 7: Commit persistence changes**

Commit message: `feat: persist Jarvis conversation history`

### Task 4: Conversation Controller And Safety Decisions

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/JarvisConversationController.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisState.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`
- Test: `app/src/test/java/com/example/icarcontroller/JarvisConversationPolicyTest.kt`

- [ ] **Step 1: Write failing safety-policy tests**

```kotlin
assertEquals(SwitchRequirement.ASK_USER, switchRequirement(JarvisControlTaskState.RUNNING))
assertEquals(SwitchRequirement.SWITCH_NOW, switchRequirement(JarvisControlTaskState.PREPARING))
```

Cover `STARTING`, `RUNNING`, all terminal states, and no task.

- [ ] **Step 2: Verify RED and implement pure policy**

Add `JarvisConversationPolicy` with no Android dependencies, then make tests pass.

- [ ] **Step 3: Implement controller lifetime**

Create one controller in `MainActivity`; pass it into each recreated `JarvisChatPage`. Persist every reducer change, restore the last active conversation, and keep polling updates tied to conversation IDs rather than View instances.

- [ ] **Step 4: Verify page recreation restoration**

Add a controller test that creates messages, constructs a second controller/page state from the same store, and compares items.

- [ ] **Step 5: Run Android tests and commit**

Commit message: `feat: restore Jarvis sessions across pages`

### Task 5: History Drawer And Conversation Actions

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/JarvisHistoryDrawer.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisChatPage.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`

- [ ] **Step 1: Add header icons and drawer shell**

Use familiar Android icon drawables: history/menu at left, `贾维斯` centered, new conversation at right. Keep 48dp touch targets and content descriptions.

- [ ] **Step 2: Implement slide and scrim behavior**

Animate drawer translation from `-drawerWidth` to `0` and scrim alpha from `0` to the theme-appropriate value. Scrim tap and Back close the drawer.

- [ ] **Step 3: Implement history list and actions**

Show non-archived conversations by recent update. Long press opens Rename, Archive, Delete. Add `已归档` at the bottom with Restore and Delete actions.

- [ ] **Step 4: Implement active-task switch modal**

Show only `停止任务并切换`, `保持运行并切换`, and an upper-right close icon. Stop must complete successfully before switching; keep-running preserves background polling.

- [ ] **Step 5: Verify light/dark layouts and compact screens**

Use ADB screenshots on the connected device for chat, open drawer, archived list, and switch modal. Confirm no overlap with the composer or bottom navigation.

- [ ] **Step 6: Commit UI changes**

Commit message: `feat: add Jarvis conversation drawer`

### Task 6: Deployment And End-To-End Verification

**Files:**
- Deploy backend files under `/home/jetson/jarvis-agent/jarvis_agent/src/jarvis_agent/`
- Install: `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Run full local verification**

Run Jarvis pytest, motion-bridge unittest, Android unit tests, and debug APK assembly. Require zero failures.

- [ ] **Step 2: Deploy backend and restart Jarvis**

Upload changed backend files, restart `jarvis-agent.service`, and confirm both service health endpoints. Control health must report `icar-runtime` and `motion_bridge_ready: true`.

- [ ] **Step 3: Verify preparation boundary through API**

Send `启动摄像头`; verify preparation reaches `READY` without starting the camera action, then call Start and verify `COMPLETED`. Repeat with a bounded movement draft without starting physical motion unless the area is confirmed safe.

- [ ] **Step 4: Install and restart APK**

Use ADB install-replace, force-stop, and launch. Confirm `MainActivity` is resumed.

- [ ] **Step 5: Verify persistence and multi-conversation UI**

Create two conversations, switch pages, restart the app, rename/archive/restore one conversation, and verify both histories. Exercise the running-task switch modal using a safely stoppable task.

- [ ] **Step 6: Final commit and repository status**

Commit any verification-driven fixes. Report local commit hashes and whether GitHub push succeeded; never imply remote sync without a successful push.
