import unittest
from dataclasses import dataclass
from typing import Optional, Tuple

from tracker import IoUTracker, TrackedDetection, box_iou, filter_confirmed


class _Det:
    def __init__(self, class_id, label, confidence, box, frame_size=(640, 480)):
        self.class_id = class_id
        self.label = label
        self.confidence = confidence
        self.box = box
        self.frame_size = frame_size


@dataclass(frozen=True)
class _FrozenDet:
    class_id: int
    label: str
    confidence: float
    box: Tuple[float, float, float, float]
    frame_size: Tuple[int, int]
    track_id: Optional[int] = None
    confirmed: bool = False
    hits: int = 0


class IoUTrackerTest(unittest.TestCase):
    def test_same_sign_keeps_track_id_across_frames(self):
        tracker = IoUTracker(iou_threshold=0.3, max_age=5, min_hits=2)
        frame1 = tracker.update([_Det(2, "no_parking_sign", 0.9, (100, 80, 180, 200))])
        self.assertEqual(len(frame1), 1)
        track_id = frame1[0].track_id
        self.assertFalse(frame1[0].confirmed)

        frame2 = tracker.update([_Det(2, "no_parking_sign", 0.88, (104, 82, 184, 204))])
        self.assertEqual(len(frame2), 1)
        self.assertEqual(frame2[0].track_id, track_id)
        self.assertTrue(frame2[0].confirmed)
        self.assertEqual(frame2[0].frame_size, (640, 480))

    def test_different_class_does_not_steal_track(self):
        tracker = IoUTracker(iou_threshold=0.3, max_age=5, min_hits=1)
        first = tracker.update([_Det(2, "no_parking_sign", 0.9, (100, 80, 180, 200))])
        second = tracker.update([_Det(1, "car", 0.9, (100, 80, 180, 200))])
        self.assertEqual(first[0].track_id, 1)
        self.assertEqual(second[0].track_id, 2)

    def test_assign_to_frozen_detection(self):
        tracker = IoUTracker(iou_threshold=0.3, max_age=5, min_hits=2)
        first = tracker.assign_to_detections(
            [_FrozenDet(2, "no_parking_sign", 0.9, (100, 80, 180, 200), (640, 480))]
        )
        second = tracker.assign_to_detections(
            [_FrozenDet(2, "no_parking_sign", 0.88, (104, 82, 184, 204), (640, 480))]
        )
        self.assertEqual(first[0].track_id, second[0].track_id)
        self.assertFalse(first[0].confirmed)
        self.assertTrue(second[0].confirmed)
        self.assertEqual(second[0].hits, 2)

    def test_filter_confirmed(self):
        items = [
            TrackedDetection(1, "car", 0.9, (0, 0, 1, 1), 1, 1, 1, 0, False),
            TrackedDetection(1, "car", 0.9, (0, 0, 1, 1), 2, 3, 3, 0, True),
        ]
        self.assertEqual([item.track_id for item in filter_confirmed(items)], [2])

    def test_box_iou_identical(self):
        self.assertAlmostEqual(box_iou((0, 0, 10, 10), (0, 0, 10, 10)), 1.0)


if __name__ == "__main__":
    unittest.main()
