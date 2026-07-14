from pathlib import Path
import threading
import time

import pytest
from fastapi.testclient import TestClient

from jarvis_agent.api import create_app
from jarvis_agent.config import Settings
from jarvis_agent.deepseek import ModelResponseError
from jarvis_agent.models import Action, AssistantReply, MissionPlan, MissionStep


class FakePlanner:
    def __init__(self):
        self.calls = []
        self.reply_calls = []

    async def plan(self, message, context):
        self.calls.append((message, context))
        return MissionPlan(
            summary="Start camera patrol",
            steps=[
                MissionStep(action=Action.CHECK_STATUS),
                MissionStep(action=Action.START_TASK, arguments={"task": "camera"}),
            ],
        )

    async def reply(self, message, context):
        self.reply_calls.append((message, context))
        return AssistantReply(
            reply="你好，我在。有什么可以帮你？",
            spoken_reply="你好，我在。有什么可以帮你？",
        )


class FakeVision:
    def __init__(self, scene=None):
        self.started = 0
        self.stopped = 0
        self.scene = scene or {
            "state": "LIVE",
            "observed_at": 10.0,
            "summary": {"total": 0, "by_class": {}},
            "objects": [],
            "recent_objects": [],
            "error": None,
        }

    def start(self):
        self.started += 1

    def stop(self):
        self.stopped += 1

    def snapshot(self):
        return dict(self.scene)

    def health(self):
        return {
            "state": self.scene["state"],
            "updated_at": self.scene.get("observed_at"),
            "error": self.scene.get("error"),
        }


class InvalidPlanner:
    async def plan(self, message, context):
        raise ModelResponseError("invalid plan")


class FakeControl:
    def __init__(self):
        self.calls = []

    async def health(self):
        self.calls.append(("health", None))
        return {"ok": True}

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

    async def move(self, direction, speed=0.2, turn=0.65):
        self.calls.append(("move", direction, speed, turn))
        return {"ok": True, "direction": direction}

    async def enqueue_speech(self, text, request_id):
        self.calls.append(("speech", text, request_id))
        return {"ok": True, "state": "queued", "request_id": request_id}


class FailingOnceControl(FakeControl):
    def __init__(self):
        super().__init__()
        self.fail_health = True

    async def health(self):
        self.calls.append(("health", None))
        if self.fail_health:
            self.fail_health = False
            raise RuntimeError("offline")
        return {"ok": True}


class BlockingFeatureControl(FakeControl):
    def __init__(self):
        super().__init__()
        self.feature_started = threading.Event()
        self.release_feature = threading.Event()

    async def start_task(self, task):
        self.calls.append(("start", task))
        if task == "follow":
            self.feature_started.set()
            self.release_feature.wait(timeout=2.0)
        return {"started": task}


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


def wait_for_control_state(test_client, task_id, expected, attempts=100):
    for _ in range(attempts):
        status = test_client.get(
            "/api/v1/control-tasks/%s" % task_id,
            headers=auth(),
        ).json()
        if status["state"] == expected:
            return status
        time.sleep(0.01)
    raise AssertionError("task did not reach %s; last state was %s" % (expected, status["state"]))


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


def test_scene_requires_auth_and_returns_all_objects(tmp_path):
    scene = {
        "state": "LIVE",
        "observed_at": 10.0,
        "summary": {"total": 2, "by_class": {"car": 1, "no_parking_sign": 1}},
        "objects": [
            {"track_id": "car-1", "label": "car"},
            {"track_id": "sign-1", "label": "no_parking_sign"},
        ],
        "recent_objects": [],
        "error": None,
    }
    vision = FakeVision(scene)
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    app = create_app(
        settings=settings,
        planner=FakePlanner(),
        control=FakeControl(),
        vision=vision,
    )

    with TestClient(app) as test_client:
        assert test_client.get("/api/v1/scene").status_code == 401
        response = test_client.get("/api/v1/scene", headers=auth())

    assert response.status_code == 200
    assert len(response.json()["objects"]) == 2
    assert vision.started == 1
    assert vision.stopped == 1


