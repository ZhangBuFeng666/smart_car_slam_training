from fastapi.testclient import TestClient

from jarvis_agent.api import create_app
from jarvis_agent.config import Settings
from jarvis_agent.models import Action, MissionPlan, MissionStep


class FakePlanner:
    async def plan(self, message, context):
        return MissionPlan(
            summary="Start a simulated parking-lot patrol.",
            steps=[
                MissionStep(action=Action.CHECK_STATUS),
                MissionStep(action=Action.START_TASK, arguments={"task": "camera"}),
                MissionStep(action=Action.START_TASK, arguments={"task": "avoidance"}),
            ],
        )


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


def auth():
    return {"Authorization": "Bearer test-token"}


def event_payload(mission_id, event_type, label, track_id):
    return {
        "mission_id": mission_id,
        "source": "front_camera",
        "event_type": event_type,
        "label": label,
        "confidence": 0.91,
        "position": "parking aisle center",
        "track_id": track_id,
        "image_path": "/var/lib/jarvis/events/%s.jpg" % track_id,
        "timestamp": "2026-01-15T10:30:00+08:00",
        "metadata": {"scenario": "parking-lot"},
    }


def test_simulated_patrol_workflow_reaches_report(tmp_path):
    control = FakeControl()
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    client = TestClient(
        create_app(settings=settings, planner=FakePlanner(), control=control)
    )

    chat = client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "create plan: patrol the parking lot", "context": {}},
    )
    mission = client.post(
        "/api/v1/missions",
        headers=auth(),
        json={"plan": chat.json()["plan"]},
    )
    mission_id = mission.json()["id"]
    confirmed = client.post(
        "/api/v1/missions/%s/confirm" % mission_id,
        headers=auth(),
    )

    paper_box = client.post(
        "/api/v1/vision-events",
        headers=auth(),
        json=event_payload(mission_id, "obstacle", "paper box", "paper-box-001"),
    )
    client.post(
        "/api/v1/missions/%s/decisions" % mission_id,
        headers=auth(),
        json={
            "decision": "continue",
            "event_id": paper_box.json()["event_id"],
            "note": "continue patrol",
        },
    )
    standing_water = client.post(
        "/api/v1/vision-events",
        headers=auth(),
        json=event_payload(
            mission_id, "standing_water", "standing water", "water-001"
        ),
    )
    client.post(
        "/api/v1/missions/%s/decisions" % mission_id,
        headers=auth(),
        json={
            "decision": "finish",
            "event_id": standing_water.json()["event_id"],
            "note": "finish patrol",
        },
    )
    report = client.get(
        "/api/v1/missions/%s/report" % mission_id,
        headers=auth(),
    )
    loaded = client.get(
        "/api/v1/missions/%s" % mission_id,
        headers=auth(),
    )

    assert confirmed.json()["state"] == "RUNNING"
    assert control.calls == [("status", None), ("start", "camera"), ("start", "avoidance")]
    assert "paper box" in report.json()["markdown"]
    assert "standing water" in report.json()["markdown"]
    assert "User decision: finish" in report.json()["markdown"]
    assert loaded.json()["state"] == "COMPLETED"
