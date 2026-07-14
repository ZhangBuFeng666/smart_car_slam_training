import re
import time

import httpx
import pytest

from jarvis_agent.config import Settings
from jarvis_agent.vision_context import (
    SceneTracker,
    VisionContextCollector,
    horizontal_position,
    intersection_over_union,
)


def detection(label, box, confidence=0.9):
    return {"label": label, "confidence": confidence, "box": box}


def box(left, top=0.2, right=None, bottom=0.8):
    if right is None:
        right = left + 0.2
    return {"left": left, "top": top, "right": right, "bottom": bottom}


def test_geometry_helpers_calculate_overlap_and_horizontal_thirds():
    assert intersection_over_union(box(0.0), box(0.1, right=0.3)) == pytest.approx(1 / 3)
    assert intersection_over_union(box(0.0), box(0.4)) == 0.0
    assert horizontal_position(box(0.02, right=0.22)) == "left"
    assert horizontal_position(box(0.4, right=0.6)) == "center"
    assert horizontal_position(box(0.76, right=0.96)) == "right"


def test_keeps_multiple_labels_and_returns_a_complete_summary():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update(
        [
            detection("no_parking_sign", box(0.75, top=0.1, right=0.9, bottom=0.4)),
            detection("car", box(0.2, top=0.3, right=0.6, bottom=0.9), 0.91),
        ],
        observed_at=10.0,
    )

    scene = tracker.snapshot(now=10.0)

    assert scene["state"] == "LIVE"
    assert scene["observed_at"] == 10.0
    assert [item["label"] for item in scene["objects"]] == ["car", "no_parking_sign"]
    assert scene["summary"] == {
        "total": 2,
        "by_class": {"car": 1, "no_parking_sign": 1},
    }
    assert scene["recent_objects"] == []
    assert scene["error"] is None


def test_tracks_two_same_class_objects_with_one_to_one_assignment():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update(
        [detection("car", box(0.02, right=0.22)), detection("car", box(0.76, right=0.96))],
        observed_at=1.0,
    )
    first = tracker.snapshot(now=1.0)["objects"]
    ids_by_position = {item["position"]: item["track_id"] for item in first}

    tracker.update(
        [detection("car", box(0.74, right=0.94)), detection("car", box(0.04, right=0.24))],
        observed_at=1.5,
    )
    second = tracker.snapshot(now=1.5)["objects"]

    assert len(second) == 2
    assert {item["position"]: item["track_id"] for item in second} == ids_by_position
    assert all(re.fullmatch(r"car-\d+", item["track_id"]) for item in second)


def test_assignment_preserves_maximum_number_of_existing_tracks():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update(
        [detection("car", box(0.2, right=0.5)), detection("car", box(0.45, right=0.75))],
        observed_at=1.0,
    )
    first = tracker.snapshot(now=1.0)["objects"]
    left_track = min(first, key=lambda item: item["box"]["left"])["track_id"]
    right_track = max(first, key=lambda item: item["box"]["left"])["track_id"]

    tracker.update(
        [detection("car", box(0.3, right=0.55)), detection("car", box(0.1, right=0.3))],
        observed_at=1.5,
    )
    second = tracker.snapshot(now=1.5)["objects"]
    ids_by_left = {item["box"]["left"]: item["track_id"] for item in second}

    assert {item["track_id"] for item in second} == {left_track, right_track}
    assert ids_by_left[0.1] == left_track
    assert ids_by_left[0.3] == right_track


def test_dense_assignment_is_polynomial_and_preserves_existing_ids():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    shared_box = box(0.2, right=0.8)
    detections = [detection("dense_car", shared_box) for _ in range(8)]
    tracker.update(detections, observed_at=1.0)
    original_ids = {
        item["track_id"] for item in tracker.snapshot(now=1.0)["objects"]
    }

    started_at = time.perf_counter()
    tracker.update(detections, observed_at=2.0)
    elapsed = time.perf_counter() - started_at
    updated_ids = {
        item["track_id"] for item in tracker.snapshot(now=2.0)["objects"]
    }

    assert elapsed < 0.25
    assert updated_ids == original_ids


def test_uses_center_distance_when_boxes_do_not_overlap():
    tracker = SceneTracker(stable_frames=2, stale_after=3.0, forget_after=5.0)
    tracker.update([detection("cone", box(0.10, right=0.15))], observed_at=2.0)
    tracker.update([detection("cone", box(0.25, right=0.30))], observed_at=2.5)

    objects = tracker.snapshot(now=2.5)["objects"]

    assert len(objects) == 1
    assert objects[0]["stable_for_ms"] == 500


