"""Lightweight multi-object tracker for iCar vision.

Associates YOLO detections across video frames by class + IoU matching and
assigns stable integer track IDs. Designed for low-FPS patrol streams
(about 3 FPS) without a ReID network.

Deploy next to vision_service.py on Jetson:
    /home/jetson/icar_vision/tracker.py
Repo source of truth for service packaging:
    jetson_perception/tracker.py

vision_service.Detection is a frozen dataclass with frame_size. Add optional
track fields on Detection, then in VisionWorker:

    from tracker import IoUTracker

    self.tracker = IoUTracker(iou_threshold=0.3, max_age=5, min_hits=2)
    ...
    detections = self.tracker.assign_to_detections(detections)
"""

from __future__ import annotations

from dataclasses import dataclass, replace
from typing import Iterable, List, Optional, Sequence, Tuple


Box = Tuple[float, float, float, float]


def box_iou(a: Box, b: Box) -> float:
    """Intersection-over-union for xyxy boxes."""
    ax1, ay1, ax2, ay2 = a
    bx1, by1, bx2, by2 = b
    inter_x1 = max(ax1, bx1)
    inter_y1 = max(ay1, by1)
    inter_x2 = min(ax2, bx2)
    inter_y2 = min(ay2, by2)
    inter_w = max(0.0, inter_x2 - inter_x1)
    inter_h = max(0.0, inter_y2 - inter_y1)
    inter = inter_w * inter_h
    if inter <= 0.0:
        return 0.0
    area_a = max(0.0, ax2 - ax1) * max(0.0, ay2 - ay1)
    area_b = max(0.0, bx2 - bx1) * max(0.0, by2 - by1)
    union = area_a + area_b - inter
    if union <= 0.0:
        return 0.0
    return inter / union


def _as_box(value) -> Box:
    if len(value) != 4:
        raise ValueError("box must be (x1, y1, x2, y2)")
    return (float(value[0]), float(value[1]), float(value[2]), float(value[3]))


def _frame_size_of(detection) -> Optional[Tuple[int, int]]:
    value = getattr(detection, "frame_size", None)
    if value is None:
        value = getattr(detection, "image_size", None)
    if value is None:
        return None
    return (int(value[0]), int(value[1]))


@dataclass
class TrackedDetection:
    """Detection enriched with tracker metadata."""

    class_id: int
    label: str
    confidence: float
    box: Box
    track_id: int
    hits: int
    age: int
    time_since_update: int
    confirmed: bool
    frame_size: Optional[Tuple[int, int]] = None
    direction: Optional[str] = None
    stable_direction: Optional[str] = None
    direction_confidence: Optional[float] = None

    def to_event_track_id(self, prefix: str = "trk") -> str:
        """String ID suitable for Jarvis vision_events.track_id."""
        return "%s-%s-%d" % (prefix, self.label, self.track_id)


@dataclass
class _Track:
    track_id: int
    class_id: int
    label: str
    confidence: float
    box: Box
    hits: int = 1
    age: int = 1
    time_since_update: int = 0
    frame_size: Optional[Tuple[int, int]] = None
    direction: Optional[str] = None
    stable_direction: Optional[str] = None
    direction_confidence: Optional[float] = None


