import pytest
import respx
from httpx import Response

from jarvis_agent.config import Settings
from jarvis_agent.control_client import ControlClient


@pytest.mark.asyncio
@respx.mock
async def test_control_client_uses_existing_start_and_stop_routes():
    start = respx.get("http://control.test/start/avoidance").mock(
        return_value=Response(200, json={"status": "started"})
    )
    stop = respx.get("http://control.test/stop/avoidance").mock(
        return_value=Response(200, json={"status": "stopped"})
    )
    client = ControlClient(Settings(control_base_url="http://control.test"))

    await client.start_task("avoidance")
    await client.stop_task("avoidance")

    assert start.called
    assert stop.called


@pytest.mark.asyncio
@respx.mock
async def test_control_client_uses_motion_route():
    move = respx.get(
        "http://control.test/move/front?speed=0.2&turn=0.65"
    ).mock(return_value=Response(200, json={"ok": True}))
    client = ControlClient(Settings(control_base_url="http://control.test"))

    await client.move("front", speed=0.2, turn=0.65)

    assert move.called


@pytest.mark.asyncio
@respx.mock
async def test_control_client_enqueues_spoken_reply_as_json():
    speech = respx.post("http://control.test/speech/enqueue").mock(
        return_value=Response(202, json={"state": "queued", "request_id": "reply-1"})
    )
    client = ControlClient(Settings(control_base_url="http://control.test"))

    result = await client.enqueue_speech("前方通道畅通。", "reply-1")

    assert result["state"] == "queued"
    assert speech.calls.last.request.read().decode("utf-8") == (
        '{"text":"前方通道畅通。","source":"jarvis","request_id":"reply-1"}'
    )
