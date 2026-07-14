"""Vision incident engine for fire (and future dwell) alerts.

Runs after YOLO + IoUTracker each frame:

  detections (with track_id)
    → FireRules (confidence + consecutive frames + cooldown)
    → draw box on inference frame → JPEG under incidents_dir
    → append to in-memory list (+ optional index.jsonl)

Deploy next to vision_service.py on Jetson:
    /home/jetson/icar_vision/incident_engine.py
"""

from __future__ import annotations

import json
import os
import threading
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from typing import Dict, Iterable, List, Optional, Sequence, Tuple


Box = Tuple[float, float, float, float]
NormBox = Dict[str, float]


def _unix_now() -> float:
    return time.time()


def _iso_local(ts: Optional[float] = None) -> str:
    moment = datetime.fromtimestamp(ts if ts is not None else _unix_now()).astimezone()
    return moment.isoformat(timespec="seconds")


def _as_box(value) -> Box:
    if isinstance(value, dict):
        return (
            float(value["left"]),
            float(value["top"]),
            float(value["right"]),
            float(value["bottom"]),
        )
    if len(value) != 4:
        raise ValueError("box must be (x1, y1, x2, y2) or normalized dict")
    return (float(value[0]), float(value[1]), float(value[2]), float(value[3]))


def _normalize_box(box: Box, frame_size: Tuple[int, int]) -> NormBox:
    width, height = frame_size
    left, top, right, bottom = box
    return {
        "left": round(max(0.0, min(1.0, left / max(1, width))), 4),
        "top": round(max(0.0, min(1.0, top / max(1, height))), 4),
        "right": round(max(0.0, min(1.0, right / max(1, width))), 4),
        "bottom": round(max(0.0, min(1.0, bottom / max(1, height))), 4),
    }


def _denormalize_box(box: NormBox, frame_size: Tuple[int, int]) -> Box:
    width, height = frame_size
    return (
        float(box["left"]) * width,
        float(box["top"]) * height,
        float(box["right"]) * width,
        float(box["bottom"]) * height,
    )


def _frame_size_of(detection, frame=None) -> Tuple[int, int]:
    value = getattr(detection, "frame_size", None) or getattr(detection, "image_size", None)
    if value is not None:
        return (int(value[0]), int(value[1]))
    if frame is not None:
        return (int(frame.shape[1]), int(frame.shape[0]))
    raise ValueError("frame_size missing")


@dataclass
class Incident:
    id: str
    type: str
    label: str
    track_id: int
    confidence: float
    timestamp: str
    box: NormBox
    image_path: Optional[str] = None
    source: str = "icar-vision"
    peak_confidence: Optional[float] = None

    def to_dict(self, image_url_prefix: str = "/vision/incidents") -> dict:
        payload = {
            "id": self.id,
            "type": self.type,
            "label": self.label,
            "track_id": int(self.track_id),
            "confidence": round(float(self.confidence), 4),
            "timestamp": self.timestamp,
            "box": dict(self.box),
            "image_url": "%s/%s/image" % (image_url_prefix.rstrip("/"), self.id),
            "source": self.source,
        }
        if self.peak_confidence is not None:
            payload["peak_confidence"] = round(float(self.peak_confidence), 4)
        return payload


@dataclass
class FireConfig:
    confidence: float = 0.6
    confirm_frames: int = 5
    cooldown_s: float = 180.0
    require_confirmed_track: bool = True
    labels: Tuple[str, ...] = ("flame", "danger_sign")


