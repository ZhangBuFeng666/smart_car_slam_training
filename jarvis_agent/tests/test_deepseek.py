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
