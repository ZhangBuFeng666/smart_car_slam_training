"""Serialized speech playback for the Jetson control service."""

import queue
import threading
import time
from collections import deque


class SpeechQueue:
    def __init__(self, speaker, max_size=20, dedupe_size=128):
        self._speaker = speaker
        self._queue = queue.Queue(maxsize=max_size)
        self._seen_order = deque()
        self._seen = set()
        self._dedupe_size = max(1, dedupe_size)
        self._lock = threading.Lock()
        self._closed = False
        self._last_result = None
        self._stop = object()
        self._worker = threading.Thread(
            target=self._run,
            name="icar-speech-queue",
            daemon=True,
        )
        self._worker.start()

    def enqueue(self, text, request_id):
        message = " ".join(str(text or "").split()).strip()[:500]
        identifier = str(request_id or "").strip()
        if not message:
            raise ValueError("text is required")
        if not identifier:
            raise ValueError("request_id is required")
        with self._lock:
            if self._closed:
                return {"ok": False, "state": "unavailable"}
            if identifier in self._seen:
                return {"ok": True, "state": "duplicate", "request_id": identifier}
            try:
                self._queue.put_nowait((message, identifier))
            except queue.Full:
                return {"ok": False, "state": "full", "request_id": identifier}
            self._remember(identifier)
        return {"ok": True, "state": "queued", "request_id": identifier}

    def snapshot(self):
        with self._lock:
            return {
                "running": not self._closed and self._worker.is_alive(),
                "queued": self._queue.qsize(),
                "last_result": self._last_result,
            }

    def wait_until_idle(self, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self._queue.unfinished_tasks == 0:
                return True
            time.sleep(0.01)
        return self._queue.unfinished_tasks == 0

    def shutdown(self, timeout=2.0):
        with self._lock:
            if self._closed:
                return
            self._closed = True
        try:
            self._queue.put(self._stop, timeout=timeout)
        except queue.Full:
            return
        self._worker.join(timeout=timeout)

    def _remember(self, request_id):
        self._seen.add(request_id)
        self._seen_order.append(request_id)
        while len(self._seen_order) > self._dedupe_size:
            self._seen.discard(self._seen_order.popleft())

    def _run(self):
        while True:
            item = self._queue.get()
            try:
                if item is self._stop:
                    return
                text, request_id = item
                try:
                    result = self._speaker(text)
                except Exception as error:
                    result = {"ok": False, "error": str(error)}
                with self._lock:
                    self._last_result = {
                        "request_id": request_id,
                        "result": result,
                    }
            finally:
                self._queue.task_done()
