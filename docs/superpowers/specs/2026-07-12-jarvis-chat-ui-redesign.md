# Jarvis Chat UI Redesign

## Goal

Rebuild the Android Jarvis page as a dark, technology-styled chat assistant. The page should feel like a natural-language Jarvis interface while still showing mission plans, execution confirmation, task progress, errors, and reports clearly inside the chat timeline.

## Problem

The current AI page mixes prompt input, examples, static default steps, action buttons, logs, task controls, and report actions in one vertical panel. The result is visually noisy and does not make the LLM interaction feel intelligent. Even when the backend returns a valid plan, the result can be hard to notice because it is displayed as timeline actions or a global log rather than as a visible assistant response.

## Design Direction

Use a single primary interaction model:

```text
Jarvis page = chat timeline
Plan / confirmation / progress / report = cards inside the chat timeline
```

The user should not need to understand whether an item came from a model response, mission API, timeline API, or report API. Everything appears as a conversation with Jarvis.

## Visual Style

Use a dark Jarvis-like engineering interface:

- Background: deep navy / black-blue.
- Cards: dark blue-gray panels.
- Borders: low-opacity cyan/blue.
- Primary accent: cyan / electric blue.
- Success: green.
- Warning: amber.
- Danger: red-orange.
- Text: high-contrast off-white for primary text, muted blue-gray for secondary text.

The style should be clean and readable, not overloaded with HUD decorations. Use subtle glow, status dots, and thin borders instead of complex animation.

## Page Structure

The redesigned page has three persistent regions.

### 1. Top Status Bar

Shows only operational status:

- Title: `JARVIS`
- Subtitle: `Jetson 10.161.57.230:8100`
- Status chips:
  - Jarvis service: online / offline / checking
  - Token: configured / missing
  - Control service: managed by Jarvis on port 8000

This bar should not contain mission controls. Controls belong to chat cards.

### 2. Chat Timeline

The center of the page is a vertical chat timeline. It contains typed UI items:

- user message bubble
- assistant text bubble
- plan card
- mission progress card
- report card
- error card
- lightweight system event row

The timeline starts with a short assistant greeting, for example:

```text
Jarvis online. Tell me the patrol target. I will generate a safe plan before controlling the car.
```

### 3. Bottom Composer

The bottom input area contains:

- multiline text input
- send button
- compact quick actions:
  - 检查状态
  - 打开摄像头
  - 开始巡检
  - 生成报告

Token configuration can stay available, but it should be visually secondary. It can be placed behind a small settings button or compact expandable row, not in the main prompt area.

## Chat Item Model

Replace the current page-local `timeline` view logic with an explicit chat item model:

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

The first implementation can render this list by rebuilding a `LinearLayout`. A RecyclerView is optional and can be added later if the timeline becomes long.

## Plan Card

The plan card is the main "wow" moment after the user sends a request.

Content:

- title: generated from `plan.summary`
- status: `等待确认`
- summary text
- step list
- completion criteria
- safety line:
  - if `requiresConfirmation == true`: `确认后才会执行控制动作`
  - otherwise: `该计划仅需继续对话确认`
- actions:
  - `确认执行`
  - `修改目标`

Behavior:

- `确认执行` calls create mission, confirm mission, then fetch timeline.
- On success, append an assistant message and a progress card.
- On failure, append an error card.
- `修改目标` focuses the composer and pre-fills the previous request or a short hint.

## Progress Card

The progress card represents mission state after confirmation or refresh.

Content:

- mission state: waiting, running, paused, failed, cancelled, completed
- current step inferred from latest timeline entry
- compact timeline rows
- pending decision if present
- actions:
  - `刷新`
  - `取消任务`
  - if pending decision exists: decision buttons such as `继续`, `忽略`, `暂停`, `完成`

Behavior:

- `刷新` calls mission timeline and updates/appends a progress card.
- `取消任务` calls cancel mission and appends a system event or progress card.
- Decision actions submit the decision and then refresh mission state.

## Report Card

The report card appears after the user asks for a report or when a mission completes.

Content:

- title: `巡检报告`
- mission id
- generated time
- first 3-5 lines of markdown summary
- action:
  - `查看完整报告`

Behavior:

- The compact card stays in the chat timeline.
- `查看完整报告` opens the existing bottom sheet/dialog with full markdown.

## Error Handling

All failures should appear in the chat timeline as error cards rather than only changing the global status text.

Examples:

- Missing token:
  - title: `缺少 Token`
  - detail: `请先配置 Jarvis Token`
- Network failure:
  - title: `无法连接 Jarvis`
  - detail: include host and port
- Model invalid plan fallback:
  - show assistant clarification message or fallback plan card, not a raw 502 message.
- Mission control failure:
  - title: `执行失败`
  - detail: API error or exception class.

## Data Flow

### Send Message

```text
User taps Send
→ append UserMessage
→ append temporary SystemEvent("Jarvis 正在生成计划")
→ POST /api/v1/chat
→ remove/update temporary item
→ append AssistantMessage
→ append PlanCard
```

### Confirm Plan

```text
User taps Confirm on PlanCard
→ append SystemEvent("已确认执行")
→ POST /api/v1/missions
→ POST /api/v1/missions/{id}/confirm
→ GET /api/v1/missions/{id}/timeline
→ append ProgressCard
```

### Refresh Progress

```text
User taps Refresh on ProgressCard
→ GET /api/v1/missions/{id}
→ GET /api/v1/missions/{id}/timeline
→ append or replace latest ProgressCard
```

For the first pass, appending a new progress card is acceptable and clearer for debugging. Replacing the latest progress card can be added later.

## Files to Change

Expected Android changes:

- `app/src/main/java/com/example/icarcontroller/MainActivity.kt`
  - replace `parkingAiWorkspace()` implementation
  - add chat rendering helpers
  - wire send, confirm, refresh, report actions to chat items
- `app/src/main/java/com/example/icarcontroller/JarvisState.kt`
  - optionally add chat state reducer if keeping logic testable
- `app/src/main/java/com/example/icarcontroller/JarvisUiSpec.kt`
  - update labels and quick actions
- `app/src/main/java/com/example/icarcontroller/JarvisModels.kt`
  - no contract changes expected
- `app/src/main/java/com/example/icarcontroller/JarvisApi.kt`
  - no required API changes; may add small helpers if needed

Expected tests:

- `app/src/test/java/com/example/icarcontroller/JarvisStateTest.java`
  - add tests for send-message, plan-ready, confirm-success, and error states if reducer is used.
- Existing Jarvis model/API parsing tests should continue to pass.

Backend changes are not required for the UI redesign. The existing backend fallback behavior can remain as a separate safety improvement.

## Non-Goals

Do not include these in this redesign pass:

- voice input
- camera stream embedded in the Jarvis page
- complex animation or animated HUD effects
- long-term memory
- autonomous execution without confirmation
- backend API contract changes

## Acceptance Criteria

- The Jarvis page visually reads as a chat assistant, not a mixed control panel.
- After sending a message, the user sees their message in the timeline.
- A successful plan response appears as a visible assistant response plus a large plan card.
- The plan card has clear `确认执行` and `修改目标` actions.
- Confirming a plan appends task progress to the chat timeline.
- Report retrieval appears as a report card.
- Errors are visible inside the chat timeline.
- The page uses a dark Jarvis-like visual style and remains readable on a phone screen.
- Android debug build succeeds.
