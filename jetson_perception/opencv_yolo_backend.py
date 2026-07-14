from dataclasses import dataclass
import time

import cv2
import numpy as np


@dataclass(frozen=True)
class DecodedDetection:
    class_id: int
    label: str
    confidence: float
    box: tuple


def configure_opencv_threads(threads):
    cv2.setNumThreads(max(1, int(threads)))
    return cv2.getNumThreads()


def letterbox(image, image_size, color=(114, 114, 114)):
    height, width = image.shape[:2]
    ratio = min(float(image_size) / height, float(image_size) / width)
    resized_width = int(round(width * ratio))
    resized_height = int(round(height * ratio))
    horizontal_padding = (image_size - resized_width) / 2.0
    vertical_padding = (image_size - resized_height) / 2.0
    if (resized_width, resized_height) != (width, height):
        image = cv2.resize(image, (resized_width, resized_height), interpolation=cv2.INTER_LINEAR)
    left = int(round(horizontal_padding - 0.1))
    right = int(round(horizontal_padding + 0.1))
    top = int(round(vertical_padding - 0.1))
    bottom = int(round(vertical_padding + 0.1))
    image = cv2.copyMakeBorder(image, top, bottom, left, right, cv2.BORDER_CONSTANT, value=color)
    return image, (ratio, ratio), (horizontal_padding, vertical_padding)


def decode_detections(output, names, original_shape, ratio, pad, confidence, iou):
    predictions = np.asarray(output)[0]
    if predictions.size == 0:
        return []
    class_scores = predictions[:, 5:]
    class_ids = class_scores.argmax(axis=1)
    scores = predictions[:, 4] * class_scores[np.arange(len(predictions)), class_ids]
    valid = np.isfinite(scores) & (scores >= float(confidence))
    predictions = predictions[valid]
    class_ids = class_ids[valid]
    scores = scores[valid]
    if not len(predictions):
        return []

    boxes = []
    for center_x, center_y, width, height in predictions[:, :4]:
        boxes.append([
            float(center_x - width / 2.0),
            float(center_y - height / 2.0),
            float(width),
            float(height),
        ])

    selected = []
    for class_id in np.unique(class_ids):
        class_indexes = np.flatnonzero(class_ids == class_id)
        kept = cv2.dnn.NMSBoxes(
            [boxes[index] for index in class_indexes],
            [float(scores[index]) for index in class_indexes],
            float(confidence),
            float(iou),
        )
        for local_index in np.asarray(kept).reshape(-1):
            selected.append(int(class_indexes[int(local_index)]))

    original_height, original_width = original_shape
    ratio_x, ratio_y = ratio
    pad_x, pad_y = pad
    detections = []
    for index in sorted(selected, key=lambda item: float(scores[item]), reverse=True):
        left, top, width, height = boxes[index]
        right = left + width
        bottom = top + height
        scaled_box = (
            max(0.0, min(float(original_width), (left - pad_x) / ratio_x)),
            max(0.0, min(float(original_height), (top - pad_y) / ratio_y)),
            max(0.0, min(float(original_width), (right - pad_x) / ratio_x)),
            max(0.0, min(float(original_height), (bottom - pad_y) / ratio_y)),
        )
        class_id = int(class_ids[index])
        detections.append(
            DecodedDetection(
                class_id=class_id,
                label=names[class_id],
                confidence=float(scores[index]),
                box=scaled_box,
            )
        )
    return detections


class OpenCvYoloBackend:
    def __init__(self, weights, names, image_size, confidence, iou, threads=2):
        configure_opencv_threads(threads)
        self.net = cv2.dnn.readNetFromONNX(weights)
        self.names = tuple(names)
        self.image_size = int(image_size)
        self.confidence = float(confidence)
        self.iou = float(iou)

    def warmup(self):
        self.infer(np.zeros((self.image_size, self.image_size, 3), dtype=np.uint8))

    def infer(self, frame):
        image, ratio, pad = letterbox(frame, self.image_size)
        blob = cv2.dnn.blobFromImage(
            image,
            scalefactor=1.0 / 255.0,
            size=(self.image_size, self.image_size),
            swapRB=True,
            crop=False,
        )
        self.net.setInput(blob)
        started = time.perf_counter()
        output = self.net.forward()
        inference_ms = (time.perf_counter() - started) * 1000.0
        detections = decode_detections(
            output,
            self.names,
            frame.shape[:2],
            ratio,
            pad,
            self.confidence,
            self.iou,
        )
        return detections, inference_ms
