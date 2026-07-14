"""Pure, freshness-aware tracking for structured vision detections."""

from dataclasses import dataclass
import math
import threading
import time
from collections.abc import Mapping
from typing import Any, Dict, Iterable, List, Optional, Tuple

import httpx


_IOU_THRESHOLD = 0.2
_CENTER_DISTANCE_THRESHOLD = 0.18
_BOX_KEYS = ("left", "top", "right", "bottom")
_sequence_lock = threading.Lock()
_sequences_by_label: Dict[str, int] = {}


def _next_track_id(label: str) -> str:
    with _sequence_lock:
        sequence = _sequences_by_label.get(label, 0) + 1
        _sequences_by_label[label] = sequence
    return "%s-%d" % (label, sequence)


def _finite_number(value: Any) -> Optional[float]:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    result = float(value)
    if not math.isfinite(result):
        return None
    return result


def _normalized_box(value: Any) -> Optional[Dict[str, float]]:
    if not isinstance(value, Mapping):
        return None

    result: Dict[str, float] = {}
    for key in _BOX_KEYS:
        number = _finite_number(value.get(key))
        if number is None or number < 0.0 or number > 1.0:
            return None
        result[key] = number

    if result["left"] >= result["right"] or result["top"] >= result["bottom"]:
        return None
    return result


def intersection_over_union(left: Mapping, right: Mapping) -> float:
    """Return the intersection-over-union of two normalized boxes."""

    left_box = _normalized_box(left)
    right_box = _normalized_box(right)
    if left_box is None or right_box is None:
        return 0.0

    intersection_width = max(
        0.0,
        min(left_box["right"], right_box["right"])
        - max(left_box["left"], right_box["left"]),
    )
    intersection_height = max(
        0.0,
        min(left_box["bottom"], right_box["bottom"])
        - max(left_box["top"], right_box["top"]),
    )
    intersection = intersection_width * intersection_height
    if intersection == 0.0:
        return 0.0

    left_area = (left_box["right"] - left_box["left"]) * (
        left_box["bottom"] - left_box["top"]
    )
    right_area = (right_box["right"] - right_box["left"]) * (
        right_box["bottom"] - right_box["top"]
    )
    return intersection / (left_area + right_area - intersection)


def horizontal_position(box: Mapping) -> str:
    """Classify a normalized box center into left, center, or right thirds."""

    normalized = _normalized_box(box)
    if normalized is None:
        raise ValueError("box must contain valid normalized coordinates")

    center = (normalized["left"] + normalized["right"]) / 2.0
    if center < 1.0 / 3.0:
        return "left"
    if center > 2.0 / 3.0:
        return "right"
    return "center"


def _center_distance(left: Mapping, right: Mapping) -> float:
    left_x = (left["left"] + left["right"]) / 2.0
    left_y = (left["top"] + left["bottom"]) / 2.0
    right_x = (right["left"] + right["right"]) / 2.0
    right_y = (right["top"] + right["bottom"]) / 2.0
    return math.hypot(left_x - right_x, left_y - right_y)


def _timestamp(value: Optional[float]) -> float:
    if value is None:
        return time.time()
    result = _finite_number(value)
    if result is None:
        raise ValueError("timestamp must be a finite number")
    return result


@dataclass
class _Track:
    track_id: str
    label: str
    confidence: float
    box: Dict[str, float]
    first_seen: float
    last_seen: float
    last_frame: int
    consecutive_frames: int = 1
    ever_stable: bool = False
    visible: bool = True


@dataclass
class _FlowEdge:
    to: int
    reverse: int
    capacity: int
    cost: Tuple[float, float, float]
    track_id: Optional[str] = None