def test_chat_and_plan_receive_scene_context(tmp_path):
    planner = FakePlanner()
    vision = FakeVision(
        {
            "state": "LIVE",
            "observed_at": 10.0,
            "summary": {"total": 1, "by_class": {"car": 1}},
            "objects": [{"track_id": "car-1", "label": "car"}],
            "recent_objects": [],
            "error": None,
        }
    )
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    app = create_app(
        settings=settings,
        planner=planner,
        control=FakeControl(),
        vision=vision,
    )

    with TestClient(app) as test_client:
        reply = test_client.post(
            "/api/v1/chat",
            headers=auth(),
            json={"message": "what do you see", "context": {"screen": "ai"}},
        )
        plan = test_client.post(
            "/api/v1/chat",
            headers=auth(),
            json={"message": "create plan", "context": {"screen": "ai"}},
        )

    assert reply.status_code == 200
    assert plan.status_code == 200
    assert planner.reply_calls[0][1]["vision"]["objects"][0]["label"] == "car"
    assert planner.calls[0][1]["vision"]["objects"][0]["label"] == "car"
    assert planner.reply_calls[0][1]["screen"] == "ai"


def test_health_exposes_only_vision_status_metadata(tmp_path):
    vision = FakeVision()
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    app = create_app(
        settings=settings,
        planner=FakePlanner(),
        control=FakeControl(),
        vision=vision,
    )

    with TestClient(app) as test_client:
        body = test_client.get("/health").json()

    assert body["vision_state"] == "LIVE"
    assert body["vision_updated_at"] == 10.0
    assert body["vision_error"] is None
    assert "objects" not in body


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
        json={"message": "生成计划：start patrol", "context": {"car": "online"}},
    )

    assert response.status_code == 200
    assert response.json()["plan"]["requires_confirmation"] is True
    assert response.json()["plan"]["steps"][1]["arguments"] == {"task": "camera"}


def test_chat_greeting_returns_reply_without_plan(tmp_path):
    planner = FakePlanner()
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    test_client = TestClient(
        create_app(settings=settings, planner=planner, control=FakeControl())
    )

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "你好", "context": {}},
    )

    assert response.status_code == 200
    assert response.json() == {"reply": "你好，我在。有什么可以帮你？"}
    assert planner.calls == []


def test_chat_enqueues_spoken_reply_when_enabled(tmp_path):
    test_client, control = client(tmp_path)

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "你好", "context": {}, "speech_enabled": True},
    )

    body = response.json()
    assert response.status_code == 200
    assert body["reply"] == "你好，我在。有什么可以帮你？"
    assert body["spoken_reply"] == "你好，我在。有什么可以帮你？"
    assert body["speech"]["state"] == "queued"
    assert control.calls[0][0:2] == ("speech", "你好，我在。有什么可以帮你？")


def test_chat_only_plans_when_explicitly_requested(tmp_path):
    planner = FakePlanner()
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    test_client = TestClient(
        create_app(settings=settings, planner=planner, control=FakeControl())
    )

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "帮我看看摄像头能做什么", "context": {}},
    )

    assert response.status_code == 200
    assert "plan" not in response.json()
    assert planner.calls == []


def test_chat_directly_starts_avoidance_with_dependencies(tmp_path):
    test_client, control = client(tmp_path)

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "启动自动避障功能", "context": {}},
    )

    assert response.status_code == 200
    body = response.json()
    assert body["reply"] == "正在完成启动前准备。"
    task_id = body["control_task"]["id"]
    ready = wait_for_control_state(test_client, task_id, "READY")
    assert ready["current_message"] == "准备就绪，等待启动。"
    assert control.calls == [
        ("health", None),
        ("start", "base"),
        ("start", "lidar"),
    ]

    started = test_client.post(
        "/api/v1/control-tasks/%s/start" % task_id,
        headers=auth(),
    )
    assert started.status_code == 200
    status = wait_for_control_state(test_client, task_id, "RUNNING")

    assert status["result"] == "自动避障功能正在运行。"
    assert control.calls == [
        ("health", None),
        ("start", "base"),
        ("start", "lidar"),
        ("start", "avoidance"),
    ]


