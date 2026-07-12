import argparse
from datetime import datetime, timezone
from typing import Dict, Iterable

import httpx


def parking_lot_events(mission_id: str) -> Iterable[Dict[str, object]]:
    base = {
        "mission_id": mission_id,
        "source": "front_camera",
        "confidence": 0.92,
        "position": "parking aisle center",
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "metadata": {"scenario": "parking-lot"},
    }
    yield {
        **base,
        "event_type": "obstacle",
        "label": "paper box",
        "track_id": "sim-paper-box-001",
        "image_path": "/var/lib/jarvis/simulator/paper-box.jpg",
    }
    yield {
        **base,
        "event_type": "standing_water",
        "label": "standing water",
        "track_id": "sim-standing-water-001",
        "image_path": "/var/lib/jarvis/simulator/standing-water.jpg",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Post simulated Jarvis vision events.")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--mission-id", required=True)
    parser.add_argument("--scenario", default="parking-lot", choices=["parking-lot"])
    args = parser.parse_args()

    headers = {"Authorization": "Bearer %s" % args.token}
    with httpx.Client(base_url=args.base_url.rstrip("/"), headers=headers, timeout=10) as client:
        for event in parking_lot_events(args.mission_id):
            response = client.post("/api/v1/vision-events", json=event)
            response.raise_for_status()
            print(response.text)


if __name__ == "__main__":
    main()
