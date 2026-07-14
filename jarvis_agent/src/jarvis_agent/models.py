from enum import Enum
from typing import Any, Dict, List, Optional

from pydantic import AwareDatetime, BaseModel, ConfigDict, Field, field_validator


class Action(str, Enum):
    CHECK_STATUS = "CHECK_STATUS"
    START_TASK = "START_TASK"
    STOP_TASK = "STOP_TASK"
    STOP_ALL = "STOP_ALL"
    RECORD_EVENT = "RECORD_EVENT"
    ASK_USER = "ASK_USER"
    GENERATE_REPORT = "GENERATE_REPORT"


class MissionState(str, Enum):
    DRAFT = "DRAFT"
    WAITING_CONFIRMATION = "WAITING_CONFIRMATION"
    RUNNING = "RUNNING"
    PAUSED = "PAUSED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    COMPLETED = "COMPLETED"


class ControlTaskState(str, Enum):
    DRAFT = "DRAFT"
    PREPARING = "PREPARING"
    READY = "READY"
    PREPARATION_FAILED = "PREPARATION_FAILED"
    STARTING = "STARTING"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    STOPPED = "STOPPED"
    FAILED = "FAILED"


class DecisionType(str, Enum):
    CONTINUE = "continue"
    IGNORE = "ignore"
    PAUSE = "pause"
    FINISH = "finish"


class ExternalModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class MissionStep(ExternalModel):
    action: Action
    arguments: Dict[str, Any] = Field(default_factory=dict)


class MissionPlan(ExternalModel):
    summary: str
    steps: List[MissionStep]
    completion_criteria: List[str] = Field(default_factory=list)
    requires_confirmation: bool = False

    @field_validator("summary")
    @classmethod
    def summary_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("summary must not be blank")
        return value


class ChatRequest(ExternalModel):
    message: str
    context: Dict[str, Any] = Field(default_factory=dict)
    speech_enabled: bool = False

    @field_validator("message")
    @classmethod
    def message_must_not_be_blank(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("message must not be blank")
        return value


class AssistantReply(ExternalModel):
    reply: str
    spoken_reply: str


class ControlTaskView(ExternalModel):
    id: str
    title: str
    kind: str
    state: ControlTaskState
    steps: List[str]
    completed_steps: int = 0
    current_message: str
    current_value: float = 0.0
    target_value: float = 0.0
    unit: str = ""
    result: Optional[str] = None


class ChatResponse(ExternalModel):
    reply: str
    spoken_reply: Optional[str] = None
    speech: Optional["SpeechStatus"] = None
    plan: Optional[MissionPlan] = None
    control_task: Optional[ControlTaskView] = None


class SpeechStatus(ExternalModel):
    state: str
    request_id: Optional[str] = None


class MissionCreateRequest(ExternalModel):
    plan: MissionPlan


class DecisionRequest(ExternalModel):
    decision: DecisionType
    event_id: Optional[str] = None
    note: Optional[str] = None


class VisionEvent(ExternalModel):
    mission_id: str
    source: str
    event_type: str
    label: str
    confidence: float = Field(ge=0.0, le=1.0)
    position: str
    track_id: str
    image_path: str
    timestamp: AwareDatetime
    metadata: Dict[str, Any] = Field(default_factory=dict)


class TimelineEntry(ExternalModel):
    id: str
    mission_id: str
    timestamp: AwareDatetime
    kind: str
    message: str
    metadata: Dict[str, Any] = Field(default_factory=dict)


class MissionView(ExternalModel):
    id: str
    state: MissionState
    plan: MissionPlan
    created_at: AwareDatetime
    updated_at: AwareDatetime
    pending_decision: Optional[DecisionRequest] = None


class ReportView(ExternalModel):
    mission_id: str
    markdown: str
    created_at: AwareDatetime
