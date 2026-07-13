from datetime import datetime, timezone
from typing import Any, Dict, Optional

from jetson_perception.class_map import ClassMapping, map_class
from jetson_perception.models import YoloDetection


DEFAULT_CONFIDENCE_THRESHOLD = 0.60
DEFAULT_IMAGE_ROOT = "/var/lib/jarvis/events"


def describe_position(bbox_xyxy, frame_width: float = 640.0, frame_height: float = 480.0) -> str:
    x1, y1, x2, y2 = bbox_xyxy
    center_x = (x1 + x2) / 2.0
    center_y = (y1 + y2) / 2.0
    horizontal = "偏左" if center_x < frame_width * 0.35 else "偏右" if center_x > frame_width * 0.65 else "中央"
    vertical = "近端" if center_y > frame_height * 0.65 else "远端" if center_y < frame_height * 0.35 else "中段"
    return "%s%s" % (vertical, horizontal)


def build_image_path(mission_id: str, track_id: str, frame_id: int) -> str:
    safe_track = track_id.replace("/", "_")
    return "%s/%s/%s-frame-%04d.jpg" % (DEFAULT_IMAGE_ROOT, mission_id, safe_track, frame_id)


def map_detection_to_event(
    detection: YoloDetection,
    mission_id: str,
    *,
    source: str = "front_camera",
    frame_width: float = 640.0,
    frame_height: float = 480.0,
    timestamp: Optional[datetime] = None,
    confidence_threshold: float = DEFAULT_CONFIDENCE_THRESHOLD,
) -> Optional[Dict[str, Any]]:
    if detection.confidence < confidence_threshold:
        return None

    mapping = map_class(detection.class_name)
    if mapping is None:
        return None

    event_time = timestamp or datetime.now(timezone.utc)
    metadata = dict(detection.metadata)
    metadata.update(
        {
            "class_name": detection.class_name,
            "bbox_xyxy": list(detection.bbox_xyxy),
            "frame_id": detection.frame_id,
            "severity_hint": mapping.severity,
        }
    )
    return {
        "mission_id": mission_id,
        "source": source,
        "event_type": mapping.event_type,
        "label": mapping.label,
        "confidence": round(float(detection.confidence), 4),
        "position": describe_position(
            detection.bbox_xyxy, frame_width=frame_width, frame_height=frame_height
        ),
        "track_id": detection.track_id,
        "image_path": build_image_path(mission_id, detection.track_id, detection.frame_id),
        "timestamp": event_time.isoformat(),
        "metadata": metadata,
    }


def mapping_for(class_name: str) -> Optional[ClassMapping]:
    return map_class(class_name)
