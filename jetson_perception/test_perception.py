import json
import unittest
from datetime import datetime, timezone
from pathlib import Path

import httpx

from jetson_perception.event_mapper import map_detection_to_event
from jetson_perception.jarvis_client import JarvisVisionClient
from jetson_perception.mock_detector import parking_lot_mock_detections
from jetson_perception.models import YoloDetection
from jetson_perception.pipeline import PerceptionPipeline
from jetson_perception.temporal_filter import TemporalConfirmationFilter


FIXTURES = Path(__file__).parents[1] / "jarvis_agent" / "tests" / "fixtures"


class EventMapperTests(unittest.TestCase):
    def test_maps_standing_water_to_fixture_contract(self):
        detection = YoloDetection(
            class_name="standing_water",
            confidence=0.94,
            track_id="obstacle-track-001",
            bbox_xyxy=(210.0, 280.0, 360.0, 410.0),
            frame_id=1284,
            metadata={"camera_id": "front-camera-01", "patrol_zone": "warehouse-aisle-a"},
        )
        event = map_detection_to_event(
            detection,
            "mission-001",
            timestamp=datetime(2026, 1, 15, 10, 30, tzinfo=timezone.utc),
        )

        self.assertIsNotNone(event)
        fixture = json.loads((FIXTURES / "vision_event.json").read_text(encoding="utf-8"))
        self.assertEqual(event["mission_id"], fixture["mission_id"])
        self.assertEqual(event["source"], fixture["source"])
        self.assertEqual(event["event_type"], "standing_water")
        self.assertEqual(event["label"], "积水")
        self.assertEqual(event["confidence"], 0.94)
        self.assertEqual(event["track_id"], fixture["track_id"])
        self.assertIn("timestamp", event)
        self.assertIn("position", event)
        self.assertIn("image_path", event)
        self.assertIn("metadata", event)

    def test_low_confidence_detection_is_filtered(self):
        detection = YoloDetection(
            class_name="foreign_object",
            confidence=0.42,
            track_id="mock-low-001",
            bbox_xyxy=(10.0, 10.0, 40.0, 40.0),
        )
        event = map_detection_to_event(detection, "mission-001")
        self.assertIsNone(event)

    def test_unknown_class_is_ignored(self):
        detection = YoloDetection(
            class_name="unknown_label",
            confidence=0.95,
            track_id="unknown-001",
            bbox_xyxy=(10.0, 10.0, 40.0, 40.0),
        )
        event = map_detection_to_event(detection, "mission-001")
        self.assertIsNone(event)


class TemporalFilterTests(unittest.TestCase):
    def test_requires_multiple_frames_before_confirmation(self):
        temporal = TemporalConfirmationFilter(required_frames=3)
        self.assertFalse(temporal.observe("standing_water", "track-001"))
        self.assertFalse(temporal.observe("standing_water", "track-001"))
        self.assertTrue(temporal.observe("standing_water", "track-001"))


class PipelineTests(unittest.TestCase):
    def test_mock_detections_post_to_jarvis_after_confirmation(self):
        posted = []

        def handler(request: httpx.Request) -> httpx.Response:
            posted.append(json.loads(request.content.decode("utf-8")))
            return httpx.Response(
                200,
                json={"accepted": True, "reason": "ACCEPTED", "event_id": "evt-001"},
            )

        transport = httpx.MockTransport(handler)
        client = JarvisVisionClient("http://127.0.0.1:8100", "test-token", transport=transport)
        pipeline = PerceptionPipeline("mission-001", client, required_frames=3)

        for _ in range(3):
            results = pipeline.process_detections(parking_lot_mock_detections())

        accepted = [item for item in results if item.get("accepted")]
        self.assertTrue(accepted)
        self.assertGreaterEqual(len(posted), 1)
        self.assertEqual(posted[0]["event_type"], "standing_water")


if __name__ == "__main__":
    unittest.main()
