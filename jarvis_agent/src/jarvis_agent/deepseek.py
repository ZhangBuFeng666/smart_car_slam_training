import json
from typing import Any, Dict

import httpx
from pydantic import ValidationError

from jarvis_agent.config import Settings
from jarvis_agent.models import Action, MissionPlan
from jarvis_agent.validator import ALLOWED_TASKS, PlanValidationError, validate_plan


class ModelUnavailableError(RuntimeError):
    pass


class ModelTimeoutError(RuntimeError):
    pass


class ModelResponseError(RuntimeError):
    pass


class DeepSeekPlanner:
    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    async def plan(self, message: str, context: Dict[str, Any]) -> MissionPlan:
        if not self.settings.deepseek_configured:
            raise ModelUnavailableError("DeepSeek API key is not configured")

        payload = {
            "model": self.settings.deepseek_model,
            "messages": [
                {"role": "system", "content": _system_prompt()},
                {
                    "role": "user",
                    "content": json.dumps(
                        {"message": message, "context": context},
                        ensure_ascii=False,
                    ),
                },
            ],
            "temperature": 0.1,
            "response_format": {"type": "json_object"},
        }
        response = await self._post_with_retry(payload)
        return _parse_plan_response(response)

    async def _post_with_retry(self, payload: Dict[str, Any]) -> httpx.Response:
        last_timeout = None
        for _ in range(2):
            try:
                async with httpx.AsyncClient(
                    base_url=self.settings.deepseek_base_url,
                    timeout=self.settings.request_timeout_seconds,
                ) as client:
                    response = await client.post(
                        "/chat/completions",
                        headers={
                            "Authorization": "Bearer %s"
                            % self.settings.deepseek_api_key.strip()
                        },
                        json=payload,
                    )
                    response.raise_for_status()
                    return response
            except httpx.TimeoutException as exc:
                last_timeout = exc
            except httpx.HTTPStatusError as exc:
                raise ModelUnavailableError("DeepSeek request failed") from exc
            except httpx.HTTPError as exc:
                raise ModelUnavailableError("DeepSeek request failed") from exc
        raise ModelTimeoutError("DeepSeek request timed out") from last_timeout


def _system_prompt() -> str:
    return (
        "You are Jarvis, a smart-car patrol planner. Return only JSON for a "
        "MissionPlan with keys summary, steps, completion_criteria, and "
        "requires_confirmation. Allowed actions: %s. Allowed START_TASK and "
        "STOP_TASK task values: %s. Never output move, wheel speed, shell, URL, "
        "or arbitrary ROS commands. State-changing actions require user "
        "confirmation."
        % (
            ", ".join(action.value for action in Action),
            ", ".join(ALLOWED_TASKS),
        )
    )


def _parse_plan_response(response: httpx.Response) -> MissionPlan:
    try:
        data = response.json()
        content = data["choices"][0]["message"]["content"]
        payload = json.loads(_strip_markdown_fence(content))
        plan = MissionPlan.model_validate(payload)
        return validate_plan(plan)
    except (KeyError, IndexError, TypeError, json.JSONDecodeError) as exc:
        raise ModelResponseError("DeepSeek returned malformed JSON") from exc
    except (ValidationError, PlanValidationError) as exc:
        raise ModelResponseError("DeepSeek returned an invalid plan") from exc


def _strip_markdown_fence(content: str) -> str:
    text = content.strip()
    if not text.startswith("```"):
        return text

    lines = text.splitlines()
    if len(lines) >= 3 and lines[0].startswith("```") and lines[-1].strip() == "```":
        return "\n".join(lines[1:-1]).strip()
    return text