def test_requires_consecutive_observations_and_resets_after_a_missed_frame():
    tracker = SceneTracker(stable_frames=2, stale_after=3.0, forget_after=5.0)
    car = detection("car", box(0.3, right=0.6))

    tracker.update([car], observed_at=1.0)
    assert tracker.snapshot(now=1.0)["objects"] == []
    tracker.update([], observed_at=1.5)
    tracker.update([car], observed_at=2.0)
    assert tracker.snapshot(now=2.0)["objects"] == []
    tracker.update([car], observed_at=2.5)

    objects = tracker.snapshot(now=2.5)["objects"]
    assert len(objects) == 1
    assert objects[0]["stable_for_ms"] == 500


def test_object_fields_use_latest_detection_and_copy_normalized_box():
    tracker = SceneTracker(stable_frames=2, stale_after=3.0, forget_after=5.0)
    first_box = box(0.35, right=0.55)
    latest_box = box(0.4, right=0.6)
    tracker.update([detection("car", first_box, 0.8)], observed_at=5.0)
    tracker.update([detection("car", latest_box, 0.95)], observed_at=5.75)
    latest_box["left"] = 0.0

    item = tracker.snapshot(now=5.75)["objects"][0]

    assert item == {
        "track_id": item["track_id"],
        "label": "car",
        "confidence": 0.95,
        "position": "center",
        "box": {"left": 0.4, "top": 0.2, "right": 0.6, "bottom": 0.8},
        "stable_for_ms": 750,
        "visible": True,
    }


def test_stale_scene_hides_current_objects_then_forgets_recent_objects():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update([detection("car", box(0.3, right=0.6))], observed_at=1.0)

    stale = tracker.snapshot(now=4.1)

    assert stale["state"] == "STALE"
    assert stale["objects"] == []
    assert stale["summary"] == {"total": 0, "by_class": {}}
    assert stale["recent_objects"][0]["label"] == "car"
    assert stale["recent_objects"][0]["visible"] is False
    assert tracker.snapshot(now=6.1)["recent_objects"] == []


def test_future_snapshot_does_not_destroy_data_for_an_earlier_snapshot():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update([detection("car", box(0.3, right=0.6))], observed_at=10.0)
    track_id = tracker.snapshot(now=10.0)["objects"][0]["track_id"]

    assert tracker.snapshot(now=100.0)["recent_objects"] == []
    earlier = tracker.snapshot(now=10.0)

    assert earlier["state"] == "LIVE"
    assert earlier["objects"][0]["track_id"] == track_id


def test_regressive_update_does_not_overwrite_newer_track_state():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update([detection("car", box(0.3, right=0.5), 0.8)], observed_at=10.0)
    tracker.update([detection("car", box(0.31, right=0.51), 0.95)], observed_at=11.0)
    expected = tracker.snapshot(now=11.0)["objects"][0]

    tracker.update([detection("car", box(0.32, right=0.52), 0.1)], observed_at=9.0)
    scene = tracker.snapshot(now=11.0)

    assert scene["observed_at"] == 11.0
    assert scene["objects"] == [expected]
    assert scene["objects"][0]["stable_for_ms"] == 1000


def test_disappeared_stable_track_is_recent_while_scene_is_live():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update([detection("car", box(0.3, right=0.6))], observed_at=1.0)
    tracker.update([], observed_at=2.0)

    scene = tracker.snapshot(now=2.0)

    assert scene["state"] == "LIVE"
    assert scene["objects"] == []
    assert scene["recent_objects"][0]["visible"] is False


def test_mark_unavailable_without_a_successful_frame_reports_unavailable():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    assert tracker.snapshot(now=1.0)["state"] == "STARTING"

    tracker.mark_unavailable(RuntimeError("camera offline"), observed_at=1.0)
    scene = tracker.snapshot(now=1.0)

    assert scene["state"] == "UNAVAILABLE"
    assert scene["observed_at"] is None
    assert scene["objects"] == []
    assert scene["error"] == "camera offline"


def test_mark_unavailable_keeps_fresh_frame_live_then_reports_unavailable():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update([detection("car", box(0.3, right=0.6))], observed_at=10.0)
    tracker.mark_unavailable("temporary failure", observed_at=11.0)

    fresh = tracker.snapshot(now=12.0)
    stale = tracker.snapshot(now=13.1)

    assert fresh["state"] == "LIVE"
    assert len(fresh["objects"]) == 1
    assert fresh["error"] == "temporary failure"
    assert stale["state"] == "UNAVAILABLE"
    assert stale["objects"] == []
    assert stale["error"] == "temporary failure"


