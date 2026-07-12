import secrets
import logging
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, Optional

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from jarvis_agent.config import Settings
from jarvis_agent.control_client import ControlClient
from jarvis_agent.deepseek import (
    DeepSeekPlanner,
    ModelResponseError,
    ModelTimeoutError,
    ModelUnavailableError,
)
from jarvis_agent.event_adapter import EventAdapter
from jarvis_agent.mission_engine import MissionEngine, MissionNotFoundError
from jarvis_agent.models import (
    ChatRequest,
    ChatResponse,
    DecisionType,
    DecisionRequest,
    MissionCreateRequest,
    MissionState,
    MissionPlan,
    MissionStep,
    Action,
    VisionEvent,
)
from jarvis_agent.reporting import ReportService
from jarvis_agent.repository import Repository
from jarvis_agent.validator import PlanValidationError, validate_plan

logger = logging.getLogger(__name__)


class ErrorBody(BaseModel):
    code: str
    message: str


def create_app(
    settings: Optional[Settings] = None,
    planner: Optional[Any] = None,
    control: Optional[Any] = None,
    repository: Optional[Repository] = None,
) -> FastAPI:
    settings = settings or Settings()
    repository = repository or Repository(Path(settings.database_path))
    repository.initialize()
    planner = planner or DeepSeekPlanner(settings)
    control = control or ControlClient(settings)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        repository.initialize()
        yield

    app = FastAPI(title="Jarvis Agent", version="0.1.0", lifespan=lifespan)
    engine = MissionEngine(repository, control)
    events = EventAdapter(
        repository, cooldown_seconds=settings.event_cooldown_seconds
    )
    reports = ReportService(repository)

    def require_auth(authorization: str = Header(default="")) -> None:
        expected = "Bearer %s" % settings.jarvis_app_token
        if not settings.jarvis_app_token or not secrets.compare_digest(
            authorization, expected
        ):
            raise _http_error(401, "UNAUTHORIZED", "Missing or invalid token")

    @app.exception_handler(HTTPException)
    async def http_error_handler(_, exc: HTTPException):
        if isinstance(exc.detail, dict) and "error" in exc.detail:
            return JSONResponse(status_code=exc.status_code, content=exc.detail)
        return JSONResponse(
            status_code=exc.status_code,
            content={"error": {"code": "HTTP_ERROR", "message": str(exc.detail)}},
        )

    @app.get("/health")
    def health():
        return {"status": "ok"}

    @app.post(
        "/api/v1/chat",
        response_model=ChatResponse,
        dependencies=[Depends(require_auth)],
    )
    async def chat(request: ChatRequest):
        try:
            plan = validate_plan(await planner.plan(request.message, request.context))
        except ModelTimeoutError as exc:
            raise _http_error(504, "MODEL_TIMEOUT", "Model request timed out") from exc
        except ModelUnavailableError as exc:
            raise _http_error(503, "MODEL_UNAVAILABLE", "Model is unavailable") from exc
        except (ModelResponseError, PlanValidationError) as exc:
            logger.warning("Model returned invalid plan; falling back to clarification", exc_info=exc)
            plan = _fallback_clarification_plan(request.message)
        return ChatResponse(reply="Plan ready for confirmation.", plan=plan)

    @app.post("/api/v1/missions", dependencies=[Depends(require_auth)])
    def create_mission(request: MissionCreateRequest):
        return repository.create_mission(request.plan)

    @app.get("/api/v1/missions/{mission_id}", dependencies=[Depends(require_auth)])
    def get_mission(mission_id: str):
        mission = repository.get_mission(mission_id)
        if mission is None:
            raise _http_error(404, "MISSION_NOT_FOUND", "Mission not found")
        return mission

    @app.post(
        "/api/v1/missions/{mission_id}/confirm",
        dependencies=[Depends(require_auth)],
    )
    async def confirm_mission(mission_id: str):
        try:
            return await engine.confirm(mission_id)
        except MissionNotFoundError as exc:
            raise _http_error(404, "MISSION_NOT_FOUND", "Mission not found") from exc

    @app.post(
        "/api/v1/missions/{mission_id}/cancel",
        dependencies=[Depends(require_auth)],
    )
    async def cancel_mission(mission_id: str):
        try:
            return await engine.cancel(mission_id)
        except MissionNotFoundError as exc:
            raise _http_error(404, "MISSION_NOT_FOUND", "Mission not found") from exc

    @app.get(
        "/api/v1/missions/{mission_id}/timeline",
        dependencies=[Depends(require_auth)],
    )
    def get_timeline(mission_id: str):
        return repository.list_timeline(mission_id)

    @app.post("/api/v1/vision-events", dependencies=[Depends(require_auth)])
    def post_vision_event(event: VisionEvent):
        result = events.process(event)
        return {
            "accepted": result.accepted,
            "reason": result.reason,
            "severity": result.severity,
            "event_id": result.persisted_event.id if result.persisted_event else None,
            "decision": result.decision_request,
        }

    @app.post(
        "/api/v1/missions/{mission_id}/decisions",
        dependencies=[Depends(require_auth)],
    )
    def submit_decision(mission_id: str, request: DecisionRequest):
        decision = repository.save_decision(mission_id, request)
        if request.decision is DecisionType.FINISH:
            reports.build_fallback_report(mission_id)
            repository.set_mission_state(mission_id, MissionState.COMPLETED)
        return decision

    @app.get(
        "/api/v1/missions/{mission_id}/report",
        dependencies=[Depends(require_auth)],
    )
    def get_report(mission_id: str):
        report = repository.get_report(mission_id)
        if report is None:
            markdown = reports.build_fallback_report(mission_id)
            report = repository.get_report(mission_id)
            if report is None:
                raise _http_error(500, "REPORT_FAILED", "Report was not created")
        return report

    return app


def _http_error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={"error": {"code": code, "message": message}},
    )


def _fallback_clarification_plan(message: str) -> MissionPlan:
    return MissionPlan(
        summary="Jarvis could not safely convert the request into an executable plan.",
        steps=[
            MissionStep(
                action=Action.ASK_USER,
                arguments={
                    "question": (
                        "我理解你的请求是：%s。为了安全执行，请换一种更明确的说法，"
                        "例如：检查状态、打开摄像头、开始避障、生成巡检报告。"
                    )
                    % message.strip()
                },
            )
        ],
        completion_criteria=["User provides a clearer patrol or control instruction."],
        requires_confirmation=False,
    )
