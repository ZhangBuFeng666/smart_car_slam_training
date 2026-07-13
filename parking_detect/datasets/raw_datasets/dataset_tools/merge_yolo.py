import hashlib
import shutil
from collections import Counter
from pathlib import Path

from config import CLASSES, DATASET_MAP

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT.parent / "parking_dataset"
IMAGE_EXTENSIONS = {".bmp", ".jpeg", ".jpg", ".png", ".tif", ".tiff", ".webp"}
SPLITS = ("train", "valid", "val", "test")


def source_key(dataset, image):
    """Group Roboflow augmentations of one source image into the same split."""
    original_stem = image.stem.rsplit(".rf.", 1)[0]
    return f"{dataset}/{original_stem}"


def target_split(key):
    """Assign source-image groups deterministically with an 80/10/10 ratio."""
    bucket = int(hashlib.sha1(key.encode()).hexdigest()[:8], 16) % 10
    return "train" if bucket < 8 else "val" if bucket == 8 else "test"


def remap_label(label_path, class_map):
    lines = []
    for line_number, line in enumerate(label_path.read_text(encoding="utf-8").splitlines(), 1):
        values = line.split()
        if len(values) < 5 or len(values) % 2 == 0:
            raise ValueError(f"{label_path}:{line_number}: invalid YOLO label")

        old_class = int(values[0])
        if old_class not in class_map:
            raise ValueError(f"{label_path}:{line_number}: unknown class {old_class}")

        coordinates = [float(value) for value in values[1:]]
        if not all(0 <= value <= 1 for value in coordinates):
            raise ValueError(f"{label_path}:{line_number}: coordinates outside [0, 1]")

        if len(values) == 5:
            box = coordinates
        else:
            xs, ys = coordinates[::2], coordinates[1::2]
            x_min, x_max, y_min, y_max = min(xs), max(xs), min(ys), max(ys)
            box = [(x_min + x_max) / 2, (y_min + y_max) / 2, x_max - x_min, y_max - y_min]
        if box[2] <= 0 or box[3] <= 0:
            raise ValueError(f"{label_path}:{line_number}: box has no area")

        class_name = class_map[old_class]
        if class_name is not None:
            lines.append(f"{CLASSES[class_name]} " + " ".join(f"{value:.10g}" for value in box))
    return lines


def collect_samples():
    samples = []
    for dataset, class_map in DATASET_MAP.items():
        dataset_root = ROOT / dataset
        for source_split in SPLITS:
            images_dir = dataset_root / source_split / "images"
            labels_dir = dataset_root / source_split / "labels"
            if not images_dir.is_dir():
                continue

            for image in sorted(images_dir.iterdir()):
                if not image.is_file() or image.suffix.lower() not in IMAGE_EXTENSIONS:
                    continue
                label = labels_dir / f"{image.stem}.txt"
                if not label.is_file():
                    raise FileNotFoundError(f"Missing label for {image}")
                samples.append((dataset, image, remap_label(label, class_map)))
    return samples


def write_dataset(samples):
    if OUT.exists():
        shutil.rmtree(OUT)
    for split in ("train", "val", "test"):
        (OUT / "images" / split).mkdir(parents=True)
        (OUT / "labels" / split).mkdir(parents=True)

    image_counts = Counter()
    instance_counts = Counter()
    negative_counts = Counter()
    seen_content = set()

    for index, (dataset, image, labels) in enumerate(samples):
        digest = hashlib.sha256(image.read_bytes()).hexdigest()
        if digest in seen_content:
            continue
        seen_content.add(digest)

        split = target_split(source_key(dataset, image))
        filename = f"{dataset}_{index:06d}{image.suffix.lower()}"
        shutil.copy2(image, OUT / "images" / split / filename)
        (OUT / "labels" / split / f"{Path(filename).stem}.txt").write_text(
            "\n".join(labels) + ("\n" if labels else ""), encoding="utf-8"
        )

        image_counts[split] += 1
        if not labels:
            negative_counts[split] += 1
        for label in labels:
            instance_counts[(split, int(label.split()[0]))] += 1

    yaml = "path: datasets/parking_dataset\ntrain: images/train\nval: images/val\ntest: images/test\n\nnames:\n"
    yaml += "".join(f"  {class_id}: {name}\n" for name, class_id in CLASSES.items())
    (OUT / "data.yaml").write_text(yaml, encoding="utf-8")
    return image_counts, instance_counts, negative_counts, len(seen_content)


def main():
    samples = collect_samples()
    image_counts, instance_counts, negative_counts, unique_images = write_dataset(samples)

    print(f"Output: {OUT}")
    print(f"Source images: {len(samples)}, unique images: {unique_images}")
    for split in ("train", "val", "test"):
        print(f"{split}: {image_counts[split]} images ({negative_counts[split]} negative)")
        for name, class_id in CLASSES.items():
            print(f"  {class_id} {name}: {instance_counts[(split, class_id)]}")


if __name__ == "__main__":
    main()