class IoUTracker:
    """Greedy class-aware IoU tracker.

    Parameters
    ----------
    iou_threshold:
        Minimum IoU to continue an existing track. 0.25-0.35 works well at 3 FPS.
    max_age:
        Delete a track after this many unmatched frames.
    min_hits:
        Frames needed before a track is marked confirmed (reduces flicker).
    class_aware:
        If True, only match detections with the same class_id.
    """

    def __init__(
        self,
        iou_threshold: float = 0.3,
        max_age: int = 5,
        min_hits: int = 2,
        class_aware: bool = True,
    ) -> None:
        if not 0.0 <= iou_threshold <= 1.0:
            raise ValueError("iou_threshold must be in [0, 1]")
        if max_age < 1:
            raise ValueError("max_age must be >= 1")
        if min_hits < 1:
            raise ValueError("min_hits must be >= 1")

        self.iou_threshold = float(iou_threshold)
        self.max_age = int(max_age)
        self.min_hits = int(min_hits)
        self.class_aware = bool(class_aware)
        self._next_id = 1
        self._tracks: List[_Track] = []

    def reset(self) -> None:
        self._next_id = 1
        self._tracks = []

    @property
    def active_tracks(self) -> int:
        return len(self._tracks)

    def update(self, detections: Sequence[object]) -> List[TrackedDetection]:
        """Associate current-frame detections and return tracked results.

        Each detection must provide at least:
            class_id, label, confidence, box
        Optional fields copied when present:
            frame_size (or image_size), direction, stable_direction,
            direction_confidence
        """
        det_list = list(detections)
        for track in self._tracks:
            track.age += 1
            track.time_since_update += 1

        if not det_list:
            self._tracks = [
                track for track in self._tracks if track.time_since_update <= self.max_age
            ]
            return []

        unmatched_dets = set(range(len(det_list)))
        matched_pairs: List[Tuple[int, int]] = []

        candidates: List[Tuple[float, int, int]] = []
        for track_index, track in enumerate(self._tracks):
            for det_index, detection in enumerate(det_list):
                class_id = int(getattr(detection, "class_id"))
                if self.class_aware and class_id != track.class_id:
                    continue
                iou = box_iou(track.box, _as_box(getattr(detection, "box")))
                if iou >= self.iou_threshold:
                    candidates.append((iou, track_index, det_index))

        candidates.sort(key=lambda item: item[0], reverse=True)
        used_tracks = set()
        used_dets = set()
        for iou, track_index, det_index in candidates:
            if track_index in used_tracks or det_index in used_dets:
                continue
            used_tracks.add(track_index)
            used_dets.add(det_index)
            unmatched_dets.discard(det_index)
            matched_pairs.append((track_index, det_index))

        for track_index, det_index in matched_pairs:
            detection = det_list[det_index]
            track = self._tracks[track_index]
            track.class_id = int(getattr(detection, "class_id"))
            track.label = str(getattr(detection, "label"))
            track.confidence = float(getattr(detection, "confidence"))
            track.box = _as_box(getattr(detection, "box"))
            track.hits += 1
            track.time_since_update = 0
            track.frame_size = _frame_size_of(detection) or track.frame_size
            track.direction = getattr(detection, "direction", None)
            track.stable_direction = getattr(detection, "stable_direction", None)
            track.direction_confidence = getattr(detection, "direction_confidence", None)

        for det_index in sorted(unmatched_dets):
            detection = det_list[det_index]
            self._tracks.append(
                _Track(
                    track_id=self._next_id,
                    class_id=int(getattr(detection, "class_id")),
                    label=str(getattr(detection, "label")),
                    confidence=float(getattr(detection, "confidence")),
                    box=_as_box(getattr(detection, "box")),
                    frame_size=_frame_size_of(detection),
                    direction=getattr(detection, "direction", None),
                    stable_direction=getattr(detection, "stable_direction", None),
                    direction_confidence=getattr(detection, "direction_confidence", None),
                )
            )
            self._next_id += 1

        self._tracks = [
            track for track in self._tracks if track.time_since_update <= self.max_age
        ]

        results: List[TrackedDetection] = []
        for track in self._tracks:
            if track.time_since_update != 0:
                continue
            results.append(
                TrackedDetection(
                    class_id=track.class_id,
                    label=track.label,
                    confidence=track.confidence,
                    box=track.box,
                    track_id=track.track_id,
                    hits=track.hits,
                    age=track.age,
                    time_since_update=track.time_since_update,
                    confirmed=track.hits >= self.min_hits,
                    frame_size=track.frame_size,
                    direction=track.direction,
                    stable_direction=track.stable_direction,
                    direction_confidence=track.direction_confidence,
                )
            )
        return results

    def assign_to_detections(self, detections: Sequence[object]) -> List[object]:
        """Return new frozen Detection objects with track fields via replace().

        Requires each Detection to declare optional fields:
            track_id: Optional[int] = None
            confirmed: bool = False
            hits: int = 0
        """
        tracked = self.update(detections)
        used = set()
        assigned: List[object] = []
        for item in tracked:
            best_index = None
            best_iou = -1.0
            for index, detection in enumerate(detections):
                if index in used:
                    continue
                if int(getattr(detection, "class_id")) != item.class_id:
                    continue
                iou = box_iou(_as_box(getattr(detection, "box")), item.box)
                if iou > best_iou:
                    best_iou = iou
                    best_index = index
            if best_index is None or best_iou < self.iou_threshold:
                continue
            used.add(best_index)
            assigned.append(
                replace(
                    detections[best_index],
                    track_id=item.track_id,
                    confirmed=item.confirmed,
                    hits=item.hits,
                )
            )
        return assigned


def filter_confirmed(tracked: Iterable[TrackedDetection]) -> List[TrackedDetection]:
    """Keep only tracks that survived min_hits (good for business alerts)."""
    return [item for item in tracked if item.confirmed]