class SceneTracker:
    """Track multiple detections without performing any I/O."""

    def __init__(self, stable_frames: int, stale_after: float, forget_after: float):
        if isinstance(stable_frames, bool) or not isinstance(stable_frames, int):
            raise ValueError("stable_frames must be an integer")
        if stable_frames < 1:
            raise ValueError("stable_frames must be at least 1")

        stale_value = _finite_number(stale_after)
        forget_value = _finite_number(forget_after)
        if stale_value is None or stale_value <= 0.0:
            raise ValueError("stale_after must be positive")
        if forget_value is None or forget_value <= 0.0:
            raise ValueError("forget_after must be positive")

        self._stable_frames = stable_frames
        self._stale_after = stale_value
        self._forget_after = forget_value
        self._tracks: Dict[str, _Track] = {}
        self._frame_index = 0
        self._last_successful_at: Optional[float] = None
        self._last_event_at: Optional[float] = None
        self._error: Optional[str] = None
        self._lock = threading.RLock()

    def update(self, detections: Iterable[Any], observed_at: Optional[float] = None) -> None:
        """Ingest one successful frame, ignoring malformed detections."""

        timestamp = _timestamp(observed_at)

        with self._lock:
            if self._last_event_at is not None and timestamp < self._last_event_at:
                return

            valid_detections = self._valid_detections(detections)
            self._prune(timestamp)
            self._frame_index += 1
            for track in self._tracks.values():
                track.visible = False

            matches = self._assign(valid_detections)
            for detection_index, track_id in matches.items():
                self._update_track(self._tracks[track_id], valid_detections[detection_index], timestamp)

            for index, item in enumerate(valid_detections):
                if index not in matches:
                    self._create_track(item, timestamp)

            self._last_successful_at = timestamp
            self._last_event_at = timestamp
            self._error = None

    def mark_unavailable(self, error: Any, observed_at: Optional[float] = None) -> None:
        """Record a failed observation attempt without discarding a fresh frame."""

        timestamp = _timestamp(observed_at)
        with self._lock:
            if self._last_event_at is not None and timestamp < self._last_event_at:
                return
            self._last_event_at = timestamp
            self._error = str(error)

    def snapshot(self, now: Optional[float] = None) -> Dict[str, Any]:
        """Return an isolated, deterministic view of current and recent objects."""

        timestamp = _timestamp(now)
        with self._lock:
            state = self._state(timestamp)
            retained_tracks = [
                track
                for track in self._tracks.values()
                if timestamp - track.last_seen <= self._forget_after
            ]
            current_tracks: List[_Track] = []
            if state == "LIVE":
                current_tracks = [
                    track
                    for track in retained_tracks
                    if track.visible
                    and track.last_frame == self._frame_index
                    and track.consecutive_frames >= self._stable_frames
                ]

            current_ids = {track.track_id for track in current_tracks}
            recent_tracks = [
                track
                for track in retained_tracks
                if track.ever_stable and track.track_id not in current_ids
            ]
            objects = [self._object(track, visible=True) for track in self._sort(current_tracks)]
            recent_objects = [
                self._object(track, visible=False) for track in self._sort(recent_tracks)
            ]

            by_class: Dict[str, int] = {}
            for item in objects:
                label = item["label"]
                by_class[label] = by_class.get(label, 0) + 1

            return {
                "state": state,
                "observed_at": self._last_successful_at,
                "summary": {"total": len(objects), "by_class": by_class},
                "objects": objects,
                "recent_objects": recent_objects,
                "error": self._error,
            }

    @staticmethod
    def _valid_detections(detections: Iterable[Any]) -> List[Dict[str, Any]]:
        if isinstance(detections, (str, bytes, Mapping)) or detections is None:
            return []

        try:
            candidates = iter(detections)
        except TypeError:
            return []

        valid: List[Dict[str, Any]] = []
        for item in candidates:
            if not isinstance(item, Mapping):
                continue
            label = item.get("label")
            if not isinstance(label, str) or not label.strip():
                continue
            confidence = _finite_number(item.get("confidence"))
            normalized_box = _normalized_box(item.get("box"))
            if confidence is None or confidence < 0.0 or confidence > 1.0:
                continue
            if normalized_box is None:
                continue
            valid.append(
                {
                    "label": label.strip(),
                    "confidence": confidence,
                    "box": normalized_box,
                }
            )
        return valid

    def _assign(self, detections: List[Dict[str, Any]]) -> Dict[int, str]:
        matches: Dict[int, str] = {}

        labels = sorted({item["label"] for item in detections})
        for label in labels:
            detection_indices = [
                index for index, item in enumerate(detections) if item["label"] == label
            ]
            track_ids = sorted(
                track_id
                for track_id, track in self._tracks.items()
                if track.label == label
            )
            matches.update(
                self._best_label_assignment(detections, detection_indices, track_ids)
            )

        return matches

    def _best_label_assignment(
        self,
        detections: List[Dict[str, Any]],
        detection_indices: List[int],
        track_ids: List[str],
    ) -> Dict[int, str]:
        if not detection_indices or not track_ids:
            return {}

        detection_offset = 1
        track_offset = detection_offset + len(detection_indices)
        sink = track_offset + len(track_ids)
        graph: List[List[_FlowEdge]] = [[] for _ in range(sink + 1)]
        zero_cost = (0.0, 0.0, 0.0)

        for position in range(len(detection_indices)):
            self._add_flow_edge(graph, 0, detection_offset + position, zero_cost)
        for position in range(len(track_ids)):
            self._add_flow_edge(graph, track_offset + position, sink, zero_cost)

        for detection_position, detection_index in enumerate(detection_indices):
            item = detections[detection_index]
            for track_position, track_id in enumerate(track_ids):
                track = self._tracks[track_id]
                overlap = intersection_over_union(track.box, item["box"])
                if overlap >= _IOU_THRESHOLD:
                    cost = (-1.0, -overlap, 0.0)
                else:
                    distance = _center_distance(track.box, item["box"])
                    if distance > _CENTER_DISTANCE_THRESHOLD:
                        continue
                    cost = (0.0, 0.0, distance)
                self._add_flow_edge(
                    graph,
                    detection_offset + detection_position,
                    track_offset + track_position,
                    cost,
                    track_id=track_id,
                )

        self._augment_min_cost_flow(graph, source=0, sink=sink)

        matches: Dict[int, str] = {}
        for position, detection_index in enumerate(detection_indices):
            detection_node = detection_offset + position
            for edge in graph[detection_node]:
                if edge.track_id is not None and edge.capacity == 0:
                    matches[detection_index] = edge.track_id
                    break
        return matches

    @staticmethod
    def _add_flow_edge(
        graph: List[List[_FlowEdge]],
        source: int,
        target: int,
        cost: Tuple[float, float, float],
        track_id: Optional[str] = None,
    ) -> None:
        forward = _FlowEdge(
            to=target,
            reverse=len(graph[target]),
            capacity=1,
            cost=cost,
            track_id=track_id,
        )
        reverse = _FlowEdge(
            to=source,
            reverse=len(graph[source]),
            capacity=0,
            cost=tuple(-part for part in cost),
        )
        graph[source].append(forward)
        graph[target].append(reverse)

    @staticmethod
    def _augment_min_cost_flow(
        graph: List[List[_FlowEdge]], source: int, sink: int
    ) -> None:
        node_count = len(graph)
        zero_cost = (0.0, 0.0, 0.0)

        while True:
            distances: List[Optional[Tuple[float, float, float]]] = [None] * node_count
            previous_nodes = [-1] * node_count
            previous_edges = [-1] * node_count
            distances[source] = zero_cost

            for _ in range(node_count - 1):
                changed = False
                for node, edges in enumerate(graph):
                    if distances[node] is None:
                        continue
                    for edge_index, edge in enumerate(edges):
                        if edge.capacity == 0:
                            continue
                        candidate = tuple(
                            distances[node][part] + edge.cost[part]
                            for part in range(3)
                        )
                        if distances[edge.to] is None or candidate < distances[edge.to]:
                            distances[edge.to] = candidate
                            previous_nodes[edge.to] = node
                            previous_edges[edge.to] = edge_index
                            changed = True
                if not changed:
                    break

            if distances[sink] is None:
                return

            node = sink
            while node != source:
                previous_node = previous_nodes[node]
                edge = graph[previous_node][previous_edges[node]]
                edge.capacity -= 1
                graph[node][edge.reverse].capacity += 1
                node = previous_node

    def _update_track(self, track: _Track, item: Dict[str, Any], timestamp: float) -> None:
        if track.last_frame == self._frame_index - 1:
            track.consecutive_frames += 1
        else:
            track.consecutive_frames = 1
            track.first_seen = timestamp

        track.confidence = item["confidence"]
        track.box = dict(item["box"])
        track.last_seen = timestamp
        track.last_frame = self._frame_index
        track.visible = True
        if track.consecutive_frames >= self._stable_frames:
            track.ever_stable = True

    def _create_track(self, item: Dict[str, Any], timestamp: float) -> None:
        track = _Track(
            track_id=_next_track_id(item["label"]),
            label=item["label"],
            confidence=item["confidence"],
            box=dict(item["box"]),
            first_seen=timestamp,
            last_seen=timestamp,
            last_frame=self._frame_index,
        )
        track.ever_stable = self._stable_frames == 1
        self._tracks[track.track_id] = track

    def _prune(self, timestamp: float) -> None:
        forgotten = [
            track_id
            for track_id, track in self._tracks.items()
            if timestamp - track.last_seen > self._forget_after
        ]
        for track_id in forgotten:
            del self._tracks[track_id]

    def _state(self, timestamp: float) -> str:
        if self._last_successful_at is None:
            return "UNAVAILABLE" if self._error is not None else "STARTING"
        if timestamp - self._last_successful_at <= self._stale_after:
            return "LIVE"
        if self._error is not None:
            return "UNAVAILABLE"
        return "STALE"

    @staticmethod
    def _sort(tracks: Iterable[_Track]) -> List[_Track]:
        return sorted(tracks, key=lambda track: (track.label, track.track_id))

    @staticmethod
    def _object(track: _Track, visible: bool) -> Dict[str, Any]:
        duration = max(0.0, track.last_seen - track.first_seen)
        return {
            "track_id": track.track_id,
            "label": track.label,
            "confidence": track.confidence,
            "position": horizontal_position(track.box),
            "box": dict(track.box),
            "stable_for_ms": int(round(duration * 1000.0)),
            "visible": visible,
        }


