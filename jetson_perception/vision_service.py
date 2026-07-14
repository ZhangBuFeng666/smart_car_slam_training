#!/usr/bin/env python3
import argparse
import json
import sys
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Dict, Iterable, Optional, Tuple
from urllib.parse import urlparse


PARKING_CLASSES = (
    "parking_slot",
    "car",
    "no_parking_sign",
    "entrance_sign",
    "exit_sign",
    "direction_arrow",
    "stop_line",
    "roadblock",
    "danger_sign",
)


@dataclass(frozen=True)
class Detection:
    class_id: int
    label: str
    confidence: float
    box: Tuple[float, float, float, float]
    frame_size: Tuple[int, int]

    def to_dict(self):
        width, height = self.frame_size
        left, top, right, bottom = self.box
        return {
            "class_id": self.class_id,
            "label": self.label,
            "confidence": round(float(self.confidence), 4),
            "box": {
                "left": round(max(0.0, min(1.0, left / width)), 4),
                "top": round(max(0.0, min(1.0, top / height)), 4),
                "right": round(max(0.0, min(1.0, right / width)), 4),
                "bottom": round(max(0.0, min(1.0, bottom / height)), 4),
            },
        }


def summarize_detections(detections: Iterable[Detection]):
    items = list(detections)
    counts = {name: 0 for name in PARKING_CLASSES}
    for item in items:
        counts[item.label] = counts.get(item.label, 0) + 1
    return {
        "total": len(items),
        "parking_slots": counts["parking_slot"],
        "cars": counts["car"],
        "signs": sum(counts[name] for name in ("no_parking_sign", "entrance_sign", "exit_sign", "danger_sign")),
        "obstacles": counts["roadblock"],
        "by_class": counts,
    }


class VisionState:
    def __init__(self):
        self._condition = threading.Condition()
        self._state = "starting"
        self._error = None
        self._detections = []
        self._summary = summarize_detections([])
        self._model = {"ready": False, "device": None, "fp16": False, "names": {}}
        self._inference_ms = 0.0
        self._source_fps = 0.0
        self._updated_at = None
        self._jpeg = None
        self._sequence = 0

    def mark_model_ready(self, device, fp16, names):
        with self._condition:
            self._model = {
                "ready": True,
                "device": str(device),
                "fp16": bool(fp16),
                "names": {str(key): value for key, value in dict(names).items()},
            }
            self._error = None

    def mark_error(self, message):
        with self._condition:
            self._state = "error"
            self._error = str(message)
            self._condition.notify_all()

    def mark_connecting(self):
        with self._condition:
            self._state = "connecting"
            self._error = None

    def update_frame(self, detections, inference_ms, source_fps, jpeg):
        with self._condition:
            self._detections = list(detections)
            self._summary = summarize_detections(self._detections)
            self._inference_ms = round(float(inference_ms), 2)
            self._source_fps = round(float(source_fps), 2)
            self._updated_at = time.time()
            self._jpeg = jpeg
            self._sequence += 1
            self._state = "live"
            self._error = None
            self._condition.notify_all()

    def snapshot(self):
        with self._condition:
            return {
                "state": self._state,
                "error": self._error,
                "model": dict(self._model),
                "inference_ms": self._inference_ms,
                "source_fps": self._source_fps,
                "updated_at": self._updated_at,
                "summary": dict(self._summary),
                "detections": [item.to_dict() for item in self._detections],
            }

    def latest_jpeg(self):
        with self._condition:
            return self._jpeg

    def wait_for_jpeg(self, sequence, timeout=2.0):
        with self._condition:
            if self._sequence <= sequence:
                self._condition.wait(timeout)
            return self._sequence, self._jpeg


