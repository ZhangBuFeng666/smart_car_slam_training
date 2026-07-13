#!/usr/bin/env python3
import argparse
import json
import sys

from jetson_perception.jarvis_client import JarvisVisionClient
from jetson_perception.mock_detector import parking_lot_mock_detections
from jetson_perception.pipeline import PerceptionPipeline


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description="Run mock YOLO detections through the Jarvis vision pipeline.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8100")
    parser.add_argument("--token", required=True)
    parser.add_argument("--mission-id", required=True)
    parser.add_argument("--dry-run", action="store_true", help="Map events without posting to Jarvis.")
    parser.add_argument("--repeat-frames", type=int, default=3, help="Frames required for temporal confirmation.")
    args = parser.parse_args(argv)

    client = None if args.dry_run else JarvisVisionClient(args.base_url, args.token)
    pipeline = PerceptionPipeline(
        args.mission_id,
        client,
        required_frames=max(1, args.repeat_frames),
    )

    detections = parking_lot_mock_detections()
    results = []
    for _ in range(max(1, args.repeat_frames)):
        results = pipeline.process_detections(detections)

    print(json.dumps(results, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
