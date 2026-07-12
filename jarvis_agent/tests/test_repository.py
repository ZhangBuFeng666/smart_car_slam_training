from datetime import datetime, timedelta, timezone

from jarvis_agent.models import (
    Action,
    DecisionRequest,
    DecisionType,
    MissionPlan,
    MissionState,
    MissionStep,
    VisionEvent,
)
from jarvis_agent.repository import Repository


def sample_plan():
    return MissionPlan(
        summary="Inspect parking area",
        steps=[
            MissionStep(action=Action.CHECK_STATUS),
            MissionStep(action=Action.START_TASK, arguments={"task": "camera"}),
        ],
        requires_confirmation=True,
    )


def sample_event(mission_id, track_id="track-001"):
    return VisionEvent(
        mission_id=mission_id,
        source="front_camera",
        event_type="obstacle",
        label="paper box",
        confidence=0.91,
        position="center aisle",
        track_id=track_id,
        image_path="/var/lib/jarvis/events/paper-box.jpg",
        timestamp=datetime.now(timezone.utc),
        metadata={"zone": "parking-lot"},
    )


def test_initialize_creates_required_tables(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")

    repo.initialize()

    with repo.connect() as conn:
        rows = conn.execute(
            "select name from sqlite_master where type = 'table'"
        ).fetchall()
    assert {row["name"] for row in rows} >= {
        "missions",
        "timeline",
        "vision_events",
        "decisions",
        "reports",
    }


def test_mission_round_trips_with_plan(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    plan = sample_plan()

    mission = repo.create_mission(plan)
    loaded = repo.get_mission(mission.id)

    assert loaded is not None
    assert loaded.plan == plan
    assert loaded.state is MissionState.WAITING_CONFIRMATION
    assert loaded.created_at.utcoffset() is not None
    assert loaded.updated_at.utcoffset() is not None


def test_state_update_changes_mission_state(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan())

    updated = repo.set_mission_state(mission.id, MissionState.RUNNING)

    assert updated.state is MissionState.RUNNING
    assert repo.get_mission(mission.id).state is MissionState.RUNNING


def test_timeline_entries_return_in_insertion_order(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan())

    first = repo.append_timeline(mission.id, "system", "created")
    second = repo.append_timeline(mission.id, "control", "camera started")

    entries = repo.list_timeline(mission.id)

    assert [entry.id for entry in entries] == [first.id, second.id]
    assert [entry.message for entry in entries] == ["created", "camera started"]


def test_event_decision_and_report_round_trip(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan())
    event = sample_event(mission.id)

    saved_event = repo.save_event(event)
    recent = repo.find_recent_event(
        mission_id=mission.id,
        track_id=event.track_id,
        label=event.label,
        since=event.timestamp - timedelta(seconds=15),
    )
    decision = repo.save_decision(
        mission.id,
        DecisionRequest(
            decision=DecisionType.CONTINUE,
            event_id=saved_event.id,
            note="safe to continue",
        ),
    )
    report = repo.save_report(mission.id, "# Jarvis report")

    assert recent is not None
    assert recent.id == saved_event.id
    assert decision.decision is DecisionType.CONTINUE
    assert repo.get_report(mission.id).markdown == report.markdown
