from pathlib import Path

from fastapi.testclient import TestClient

from jarvis_agent.api import create_app
from jarvis_agent.config import Settings
from jarvis_agent.deepseek import ModelResponseError
from jarvis_agent.models import Action, MissionPlan, MissionStep


class FakePlanner:
    async def plan(self, message, context):
        return MissionPlan(
            summary="Start camera patrol",
            steps=[
                MissionStep(action=Action.CHECK_STATUS),
                MissionStep(action=Action.START_TASK, arguments={"task": "camera"}),
            ],
        )


class InvalidPlanner:
    async def plan(self, message, context):
        raise ModelResponseError("invalid plan")


class FakeControl:
    def __init__(self):
        self.calls = []

    async def status(self):
        self.calls.append(("status", None))
        return {"ok": True}

    async def start_task(self, task):
        self.calls.append(("start", task))
        return {"started": task}

    async def stop_task(self, task):
        self.calls.append(("stop", task))
        return {"stopped": task}

    async def stop_all(self):
        self.calls.append(("stop_all", None))
        return {"stopped": "all"}


def client(tmp_path):
    control = FakeControl()
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    app = create_app(settings=settings, planner=FakePlanner(), control=control)
    return TestClient(app), control


def auth():
    return {"Authorization": "Bearer test-token"}


def sample_plan_json():
    return {
        "summary": "Start camera patrol",
        "steps": [{"action": "START_TASK", "arguments": {"task": "camera"}}],
        "completion_criteria": [],
        "requires_confirmation": True,
    }


def test_health_remains_public(tmp_path):
    test_client, _ = client(tmp_path)

    response = test_client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_protected_route_requires_token(tmp_path):
    test_client, _ = client(tmp_path)

    response = test_client.post("/api/v1/chat", json={"message": "start patrol"})

    assert response.status_code == 401
    assert response.json()["error"]["code"] == "UNAUTHORIZED"


def test_chat_returns_validated_plan(tmp_path):
    test_client, _ = client(tmp_path)

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "start patrol", "context": {"car": "online"}},
    )

    assert response.status_code == 200
    assert response.json()["plan"]["requires_confirmation"] is True
    assert response.json()["plan"]["steps"][1]["arguments"] == {"task": "camera"}


def test_chat_falls_back_when_model_returns_invalid_plan(tmp_path):
    control = FakeControl()
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    test_client = TestClient(
        create_app(settings=settings, planner=InvalidPlanner(), control=control)
    )

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "start an unsafe unknown task", "context": {}},
    )

    body = response.json()
    assert response.status_code == 200
    assert body["plan"]["steps"][0]["action"] == "ASK_USER"
    assert "start an unsafe unknown task" in body["plan"]["steps"][0]["arguments"]["question"]


def test_mission_create_confirm_poll_and_timeline(tmp_path):
    test_client, control = client(tmp_path)

    created = test_client.post(
        "/api/v1/missions",
        headers=auth(),
        json={"plan": sample_plan_json()},
    )
    mission_id = created.json()["id"]
    confirmed = test_client.post(
        "/api/v1/missions/%s/confirm" % mission_id,
        headers=auth(),
    )
    loaded = test_client.get(
        "/api/v1/missions/%s" % mission_id,
        headers=auth(),
    )
    timeline = test_client.get(
        "/api/v1/missions/%s/timeline" % mission_id,
        headers=auth(),
    )

    assert created.status_code == 200
    assert confirmed.json()["state"] == "RUNNING"
    assert loaded.json()["id"] == mission_id
    assert timeline.json()[0]["kind"] == "control"
    assert control.calls == [("start", "camera")]


def test_vision_event_decision_and_report(tmp_path):
    test_client, _ = client(tmp_path)
    mission_id = test_client.post(
        "/api/v1/missions",
        headers=auth(),
        json={"plan": sample_plan_json()},
    ).json()["id"]

    event = {
        "mission_id": mission_id,
        "source": "front_camera",
        "event_type": "obstacle",
        "label": "paper box",
        "confidence": 0.91,
        "position": "center aisle",
        "track_id": "track-001",
        "image_path": "/var/lib/jarvis/events/paper-box.jpg",
        "timestamp": "2026-01-15T10:30:00+08:00",
        "metadata": {},
    }
    event_response = test_client.post(
        "/api/v1/vision-events",
        headers=auth(),
        json=event,
    )
    event_id = event_response.json()["event_id"]
    decision = test_client.post(
        "/api/v1/missions/%s/decisions" % mission_id,
        headers=auth(),
        json={"decision": "continue", "event_id": event_id, "note": "safe"},
    )
    report = test_client.get(
        "/api/v1/missions/%s/report" % mission_id,
        headers=auth(),
    )

    assert event_response.status_code == 200
    assert event_response.json()["accepted"] is True
    assert decision.status_code == 200
    assert "paper box" in report.json()["markdown"]
    assert "User decision: continue" in report.json()["markdown"]


def test_cancel_mission_calls_stop_all(tmp_path):
    test_client, control = client(tmp_path)
    mission_id = test_client.post(
        "/api/v1/missions",
        headers=auth(),
        json={"plan": sample_plan_json()},
    ).json()["id"]

    response = test_client.post(
        "/api/v1/missions/%s/cancel" % mission_id,
        headers=auth(),
    )

    assert response.status_code == 200
    assert response.json()["state"] == "CANCELLED"
    assert control.calls == [("stop_all", None)]