def test_chat_directly_starts_follow_and_warning(tmp_path):
    test_client, control = client(tmp_path)

    follow = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "开启自动跟随", "context": {}},
    )
    warning = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "启动自动警卫", "context": {}},
    )

    wait_for_control_state(test_client, follow.json()["control_task"]["id"], "READY")
    wait_for_control_state(test_client, warning.json()["control_task"]["id"], "READY")
    assert ("start", "follow") not in control.calls
    assert ("start", "warning") not in control.calls


def test_persistent_feature_remains_running_until_stopped(tmp_path):
    test_client, control = client(tmp_path)
    created = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "enable follow", "context": {}},
    ).json()["control_task"]
    wait_for_control_state(test_client, created["id"], "READY")

    test_client.post(
        "/api/v1/control-tasks/%s/start" % created["id"],
        headers=auth(),
    )
    running = wait_for_control_state(test_client, created["id"], "RUNNING")
    time.sleep(0.05)

    assert running["state"] == "RUNNING"
    assert test_client.get(
        "/api/v1/control-tasks/%s" % created["id"], headers=auth()
    ).json()["state"] == "RUNNING"
    assert ("start", "follow") in control.calls


def test_natural_language_stop_synchronizes_running_feature_task(tmp_path):
    test_client, control = client(tmp_path)
    created = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "enable follow", "context": {}},
    ).json()["control_task"]
    wait_for_control_state(test_client, created["id"], "READY")
    test_client.post(
        "/api/v1/control-tasks/%s/start" % created["id"], headers=auth()
    )
    wait_for_control_state(test_client, created["id"], "RUNNING")

    stopped = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "disable follow", "context": {}},
    )

    assert stopped.status_code == 200
    assert test_client.get(
        "/api/v1/control-tasks/%s" % created["id"], headers=auth()
    ).json()["state"] == "STOPPED"
    assert ("stop", "follow") in control.calls


def test_duplicate_feature_start_reuses_active_control_task(tmp_path):
    test_client, _ = client(tmp_path)
    first = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "enable camera", "context": {}},
    ).json()["control_task"]
    wait_for_control_state(test_client, first["id"], "READY")

    second = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "start camera", "context": {}},
    ).json()["control_task"]

    assert second["id"] == first["id"]


@pytest.mark.parametrize(
    ("message", "task_name"),
    [
        ("enable base", "base"),
        ("enable lidar", "lidar"),
        ("enable avoidance", "avoidance"),
        ("enable follow", "follow"),
        ("enable warning", "warning"),
        ("enable camera", "camera"),
        ("enable hsv", "hsv"),
        ("enable color track", "color_track"),
    ],
)
def test_all_persistent_features_remain_running(tmp_path, message, task_name):
    test_client, control = client(tmp_path)
    created = test_client.post(
        "/api/v1/chat", headers=auth(), json={"message": message, "context": {}}
    ).json()["control_task"]
    wait_for_control_state(test_client, created["id"], "READY")

    test_client.post(
        "/api/v1/control-tasks/%s/start" % created["id"], headers=auth()
    )

    assert wait_for_control_state(test_client, created["id"], "RUNNING")["state"] == "RUNNING"
    assert ("start", task_name) in control.calls


