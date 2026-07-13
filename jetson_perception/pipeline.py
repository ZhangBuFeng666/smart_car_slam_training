from typing import Dict, Iterable, List, Optional

from jetson_perception.event_mapper import map_detection_to_event
from jetson_perception.jarvis_client import JarvisVisionClient
from jetson_perception.models import YoloDetection
from jetson_perception.temporal_filter import TemporalConfirmationFilter


class PerceptionPipeline:
    def __init__(
        self,
        mission_id: str,
        jarvis_client: Optional[JarvisVisionClient] = None,
        *,
        confidence_threshold: float = 0.60,
        required_frames: int = 3,
    ) -> None:
        self.mission_id = mission_id
        self.jarvis_client = jarvis_client
        self.confidence_threshold = confidence_threshold
        self.temporal_filter = TemporalConfirmationFilter(required_frames=required_frames)

    def process_detections(self, detections: Iterable[YoloDetection]) -> List[Dict[str, object]]:
        results: List[Dict[str, object]] = []
        for detection in detections:
            if not self.temporal_filter.observe(detection.class_name, detection.track_id):
                results.append(
                    {
                        "accepted": False,
                        "reason": "PENDING_CONFIRMATION",
                        "track_id": detection.track_id,
                        "class_name": detection.class_name,
                    }
                )
                continue

            event = map_detection_to_event(
                detection,
                self.mission_id,
                confidence_threshold=self.confidence_threshold,
            )
            if event is None:
                results.append(
                    {
                        "accepted": False,
                        "reason": "FILTERED",
                        "track_id": detection.track_id,
                        "class_name": detection.class_name,
                    }
                )
                continue

            if self.jarvis_client is None:
                results.append({"accepted": True, "reason": "MAPPED", "event": event})
                continue

            response = self.jarvis_client.post_vision_event(event)
            results.append({"accepted": response.get("accepted", False), "response": response})
        return results