class VisionContextCollector:
    """Continuously collect structured detections from the local vision API."""

    def __init__(self, settings: Any, request_once=None):
        self._settings = settings
        self._tracker = SceneTracker(
            stable_frames=settings.vision_stable_frames,
            stale_after=settings.vision_stale_after_seconds,
            forget_after=settings.vision_forget_after_seconds,
        )
        self._request_once = request_once or self._request_detections
        self._stop_event = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._lifecycle_lock = threading.Lock()

    @property
    def is_running(self) -> bool:
        with self._lifecycle_lock:
            return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        with self._lifecycle_lock:
            if self._thread is not None and self._thread.is_alive():
                return
            self._stop_event.clear()
            self._thread = threading.Thread(
                target=self._run,
                name="jarvis-vision-context",
                daemon=True,
            )
            self._thread.start()

    def stop(self) -> None:
        with self._lifecycle_lock:
            thread = self._thread
            self._stop_event.set()
        if thread is not None and thread is not threading.current_thread():
            thread.join(timeout=max(1.0, self._settings.vision_poll_interval_seconds * 3))
        with self._lifecycle_lock:
            if self._thread is thread and (thread is None or not thread.is_alive()):
                self._thread = None

    def poll_once(self, now: Optional[float] = None) -> None:
        timestamp = _timestamp(now)
        try:
            payload = self._request_once()
            if isinstance(payload, httpx.Response):
                payload.raise_for_status()
                payload = payload.json()
            if not isinstance(payload, Mapping):
                raise ValueError("vision response must be an object")
            detections = payload.get("detections")
            if not isinstance(detections, list):
                raise ValueError("vision detections must be a list")
            self._tracker.update(detections, observed_at=timestamp)
        except Exception as error:
            self._tracker.mark_unavailable(error, observed_at=timestamp)

    def snapshot(self, now: Optional[float] = None) -> Dict[str, Any]:
        return self._tracker.snapshot(now=now)

    def health(self, now: Optional[float] = None) -> Dict[str, Any]:
        scene = self.snapshot(now=now)
        return {
            "state": scene["state"],
            "updated_at": scene["observed_at"],
            "error": scene["error"],
        }

    def _run(self) -> None:
        interval = self._settings.vision_poll_interval_seconds
        while not self._stop_event.is_set():
            self.poll_once()
            self._stop_event.wait(interval)

    def _request_detections(self) -> Dict[str, Any]:
        base_url = self._settings.vision_base_url.rstrip("/")
        with httpx.Client(timeout=1.5) as client:
            response = client.get("%s/vision/detections" % base_url)
            response.raise_for_status()
            return response.json()
