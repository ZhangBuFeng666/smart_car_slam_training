from dataclasses import dataclass
from datetime import timedelta
from typing import Optional

from jarvis_agent.models import DecisionRequest, DecisionType, VisionEvent
from jarvis_agent.repository import PersistedVisionEvent, Repository


SEVERITY_BY_EVENT_TYPE = {
    "standing_water": "HIGH",
    "obstacle": "MEDIUM",
    "illegal_parking": "MEDIUM",
    "pavement_defect": "MEDIUM",
}

_DECISION_EVENT_TYPES = {
    "obstacle",
    "standing_water",
    "illegal_parking",
    "pavement_defect",
}


@dataclass(frozen=True)
class EventProcessResult:
    accepted: bool
    reason: str
    severity: Optional[str] = None
    persisted_event: Optional[PersistedVisionEvent] = None
    decision_request: Optional[DecisionRequest] = None


class EventAdapter:
    def __init__(
        self,
        repository: Repository,
        confidence_threshold: float = 0.60,
        cooldown_seconds: int = 15,
    ) -> None:
        self.repository = repository
        self.confidence_threshold = confidence_threshold
        self.cooldown_seconds = cooldown_seconds

    def process(self, event: VisionEvent) -> EventProcessResult:
        if event.confidence < self.confidence_threshold:
            return EventProcessResult(accepted=False, reason="LOW_CONFIDENCE")

        recent = self.repository.find_recent_event(
            mission_id=event.mission_id,
            track_id=event.track_id,
            label=event.label,
            since=event.timestamp - timedelta(seconds=self.cooldown_seconds),
        )
        if recent is not None:
            return EventProcessResult(accepted=False, reason="DUPLICATE")

        persisted = self.repository.save_event(event)
        severity = SEVERITY_BY_EVENT_TYPE.get(event.event_type, "LOW")
        self.repository.append_timeline(
            event.mission_id,
            "vision_event",
            "%s detected: %s" % (event.event_type, event.label),
            {
                "event_id": persisted.id,
                "severity": severity,
                "confidence": event.confidence,
                "image_path": event.image_path,
            },
        )

        decision = None
        if event.event_type in _DECISION_EVENT_TYPES:
            decision = DecisionRequest(
                decision=DecisionType.PAUSE,
                event_id=persisted.id,
                note="pause_or_continue",
            )
            self.repository.append_timeline(
                event.mission_id,
                "decision_required",
                "User decision required for %s" % event.label,
                {
                    "event_id": persisted.id,
                    "decision": "pause_or_continue",
                    "severity": severity,
                },
            )

        return EventProcessResult(
            accepted=True,
            reason="ACCEPTED",
            severity=severity,
            persisted_event=persisted,
            decision_request=decision,
        )
