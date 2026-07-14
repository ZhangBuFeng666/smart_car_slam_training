# Jarvis Spoken Replies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a concise spoken version of every Jarvis reply in the same LLM call, queue it on the Jetson, and expose Android auto-speech and replay controls without delaying text chat.

**Architecture:** DeepSeek returns a validated `AssistantReply` containing display and spoken text. Jarvis sends the spoken text to a non-blocking FIFO queue in the control service when the request enables speech, while Android stores both forms and exposes one persistent header toggle plus per-message replay.

**Tech Stack:** Python 3.8, FastAPI, Pydantic, httpx, Edge TTS, `queue.Queue`, Kotlin, Android Views, JUnit.

**Commit policy:** Do not create commits until automated tests and live Jetson/phone verification have passed, per the user's existing instruction.

---

## File Structure

- Create `jetson_server/speech_queue.py`: isolated FIFO queue, request de-duplication, worker lifecycle.
- Create `jetson_server/test_speech_queue.py`: queue ordering, duplicate handling, failure recovery.
- Create `app/src/main/java/com/example/icarcontroller/JarvisSpeechPreferences.kt`: persistent auto-speech setting.
- Create `app/src/test/java/com/example/icarcontroller/JarvisSpeechPreferencesTest.java`: preference contract constants.
- Modify `jarvis_agent/src/jarvis_agent/models.py`: dual reply and speech status API models.
- Modify `jarvis_agent/src/jarvis_agent/deepseek.py`: one-call JSON reply and deterministic fallback.
- Modify `jarvis_agent/src/jarvis_agent/control_client.py`: JSON speech enqueue request.
- Modify `jarvis_agent/src/jarvis_agent/api.py`: consistent response construction and best-effort enqueue.
- Modify `jetson_server/server.py`: `/speech/enqueue` route and queue lifecycle.
- Modify Android Jarvis API, state, codec, chat page, and focused tests to retain and replay spoken text.

### Task 1: Dual LLM Reply Contract

**Files:**
- Modify: `jarvis_agent/src/jarvis_agent/models.py`
- Modify: `jarvis_agent/src/jarvis_agent/deepseek.py`
- Modify: `jarvis_agent/tests/test_deepseek.py`

- [ ] **Step 1: Add failing parser and prompt tests**

Add tests asserting that `_parse_chat_response` accepts JSON content and returns both strings, that plain-text model output falls back to the first safe sentence, and that spoken text is capped at 60 Chinese characters without Markdown URLs or code fences.

```python
reply = _parse_chat_response(response_with_content(
    '{"reply":"完整解释。第二句。","spoken_reply":"简短解释。"}'
))
assert reply.reply == "完整解释。第二句。"
assert reply.spoken_reply == "简短解释。"

fallback = _parse_chat_response(response_with_content("第一句。第二句。"))
assert fallback.reply == "第一句。第二句。"
assert fallback.spoken_reply == "第一句。"
```

- [ ] **Step 2: Run the focused test and verify failure**

Run: `python -m pytest jarvis_agent/tests/test_deepseek.py -q`

Expected: FAIL because `DeepSeekPlanner.reply()` still returns `str` and no `AssistantReply` exists.

- [ ] **Step 3: Add the model and deterministic sanitization**

Add the following external model and make `reply()` return it:

```python
class AssistantReply(ExternalModel):
    reply: str
    spoken_reply: str
```

Configure the request with `response_format={"type": "json_object"}` and instruct the existing system prompt to return exactly `reply` and `spoken_reply`. Implement `_spoken_fallback(text)` that strips fences, Markdown links, URLs and repeated whitespace, takes the first sentence boundary, and truncates to 60 characters.

- [ ] **Step 4: Run focused tests**

Run: `python -m pytest jarvis_agent/tests/test_deepseek.py -q`

Expected: PASS.

### Task 2: Jetson FIFO Speech Queue

**Files:**
- Create: `jetson_server/speech_queue.py`
- Create: `jetson_server/test_speech_queue.py`
- Modify: `jetson_server/server.py`
- Modify: `jetson_server/test_server_config.py`

- [ ] **Step 1: Write failing queue tests**

Cover ordered playback, request-ID de-duplication, queue-full response, and continued playback after a speaker exception.

