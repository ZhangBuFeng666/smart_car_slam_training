#!/usr/bin/env python3
"""从 direction_arrow 检测标注裁剪箭头小图，生成分类训练集 (ImageFolder)。

用法（在 yolov5 根目录）:
  python tools/prepare_arrow_cls.py
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import cv2

# 与 datasets/raw_datasets/direction_arrow/data.yaml 一致
CLASS_NAMES = {
    0: "go_straight",
    1: "stop",
    2: "turn_left",
    3: "turn_right",
}

IMG_EXTS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}


def find_image(images_dir: Path, stem: str) -> Path | None:
    for ext in IMG_EXTS:
        p = images_dir / f"{stem}{ext}"
        if p.is_file():
            return p
        p2 = images_dir / f"{stem}{ext.upper()}"
        if p2.is_file():
            return p2
    # Roboflow 有时后缀大小写混用，扫一遍 stem
    for p in images_dir.glob(f"{stem}.*"):
        if p.suffix.lower() in IMG_EXTS:
            return p
    return None


def yolo_to_xyxy(xc, yc, w, h, img_w, img_h):
    bw, bh = w * img_w, h * img_h
    x1 = int(round(xc * img_w - bw / 2))
    y1 = int(round(yc * img_h - bh / 2))
    x2 = int(round(xc * img_w + bw / 2))
    y2 = int(round(yc * img_h + bh / 2))
    x1 = max(0, min(img_w - 1, x1))
    y1 = max(0, min(img_h - 1, y1))
    x2 = max(0, min(img_w, x2))
    y2 = max(0, min(img_h, y2))
    return x1, y1, x2, y2


def process_split(src_root: Path, split_in: str, split_out: str, out_root: Path, pad: float, skip: set[int]) -> Counter:
    labels_dir = src_root / split_in / "labels"
    images_dir = src_root / split_in / "images"
    counts: Counter = Counter()
    if not labels_dir.is_dir():
        print(f"[skip] no labels: {labels_dir}")
        return counts

    for label_path in sorted(labels_dir.glob("*.txt")):
        img_path = find_image(images_dir, label_path.stem)
        if img_path is None:
            print(f"[warn] image missing for {label_path.name}")
            continue
        img = cv2.imread(str(img_path))
        if img is None:
            print(f"[warn] cannot read {img_path}")
            continue
        ih, iw = img.shape[:2]
        lines = label_path.read_text(encoding="utf-8", errors="ignore").strip().splitlines()
        for i, line in enumerate(lines):
            parts = line.strip().split()
            if len(parts) < 5:
                continue
            cls_id = int(float(parts[0]))
            if cls_id in skip or cls_id not in CLASS_NAMES:
                continue
            xc, yc, w, h = map(float, parts[1:5])
            # 略扩边，贴近检测框不那么贴边
            w = min(1.0, w * (1 + pad))
            h = min(1.0, h * (1 + pad))
            x1, y1, x2, y2 = yolo_to_xyxy(xc, yc, w, h, iw, ih)
            if x2 - x1 < 4 or y2 - y1 < 4:
                continue
            crop = img[y1:y2, x1:x2]
            name = CLASS_NAMES[cls_id]
            dst_dir = out_root / split_out / name
            dst_dir.mkdir(parents=True, exist_ok=True)
            dst = dst_dir / f"{label_path.stem}_{i}.jpg"
            cv2.imwrite(str(dst), crop)
            counts[name] += 1
    return counts


def main():
    parser = argparse.ArgumentParser(description="Prepare arrow direction classification crops")
    parser.add_argument(
        "--src",
        type=Path,
        default=Path("datasets/raw_datasets/direction_arrow"),
        help="direction_arrow 检测数据集根目录",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("datasets/arrow_cls"),
        help="输出 ImageFolder 根目录",
    )
    parser.add_argument("--pad", type=float, default=0.05, help="裁剪框外扩比例")
    parser.add_argument(
        "--keep-stop",
        action="store_true",
        help="保留 Stop 类(id=1)；默认跳过，只保留直行/左/右",
    )
    args = parser.parse_args()

    skip = set() if args.keep_stop else {1}
    args.out.mkdir(parents=True, exist_ok=True)

    # YOLOv5 classify 需要 train/ + val/
    total = Counter()
    for split_in, split_out in (("train", "train"), ("valid", "val"), ("test", "val")):
        c = process_split(args.src, split_in, split_out, args.out, args.pad, skip)
        if c:
            print(f"{split_in} -> {split_out}: {dict(c)}")
            total.update(c)

    print("total crops:", dict(total))
    print("done ->", args.out.resolve())
    print("next: python classify/train.py --model yolov5s-cls.pt --data", args.out.as_posix(), "--epochs 30 --img 128 --batch-size 64")


if __name__ == "__main__":
    main()