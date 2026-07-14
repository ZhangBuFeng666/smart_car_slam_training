import json

import httpx
import pytest
import respx

from jarvis_agent.config import Settings
from jarvis_agent.deepseek import (
    DeepSeekPlanner,
    ModelResponseError,
    ModelTimeoutError,
    ModelUnavailableError,
    _parse_chat_response,
)
from jarvis_agent.models import Action


VALID_PLAN = {
    "summary": "Check status and start camera patrol.",
    "steps": [
        {"action": "CHECK_STATUS", "arguments": {}},
        {"action": "START_TASK", "arguments": {"task": "camera"}},
    ],
    "completion_criteria": ["Camera monitoring is active."],
    "requires_confirmation": False,
}
VALID_PLAN_JSON = json.dumps(VALID_PLAN)


def settings(**overrides):
    values = {
        "deepseek_api_key": "test-key",
        "deepseek_base_url": "https://api.deepseek.com",
        "deepseek_model": "deepseek-chat",
        "request_timeout_seconds": 0.01,
    }
    values.update(overrides)
    return Settings(**values)


@pytest.mark.asyncio
@respx.mock
async def test_planner_posts_structured_chat_request():
    route = respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={"choices": [{"message": {"content": VALID_PLAN_JSON}}]},
        )
    )

    plan = await DeepSeekPlanner(settings()).plan(
        "inspect the parking lot", {"control": "online"}
    )

    request = route.calls.last.request
    body = json.loads(request.content)
    assert request.headers["authorization"] == "Bearer test-key"
    assert body["model"] == "deepseek-chat"
    assert body["temperature"] == 0.1
    assert body["response_format"] == {"type": "json_object"}
    assert body["messages"][0]["role"] == "system"
    assert body["messages"][1]["role"] == "user"
    assert plan.steps[0].action is Action.CHECK_STATUS
    assert plan.requires_confirmation is True


@pytest.mark.asyncio
@respx.mock
async def test_plan_and_reply_prompts_constrain_vision_context():
    route = respx.post("https://api.deepseek.com/chat/completions").mock(
        side_effect=[
            httpx.Response(200, json={"choices": [{"message": {"content": VALID_PLAN_JSON}}]}),
            httpx.Response(200, json={"choices": [{"message": {"content": "I see a car."}}]}),
        ]
    )
    planner = DeepSeekPlanner(settings())
    context = {"vision": {"state": "LIVE", "objects": [{"label": "car"}]}}

    await planner.plan("inspect", context)
    await planner.reply("what do you see", context)

    for call in route.calls:
        prompt = json.loads(call.request.content)["messages"][0]["content"]
        assert "sensor observations" in prompt
        assert "do not invent" in prompt
        assert "STALE" in prompt
        assert "must not bypass confirmation" in prompt


@pytest.mark.asyncio
@respx.mock
async def test_reply_returns_display_and_spoken_versions_from_one_request():
    route = respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "reply": "前方通道暂时畅通，可以继续巡检。",
                                    "spoken_reply": "前方通道畅通，可以继续巡检。",
                                },
                                ensure_ascii=False,
                            )
                        }
                    }
                ]
            },
        )
    )

    reply = await DeepSeekPlanner(settings()).reply("前方安全吗", {})

    assert reply.reply == "前方通道暂时畅通，可以继续巡检。"
    assert reply.spoken_reply == "前方通道畅通，可以继续巡检。"
    body = json.loads(route.calls.last.request.content)
    assert body["response_format"] == {"type": "json_object"}


def test_plain_text_reply_uses_first_sentence_as_spoken_fallback():
    response = httpx.Response(
        200,
        json={"choices": [{"message": {"content": "第一句适合播报。第二句只在屏幕显示。"}}]},
    )

    reply = _parse_chat_response(response)

    assert reply.reply == "第一句适合播报。第二句只在屏幕显示。"
    assert reply.spoken_reply == "第一句适合播报。"


