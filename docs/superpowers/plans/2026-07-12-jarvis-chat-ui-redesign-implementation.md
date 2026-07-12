# Jarvis Chat UI Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Android Jarvis page as a dark chat assistant where user messages, Jarvis replies, mission plans, progress, reports, and errors appear as chat timeline items.

**Architecture:** Add a typed chat item model to `JarvisState.kt`, test the state transitions, then replace the current `parkingAiWorkspace()` mixed control panel with a chat timeline renderer in `MainActivity.kt`. Keep existing `JarvisApi` and backend contracts unchanged.

**Tech Stack:** Android Kotlin, programmatic Android Views, existing `JarvisApi`, existing `JarvisModels`, Gradle 8.7, JUnit tests.

---

## File Structure

- Modify: `app/src/main/java/com/example/icarcontroller/JarvisState.kt`
  - Add `JarvisChatItem`.
  - Add chat-oriented state events and reducer helpers.
  - Keep existing reducer behavior compatible with current tests.
- Modify: `app/src/test/java/com/example/icarcontroller/JarvisStateTest.java`
  - Add tests for user message, assistant plan card, mission progress card, report card, and error card state.
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisUiSpec.kt`
  - Replace garbled/old action labels with chat-first labels.
  - Add quick prompt labels.
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`
  - Replace `parkingAiWorkspace()` with dark chat UI.
  - Add render helpers for user bubbles, assistant bubbles, plan cards, progress cards, report cards, error cards, system rows, and composer.
  - Wire send, confirm, refresh, cancel, and report actions to chat timeline items.
- No expected change: `app/src/main/java/com/example/icarcontroller/JarvisApi.kt`
- No expected change: `app/src/main/java/com/example/icarcontroller/JarvisModels.kt`

---

### Task 1: Add Chat Item State Model

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisState.kt`
- Test: `app/src/test/java/com/example/icarcontroller/JarvisStateTest.java`

- [ ] **Step 1: Add failing tests for chat timeline state**

Add tests that assert:

```java
@Test
public void chatMessageAppendsUserAndLoadingEvent() {
    JarvisViewState state = JarvisViewState.initial();

    JarvisViewState next = JarvisReducer.reduce(
        state,
        new JarvisEvent.UserMessageSubmitted("巡检 B2 东侧停车区")
    );

    assertEquals(2, next.getChatItems().size());
    assertTrue(next.getChatItems().get(0) instanceof JarvisChatItem.UserMessage);
    assertTrue(next.getChatItems().get(1) instanceof JarvisChatItem.SystemEvent);
    assertTrue(next.getLoading());
}

@Test
public void planReadyAppendsAssistantAndPlanCard() {
    JarvisViewState state = JarvisReducer.reduce(
        JarvisViewState.initial(),
        new JarvisEvent.UserMessageSubmitted("打开摄像头")
    );
    JarvisMissionPlan plan = samplePlan();

    JarvisViewState next = JarvisReducer.reduce(state, new JarvisEvent.PlanReady(plan));

    assertFalse(next.getLoading());
    assertEquals(plan, next.getPlan());
    assertTrue(next.getChatItems().get(next.getChatItems().size() - 2) instanceof JarvisChatItem.AssistantMessage);
    assertTrue(next.getChatItems().get(next.getChatItems().size() - 1) instanceof JarvisChatItem.PlanCard);
}

