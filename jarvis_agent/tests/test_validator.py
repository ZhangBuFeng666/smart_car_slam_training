import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from jarvis_agent.models import (
    Action,
    ChatRequest,
    DecisionType,
    MissionPlan,
    MissionState,
    MissionStep,
    VisionEvent,
)
from jarvis_agent.validator import ALLOWED_TASKS, PlanValidationError, validate_plan


FIXTURES = Path(__file__).parent / "fixtures"


def plan_with(action, arguments=None, requires_confirmation=False):
    return MissionPlan(
        summary="Test plan",
        steps=[MissionStep(action=action, arguments=arguments or {})],
        requires_confirmation=requires_confirmation,
    )


def test_valid_whitelisted_start_task_is_accepted():
    plan = plan_with(Action.START_TASK, {"task": "camera"})

    validated = validate_plan(plan)

    assert validated.steps[0].arguments == {"task": "camera"}


@pytest.mark.parametrize("task", ["move", "unknown"])
def test_unknown_start_task_is_rejected(task):
    with pytest.raises(PlanValidationError, match="allowed task"):
        validate_plan(plan_with(Action.START_TASK, {"task": task}))


@pytest.mark.parametrize("step_count", [0, 13])
def test_plan_step_count_must_be_between_one_and_twelve(step_count):
    plan = MissionPlan(
        summary="Invalid size",
        steps=[MissionStep(action=Action.CHECK_STATUS) for _ in range(step_count)],
    )

    with pytest.raises(PlanValidationError, match="1 to 12"):
        validate_plan(plan)


def test_task_action_rejects_extra_argument():
    plan = plan_with(Action.START_TASK, {"task": "camera", "mode": "auto"})

    with pytest.raises(PlanValidationError, match="exactly one"):
        validate_plan(plan)


@pytest.mark.parametrize(
    "action", [Action.CHECK_STATUS, Action.STOP_ALL, Action.GENERATE_REPORT]
)
def test_argument_free_action_rejects_arguments(action):
    with pytest.raises(PlanValidationError, match="does not accept arguments"):
        validate_plan(plan_with(action, {"unexpected": "value"}))


@pytest.mark.parametrize(
    "arguments",
    [
        {"question": "See https://example.com"},
        {"event_type": "obstacle; stop", "label": "box"},
        {"question": "first\nsecond"},
        {"question": "run && stop"},
        {"event_type": "obstacle", "label": {"nested": ["$(shutdown)"]}},
    ],
)
def test_unsafe_strings_are_rejected_recursively(arguments):
    action = Action.ASK_USER if "question" in arguments else Action.RECORD_EVENT

    with pytest.raises(PlanValidationError, match="unsafe"):
        validate_plan(plan_with(action, arguments))


def test_safe_chinese_question_is_accepted():
    plan = plan_with(Action.ASK_USER, {"question": "是否继续巡检？请确认。"})

    validated = validate_plan(plan)

    assert validated.steps[0].arguments["question"] == "是否继续巡检？请确认。"


@pytest.mark.parametrize(
    "action", [Action.START_TASK, Action.STOP_TASK, Action.STOP_ALL]
)
def test_control_actions_force_confirmation_without_mutating_input(action):
    arguments = {"task": "camera"} if action != Action.STOP_ALL else {}
    plan = plan_with(action, arguments)

    validated = validate_plan(plan)

    assert validated is not plan
    assert validated.requires_confirmation is True
    assert plan.requires_confirmation is False


def test_check_status_does_not_force_confirmation():
    validated = validate_plan(plan_with(Action.CHECK_STATUS))

    assert validated.requires_confirmation is False


def test_existing_true_confirmation_is_preserved():
    validated = validate_plan(
        plan_with(Action.CHECK_STATUS, requires_confirmation=True)
    )

    assert validated.requires_confirmation is True


def test_vision_event_accepts_fixture_with_aware_timestamp():
    payload = json.loads((FIXTURES / "vision_event.json").read_text(encoding="utf-8"))

    event = VisionEvent.model_validate(payload)

    assert event.timestamp.utcoffset() is not None


@pytest.mark.parametrize("confidence", [-0.01, 1.01])
def test_vision_event_rejects_confidence_outside_unit_interval(confidence):
    payload = json.loads((FIXTURES / "vision_event.json").read_text(encoding="utf-8"))
    payload["confidence"] = confidence

    with pytest.raises(ValidationError):
        VisionEvent.model_validate(payload)


def test_vision_event_rejects_naive_timestamp():
    payload = json.loads((FIXTURES / "vision_event.json").read_text(encoding="utf-8"))
    payload["timestamp"] = "2026-01-15T10:30:00"

    with pytest.raises(ValidationError, match="timezone"):
        VisionEvent.model_validate(payload)


def test_external_models_reject_unknown_fields():
    with pytest.raises(ValidationError, match="extra_forbidden"):
        ChatRequest(message="status", unexpected=True)


def test_models_match_api_contract():
    contract = json.loads(
        (Path(__file__).parents[2] / "contracts" / "jarvis-api-v1.json").read_text(
            encoding="utf-8"
        )
    )

    assert [action.value for action in Action] == contract["actions"]
    assert [state.value for state in MissionState] == contract["mission_states"]
    assert [decision.value for decision in DecisionType] == contract["decision_types"]
    assert list(ALLOWED_TASKS) == contract["allowed_tasks"]


def test_nonblank_fields_are_rejected():
    with pytest.raises(ValidationError):
        MissionPlan(summary="   ", steps=[])

    with pytest.raises(ValidationError):
        ChatRequest(message="\t")


@pytest.mark.parametrize(
    ("action", "arguments"),
    [
        (Action.START_TASK, {}),
        (Action.STOP_TASK, {"task": 1}),
        (Action.RECORD_EVENT, {"event_type": "obstacle", "extra": "value"}),
        (Action.RECORD_EVENT, {"event_type": 1}),
        (Action.ASK_USER, {"question": "   "}),
        (Action.ASK_USER, {"question": "continue", "extra": "value"}),
    ],
)
def test_action_argument_shapes_are_enforced(action, arguments):
    with pytest.raises(PlanValidationError):
        validate_plan(plan_with(action, arguments))