def test_spoken_reply_removes_non_speech_content_and_limits_length():
    response = httpx.Response(
        200,
        json={
            "choices": [
                {
                    "message": {
                        "content": json.dumps(
                            {
                                "reply": "完整回答",
                                "spoken_reply": (
                                    "请查看[巡检报告](https://example.com/report)，"
                                    "```python print('secret') ```"
                                    + "这是一段很长的播报内容" * 10
                                ),
                            },
                            ensure_ascii=False,
                        )
                    }
                }
            ]
        },
    )

    reply = _parse_chat_response(response)

    assert "http" not in reply.spoken_reply
    assert "```" not in reply.spoken_reply
    assert "print" not in reply.spoken_reply
    assert len(reply.spoken_reply) <= 60


@pytest.mark.asyncio
@respx.mock
async def test_planner_retries_once_after_timeout():
    route = respx.post("https://api.deepseek.com/chat/completions").mock(
        side_effect=[
            httpx.TimeoutException("timeout"),
            httpx.Response(
                200,
                json={"choices": [{"message": {"content": VALID_PLAN_JSON}}]},
            ),
        ]
    )

    plan = await DeepSeekPlanner(settings()).plan("start camera", {})

    assert route.call_count == 2
    assert plan.steps[1].action is Action.START_TASK


@pytest.mark.asyncio
@respx.mock
async def test_timeout_after_retry_raises_typed_error():
    respx.post("https://api.deepseek.com/chat/completions").mock(
        side_effect=httpx.TimeoutException("timeout")
    )

    with pytest.raises(ModelTimeoutError):
        await DeepSeekPlanner(settings()).plan("start camera", {})


@pytest.mark.asyncio
@respx.mock
async def test_markdown_fenced_json_is_accepted():
    content = "```json\n%s\n```" % VALID_PLAN_JSON
    respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={"choices": [{"message": {"content": content}}]},
        )
    )

    plan = await DeepSeekPlanner(settings()).plan("start camera", {})

    assert plan.steps[1].action is Action.START_TASK


@pytest.mark.asyncio
@respx.mock
async def test_model_params_and_string_completion_criteria_are_normalized():
    content = json.dumps(
        {
            "summary": "Ask user to clarify.",
            "steps": [
                {
                    "action": "ASK_USER",
                    "params": {"question": "Please describe the patrol again."},
                }
            ],
            "completion_criteria": "User provides a clear instruction.",
            "requires_confirmation": False,
        }
    )
    respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={"choices": [{"message": {"content": content}}]},
        )
    )

    plan = await DeepSeekPlanner(settings()).plan("patrol", {})

    assert plan.steps[0].arguments == {
        "question": "Please describe the patrol again."
    }
    assert plan.completion_criteria == ["User provides a clear instruction."]


@pytest.mark.asyncio
@respx.mock
async def test_flat_step_fields_are_normalized_into_safe_arguments():
    content = json.dumps(
        {
            "summary": "Start patrol.",
            "steps": [
                {"action": "START_TASK", "task": "camera"},
                {"action": "CHECK_STATUS", "task": "camera"},
                {"action": "RECORD_EVENT", "event": "Patrol started"},
                {
                    "action": "ASK_USER",
                    "question": "Continue?",
                    "condition": "on detection",
                },
            ],
            "completion_criteria": "Done.",
            "requires_confirmation": True,
        }
    )
    respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={"choices": [{"message": {"content": content}}]},
        )
    )

    plan = await DeepSeekPlanner(settings()).plan("patrol", {})

    assert plan.steps[0].arguments == {"task": "camera"}
    assert plan.steps[1].arguments == {}
    assert plan.steps[2].arguments == {
        "event_type": "model_note",
        "label": "Patrol started",
    }
    assert plan.steps[3].arguments == {"question": "Continue?"}


@pytest.mark.asyncio
@respx.mock
async def test_invalid_action_is_rejected():
    bad_plan = dict(VALID_PLAN)
    bad_plan["steps"] = [{"action": "MOVE", "arguments": {"direction": "forward"}}]
    respx.post("https://api.deepseek.com/chat/completions").mock(
        return_value=httpx.Response(
            200,
            json={"choices": [{"message": {"content": json.dumps(bad_plan)}}]},
        )
    )

    with pytest.raises(ModelResponseError):
        await DeepSeekPlanner(settings()).plan("move forward", {})


@pytest.mark.asyncio
async def test_missing_api_key_raises_unavailable_without_http():
    with pytest.raises(ModelUnavailableError):
        await DeepSeekPlanner(settings(deepseek_api_key=" ")).plan("status", {})
