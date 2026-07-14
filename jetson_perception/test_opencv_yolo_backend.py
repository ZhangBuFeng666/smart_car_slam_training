import unittest

import cv2
import numpy as np

from opencv_yolo_backend import configure_opencv_threads, decode_detections, letterbox


class LetterboxTest(unittest.TestCase):
    def test_preserves_aspect_ratio_and_reports_padding(self):
        image = np.zeros((480, 640, 3), dtype=np.uint8)

        resized, ratio, pad = letterbox(image, 416)

        self.assertEqual((416, 416, 3), resized.shape)
        self.assertEqual((0.65, 0.65), ratio)
        self.assertEqual((0.0, 52.0), pad)

    def test_configures_bounded_opencv_worker_threads(self):
        previous = cv2.getNumThreads()
        try:
            self.assertEqual(2, configure_opencv_threads(2))
            self.assertEqual(2, cv2.getNumThreads())
        finally:
            cv2.setNumThreads(previous)


class DecodeDetectionsTest(unittest.TestCase):
    def test_multiplies_objectness_and_class_score_then_applies_nms(self):
        output = np.zeros((1, 2, 14), dtype=np.float32)
        output[0, 0, :5] = [208, 208, 100, 80, 0.9]
        output[0, 0, 6] = 0.8
        output[0, 1, :5] = [210, 210, 100, 80, 0.7]
        output[0, 1, 6] = 0.7

        detections = decode_detections(
            output,
            names=("parking_slot", "car"),
            original_shape=(480, 640),
            ratio=(0.65, 0.65),
            pad=(0.0, 52.0),
            confidence=0.35,
            iou=0.45,
        )

        self.assertEqual(1, len(detections))
        detection = detections[0]
        self.assertEqual(1, detection.class_id)
        self.assertEqual("car", detection.label)
        self.assertAlmostEqual(0.72, detection.confidence, places=4)
        self.assertAlmostEqual(243.08, detection.box[0], places=1)
        self.assertAlmostEqual(178.46, detection.box[1], places=1)
        self.assertAlmostEqual(396.92, detection.box[2], places=1)
        self.assertAlmostEqual(301.54, detection.box[3], places=1)


if __name__ == "__main__":
    unittest.main()