```python
played = []
queue = SpeechQueue(lambda text: played.append(text) or {"ok": True}, max_size=3)
assert queue.enqueue("第一条", "r1")["state"] == "queued"
assert queue.enqueue("第二条", "r2")["state"] == "queued"
assert queue.enqueue("重复", "r1")["state"] == "duplicate"
assert queue.wait_until_idle(1.0)
assert played == ["第一条", "第二条"]
queue.shutdown()
```

- [ ] **Step 2: Verify the queue tests fail**

Run: `python -m pytest jetson_server/test_speech_queue.py -q`

Expected: FAIL because `speech_queue.py` does not exist.

- [ ] **Step 3: Implement `SpeechQueue`**

Use one daemon worker and `queue.Queue`. Normalize text before enqueue, cap it at 500 characters, retain the latest 128 request IDs in a set/deque pair, catch speaker exceptions inside the worker, and expose `enqueue`, `snapshot`, `wait_until_idle`, and `shutdown`.

- [ ] **Step 4: Add `/speech/enqueue` without changing `/speak`**

Add a global `SPEECH_QUEUE`, initialize it in `main()` with `SpeechQueue(speak_text)`, and stop it in the existing shutdown block. Parse this request:

```json
{"text":"播报内容","source":"jarvis","request_id":"uuid"}
```

Return HTTP 202 for `queued`, HTTP 200 for `duplicate`, HTTP 429 for a full queue, and HTTP 503 if the queue is unavailable. Keep existing synchronous `/speak` behavior intact.

- [ ] **Step 5: Run queue and server route tests**

Run: `python -m pytest jetson_server/test_speech_queue.py jetson_server/test_server_config.py -q`

Expected: PASS.

### Task 3: Jarvis Best-Effort Speech Dispatch

**Files:**
- Modify: `jarvis_agent/src/jarvis_agent/models.py`
- Modify: `jarvis_agent/src/jarvis_agent/control_client.py`
- Modify: `jarvis_agent/src/jarvis_agent/api.py`
- Modify: `jarvis_agent/tests/test_control_client.py`
- Modify: `jarvis_agent/tests/test_api.py`

- [ ] **Step 1: Add failing API contract tests**

Assert that `speech_enabled` defaults to false, enabling it calls `control.enqueue_speech()` once, queue failure still returns HTTP 200, and direct task responses have deterministic spoken text.

```python
response = client.post(
    "/api/v1/chat",
    headers=auth_headers,
    json={"message": "你看到了什么", "speech_enabled": True},
)
assert response.status_code == 200
assert response.json()["spoken_reply"]
assert response.json()["speech"]["state"] == "queued"
```

- [ ] **Step 2: Verify focused tests fail**

Run: `python -m pytest jarvis_agent/tests/test_control_client.py jarvis_agent/tests/test_api.py -q`

Expected: FAIL because the request, response and control client lack speech fields.

- [ ] **Step 3: Add speech models and control request**

Extend `ChatRequest` with `speech_enabled: bool = False`. Extend `ChatResponse` with `spoken_reply: str` and a `SpeechStatus` object containing `state` and optional `request_id`. Add:

```python
async def enqueue_speech(self, text: str, request_id: str) -> Dict[str, Any]:
    return await self._request(
        "POST",
        "/speech/enqueue",
        json={"text": text, "source": "jarvis", "request_id": request_id},
    )
```

Update `_request` to accept an optional JSON body.

- [ ] **Step 4: Centralize chat response construction**

Inside `create_app`, add one async response builder used by every chat return path. It accepts display text, spoken text, plan/control card and `speech_enabled`; when enabled it awaits only the quick enqueue HTTP request. Catch `ControlServiceError` and return `speech.state="unavailable"` without failing chat.

- [ ] **Step 5: Run Jarvis tests**

Run: `python -m pytest jarvis_agent/tests -q`

Expected: PASS.

### Task 4: Android Protocol and Conversation Persistence

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisApi.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisState.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisChatItemCodec.kt`
- Modify: `app/src/test/java/com/example/icarcontroller/JarvisModelsTest.java`
- Modify: `app/src/test/java/com/example/icarcontroller/JarvisStateTest.java`
- Modify: `app/src/test/java/com/example/icarcontroller/JarvisChatItemCodecTest.java`

- [ ] **Step 1: Add failing Android model tests**

Assert that `JarvisApi.chat(message, true)` writes `speech_enabled`, parses `spoken_reply` and `speech.state`, the reducer stores spoken text, and the codec round-trips it while decoding old records without the field.

- [ ] **Step 2: Run focused Android tests and verify failure**

Run: `gradlew.bat testDebugUnitTest --tests "com.example.icarcontroller.JarvisModelsTest" --tests "com.example.icarcontroller.JarvisStateTest" --tests "com.example.icarcontroller.JarvisChatItemCodecTest"`

Expected: FAIL on missing speech properties.

- [ ] **Step 3: Implement backward-compatible Android models**

Use these shapes so existing positional constructors remain valid:

```kotlin
data class JarvisChatResponse(
    val reply: String,
    val spokenReply: String,
    val speechState: String,
    val plan: JarvisMissionPlan?,
    val controlTask: JarvisControlTask?
)

