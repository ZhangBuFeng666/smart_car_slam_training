import json
import sqlite3
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional
from uuid import uuid4

from jarvis_agent.models import (
    DecisionRequest,
    DecisionType,
    MissionPlan,
    MissionState,
    MissionView,
    ReportView,
    TimelineEntry,
    VisionEvent,
)


@dataclass(frozen=True)
class PersistedVisionEvent:
    id: str
    event: VisionEvent


@dataclass(frozen=True)
class DecisionRecord:
    id: str
    mission_id: str
    decision: DecisionType
    event_id: Optional[str]
    note: Optional[str]
    created_at: datetime


class Repository:
    def __init__(self, database_path: Path) -> None:
        self.database_path = Path(database_path)

    def connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(str(self.database_path))
        conn.row_factory = sqlite3.Row
        conn.execute("pragma foreign_keys = on")
        return conn

    def initialize(self) -> None:
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as conn:
            conn.executescript(
                """
                create table if not exists missions (
                    id text primary key,
                    state text not null,
                    plan_json text not null,
                    created_at text not null,
                    updated_at text not null
                );

                create table if not exists timeline (
                    id text primary key,
                    mission_id text not null references missions(id) on delete cascade,
                    timestamp text not null,
                    kind text not null,
                    message text not null,
                    metadata_json text not null
                );

                create table if not exists vision_events (
                    id text primary key,
                    mission_id text not null references missions(id) on delete cascade,
                    source text not null,
                    event_type text not null,
                    label text not null,
                    confidence real not null,
                    position text not null,
                    track_id text not null,
                    image_path text not null,
                    timestamp text not null,
                    metadata_json text not null
                );

                create table if not exists decisions (
                    id text primary key,
                    mission_id text not null references missions(id) on delete cascade,
                    event_id text references vision_events(id) on delete set null,
                    decision text not null,
                    note text,
                    created_at text not null
                );

                create table if not exists reports (
                    mission_id text primary key references missions(id) on delete cascade,
                    markdown text not null,
                    created_at text not null
                );

                create index if not exists idx_timeline_mission_timestamp
                    on timeline(mission_id, timestamp);
                create index if not exists idx_events_recent
                    on vision_events(mission_id, track_id, label, timestamp);
                """
            )

    def create_mission(self, plan: MissionPlan) -> MissionView:
        now = _now()
        mission_id = _new_id("mission")
        state = MissionState.WAITING_CONFIRMATION
        with self.connect() as conn:
            conn.execute(
                """
                insert into missions(id, state, plan_json, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                """,
                (
                    mission_id,
                    state.value,
                    _to_json(plan),
                    _format_dt(now),
                    _format_dt(now),
                ),
            )
        return MissionView(
            id=mission_id,
            state=state,
            plan=plan,
            created_at=now,
            updated_at=now,
        )

    def get_mission(self, mission_id: str) -> Optional[MissionView]:
        with self.connect() as conn:
            row = conn.execute(
                "select * from missions where id = ?", (mission_id,)
            ).fetchone()
        if row is None:
            return None
        return self._mission_from_row(row)

    def set_mission_state(self, mission_id: str, state: MissionState) -> MissionView:
        now = _now()
        with self.connect() as conn:
            conn.execute(
                "update missions set state = ?, updated_at = ? where id = ?",
                (state.value, _format_dt(now), mission_id),
            )
        mission = self.get_mission(mission_id)
        if mission is None:
            raise KeyError("mission not found: %s" % mission_id)
        return mission

    def append_timeline(
        self,
        mission_id: str,
        kind: str,
        message: str,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> TimelineEntry:
        entry = TimelineEntry(
            id=_new_id("timeline"),
            mission_id=mission_id,
            timestamp=_now(),
            kind=kind,
            message=message,
            metadata=metadata or {},
        )
        with self.connect() as conn:
            conn.execute(
                """
                insert into timeline(id, mission_id, timestamp, kind, message, metadata_json)
                values (?, ?, ?, ?, ?, ?)
                """,
                (
                    entry.id,
                    entry.mission_id,
                    _format_dt(entry.timestamp),
                    entry.kind,
                    entry.message,
                    json.dumps(entry.metadata, ensure_ascii=False),
                ),
            )
        return entry

    def list_timeline(self, mission_id: str) -> List[TimelineEntry]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                select * from timeline
                where mission_id = ?
                order by timestamp asc, rowid asc
                """,
                (mission_id,),
            ).fetchall()
        return [
            TimelineEntry(
                id=row["id"],
                mission_id=row["mission_id"],
                timestamp=_parse_dt(row["timestamp"]),
                kind=row["kind"],
                message=row["message"],
                metadata=json.loads(row["metadata_json"]),
            )
            for row in rows
        ]

    def save_event(self, event: VisionEvent) -> PersistedVisionEvent:
        event_id = _new_id("event")
        with self.connect() as conn:
            conn.execute(
                """
                insert into vision_events(
                    id, mission_id, source, event_type, label, confidence, position,
                    track_id, image_path, timestamp, metadata_json
                )
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event_id,
                    event.mission_id,
                    event.source,
                    event.event_type,
                    event.label,
                    event.confidence,
                    event.position,
                    event.track_id,
                    event.image_path,
                    _format_dt(event.timestamp),
                    json.dumps(event.metadata, ensure_ascii=False),
                ),
            )
        return PersistedVisionEvent(id=event_id, event=event)

    def find_recent_event(
        self,
        mission_id: str,
        track_id: str,
        label: str,
        since: datetime,
    ) -> Optional[PersistedVisionEvent]:
        with self.connect() as conn:
            row = conn.execute(
                """
                select * from vision_events
                where mission_id = ? and track_id = ? and label = ? and timestamp >= ?
                order by timestamp desc, rowid desc
                limit 1
                """,
                (mission_id, track_id, label, _format_dt(since)),
            ).fetchone()
        if row is None:
            return None
        return _event_from_row(row)

    def save_decision(
        self, mission_id: str, decision: DecisionRequest
    ) -> DecisionRecord:
        created_at = _now()
        record = DecisionRecord(
            id=_new_id("decision"),
            mission_id=mission_id,
            decision=decision.decision,
            event_id=decision.event_id,
            note=decision.note,
            created_at=created_at,
        )
        with self.connect() as conn:
            conn.execute(
                """
                insert into decisions(id, mission_id, event_id, decision, note, created_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                (
                    record.id,
                    record.mission_id,
                    record.event_id,
                    record.decision.value,
                    record.note,
                    _format_dt(record.created_at),
                ),
            )
        return record

    def list_decisions(self, mission_id: str) -> List[DecisionRecord]:
        with self.connect() as conn:
            rows = conn.execute(
                """
                select * from decisions
                where mission_id = ?
                order by created_at asc, rowid asc
                """,
                (mission_id,),
            ).fetchall()
        return [
            DecisionRecord(
                id=row["id"],
                mission_id=row["mission_id"],
                decision=DecisionType(row["decision"]),
                event_id=row["event_id"],
                note=row["note"],
                created_at=_parse_dt(row["created_at"]),
            )
            for row in rows
        ]

    def save_report(self, mission_id: str, markdown: str) -> ReportView:
        report = ReportView(
            mission_id=mission_id,
            markdown=markdown,
            created_at=_now(),
        )
        with self.connect() as conn:
            conn.execute(
                """
                insert into reports(mission_id, markdown, created_at)
                values (?, ?, ?)
                on conflict(mission_id) do update set
                    markdown = excluded.markdown,
                    created_at = excluded.created_at
                """,
                (mission_id, markdown, _format_dt(report.created_at)),
            )
        return report

    def get_report(self, mission_id: str) -> Optional[ReportView]:
        with self.connect() as conn:
            row = conn.execute(
                "select * from reports where mission_id = ?", (mission_id,)
            ).fetchone()
        if row is None:
            return None
        return ReportView(
            mission_id=row["mission_id"],
            markdown=row["markdown"],
            created_at=_parse_dt(row["created_at"]),
        )

    def _mission_from_row(self, row: sqlite3.Row) -> MissionView:
        return MissionView(
            id=row["id"],
            state=MissionState(row["state"]),
            plan=MissionPlan.model_validate(json.loads(row["plan_json"])),
            created_at=_parse_dt(row["created_at"]),
            updated_at=_parse_dt(row["updated_at"]),
        )


def _new_id(prefix: str) -> str:
    return "%s-%s" % (prefix, uuid4().hex)


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _format_dt(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("datetime must be timezone-aware")
    return value.astimezone(timezone.utc).isoformat()


def _parse_dt(value: str) -> datetime:
    return datetime.fromisoformat(value)


def _to_json(value: Any) -> str:
    return json.dumps(value.model_dump(mode="json"), ensure_ascii=False)


def _event_from_row(row: sqlite3.Row) -> PersistedVisionEvent:
    event = VisionEvent(
        mission_id=row["mission_id"],
        source=row["source"],
        event_type=row["event_type"],
        label=row["label"],
        confidence=row["confidence"],
        position=row["position"],
        track_id=row["track_id"],
        image_path=row["image_path"],
        timestamp=_parse_dt(row["timestamp"]),
        metadata=json.loads(row["metadata_json"]),
    )
    return PersistedVisionEvent(id=row["id"], event=event)
