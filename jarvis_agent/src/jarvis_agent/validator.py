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
    "map_gmapping",
    "map_display",
    "map_save",
    "nav_laser",
    "nav_display",
    "nav_dwa",
    "nav_teb",
)


class PlanValidationError(ValueError):
    pass


_CONTROL_ACTIONS = {
    Action.START_TASK,
    Action.STOP_TASK,
    Action.STOP_ALL,
    Action.SET_INITIAL_POSE,
    Action.SET_NAV_GOAL,
}
_ARGUMENT_FREE_ACTIONS = {
    Action.CHECK_STATUS,
    Action.STOP_ALL,
    Action.GENERATE_REPORT,
}
_NAV_POSE_ACTIONS = {Action.SET_INITIAL_POSE, Action.SET_NAV_GOAL}
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


def _validate_nav_pose_action(step: MissionStep) -> None:
    if set(step.arguments) != {"x", "y", "yaw"}:
        raise PlanValidationError(
            "%s requires x, y, and yaw arguments" % step.action.value
        )
    for key in ("x", "y", "yaw"):
        value = step.arguments[key]
        if not isinstance(value, (int, float)):
            raise PlanValidationError("%s must be numeric" % key)


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
    elif step.action in _NAV_POSE_ACTIONS:
        _validate_nav_pose_action(step)


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