data class AssistantMessage(
    val text: String,
    val timestamp: String,
    val spokenText: String? = null
) : JarvisChatItem()
```

Change `JarvisEvent.ChatReply` to include nullable spoken text. Store `spoken_text` in the codec and use `optNullableString` when decoding old history.

- [ ] **Step 4: Run the focused Android tests**

Run the command from Step 2.

Expected: PASS.

### Task 5: AI Page Toggle and Message Replay

**Files:**
- Create: `app/src/main/java/com/example/icarcontroller/JarvisSpeechPreferences.kt`
- Create: `app/src/test/java/com/example/icarcontroller/JarvisSpeechPreferencesTest.java`
- Modify: `app/src/main/java/com/example/icarcontroller/CarApi.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/JarvisChatPage.kt`
- Modify: `app/src/test/java/com/example/icarcontroller/CarApiTest.java`
- Modify: `app/src/test/java/com/example/icarcontroller/JarvisUiSpecTest.java`

- [ ] **Step 1: Add failing preference, URL and UI policy tests**

Assert a default-on preference, `http://host:8000/speech/enqueue`, one header speech control, and replay support only for assistant messages with nonblank spoken text.

- [ ] **Step 2: Verify focused tests fail**

Run: `gradlew.bat testDebugUnitTest --tests "com.example.icarcontroller.JarvisSpeechPreferencesTest" --tests "com.example.icarcontroller.CarApiTest" --tests "com.example.icarcontroller.JarvisUiSpecTest"`

Expected: FAIL because the preference and UI policy do not exist.

- [ ] **Step 3: Add persistent speech setting**

Store `auto_speech_enabled` in a dedicated `jarvis_speech` SharedPreferences file and default to true. The header uses one familiar speaker `ImageButton`; active and muted states use the same stable 48dp hit target, different tint/alpha, and updated content descriptions.

- [ ] **Step 4: Wire automatic and manual speech**

Pass the preference into `JarvisApi.chat(message, speechEnabled)`. Render assistant messages with a compact replay icon beside, not inside, the bubble. Replay posts JSON with a UUID to `CarApi.speechEnqueueUrl()` on the existing executor, applies a short click debounce, and reports failures only through the existing status line.

- [ ] **Step 5: Run Android unit tests and build**

Run: `gradlew.bat testDebugUnitTest assembleDebug`

Expected: all tests PASS and `app/build/outputs/apk/debug/app-debug.apk` exists.

### Task 6: Full Verification and Live Deployment

**Files:**
- Modify only if verification finds a scoped defect in files listed above.

- [ ] **Step 1: Run all server suites**

Run:

```powershell
python -m pytest jetson_server -q
python -m pytest jarvis_agent/tests -q
```

Expected: all tests PASS.

- [ ] **Step 2: Deploy server files to Jetson**

Copy `jetson_server/server.py`, `jetson_server/speech_queue.py`, and the changed `jarvis_agent/src/jarvis_agent` modules to their existing locations under `/home/jetson/icar_app_server` and `/home/jetson/jarvis-agent`. Restart `icar-control.service` and `jarvis-agent.service`; verify ports 8000 and 8100.

- [ ] **Step 3: Install the debug APK on the connected phone**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

Expected: `Success`.

- [ ] **Step 4: Exercise the acceptance matrix**

Verify one short response, one long response, two rapid responses, mute, history replay, and control-service failure. Confirm chat text never waits for playback completion, spoken replies do not overlap, and failures do not affect motion controls.

- [ ] **Step 5: Review the final diff without committing**

Run: `git diff --check` and `git status --short`.

Expected: no whitespace errors; only intentional feature files plus pre-existing Jarvis/vision work remain modified. Do not commit until the user confirms the live result.
