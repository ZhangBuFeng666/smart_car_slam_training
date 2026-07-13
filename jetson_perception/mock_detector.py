from typing import Iterable, List

from jetson_perception.models import YoloDetection


def parking_lot_mock_detections() -> List[YoloDetection]:
    return [
        YoloDetection(
            class_name="standing_water",
            confidence=0.91,
            track_id="mock-water-001",
            bbox_xyxy=(210.0, 280.0, 360.0, 410.0),
            frame_id=120,
            metadata={"scenario": "parking-lot"},
        ),
        YoloDetection(
            class_name="foreign_object",
            confidence=0.88,
            track_id="mock-debris-001",
            bbox_xyxy=(420.0, 250.0, 520.0, 360.0),
            frame_id=120,
            metadata={"scenario": "parking-lot"},
        ),
        YoloDetection(
            class_name="illegal_parking",
            confidence=0.84,
            track_id="mock-parking-001",
            bbox_xyxy=(80.0, 180.0, 220.0, 320.0),
            frame_id=120,
            metadata={"scenario": "parking-lot"},
        ),
        YoloDetection(
            class_name="parking_slot",
            confidence=0.79,
            track_id="mock-slot-001",
            bbox_xyxy=(540.0, 190.0, 620.0, 300.0),
            frame_id=120,
            metadata={"scenario": "parking-lot"},
        ),
    ]


def low_confidence_detections() -> Iterable[YoloDetection]:
    yield YoloDetection(
        class_name="foreign_object",
        confidence=0.42,
        track_id="mock-low-001",
        bbox_xyxy=(100.0, 100.0, 180.0, 180.0),
        frame_id=121,
    )
