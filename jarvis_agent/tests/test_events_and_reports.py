from datetime import datetime, timedelta, timezone

from jarvis_agent.event_adapter import EventAdapter
from jarvis_agent.models import Action, DecisionType, MissionPlan, MissionStep, VisionEvent
from jarvis_agent.reporting import ReportService
from jarvis_agent.repository import Repository


def sample_plan():
    return MissionPlan(
        summary="Inspect parking lot",
        steps=[MissionStep(action=Action.START_TASK, arguments={"task": "camera"})],
        requires_confirmation=True,
    )


def sample_event(mission_id, **overrides):
    values = {
        "mission_id": mission_id,
        "source": "front_camera",
        "event_type": "obstacle",
        "label": "paper box",
        "confidence": 0.91,
        "position": "center aisle",
        "track_id": "track-001",
        "image_path": "/var/lib/jarvis/events/paper-box.jpg",
        "timestamp": datetime.now(timezone.utc),
        "metadata": {},
    }
    values.update(overrides)
    return VisionEvent(**values)


def test_low_confidence_event_is_ignored(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan())
    adapter = EventAdapter(repo, confidence_threshold=0.60)

    result = adapter.process(sample_event(mission.id, confidence=0.59))

    assert result.accepted is False
    assert result.reason == "LOW_CONFIDENCE"
    assert repo.list_timeline(mission.id) == []


def test_duplicate_event_inside_cooldown_is_ignored(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan())
    adapter = EventAdapter(repo, cooldown_seconds=15)
    event = sample_event(mission.id)

    first = adapter.process(event)
    duplicate = adapter.process(
        sample_event(
            mission.id,
            timestamp=event.timestamp + timedelta(seconds=10),
        )
    )

    assert first.accepted is True
    assert duplicate.accepted is False
    assert duplicate.reason == "DUPLICATE"


def test_different_track_id_is_accepted(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan())
    adapter = EventAdapter(repo, cooldown_seconds=15)
    event = sample_event(mission.id)

    adapter.process(event)
    result = adapter.process(
        sample_event(
            mission.id,
            track_id="track-002",
            timestamp=event.timestamp + timedelta(seconds=10),
        )
    )

    assert result.accepted is True
    assert [entry.kind for entry in repo.list_timeline(mission.id)].count(
        "vision_event"
    ) == 2


def test_risk_event_creates_pending_decision_timeline(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan())
    adapter = EventAdapter(repo)

    result = adapter.process(sample_event(mission.id, event_type="standing_water"))

    timeline = repo.list_timeline(mission.id)
    assert result.accepted is True
    assert result.severity == "HIGH"
    assert timeline[-1].kind == "decision_required"
    assert timeline[-1].metadata["decision"] == "pause_or_continue"


def test_report_contains_timeline_events_and_decisions(tmp_path):
    repo = Repository(tmp_path / "jarvis.db")
    repo.initialize()
    mission = repo.create_mission(sample_plan())
    adapter = EventAdapter(repo)
    event_result = adapter.process(sample_event(mission.id, label="paper box"))
    repo.save_decision(
        mission.id,
        event_result.decision_request.model_copy(
            update={"decision": DecisionType.CONTINUE, "note": "continue patrol"}
        ),
    )

    markdown = ReportService(repo).build_fallback_report(mission.id)

    assert "# Jarvis Patrol Report" in markdown
    assert "paper box" in markdown
    assert "User decision: continue" in markdown