def test_mark_unavailable_ignores_failure_older_than_latest_success():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update([detection("car", box(0.3, right=0.6))], observed_at=10.0)

    tracker.mark_unavailable("delayed failure", observed_at=9.0)
    fresh = tracker.snapshot(now=12.0)
    stale = tracker.snapshot(now=13.1)

    assert fresh["state"] == "LIVE"
    assert fresh["error"] is None
    assert stale["state"] == "STALE"
    assert stale["error"] is None


def test_ignores_malformed_detections_without_dropping_valid_ones():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    malformed = [
        None,
        "car",
        {},
        detection("", box(0.1)),
        detection("car", box(0.1), confidence=float("nan")),
        detection("car", {"left": -0.1, "top": 0.2, "right": 0.2, "bottom": 0.8}),
        detection("car", {"left": 0.4, "top": 0.2, "right": 0.3, "bottom": 0.8}),
        {"label": "car", "confidence": 0.8, "box": {"left": 0.1}},
    ]

    tracker.update(malformed + [detection("cone", box(0.7, right=0.85))], observed_at=1.0)

    assert [item["label"] for item in tracker.snapshot(now=1.0)["objects"]] == ["cone"]


def test_snapshot_and_returned_objects_do_not_leak_mutable_state():
    tracker = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    tracker.update([detection("car", box(0.3, right=0.6))], observed_at=1.0)
    scene = tracker.snapshot(now=1.0)
    original_track_id = scene["objects"][0]["track_id"]

    scene["objects"][0]["box"]["left"] = 0.0
    scene["objects"].clear()
    scene["summary"]["by_class"]["car"] = 99
    scene["recent_objects"].append({"label": "fake"})

    fresh = tracker.snapshot(now=1.0)
    assert fresh["objects"][0]["box"]["left"] == 0.3
    assert fresh["objects"][0]["track_id"] == original_track_id
    assert fresh["summary"] == {"total": 1, "by_class": {"car": 1}}
    assert fresh["recent_objects"] == []


def test_track_ids_are_unique_across_tracker_instances_in_one_process():
    first = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    second = SceneTracker(stable_frames=1, stale_after=3.0, forget_after=5.0)
    target = detection("process_unique_label", box(0.2))
    first.update([target], observed_at=1.0)
    second.update([target], observed_at=1.0)

    first_id = first.snapshot(now=1.0)["objects"][0]["track_id"]
    second_id = second.snapshot(now=1.0)["objects"][0]["track_id"]

    assert first_id != second_id
    assert re.fullmatch(r"process_unique_label-\d+", first_id)
    assert re.fullmatch(r"process_unique_label-\d+", second_id)


def collector_settings():
    return Settings(
        _env_file=None,
        vision_stable_frames=1,
        vision_poll_interval_seconds=0.01,
        vision_stale_after_seconds=0.2,
        vision_forget_after_seconds=0.5,
    )


def test_collector_ingests_all_detections_and_records_network_failure():
    responses = iter(
        [
            {
                "state": "live",
                "detections": [
                    detection("car", box(0.2, right=0.6)),
                    detection("no_parking_sign", box(0.75, right=0.9)),
                ],
            },
            httpx.ConnectError("vision offline"),
        ]
    )

    def request_once():
        response = next(responses)
        if isinstance(response, Exception):
            raise response
        return response

    collector = VisionContextCollector(collector_settings(), request_once=request_once)
    collector.poll_once(now=10.0)
    scene = collector.snapshot(now=10.0)
    assert scene["summary"]["total"] == 2
    assert {item["label"] for item in scene["objects"]} == {
        "car",
        "no_parking_sign",
    }

    collector.poll_once(now=10.5)
    assert collector.snapshot(now=10.5)["state"] == "UNAVAILABLE"
    assert "vision offline" in collector.health(now=10.5)["error"]


def test_collector_rejects_unusable_payload_but_keeps_thread_recoverable():
    responses = iter(
        [
            {"state": "live", "detections": "not-a-list"},
            {"state": "live", "detections": [detection("car", box(0.2))]},
        ]
    )
    collector = VisionContextCollector(
        collector_settings(), request_once=lambda: next(responses)
    )

    collector.poll_once(now=1.0)
    assert collector.snapshot(now=1.0)["state"] == "UNAVAILABLE"
    collector.poll_once(now=1.1)
    assert collector.snapshot(now=1.1)["state"] == "LIVE"
    assert collector.health(now=1.1)["error"] is None


def test_collector_start_and_stop_are_idempotent():
    calls = []

    def request_once():
        calls.append(1)
        return {"state": "live", "detections": []}

    collector = VisionContextCollector(collector_settings(), request_once=request_once)
    collector.start()
    collector.start()
    deadline = time.time() + 0.5
    while not calls and time.time() < deadline:
        time.sleep(0.005)
    collector.stop()
    collector.stop()

    assert calls
    assert collector.is_running is False