@Test
public void networkFailureAppendsErrorCard() {
    JarvisViewState next = JarvisReducer.reduce(
        JarvisViewState.initial(),
        new JarvisEvent.NetworkFailed("无法连接 Jarvis")
    );

    assertEquals(JarvisScreenMode.ERROR, next.getMode());
    assertTrue(next.getChatItems().get(next.getChatItems().size() - 1) instanceof JarvisChatItem.ErrorMessage);
}
```

- [ ] **Step 2: Run tests and confirm failure**

Run:

```powershell
E:\android-tools\gradle-8.7\bin\gradle.bat testDebugUnitTest --no-daemon --console=plain --tests com.example.icarcontroller.JarvisStateTest
```

Expected: fails because `JarvisChatItem`, `chatItems`, and `UserMessageSubmitted` do not exist.

- [ ] **Step 3: Implement `JarvisChatItem` and chat fields**

In `JarvisState.kt`, add:

```kotlin
sealed class JarvisChatItem {
    data class UserMessage(val text: String, val timestamp: String) : JarvisChatItem()
    data class AssistantMessage(val text: String, val timestamp: String) : JarvisChatItem()
    data class PlanCard(val plan: JarvisMissionPlan, val timestamp: String) : JarvisChatItem()
    data class ProgressCard(
        val mission: JarvisMission,
        val timeline: List<JarvisTimelineEntry>,
        val timestamp: String
    ) : JarvisChatItem()
    data class ReportCard(val report: JarvisReport, val timestamp: String) : JarvisChatItem()
    data class ErrorMessage(val title: String, val detail: String, val timestamp: String) : JarvisChatItem()
    data class SystemEvent(val text: String, val timestamp: String) : JarvisChatItem()
}
```

Add `chatItems: List<JarvisChatItem>` to `JarvisViewState.initial()`.

- [ ] **Step 4: Implement reducer events**

Add:

```kotlin
data class UserMessageSubmitted(val message: String) : JarvisEvent()
data class SystemMessageAdded(val message: String) : JarvisEvent()
```

Update existing `PlanReady`, `MissionUpdated`, `ReportReady`, and `NetworkFailed` branches to append the corresponding chat item.

Use a deterministic timestamp helper for now:

```kotlin
private fun timestamp(): String = ""
```

The UI can show real time separately; reducer tests should not depend on current time.

- [ ] **Step 5: Run tests and confirm pass**

Run the same Gradle test command.

Expected: `JarvisStateTest` passes.

- [ ] **Step 6: Commit Task 1**

```powershell
git add app/src/main/java/com/example/icarcontroller/JarvisState.kt app/src/test/java/com/example/icarcontroller/JarvisStateTest.java
git commit -m "feat: add Jarvis chat state model"
```

---

### Task 2: Update Jarvis Labels and Quick Prompts

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisUiSpec.kt`
- May modify: `app/src/main/java/com/example/icarcontroller/FeatureCatalog.kt`

- [ ] **Step 1: Replace page copy**

Set readable labels:

```kotlin
fun headerKicker(): String = "B2 / JARVIS AGENT"
fun headerTitle(): String = "贾维斯巡检"
fun headerSubtitle(): String = "用自然语言生成安全计划，确认后再控制小车执行。"
fun primaryActions(): List<String> = listOf("发送", "确认执行")
fun secondaryActions(): List<String> = listOf("刷新任务", "查看报告")
fun dangerAction(): String = "急停"
fun quickPrompts(): List<String> = listOf("检查状态", "打开摄像头", "开始巡检", "生成报告")
```

- [ ] **Step 2: Run Android unit tests**

Run:

```powershell
E:\android-tools\gradle-8.7\bin\gradle.bat testDebugUnitTest --no-daemon --console=plain
```

Expected: tests pass.

- [ ] **Step 3: Commit Task 2**

```powershell
git add app/src/main/java/com/example/icarcontroller/JarvisUiSpec.kt app/src/main/java/com/example/icarcontroller/FeatureCatalog.kt
git commit -m "feat: update Jarvis chat UI copy"
```

---

