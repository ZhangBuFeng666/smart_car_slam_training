import importlib.util

from speech_queue import SpeechQueue


def test_speech_queue_module_exists():
    assert importlib.util.find_spec("speech_queue") is not None


def test_speech_queue_plays_messages_in_order():
    played = []
    speech = SpeechQueue(
        lambda text: played.append(text) or {"ok": True},
        max_size=3,
    )
    try:
        assert speech.enqueue("第一条", "r1")["state"] == "queued"
        assert speech.enqueue("第二条", "r2")["state"] == "queued"
        assert speech.wait_until_idle(1.0)
        assert played == ["第一条", "第二条"]
    finally:
        speech.shutdown()


def test_speech_queue_deduplicates_request_ids():
    played = []
    speech = SpeechQueue(lambda text: played.append(text) or {"ok": True})
    try:
        assert speech.enqueue("原始消息", "same-id")["state"] == "queued"
        assert speech.enqueue("重复消息", "same-id")["state"] == "duplicate"
        assert speech.wait_until_idle(1.0)
        assert played == ["原始消息"]
    finally:
        speech.shutdown()


def test_speech_queue_continues_after_speaker_failure():
    played = []

    def speaker(text):
        played.append(text)
        if text == "失败消息":
            raise RuntimeError("speaker failed")
        return {"ok": True}

    speech = SpeechQueue(speaker)
    try:
        speech.enqueue("失败消息", "r1")
        speech.enqueue("后续消息", "r2")
        assert speech.wait_until_idle(1.0)
        assert played == ["失败消息", "后续消息"]
        assert speech.snapshot()["last_result"]["result"]["ok"] is True
    finally:
        speech.shutdown()
