import unittest

import numpy as np

from arrow_classifier import (
    ARROW_DIRECTIONS,
    DirectionVoteBuffer,
    OpenCvArrowClassifier,
    crop_with_padding,
    preprocess_crop,
    softmax,
)


class ArrowCropTest(unittest.TestCase):
    def test_crop_expands_box_and_clamps_to_frame(self):
        frame = np.zeros((100, 200, 3), dtype=np.uint8)

        crop = crop_with_padding(frame, (10, 20, 60, 80), pad=0.1)

        self.assertEqual((72, 60, 3), crop.shape)

    def test_preprocess_center_crops_converts_rgb_and_normalizes(self):
        crop = np.zeros((10, 20, 3), dtype=np.uint8)
        crop[:, :, 2] = 255

        blob = preprocess_crop(crop, 8)

        self.assertEqual((1, 3, 8, 8), blob.shape)
        self.assertAlmostEqual((1.0 - 0.485) / 0.229, float(blob[0, 0, 0, 0]), places=4)
        self.assertAlmostEqual((0.0 - 0.456) / 0.224, float(blob[0, 1, 0, 0]), places=4)


class ArrowScoreTest(unittest.TestCase):
    def test_softmax_returns_probabilities(self):
        probabilities = softmax(np.array([1.0, 2.0, 3.0], dtype=np.float32))

        self.assertAlmostEqual(1.0, float(probabilities.sum()), places=5)
        self.assertEqual(2, int(probabilities.argmax()))

    def test_classifier_rejects_non_finite_model_output(self):
        class FakeNet:
            def setInput(self, value):
                pass

            def forward(self):
                return np.array([[np.nan, np.nan, np.nan]], dtype=np.float32)

        classifier = object.__new__(OpenCvArrowClassifier)
        classifier.net = FakeNet()
        classifier.image_size = 8
        classifier.confidence = 0.5
        classifier.names = ARROW_DIRECTIONS

        self.assertEqual((None, 0.0), classifier.classify(np.zeros((8, 8, 3), dtype=np.uint8)))


class DirectionVoteBufferTest(unittest.TestCase):
    def test_returns_direction_after_three_votes_in_five_frame_window(self):
        votes = DirectionVoteBuffer(window=5, min_count=3, miss_limit=3)

        self.assertIsNone(votes.push("turn_left"))
        self.assertIsNone(votes.push("turn_right"))
        self.assertIsNone(votes.push("turn_left"))
        self.assertEqual("turn_left", votes.push("turn_left"))

    def test_clears_stale_direction_after_consecutive_misses(self):
        votes = DirectionVoteBuffer(window=5, min_count=3, miss_limit=3)
        for _ in range(3):
            votes.push("go_straight")

        self.assertEqual("go_straight", votes.push(None))
        self.assertEqual("go_straight", votes.push(None))
        self.assertIsNone(votes.push(None))


if __name__ == "__main__":
    unittest.main()
