import secrets
import logging
import asyncio
import re
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
        if _is_task_request(request.message):
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

        motion_command = _direct_motion_command(request.message)
        if motion_command is not None:
            direction, label, duration = motion_command
            if direction == "stop":
                await control.move("stop")
                return ChatResponse(reply="小车已停止。", plan=None)
            try:
                deadline = asyncio.get_running_loop().time() + duration
                while True:
                    await control.move(direction)
                    remaining = deadline - asyncio.get_running_loop().time()
                    if remaining <= 0:
                        break
                    await asyncio.sleep(min(0.15, remaining))
                return ChatResponse(reply="已完成%s并停止。" % label, plan=None)
            except Exception as exc:
                logger.warning("Direct motion command failed", exc_info=exc)
                return ChatResponse(reply="小车%s失败，请检查控制服务。" % label, plan=None)
            finally:
                try:
                    await control.move("stop")
                except Exception:
                    logger.exception("Failed to stop after direct motion command")

        direct_command = _direct_task_command(request.message)
        if direct_command is not None:
            task, label, start = direct_command
            try:
                if start:
                    if task in {"avoidance", "follow", "warning"}:
                        await control.start_task("base")
                        await control.start_task("lidar")
                    await control.start_task(task)
                    reply = "已成功开启%s功能。" % label
                else:
                    await control.stop_task(task)
                    reply = "已成功关闭%s功能。" % label
                return ChatResponse(reply=reply, plan=None)
            except Exception as exc:
                logger.warning("Direct control command failed", exc_info=exc)
                return ChatResponse(reply="%s功能操作失败，请检查小车服务状态。" % label, plan=None)

        try:
            reply = await planner.reply(request.message, request.context)
        except (AttributeError, ModelTimeoutError, ModelUnavailableError, ModelResponseError):
            reply = _casual_reply(request.message)
        return ChatResponse(reply=reply, plan=None)

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


def _is_task_request(message: str) -> bool:
    text = message.strip().lower()
    return "生成计划" in text or "create plan" in text


def _direct_task_command(message: str):
    text = message.strip().lower()
    start = any(word in text for word in ("启动", "开启", "打开", "start", "enable"))
    stop = any(word in text for word in ("停止", "关闭", "stop", "disable"))
    if not start and not stop:
        return None

    features = (
        (("自动避障", "避障", "avoidance"), "avoidance", "自动避障"),
        (("自动跟随", "跟随", "follow"), "follow", "自动跟随"),
        (("自动警卫", "自动警戒", "警卫", "警戒", "warning"), "warning", "自动警卫"),
        (("颜色追踪", "颜色跟踪", "color track"), "color_track", "颜色追踪"),
        (("颜色识别", "hsv"), "hsv", "颜色识别"),
        (("摄像头", "相机", "camera"), "camera", "摄像头"),
        (("激光雷达", "雷达", "lidar"), "lidar", "激光雷达"),
        (("底盘", "base"), "base", "底盘"),
    )
    for aliases, task, label in features:
        if any(alias in text for alias in aliases):
            return task, label, start and not stop
    return None


def _direct_motion_command(message: str):
    text = message.strip().lower()
    if any(word in text for word in ("停止移动", "立即停止", "停车", "stop moving")):
        return "stop", "停止", 0.0

    motions = (
        (("向前", "前进", "forward"), "front", "前进"),
        (("向后", "后退", "backward"), "back", "后退"),
        (("左移", "向左移动", "move left"), "left", "左移"),
        (("右移", "向右移动", "move right"), "right", "右移"),
        (("左转", "turn left"), "turn_left", "左转"),
        (("右转", "turn right"), "turn_right", "右转"),
    )
    for aliases, direction, label in motions:
        if any(alias in text for alias in aliases):
            match = re.search(r"(\d+(?:\.\d+)?)\s*(?:秒|s|seconds?)", text)
            duration = float(match.group(1)) if match else 0.8
            return direction, label, max(0.2, min(duration, 3.0))
    return None


def _casual_reply(message: str) -> str:
    text = message.strip().lower()
    if text in {"你好", "您好", "嗨", "hello", "hi", "在吗"}:
        return "你好，我在。有什么可以帮你？"
    if "你是谁" in text or "叫什么" in text:
        return "我是贾维斯，小车的多模态巡检助手。"
    return "我在听。你可以直接告诉我想聊什么，或者给我一个具体的巡检任务。"
