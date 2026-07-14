from collections import Counter, deque

import numpy as np


ARROW_DIRECTIONS = ("go_straight", "turn_left", "turn_right")
IMAGENET_MEAN = np.array((0.485, 0.456, 0.406), dtype=np.float32).reshape(1, 3, 1, 1)
IMAGENET_STD = np.array((0.229, 0.224, 0.225), dtype=np.float32).reshape(1, 3, 1, 1)


def crop_with_padding(frame, box, pad=0.08):
    height, width = frame.shape[:2]
    x1, y1, x2, y2 = (float(value) for value in box)
    box_width, box_height = x2 - x1, y2 - y1
    left = max(0, int(x1 - box_width * pad))
    top = max(0, int(y1 - box_height * pad))
    right = min(width, int(x2 + box_width * pad))
    bottom = min(height, int(y2 + box_height * pad))
    if right <= left or bottom <= top:
        return None
    return frame[top:bottom, left:right].copy()


def preprocess_crop(crop_bgr, image_size):
    import cv2

    height, width = crop_bgr.shape[:2]
    side = min(height, width)
    top, left = (height - side) // 2, (width - side) // 2
    square = crop_bgr[top:top + side, left:left + side]
    resized = cv2.resize(square, (image_size, image_size), interpolation=cv2.INTER_LINEAR)
    rgb = resized[:, :, ::-1].transpose(2, 0, 1).astype(np.float32) / 255.0
    blob = np.ascontiguousarray(rgb[None, ...])
    return (blob - IMAGENET_MEAN) / IMAGENET_STD


def softmax(logits):
    values = np.asarray(logits, dtype=np.float32).reshape(-1)
    shifted = values - values.max()
    exponentials = np.exp(shifted)
    return exponentials / exponentials.sum()


class DirectionVoteBuffer:
    def __init__(self, window=5, min_count=3, miss_limit=3):
        self.values = deque(maxlen=int(window))
        self.min_count = int(min_count)
        self.miss_limit = int(miss_limit)
        self.misses = 0
        self.stable = None

    def push(self, label):
        if label:
            self.misses = 0
            self.values.append(label)
            name, count = Counter(self.values).most_common(1)[0]
            self.stable = name if count >= self.min_count else self.stable
        else:
            self.misses += 1
            if self.misses >= self.miss_limit:
                self.values.clear()
                self.stable = None
        return self.stable


class OpenCvArrowClassifier:
    def __init__(self, weights, image_size=128, confidence=0.5, names=ARROW_DIRECTIONS):
        import cv2

        self.net = cv2.dnn.readNetFromONNX(weights)
        self.image_size = int(image_size)
        self.confidence = float(confidence)
        self.names = tuple(names)

    def warmup(self):
        self.net.setInput(np.zeros((1, 3, self.image_size, self.image_size), dtype=np.float32))
        self.net.forward()

    def classify(self, crop_bgr):
        self.net.setInput(preprocess_crop(crop_bgr, self.image_size))
        logits = self.net.forward()
        if not np.isfinite(logits).all():
            return None, 0.0
        probabilities = softmax(logits)
        class_id = int(probabilities.argmax())
        confidence = float(probabilities[class_id])
        if class_id >= len(self.names) or confidence < self.confidence:
            return None, confidence
        return self.names[class_id], confidence
