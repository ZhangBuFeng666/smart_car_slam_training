from typing import Any, Dict, Iterable

from jarvis_agent.models import Action, MissionPlan, MissionStep


ALLOWED_TASKS = (
    "base",
    "lidar",
    "avoidance",
    "follow",
    "warning",
    "camera",
    "hsv",
    "color_track",
)


class PlanValidationError(ValueError):
    pass


_CONTROL_ACTIONS = {Action.START_TASK, Action.STOP_TASK, Action.STOP_ALL}
_ARGUMENT_FREE_ACTIONS = {
    Action.CHECK_STATUS,
    Action.STOP_ALL,
    Action.GENERATE_REPORT,
}
_UNSAFE_STRING_MARKERS = ("http://", "https://", "\n", "\r", ";", "&&", "||", "`", "$(")


def _iter_strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for key, nested_value in value.items():
            if isinstance(key, str):
                yield key
            yield from _iter_strings(nested_value)
    elif isinstance(value, (list, tuple, set, frozenset)):
        for nested_value in value:
            yield from _iter_strings(nested_value)


def _reject_unsafe_strings(arguments: Dict[str, Any]) -> None:
    for value in _iter_strings(arguments):
        normalized = value.casefold()
        if any(marker in normalized for marker in _UNSAFE_STRING_MARKERS):
            raise PlanValidationError("plan arguments contain an unsafe string")


def _validate_task_action(step: MissionStep) -> None:
    if set(step.arguments) != {"task"}:
        raise PlanValidationError(
            "%s requires exactly one task argument" % step.action.value
        )
    task = step.arguments["task"]
    if not isinstance(task, str) or task not in ALLOWED_TASKS:
        raise PlanValidationError("task must be an allowed task")


def _validate_record_event(arguments: Dict[str, Any]) -> None:
    if not arguments:
        return
    if not set(arguments).issubset({"event_type", "label"}):
        raise PlanValidationError("RECORD_EVENT accepts only event_type and label")
    if any(not isinstance(value, str) for value in arguments.values()):
        raise PlanValidationError("RECORD_EVENT arguments must be strings")


def _validate_ask_user(arguments: Dict[str, Any]) -> None:
    if not arguments:
        return
    if set(arguments) != {"question"}:
        raise PlanValidationError("ASK_USER accepts only a question")
    question = arguments["question"]
    if not isinstance(question, str) or not question.strip():
        raise PlanValidationError("ASK_USER question must be a nonblank string")


def _validate_step(step: MissionStep) -> None:
    _reject_unsafe_strings(step.arguments)

    if step.action in (Action.START_TASK, Action.STOP_TASK):
        _validate_task_action(step)
    elif step.action in _ARGUMENT_FREE_ACTIONS:
        if step.arguments:
            raise PlanValidationError(
                "%s does not accept arguments" % step.action.value
            )
    elif step.action == Action.RECORD_EVENT:
        _validate_record_event(step.arguments)
    elif step.action == Action.ASK_USER:
        _validate_ask_user(step.arguments)


def validate_plan(plan: MissionPlan) -> MissionPlan:
    if not 1 <= len(plan.steps) <= 12:
        raise PlanValidationError("plan must contain 1 to 12 steps")

    for step in plan.steps:
        _validate_step(step)

    requires_confirmation = plan.requires_confirmation or any(
        step.action in _CONTROL_ACTIONS for step in plan.steps
    )
    return plan.model_copy(
        deep=True, update={"requires_confirmation": requires_confirmation}
    )
