import unittest

from vision_service import Detection, VisionState, summarize_detections


class DetectionContractTest(unittest.TestCase):
    def test_detection_serializes_normalized_box(self):
        detection = Detection(
            class_id=1,
            label="car",
            confidence=0.9234,
            box=(64, 48, 320, 240),
            frame_size=(640, 480),
        )

        self.assertEqual(
            {
                "class_id": 1,
                "label": "car",
                "confidence": 0.9234,
                "box": {"left": 0.1, "top": 0.1, "right": 0.5, "bottom": 0.5},
            },
            detection.to_dict(),
        )

    def test_summary_groups_parking_scene_classes(self):
        detections = [
            Detection(0, "parking_slot", 0.8, (0, 0, 10, 10), (100, 100)),
            Detection(0, "parking_slot", 0.7, (0, 0, 10, 10), (100, 100)),
            Detection(1, "car", 0.9, (0, 0, 10, 10), (100, 100)),
            Detection(7, "roadblock", 0.6, (0, 0, 10, 10), (100, 100)),
        ]

        summary = summarize_detections(detections)

        self.assertEqual(2, summary["parking_slots"])
        self.assertEqual(1, summary["cars"])
        self.assertEqual(1, summary["obstacles"])
        self.assertEqual(4, summary["total"])


class VisionStateTest(unittest.TestCase):
    def test_state_returns_real_empty_snapshot_before_first_inference(self):
        state = VisionState()

        snapshot = state.snapshot()

        self.assertEqual("starting", snapshot["state"])
        self.assertEqual([], snapshot["detections"])
        self.assertEqual(0, snapshot["summary"]["total"])

    def test_update_records_model_and_performance_state(self):
        state = VisionState()
        state.mark_model_ready(device="cuda:0", fp16=True, names={0: "parking_slot", 1: "car"})
        state.update_frame([], inference_ms=43.46, source_fps=18.0, jpeg=b"jpeg")

        snapshot = state.snapshot()

        self.assertEqual("live", snapshot["state"])
        self.assertEqual("cuda:0", snapshot["model"]["device"])
        self.assertTrue(snapshot["model"]["fp16"])
        self.assertAlmostEqual(43.46, snapshot["inference_ms"])
        self.assertEqual(b"jpeg", state.latest_jpeg())


if __name__ == "__main__":
    unittest.main()