class IncidentEngine:
    """Stateful fire (and future) incident producer with track_id dedupe."""

    def __init__(
        self,
        incidents_dir: str,
        fire: Optional[FireConfig] = None,
        max_incidents: int = 200,
        persist_index: bool = True,
    ) -> None:
        self.incidents_dir = os.path.abspath(incidents_dir)
        self.fire_dir = os.path.join(self.incidents_dir, "fire")
        self.index_path = os.path.join(self.incidents_dir, "index.jsonl")
        self.fire = fire or FireConfig()
        self.max_incidents = int(max_incidents)
        self.persist_index = bool(persist_index)
        self._lock = threading.RLock()
        self._incidents: List[Incident] = []
        self._by_id: Dict[str, Incident] = {}
        self._pending_hits: Dict[int, int] = {}
        self._pending_peak: Dict[int, float] = {}
        self._reported_at: Dict[str, float] = {}
        os.makedirs(self.fire_dir, exist_ok=True)
        if self.persist_index:
            self._load_index()

    def update(self, frame, detections: Sequence[object]) -> List[Incident]:
        """Process one inference frame; return newly created incidents."""
        created: List[Incident] = []
        with self._lock:
            candidates = self._fire_candidates(detections)
            alive = {int(item["track_id"]) for item in candidates}
            for track_id in list(self._pending_hits):
                if track_id not in alive:
                    self._pending_hits.pop(track_id, None)
                    self._pending_peak.pop(track_id, None)

            for item in candidates:
                track_id = int(item["track_id"])
                confidence = float(item["confidence"])
                hits = self._pending_hits.get(track_id, 0) + 1
                self._pending_hits[track_id] = hits
                self._pending_peak[track_id] = max(self._pending_peak.get(track_id, 0.0), confidence)
                if hits < self.fire.confirm_frames:
                    continue
                key = self._fire_key(track_id)
                if self._in_cooldown(key):
                    continue
                incident = self._emit_fire(
                    frame,
                    track_id=track_id,
                    label=str(item["label"]),
                    confidence=confidence,
                    peak_confidence=self._pending_peak.get(track_id, confidence),
                    box=item["box"],
                    frame_size=item["frame_size"],
                )
                created.append(incident)
                self._pending_hits[track_id] = 0
                self._pending_peak.pop(track_id, None)
        return created

    def simulate(
        self,
        frame,
        *,
        track_id: int = 1,
        confidence: float = 0.95,
        label: str = "flame",
        box: Optional[NormBox] = None,
    ) -> Incident:
        """Force one fire incident (for API / UI 联调 without waiting for model)."""
        if frame is None:
            raise ValueError("frame is required for simulate")
        frame_size = (int(frame.shape[1]), int(frame.shape[0]))
        norm = box or {"left": 0.3, "top": 0.25, "right": 0.55, "bottom": 0.65}
        pixel = _denormalize_box(norm, frame_size)
        with self._lock:
            return self._emit_fire(
                frame,
                track_id=int(track_id),
                label=str(label),
                confidence=float(confidence),
                peak_confidence=float(confidence),
                box=pixel,
                frame_size=frame_size,
                force=True,
            )

    def list_incidents(self, limit: int = 50, incident_type: Optional[str] = None) -> List[dict]:
        with self._lock:
            items = list(reversed(self._incidents))
            if incident_type:
                items = [item for item in items if item.type == incident_type]
            return [item.to_dict() for item in items[: max(0, int(limit))]]

    def get(self, incident_id: str) -> Optional[Incident]:
        with self._lock:
            return self._by_id.get(incident_id)

    def read_image(self, incident_id: str) -> Optional[bytes]:
        incident = self.get(incident_id)
        if incident is None or not incident.image_path:
            return None
        path = incident.image_path
        if not os.path.isfile(path):
            return None
        with open(path, "rb") as handle:
            return handle.read()

    def stats(self) -> dict:
        with self._lock:
            return {
                "total": len(self._incidents),
                "pending_tracks": len(self._pending_hits),
                "cooldown_keys": len(self._reported_at),
                "fire": {
                    "confidence": self.fire.confidence,
                    "confirm_frames": self.fire.confirm_frames,
                    "cooldown_s": self.fire.cooldown_s,
                    "require_confirmed_track": self.fire.require_confirmed_track,
                    "labels": list(self.fire.labels),
                },
                "incidents_dir": self.incidents_dir,
            }

    def _fire_candidates(self, detections: Sequence[object]) -> List[dict]:
        labels = set(self.fire.labels)
        found: List[dict] = []
        for detection in detections:
            label = str(getattr(detection, "label", ""))
            if label not in labels:
                continue
            confidence = float(getattr(detection, "confidence", 0.0))
            if confidence < self.fire.confidence:
                continue
            track_id = getattr(detection, "track_id", None)
            if track_id is None:
                continue
            if self.fire.require_confirmed_track and not bool(getattr(detection, "confirmed", False)):
                continue
            try:
                frame_size = _frame_size_of(detection)
            except ValueError:
                continue
            found.append(
                {
                    "track_id": int(track_id),
                    "label": label,
                    "confidence": confidence,
                    "box": _as_box(getattr(detection, "box")),
                    "frame_size": frame_size,
                }
            )
        return found

    def _fire_key(self, track_id: int) -> str:
        return "fire:%d" % int(track_id)

    def _in_cooldown(self, key: str) -> bool:
        last = self._reported_at.get(key)
        if last is None:
            return False
        return (_unix_now() - last) < self.fire.cooldown_s

    def _emit_fire(
        self,
        frame,
        *,
        track_id: int,
        label: str,
        confidence: float,
        peak_confidence: float,
        box: Box,
        frame_size: Tuple[int, int],
        force: bool = False,
    ) -> Incident:
        key = self._fire_key(track_id)
        now = _unix_now()
        if not force and self._in_cooldown(key):
            raise RuntimeError("track still in cooldown")
        stamp = datetime.fromtimestamp(now).astimezone().strftime("%Y%m%dT%H%M%S")
        incident_id = "inc-%s-fire-%d" % (stamp, track_id)
        # Avoid id collision within same second.
        suffix = 1
        while incident_id in self._by_id:
            incident_id = "inc-%s-fire-%d-%d" % (stamp, track_id, suffix)
            suffix += 1

        image_path = os.path.join(self.fire_dir, "%s_trk%d.jpg" % (stamp, track_id))
        if suffix > 1:
            image_path = os.path.join(self.fire_dir, "%s_trk%d_%d.jpg" % (stamp, track_id, suffix - 1))
        self._save_annotated_jpeg(frame, box, label, confidence, track_id, image_path)

        incident = Incident(
            id=incident_id,
            type="fire",
            label=label,
            track_id=int(track_id),
            confidence=float(confidence),
            timestamp=_iso_local(now),
            box=_normalize_box(box, frame_size),
            image_path=image_path,
            peak_confidence=float(peak_confidence),
        )
        self._incidents.append(incident)
        self._by_id[incident.id] = incident
        self._reported_at[key] = now
        if len(self._incidents) > self.max_incidents:
            dropped = self._incidents[: len(self._incidents) - self.max_incidents]
            self._incidents = self._incidents[len(self._incidents) - self.max_incidents :]
            for old in dropped:
                self._by_id.pop(old.id, None)
        if self.persist_index:
            self._append_index(incident, key, now)
        return incident

    def _save_annotated_jpeg(self, frame, box: Box, label: str, confidence: float, track_id: int, path: str) -> None:
        import cv2

        canvas = frame.copy()
        x1, y1, x2, y2 = [int(round(v)) for v in box]
        color = (40, 40, 220)
        cv2.rectangle(canvas, (x1, y1), (x2, y2), color, 2)
        caption = "%s#%d %.0f%%" % (label, track_id, float(confidence) * 100.0)
        cv2.putText(
            canvas,
            caption,
            (x1, max(18, y1 - 7)),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.55,
            color,
            2,
            cv2.LINE_AA,
        )
        cv2.putText(
            canvas,
            "FIRE INCIDENT",
            (12, 28),
            cv2.FONT_HERSHEY_SIMPLEX,
            0.7,
            color,
            2,
            cv2.LINE_AA,
        )
        encoded, jpeg = cv2.imencode(".jpg", canvas, [int(cv2.IMWRITE_JPEG_QUALITY), 85])
        if not encoded:
            raise RuntimeError("failed to encode incident jpeg")
        with open(path, "wb") as handle:
            handle.write(jpeg.tobytes())

    def _append_index(self, incident: Incident, key: str, reported_at: float) -> None:
        record = {
            "key": key,
            "reported_at": reported_at,
            "incident": incident.to_dict(),
            "image_path": incident.image_path,
        }
        with open(self.index_path, "a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")

    def _load_index(self) -> None:
        if not os.path.isfile(self.index_path):
            return
        now = _unix_now()
        try:
            with open(self.index_path, "r", encoding="utf-8") as handle:
                lines = handle.readlines()
        except OSError:
            return
        for line in lines[-self.max_incidents :]:
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                continue
            key = str(record.get("key") or "")
            reported_at = float(record.get("reported_at") or 0.0)
            if key and (now - reported_at) < self.fire.cooldown_s:
                self._reported_at[key] = reported_at
            payload = record.get("incident") or {}
            incident_id = payload.get("id")
            if not incident_id or incident_id in self._by_id:
                continue
            incident = Incident(
                id=str(incident_id),
                type=str(payload.get("type") or "fire"),
                label=str(payload.get("label") or "flame"),
                track_id=int(payload.get("track_id") or 0),
                confidence=float(payload.get("confidence") or 0.0),
                timestamp=str(payload.get("timestamp") or _iso_local(reported_at)),
                box=dict(payload.get("box") or {}),
                image_path=record.get("image_path") or None,
                peak_confidence=payload.get("peak_confidence"),
            )
            self._incidents.append(incident)
            self._by_id[incident.id] = incident
