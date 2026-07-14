import unittest

import numpy as np

from arrow_classifier import DirectionVoteBuffer

from vision_service import (
    ConsecutiveFailureGuard,
    Detection,
    FrameRateLimiter,
    VisionState,
    summarize_detections,
    classify_arrow_detections,
)


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

    def test_arrow_detection_serializes_optional_direction_fields(self):
        detection = Detection(
            class_id=5,
            label="direction_arrow",
            confidence=0.91,
            box=(10, 20, 60, 80),
            frame_size=(100, 100),
            direction="turn_left",
            direction_confidence=0.934,
            stable_direction="turn_left",
        )

        payload = detection.to_dict()

        self.assertEqual("turn_left", payload["direction"])
        self.assertEqual(0.934, payload["direction_confidence"])
        self.assertEqual("turn_left", payload["stable_direction"])

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

    def test_error_clears_stale_detections_and_frame(self):
        state = VisionState()
        detection = Detection(1, "car", 0.9, (0, 0, 10, 10), (100, 100))
        state.update_frame([detection], inference_ms=30.0, source_fps=18.0, jpeg=b"old")

        state.mark_error("camera disconnected")

        snapshot = state.snapshot()
        self.assertEqual("error", snapshot["state"])
        self.assertEqual([], snapshot["detections"])
        self.assertEqual(0, snapshot["summary"]["total"])
        self.assertIsNone(state.latest_jpeg())


class ArrowDirectionPipelineTest(unittest.TestCase):
    def test_only_classifies_arrow_and_attaches_stable_vote(self):
        class FakeClassifier:
            def classify(self, crop):
                return "turn_right", 0.91

        frame = np.zeros((100, 100, 3), dtype=np.uint8)
        arrow = Detection(5, "direction_arrow", 0.8, (10, 10, 50, 50), (100, 100))
        car = Detection(1, "car", 0.9, (50, 50, 90, 90), (100, 100))
        votes = DirectionVoteBuffer(window=5, min_count=3, miss_limit=3)

        first = classify_arrow_detections(frame, [arrow, car], FakeClassifier(), votes)
        classify_arrow_detections(frame, [arrow], FakeClassifier(), votes)
        third = classify_arrow_detections(frame, [arrow], FakeClassifier(), votes)

        self.assertEqual("turn_right", first[0].direction)
        self.assertIsNone(first[0].stable_direction)
        self.assertIsNone(first[1].direction)
        self.assertEqual("turn_right", third[0].stable_direction)


class FrameRateLimiterTest(unittest.TestCase):
    def test_processes_first_frame_and_caps_following_frames(self):
        limiter = FrameRateLimiter(max_fps=10.0)

        self.assertTrue(limiter.should_process(1.0))
        self.assertFalse(limiter.should_process(1.05))
        self.assertTrue(limiter.should_process(1.1))

    def test_non_positive_limit_disables_throttling(self):
        limiter = FrameRateLimiter(max_fps=0.0)

        self.assertTrue(limiter.should_process(1.0))
        self.assertTrue(limiter.should_process(1.001))


class ConsecutiveFailureGuardTest(unittest.TestCase):
    def test_trips_after_limit_and_success_resets_count(self):
        guard = ConsecutiveFailureGuard(limit=3)

        self.assertFalse(guard.record_failure())
        self.assertFalse(guard.record_failure())
        guard.record_success()
        self.assertFalse(guard.record_failure())
        self.assertFalse(guard.record_failure())
        self.assertTrue(guard.record_failure())


if __name__ == "__main__":
    unittest.main()