### Task 3: Replace AI Workspace Layout With Chat Shell

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`

- [ ] **Step 1: Identify and isolate old workspace**

Replace the body of `parkingAiWorkspace()` instead of modifying unrelated pages. Keep `renderAiPage()` as the entry point.

- [ ] **Step 2: Create local chat state in `parkingAiWorkspace()`**

Inside `parkingAiWorkspace()`:

```kotlin
var jarvisState = JarvisViewState.initial()
val chatList = LinearLayout(this@MainActivity).apply {
    orientation = LinearLayout.VERTICAL
}
```

Initialize with a greeting:

```kotlin
jarvisState = jarvisState.copy(
    chatItems = listOf(
        JarvisChatItem.AssistantMessage(
            "Jarvis online. Tell me the patrol target. I will generate a safe plan before controlling the car.",
            ""
        )
    )
)
```

- [ ] **Step 3: Add top status bar**

Add a dark card with:

```text
JARVIS
Jetson <currentHost>:8100
Token 已配置 / Token 缺失
控制服务 8000 由 Jarvis 接管
```

Use existing helpers where possible:

- `roundedBackground`
- `parkingPalette`
- `parkingChip`
- `dp`

- [ ] **Step 4: Add scrollable chat timeline**

Create:

```kotlin
val scroll = ScrollView(this@MainActivity)
scroll.addView(chatList)
```

Give it enough weight to occupy the main page:

```kotlin
addView(scroll, LinearLayout.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    0,
    1f
))
```

- [ ] **Step 5: Add bottom composer**

Composer contains:

- `EditText` for message
- send button
- compact quick prompt row
- small token/settings row

Token should no longer dominate the page.

- [ ] **Step 6: Compile**

Run:

```powershell
E:\android-tools\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon --console=plain
```

Expected: build succeeds.

- [ ] **Step 7: Commit Task 3**

```powershell
git add app/src/main/java/com/example/icarcontroller/MainActivity.kt
git commit -m "feat: rebuild Jarvis page chat shell"
```

---

### Task 4: Render Chat Items as Jarvis Cards

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`

- [ ] **Step 1: Add `renderJarvisChat()` helper**

Add a helper:

```kotlin
private fun renderJarvisChat(
    container: LinearLayout,
    items: List<JarvisChatItem>,
    onConfirmPlan: (JarvisMissionPlan) -> Unit,
    onModifyTarget: (String) -> Unit,
    onRefreshMission: (String) -> Unit,
    onCancelMission: (String) -> Unit,
    onOpenReport: (JarvisReport) -> Unit
) {
    container.removeAllViews()
    items.forEach { item ->
        container.addView(
            when (item) {
                is JarvisChatItem.UserMessage -> jarvisUserBubble(item.text)
                is JarvisChatItem.AssistantMessage -> jarvisAssistantBubble(item.text)
                is JarvisChatItem.PlanCard -> jarvisPlanCard(item.plan, onConfirmPlan, onModifyTarget)
                is JarvisChatItem.ProgressCard -> jarvisProgressCard(item.mission, item.timeline, onRefreshMission, onCancelMission)
                is JarvisChatItem.ReportCard -> jarvisReportCard(item.report, onOpenReport)
                is JarvisChatItem.ErrorMessage -> jarvisErrorCard(item.title, item.detail)
                is JarvisChatItem.SystemEvent -> jarvisSystemRow(item.text)
            }
        )
    }
}
```

- [ ] **Step 2: Add message bubble helpers**

Add:

```kotlin
private fun jarvisUserBubble(text: String): View
private fun jarvisAssistantBubble(text: String): View
private fun jarvisSystemRow(text: String): View
```

User bubble aligns right. Assistant bubble aligns left. System row is centered and muted.

- [ ] **Step 3: Add plan card helper**

`jarvisPlanCard()` must show:

- summary
- steps
- completion criteria
- confirmation state
- `确认执行`
- `修改目标`

The confirm button calls `onConfirmPlan(plan)`.

- [ ] **Step 4: Add progress card helper**

`jarvisProgressCard()` must show:

- mission state
- timeline entries
- refresh button
- cancel button

- [ ] **Step 5: Add report and error card helpers**

`jarvisReportCard()` shows markdown preview and opens full report.

`jarvisErrorCard()` uses danger color and clear detail text.

- [ ] **Step 6: Compile**

Run:

```powershell
E:\android-tools\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon --console=plain
```

Expected: build succeeds.

- [ ] **Step 7: Commit Task 4**

```powershell
git add app/src/main/java/com/example/icarcontroller/MainActivity.kt
git commit -m "feat: render Jarvis chat cards"
```

---