class VisionWorker:
    def __init__(self, state, weights, source, yolo_root, image_size=640, confidence=0.35, iou=0.45, jpeg_quality=75):
        self.state = state
        self.weights = weights
        self.source = source
        self.yolo_root = yolo_root
        self.image_size = image_size
        self.confidence = confidence
        self.iou = iou
        self.jpeg_quality = jpeg_quality
        self.stop_event = threading.Event()
        self.thread = None

    def start(self):
        self.thread = threading.Thread(target=self.run, name="icar-vision-inference", daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=5.0)

    def run(self):
        try:
            sys.path.insert(0, self.yolo_root)
            import cv2
            import numpy as np
            import torch
            from models.common import DetectMultiBackend
            from utils.augmentations import letterbox
            from utils.general import non_max_suppression, scale_boxes
            from utils.torch_utils import select_device

            device = select_device("0" if torch.cuda.is_available() else "cpu")
            fp16 = device.type != "cpu"
            model = DetectMultiBackend(self.weights, device=device, fp16=fp16)
            stride = int(model.stride)
            names = model.names
            model.warmup(imgsz=(1, 3, self.image_size, self.image_size))
            self.state.mark_model_ready(device=device, fp16=fp16, names=names)

            while not self.stop_event.is_set():
                self.state.mark_connecting()
                capture = cv2.VideoCapture(self.source)
                if not capture.isOpened():
                    self.state.mark_error("video source unavailable: %s" % self.source)
                    capture.release()
                    self.stop_event.wait(2.0)
                    continue
                last_frame_at = time.monotonic()
                while not self.stop_event.is_set():
                    ok, frame = capture.read()
                    if not ok or frame is None:
                        self.state.mark_error("video source disconnected")
                        break
                    now = time.monotonic()
                    source_fps = 1.0 / max(0.001, now - last_frame_at)
                    last_frame_at = now
                    original = frame.copy()
                    image = letterbox(frame, self.image_size, stride=stride, auto=True)[0]
                    image = image.transpose((2, 0, 1))[::-1]
                    image = np.ascontiguousarray(image)
                    tensor = torch.from_numpy(image).to(device)
                    tensor = tensor.half() if fp16 else tensor.float()
                    tensor /= 255.0
                    if tensor.ndim == 3:
                        tensor = tensor[None]
                    started = time.perf_counter()
                    prediction = model(tensor, augment=False, visualize=False)
                    prediction = non_max_suppression(prediction, self.confidence, self.iou, max_det=100)
                    if device.type != "cpu":
                        torch.cuda.synchronize()
                    inference_ms = (time.perf_counter() - started) * 1000.0
                    detections = []
                    for detected in prediction:
                        if len(detected):
                            detected[:, :4] = scale_boxes(tensor.shape[2:], detected[:, :4], original.shape).round()
                            for x1, y1, x2, y2, confidence, class_id in detected.tolist():
                                label = names[int(class_id)]
                                item = Detection(int(class_id), label, confidence, (x1, y1, x2, y2), (original.shape[1], original.shape[0]))
                                detections.append(item)
                                color = class_color(int(class_id))
                                cv2.rectangle(original, (int(x1), int(y1)), (int(x2), int(y2)), color, 2)
                                caption = "%s %.0f%%" % (label, confidence * 100)
                                cv2.putText(original, caption, (int(x1), max(18, int(y1) - 7)), cv2.FONT_HERSHEY_SIMPLEX, 0.5, color, 2, cv2.LINE_AA)
                    cv2.putText(original, "iCar Vision | %.1f ms | %d objects" % (inference_ms, len(detections)), (12, 25), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (85, 212, 151), 2, cv2.LINE_AA)
                    encoded, jpeg = cv2.imencode(".jpg", original, [cv2.IMWRITE_JPEG_QUALITY, self.jpeg_quality])
                    if encoded:
                        self.state.update_frame(detections, inference_ms, source_fps, jpeg.tobytes())
                capture.release()
                self.stop_event.wait(1.0)
        except Exception as error:
            self.state.mark_error("%s: %s" % (type(error).__name__, error))


def class_color(class_id):
    palette = ((85, 212, 151), (80, 160, 255), (80, 80, 240), (210, 180, 70), (190, 130, 70), (235, 180, 80), (90, 210, 230), (60, 110, 240), (70, 70, 210))
    return palette[class_id % len(palette)]


class VisionHandler(BaseHTTPRequestHandler):
    state = None

    def do_GET(self):
        path = urlparse(self.path).path.rstrip("/") or "/"
        if path == "/health":
            snapshot = self.state.snapshot()
            self.reply_json(200, {"ok": snapshot["model"]["ready"], **snapshot})
        elif path == "/vision/detections":
            self.reply_json(200, self.state.snapshot())
        elif path == "/vision/stream":
            self.stream_mjpeg()
        else:
            self.reply_json(404, {"error": "unknown endpoint"})

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors_headers()
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")

    def reply_json(self, status, body):
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.cors_headers()
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def stream_mjpeg(self):
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.cors_headers()
        self.send_header("Cache-Control", "no-store, no-cache, must-revalidate")
        self.end_headers()
        sequence = 0
        try:
            while True:
                sequence, jpeg = self.state.wait_for_jpeg(sequence)
                if not jpeg:
                    continue
                self.wfile.write(b"--frame\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n" % len(jpeg))
                self.wfile.write(jpeg)
                self.wfile.write(b"\r\n")
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            return

    def log_message(self, format_string, *args):
        return


def main():
    parser = argparse.ArgumentParser(description="iCar YOLOv5 edge vision service")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8200)
    parser.add_argument("--weights", default="/home/jetson/icar_vision/models/best.pt")
    parser.add_argument("--source", default="http://127.0.0.1:8000/camera/stream")
    parser.add_argument("--yolo-root", default="/home/jetson/yolov5-7.0")
    parser.add_argument("--img-size", type=int, default=640)
    parser.add_argument("--conf", type=float, default=0.35)
    parser.add_argument("--iou", type=float, default=0.45)
    args = parser.parse_args()

    state = VisionState()
    worker = VisionWorker(state, args.weights, args.source, args.yolo_root, args.img_size, args.conf, args.iou)
    VisionHandler.state = state
    server = ThreadingHTTPServer((args.host, args.port), VisionHandler)
    worker.start()
    try:
        print("iCar vision service on %s:%s" % (args.host, args.port), flush=True)
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.shutdown()
        server.server_close()
        worker.stop()


if __name__ == "__main__":
    main()
