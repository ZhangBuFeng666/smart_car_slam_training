from dataclasses import dataclass, field
from typing import Any, Dict, Tuple


@dataclass(frozen=True)
class YoloDetection:
    class_name: str
    confidence: float
    track_id: str
    bbox_xyxy: Tuple[float, float, float, float]
    frame_id: int = 0
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def bbox_center(self) -> Tuple[float, float]:
        x1, y1, x2, y2 = self.bbox_xyxy
        return ((x1 + x2) / 2.0, (y1 + y2) / 2.0)
