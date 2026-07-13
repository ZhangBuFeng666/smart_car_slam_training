# Jarvis Preparation and Conversation History Design

## Goal

Improve Jarvis in two areas:

1. Control tasks automatically complete all non-hazardous preparation before asking the user to start the real action.
2. Conversations survive page changes and application restarts, with support for multiple conversations, history, rename, archive, and delete.

## Control Task Lifecycle

The control task lifecycle becomes:

`PREPARING -> READY -> STARTING -> RUNNING -> COMPLETED`

Terminal or interruption states are `PREPARATION_FAILED`, `FAILED`, and `STOPPED`.

When chat recognizes a state-changing command, the backend creates the task and immediately begins preparation. Preparation may include:

- checking the control service and the pinned `icar-runtime` container;
- checking the motion bridge;
- starting the base driver when required;
- starting camera, lidar, or other dependencies required by the requested function;
- verifying that each required dependency reported a successful start result.

Preparation must not start the requested motion or automatic behavior. For example, preparing obstacle avoidance may start the base and sensors, but it must not start the obstacle-avoidance node until the user presses Start.

The Start button is disabled during `PREPARING`. It becomes enabled only in `READY`. If preparation fails, the card shows the failed preparation step and a Retry button. Stop remains available during preparation and execution.

After Start, the backend performs only the requested behavior. Movement still stops automatically on completion, timeout, cancellation, or failure. Distance movement continues to use measured odometry rather than duration estimation.

## Progress Card

One card represents both preparation and execution. It contains:

- task title and current state;
- preparation and execution steps;
- current progress message;
- measured value and target for timed or distance movement;
- Stop button;
- Start button in `READY`;
- Retry button in `PREPARATION_FAILED`;
- Continue button only when a stopped task can safely be prepared again.

Preparation begins as soon as the card is returned to the app. The user is not asked whether preparation should begin.

## Persistent Conversation Model

Use Android's native SQLite API to avoid introducing annotation processing into the existing native View project.

### Conversation

- `id`: stable UUID
- `title`: first user message by default, editable by the user
- `created_at`
- `updated_at`
- `archived_at`: nullable
- `active_task_id`: nullable reference used for switch safety checks

### Conversation Item

- `id`: stable UUID
- `conversation_id`
- `position`: monotonically increasing order within the conversation
- `type`: user message, assistant message, system event, error, plan, control task, progress, or report
- `payload_json`: complete serialized item payload
- `created_at`

Indexes cover conversation update order, archive state, and item ordering. Database writes use transactions. Deleting a conversation cascades to its items.

The database is local to the Android application. Tokens remain in the existing credential preference store and are never stored in conversation payloads.

## Conversation Behavior

On first use, create one conversation containing the Jarvis greeting. A new conversation also starts with that greeting.

Every reducer result that changes visible chat content is persisted. Control-task polling updates the existing task-card item instead of appending duplicate cards. Reopening the AI page loads the last active conversation. Restarting the app restores the same conversation and all stored card states.

The default title is derived from the first user message, trimmed to a practical display length. Rename overrides the automatic title permanently.

## Navigation And History Drawer

The chat header contains:

- a history icon on the left;
- `贾维斯` centered;
- a new-conversation icon on the right.

The history icon opens a left-side drawer with a smooth slide animation and a dimmed scrim over the conversation. Tapping the scrim or using Back closes it.

The drawer lists active conversations by most recently updated. Long-pressing a conversation opens actions for Rename, Archive, and Delete. Delete requires confirmation. An `已归档` entry at the bottom opens archived conversations; archived conversations can be restored or deleted.

The new-conversation icon creates and opens a new conversation unless a running task requires a safety choice.

## Running Task Switch Safety

Before switching conversations, creating a conversation, archiving the current conversation, or deleting the current conversation, check whether its task is `STARTING` or `RUNNING`.

If so, display a modal with:

- `停止任务并切换`;
- `保持运行并切换`;
- a close icon in the upper-right corner.

The close icon dismisses the modal without changing the task or conversation. Choosing stop waits for the stop request result before switching. If stop fails, remain in the current conversation and show the error. Choosing keep-running switches immediately; background polling continues updating the original conversation's stored card.

Preparation alone is not treated as hazardous execution. Switching away during preparation is allowed, and preparation continues in the background unless the user explicitly stops it.

## Ownership And Data Flow

- `JarvisConversationStore` owns SQLite schema, queries, and transactions.
- `JarvisConversationController` owns current-conversation selection, persistence, and background task updates.
- `JarvisChatPage` renders the selected conversation and dispatches user actions.
- `JarvisReducer` remains responsible for deterministic chat-state transitions.
- The Jarvis backend owns preparation and execution state transitions.

The page is no longer the owner of conversation lifetime. Recreating `JarvisChatPage` cannot erase messages because state is loaded from the conversation controller/store.

## Error Handling

- A database read failure shows a recoverable error without deleting stored data.
- A failed write keeps the in-memory view and reports that history could not be saved.
- Unknown or incompatible item payloads render as a recoverable system message instead of crashing the page.
- Backend task-state polling resumes for non-terminal stored cards when the relevant conversation is loaded or when background tracking is active.
- Preparation timeouts produce `PREPARATION_FAILED` with the exact failed step and do not expose Start.

## Testing

Backend tests cover:

- preparation starts immediately after task creation;
- requested behavior does not start during preparation;
- Start is accepted only from `READY`;
- preparation failure and retry;
- cancellation during preparation;
- movement and feature execution after readiness.

Android unit tests cover:

- SQLite conversation and item CRUD;
- automatic title generation and rename precedence;
- archive, restore, and cascading delete;
- restoring items after page recreation;
- updating task cards without duplicates;
- active-task switch decisions;
- serialization round trips for every chat-item type.

Device verification covers drawer animation, Back/scrim dismissal, new conversation, history switching, process restart restoration, archived history, and the running-task switch modal.