### Task 5: Wire Chat Actions to Existing Jarvis API

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`

- [ ] **Step 1: Wire send action**

On send:

```kotlin
val message = input.text.toString().trim()
if (message.isBlank()) return
val token = saveJarvisToken()
if (token.isBlank()) {
    jarvisState = JarvisReducer.reduce(jarvisState, JarvisEvent.NetworkFailed("请先配置 Jarvis Token"))
    render()
    return
}
jarvisState = JarvisReducer.reduce(jarvisState, JarvisEvent.UserMessageSubmitted(message))
render()
commandExecutor.execute {
    val result = runCatching { JarvisApi(currentHost, token).chat(message) }
    runOnUiThread {
        result.fold(
            onSuccess = { plan -> jarvisState = JarvisReducer.reduce(jarvisState, JarvisEvent.PlanReady(plan)) },
            onFailure = { error -> jarvisState = JarvisReducer.reduce(jarvisState, JarvisEvent.NetworkFailed(error.message ?: error.javaClass.simpleName)) }
        )
        render()
    }
}
```

- [ ] **Step 2: Wire confirm action**

On confirm:

```kotlin
val token = saveJarvisToken()
commandExecutor.execute {
    val result = runCatching {
        val api = JarvisApi(currentHost, token)
        val mission = api.createMission(plan)
        val confirmed = api.confirmMission(mission.id)
        val entries = api.getTimeline(confirmed.id)
        confirmed to entries
    }
    runOnUiThread {
        result.fold(
            onSuccess = { pair ->
                jarvisMissionId = pair.first.id
                jarvisState = JarvisReducer.reduce(jarvisState, JarvisEvent.MissionUpdated(pair.first, pair.second))
            },
            onFailure = { error ->
                jarvisState = JarvisReducer.reduce(jarvisState, JarvisEvent.NetworkFailed(error.message ?: error.javaClass.simpleName))
            }
        )
        render()
    }
}
```

- [ ] **Step 3: Wire refresh, cancel, and report actions**

Use existing API methods:

- `getMission(missionId)`
- `getTimeline(missionId)`
- `cancelMission(missionId)`
- `getReport(missionId)`

Append progress or report cards through the reducer.

- [ ] **Step 4: Compile**

Run:

```powershell
E:\android-tools\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon --console=plain
```

Expected: build succeeds.

- [ ] **Step 5: Commit Task 5**

```powershell
git add app/src/main/java/com/example/icarcontroller/MainActivity.kt
git commit -m "feat: wire Jarvis chat actions"
```

---

### Task 6: Final Verification and Device Install

**Files:**
- No required source changes unless verification finds an issue.

- [ ] **Step 1: Run Android unit tests**

```powershell
E:\android-tools\gradle-8.7\bin\gradle.bat testDebugUnitTest --no-daemon --console=plain
```

Expected: all Android unit tests pass.

- [ ] **Step 2: Build debug APK**

```powershell
E:\android-tools\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon --console=plain
```

Expected: build succeeds.

- [ ] **Step 3: Install on connected device**

```powershell
C:\Users\lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe devices
C:\Users\lenovo\AppData\Local\Android\Sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: device is listed and install returns `Success`.

- [ ] **Step 4: Smoke test with Jetson if reachable**

Check:

```powershell
Invoke-RestMethod -Uri "http://10.161.57.230:8100/health" -TimeoutSec 10
```

If reachable, test in App:

- Host: `10.161.57.230`
- Token: `javis`
- Message: `巡检 B2 东侧停车区，打开摄像头和避障`

Expected:

- user message appears
- assistant response appears
- plan card appears
- confirm button appears

- [ ] **Step 5: Commit verification fixes if any**

Only commit if source changes were needed:

```powershell
git add app/src/main/java/com/example/icarcontroller app/src/test/java/com/example/icarcontroller
git commit -m "fix: polish Jarvis chat UI verification"
```

---

## Self-Review

- Spec coverage: all required UI elements are mapped to tasks: top status bar, chat timeline, composer, plan card, progress card, report card, errors, and dark visual style.
- Scope: this plan only changes Android UI/state. It does not change backend contracts, camera stream, voice input, or long-term memory.
- Type consistency: the plan uses existing `JarvisMissionPlan`, `JarvisMission`, `JarvisTimelineEntry`, and `JarvisReport` types.
- Execution constraint: because the user explicitly asked to stop using multiple agents, execute this plan inline in the current session rather than subagent-driven execution.