def test_stop_all_synchronizes_every_active_control_task(tmp_path):
    test_client, control = client(tmp_path)
    task_ids = []
    for message in ("enable follow", "enable camera"):
        task = test_client.post(
            "/api/v1/chat", headers=auth(), json={"message": message, "context": {}}
        ).json()["control_task"]
        task_ids.append(task["id"])
        wait_for_control_state(test_client, task["id"], "READY")
        test_client.post(
            "/api/v1/control-tasks/%s/start" % task["id"], headers=auth()
        )
        wait_for_control_state(test_client, task["id"], "RUNNING")

    response = test_client.post(
        "/api/v1/chat", headers=auth(), json={"message": "stop all", "context": {}}
    )

    assert response.status_code == 200
    assert ("stop_all", None) in control.calls
    for task_id in task_ids:
        assert test_client.get(
            "/api/v1/control-tasks/%s" % task_id, headers=auth()
        ).json()["state"] == "STOPPED"


def test_stop_during_feature_start_cannot_restore_running_state(tmp_path):
    control = BlockingFeatureControl()
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    test_client = TestClient(
        create_app(settings=settings, planner=FakePlanner(), control=control)
    )
    created = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "enable follow", "context": {}},
    ).json()["control_task"]
    wait_for_control_state(test_client, created["id"], "READY")
    test_client.post(
        "/api/v1/control-tasks/%s/start" % created["id"], headers=auth()
    )
    assert control.feature_started.wait(timeout=1.0)

    test_client.post(
        "/api/v1/control-tasks/%s/stop" % created["id"], headers=auth()
    )
    control.release_feature.set()
    time.sleep(0.05)

    assert test_client.get(
        "/api/v1/control-tasks/%s" % created["id"], headers=auth()
    ).json()["state"] == "STOPPED"


def test_chat_executes_bounded_motion_and_forces_stop(tmp_path):
    test_client, control = client(tmp_path)

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "前进0.2秒", "context": {}},
    )

    assert response.status_code == 200
    body = response.json()
    task_id = body["control_task"]["id"]
    wait_for_control_state(test_client, task_id, "READY")
    assert control.calls == [("health", None), ("start", "base")]

    test_client.post(
        "/api/v1/control-tasks/%s/start" % task_id,
        headers=auth(),
    )
    import time
    for _ in range(50):
        status = test_client.get(
            "/api/v1/control-tasks/%s" % task_id,
            headers=auth(),
        ).json()
        if status["state"] == "COMPLETED":
            break
        time.sleep(0.01)
    assert status["result"] == "已完成前进并停止。"
    assert control.calls[-1][0:2] == ("move", "stop")


def test_failed_preparation_blocks_start_and_can_retry(tmp_path):
    control = FailingOnceControl()
    settings = Settings(
        jarvis_app_token="test-token",
        database_path=str(tmp_path / "jarvis.db"),
    )
    test_client = TestClient(
        create_app(settings=settings, planner=FakePlanner(), control=control)
    )

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "启动摄像头", "context": {}},
    )
    task_id = response.json()["control_task"]["id"]
    failed = wait_for_control_state(test_client, task_id, "PREPARATION_FAILED")

    blocked = test_client.post(
        "/api/v1/control-tasks/%s/start" % task_id,
        headers=auth(),
    )
    assert blocked.status_code == 409
    assert failed["current_message"] == "准备失败"

    retry = test_client.post(
        "/api/v1/control-tasks/%s/prepare" % task_id,
        headers=auth(),
    )
    assert retry.status_code == 200
    wait_for_control_state(test_client, task_id, "READY")
    assert ("start", "camera") not in control.calls


def test_chat_stop_command_stops_immediately(tmp_path):
    test_client, control = client(tmp_path)

    response = test_client.post(
        "/api/v1/chat",
        headers=auth(),
        json={"message": "停止移动", "context": {}},
    )

    assert response.json() == {"reply": "小车已停止。"}
    assert control.calls == [("move", "stop", 0.2, 0.65)]


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
        json={"message": "生成计划：start an unsafe unknown task", "context": {}},
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
