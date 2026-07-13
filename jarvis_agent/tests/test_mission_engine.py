import pytest

from jarvis_agent.models import Action, MissionPlan, MissionState, MissionStep
from jarvis_agent.mission_engine import MissionEngine


class FakeControl:
    def __init__(self):
        self.calls = []
        self.fail_on = None

    async def status(self):
        self.calls.append(("status", None))
        if self.fail_on == "status":
            raise RuntimeError("status failed")
        return {"ok": True}

    async def start_task(self, task):
        self.calls.append(("start", task))
        if self.fail_on == "start":
            raise RuntimeError("start failed")
        return {"started": task}

    async def stop_task(self, task):
        self.calls.append(("stop", task))
        return {"stopped": task}

    async def stop_all(self):
        self.calls.append(("stop_all", None))
        return {"stopped": "all"}

    async def set_initial_pose(self, x, y, yaw):
        self.calls.append(("initial_pose", (x, y, yaw)))
        return {"published": True}

    async def set_nav_goal(self, x, y, yaw):
        self.calls.append(("nav_goal", (x, y, yaw)))
        return {"published": True}


def plan_with(*steps):
    return MissionPlan(
        summary="Test mission",
        steps=[MissionStep(action=action, arguments=args) for action, args in steps],
        requires_confirmation=True,
    )


@pytest.fixture
def repo(tmp_path):
    from jarvis_agent.repository import Repository

    repository = Repository(tmp_path / "jarvis.db")
    repository.initialize()
    return repository


@pytest.mark.asyncio
async def test_confirmation_executes_each_control_step_once(repo):
    control = FakeControl()
    mission = repo.create_mission(
        plan_with(
            (Action.CHECK_STATUS, {}),
            (Action.START_TASK, {"task": "camera"}),
        )
    )
    engine = MissionEngine(repo, control)

    result = await engine.confirm(mission.id)
    second = await engine.confirm(mission.id)

    assert control.calls == [("status", None), ("start", "camera")]
    assert result.state is MissionState.RUNNING
    assert second.state is MissionState.RUNNING


@pytest.mark.asyncio
async def test_control_failure_pauses_mission(repo):
    control = FakeControl()
    control.fail_on = "start"
    mission = repo.create_mission(
        plan_with(
            (Action.CHECK_STATUS, {}),
            (Action.START_TASK, {"task": "camera"}),
        )
    )
    engine = MissionEngine(repo, control)

    result = await engine.confirm(mission.id)

    assert result.state is MissionState.PAUSED
    assert repo.get_mission(mission.id).state is MissionState.PAUSED
    assert any(entry.kind == "failure" for entry in repo.list_timeline(mission.id))


@pytest.mark.asyncio
async def test_cancel_stops_all_and_marks_cancelled(repo):
    control = FakeControl()
    mission = repo.create_mission(
        plan_with((Action.START_TASK, {"task": "camera"}))
    )
    engine = MissionEngine(repo, control)

    result = await engine.cancel(mission.id)

    assert control.calls == [("stop_all", None)]
    assert result.state is MissionState.CANCELLED


@pytest.mark.asyncio
async def test_internal_actions_do_not_call_control_service(repo):
    control = FakeControl()
    mission = repo.create_mission(
        plan_with(
            (Action.RECORD_EVENT, {"event_type": "obstacle", "label": "box"}),
            (Action.ASK_USER, {"question": "Continue?"}),
            (Action.GENERATE_REPORT, {}),
        )
    )
    engine = MissionEngine(repo, control)

    result = await engine.confirm(mission.id)

    assert control.calls == []
    assert result.state is MissionState.RUNNING
    assert [entry.kind for entry in repo.list_timeline(mission.id)] == [
        "event",
        "question",
        "report_deferred",
        "running",
    ]


@pytest.mark.asyncio
async def test_navigation_pose_actions_call_control_service(repo):
    control = FakeControl()
    mission = repo.create_mission(
        plan_with(
            (Action.SET_INITIAL_POSE, {"x": 0.0, "y": 0.0, "yaw": 0.0}),
            (Action.SET_NAV_GOAL, {"x": 2.0, "y": 1.0, "yaw": 1.57}),
        )
    )
    engine = MissionEngine(repo, control)

    await engine.confirm(mission.id)

    assert control.calls == [
        ("initial_pose", (0.0, 0.0, 0.0)),
        ("nav_goal", (2.0, 1.0, 1.57)),
    ]
