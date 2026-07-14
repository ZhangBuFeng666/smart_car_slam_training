#!/usr/bin/env python3
"""两阶段：停车检测 + 箭头方向分类。

Stage1: parking detect → 找 direction_arrow 框
Stage2: 裁剪框 → arrow_cls 分类 → go_straight / turn_left / turn_right
可选：多帧投票，输出稳定转向动作

用法（yolov5 根目录）:
  # 摄像头
  python tools/detect_with_arrow_dir.py --source 0 --device 0

  # 图片/视频
  python tools/detect_with_arrow_dir.py --source path/to/img_or_video --device 0
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter, deque
from pathlib import Path

import torch
import torch.nn.functional as F

FILE = Path(__file__).resolve()
ROOT = FILE.parents[1]
if str(ROOT) not in sys.path:
    sys.path.append(str(ROOT))

from models.common import DetectMultiBackend
from ultralytics.utils.plotting import Annotator, colors
from utils.augmentations import classify_transforms
from utils.dataloaders import LoadImages, LoadStreams
from utils.general import (
    LOGGER,
    check_img_size,
    check_imshow,
    colorstr,
    cv2,
    increment_path,
    non_max_suppression,
    scale_boxes,
)
from utils.torch_utils import select_device, smart_inference_mode

# 检测模型中箭头类 id（parking_dataset）
ARROW_CLS_ID = 5

# 方向 → 控制动作（可按车端协议改）
ACTION = {
    "go_straight": "FORWARD",
    "turn_left": "LEFT",
    "turn_right": "RIGHT",
}


def crop_xyxy(im0, xyxy, pad: float = 0.08):
    """从原图裁剪框，轻微外扩。"""
    h, w = im0.shape[:2]
    x1, y1, x2, y2 = map(float, xyxy)
    bw, bh = x2 - x1, y2 - y1
    x1 = max(0, int(x1 - bw * pad))
    y1 = max(0, int(y1 - bh * pad))
    x2 = min(w, int(x2 + bw * pad))
    y2 = min(h, int(y2 + bh * pad))
    if x2 <= x1 or y2 <= y1:
        return None
    return im0[y1:y2, x1:x2].copy()


@smart_inference_mode()
def classify_crop(cls_model, transform, crop_bgr, device):
    """单张 crop → (name, conf)。"""
    # classify_transforms: BGR HWC → normalized CHW tensor
    im = transform(crop_bgr)
    im = im.unsqueeze(0).to(device)
    if cls_model.fp16:
        im = im.half()
    logits = cls_model(im)
    if isinstance(logits, (list, tuple)):
        logits = logits[0]
    prob = F.softmax(logits.float(), dim=1)[0]
    idx = int(prob.argmax())
    conf = float(prob[idx])
    name = cls_model.names[idx]
    if isinstance(name, (list, tuple)):
        name = name[0]
    return str(name), conf


class VoteBuffer:
    """最近 N 帧方向投票，抑制闪烁。"""

    def __init__(self, window: int = 5, min_count: int = 3):
        self.buf: deque[str] = deque(maxlen=window)
        self.min_count = min_count

    def push(self, label: str | None) -> str | None:
        if label:
            self.buf.append(label)
        if not self.buf:
            return None
        name, cnt = Counter(self.buf).most_common(1)[0]
        return name if cnt >= self.min_count else None


@smart_inference_mode()
def run(opt):
    source = str(opt.source)
    webcam = source.isnumeric() or source.endswith(".streams")
    save_img = not opt.nosave and not source.endswith(".txt")
    save_dir = increment_path(Path(opt.project) / opt.name, exist_ok=opt.exist_ok)
    save_dir.mkdir(parents=True, exist_ok=True)

    device = select_device(opt.device)

    # ---- Stage1 detect ----
    det_model = DetectMultiBackend(opt.weights, device=device, data=opt.data, fp16=opt.half)
    stride, det_names, pt = det_model.stride, det_model.names, det_model.pt
    # warmup 需要 (h, w)；CLI 传 int 时扩成正方形
    imgsz = [opt.imgsz, opt.imgsz] if isinstance(opt.imgsz, int) else list(opt.imgsz)
    imgsz = check_img_size(imgsz, s=stride)
    det_model.warmup(imgsz=(1, 3, *imgsz))

    # ---- Stage2 classify ----
    cls_model = DetectMultiBackend(opt.cls_weights, device=device, fp16=opt.half)
    cls_imgsz = opt.cls_imgsz
    transform = classify_transforms(cls_imgsz)
    cls_model.warmup(imgsz=(1, 3, cls_imgsz, cls_imgsz))
    LOGGER.info(f"cls names: {cls_model.names}")

    # dataloader
    if webcam:
        view_img = check_imshow(warn=True)
        dataset = LoadStreams(source, img_size=imgsz, stride=stride, auto=pt, vid_stride=opt.vid_stride)
        bs = len(dataset)
    else:
        view_img = opt.view_img
        dataset = LoadImages(source, img_size=imgsz, stride=stride, auto=pt, vid_stride=opt.vid_stride)
        bs = 1

    vote = VoteBuffer(window=opt.vote_window, min_count=opt.vote_min)
    vid_path, vid_writer = [None] * bs, [None] * bs

    for path, im, im0s, vid_cap, s in dataset:
        im_t = torch.from_numpy(im).to(device)
        im_t = im_t.half() if det_model.fp16 else im_t.float()
        im_t /= 255.0
        if im_t.ndimension() == 3:
            im_t = im_t[None]

        pred = det_model(im_t, augment=False)
        pred = non_max_suppression(
            pred, opt.conf_thres, opt.iou_thres, classes=None, agnostic=False, max_det=opt.max_det
        )

        for i, det in enumerate(pred):
            if webcam:
                p, im0 = path[i], im0s[i].copy()
            else:
                p, im0 = path, im0s.copy()
            p = Path(p)
            save_path = str(save_dir / p.name)
            annotator = Annotator(im0, line_width=opt.line_thickness, example=str(det_names))

            frame_dirs: list[str] = []

            if len(det):
                det[:, :4] = scale_boxes(im_t.shape[2:], det[:, :4], im0.shape).round()

                for *xyxy, conf, cls in reversed(det):
                    c = int(cls)
                    name = det_names[c] if isinstance(det_names, (list, dict)) else str(c)
                    if isinstance(det_names, dict):
                        name = det_names.get(c, str(c))

                    label = f"{name} {float(conf):.2f}"

                    # 箭头：二次分类方向
                    if c == ARROW_CLS_ID or name == "direction_arrow":
                        crop = crop_xyxy(im0, xyxy, pad=opt.crop_pad)
                        if crop is not None and crop.size > 0:
                            dname, dconf = classify_crop(cls_model, transform, crop, device)
                            if dconf >= opt.cls_conf:
                                label = f"{dname} {dconf:.2f}"
                                frame_dirs.append(dname)
                            else:
                                label = f"arrow? {dconf:.2f}"

                    annotator.box_label(xyxy, label, color=colors(c, True))

            # 本帧主方向：取置信最高的箭头已在 frame_dirs；多框时取众数
            frame_dir = None
            if frame_dirs:
                frame_dir = Counter(frame_dirs).most_common(1)[0][0]
            stable = vote.push(frame_dir)
            action = ACTION.get(stable) if stable else None

            im0 = annotator.result()
            hud = f"dir={stable or '-'} action={action or '-'}"
            cv2.putText(im0, hud, (12, 32), cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 255, 0), 2)
            if frame_dir:
                LOGGER.info(f"{p.name}: frame={frame_dir} stable={stable} action={action}")

            if view_img:
                cv2.imshow(str(p), im0)
                if cv2.waitKey(1) & 0xFF == ord("q"):
                    return

            if save_img:
                if dataset.mode == "image":
                    cv2.imwrite(save_path, im0)
                else:
                    if vid_path[i] != save_path:
                        vid_path[i] = save_path
                        if isinstance(vid_writer[i], cv2.VideoWriter):
                            vid_writer[i].release()
                        if vid_cap:
                            fps = vid_cap.get(cv2.CAP_PROP_FPS) or 30
                            w = int(vid_cap.get(cv2.CAP_PROP_FRAME_WIDTH))
                            h = int(vid_cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
                        else:
                            fps, w, h = 30, im0.shape[1], im0.shape[0]
                        save_path = str(Path(save_path).with_suffix(".mp4"))
                        vid_writer[i] = cv2.VideoWriter(
                            save_path, cv2.VideoWriter_fourcc(*"mp4v"), fps, (w, h)
                        )
                    vid_writer[i].write(im0)

    LOGGER.info(f"Results saved to {colorstr('bold', save_dir)}")


def parse_opt():
    p = argparse.ArgumentParser()
    p.add_argument(
        "--weights",
        type=str,
        default=str(ROOT / "runs/train/parking_base/weights/best_jetson.pt"),
        help="检测权重",
    )
    p.add_argument(
        "--cls-weights",
        type=str,
        default=str(ROOT / "runs/train-cls/arrow_dir2/weights/best.pt"),
        help="箭头方向分类权重",
    )
    p.add_argument("--source", type=str, default="0", help="0/图片/视频/目录")
    p.add_argument("--data", type=str, default=str(ROOT / "datasets/parking_dataset/data.yaml"))
    p.add_argument("--imgsz", type=int, default=640)
    p.add_argument("--cls-imgsz", type=int, default=128, help="训练时 --img 128")
    p.add_argument("--conf-thres", type=float, default=0.25)
    p.add_argument("--cls-conf", type=float, default=0.5, help="方向分类最低置信度")
    p.add_argument("--iou-thres", type=float, default=0.45)
    p.add_argument("--max-det", type=int, default=1000)
    p.add_argument("--device", default="0")
    p.add_argument("--view-img", action="store_true")
    p.add_argument("--nosave", action="store_true")
    p.add_argument("--half", action="store_true")
    p.add_argument("--project", default=str(ROOT / "runs/detect-arrow"))
    p.add_argument("--name", default="exp")
    p.add_argument("--exist-ok", action="store_true")
    p.add_argument("--line-thickness", type=int, default=2)
    p.add_argument("--vid-stride", type=int, default=1)
    p.add_argument("--crop-pad", type=float, default=0.08)
    p.add_argument("--vote-window", type=int, default=5, help="多帧投票窗口")
    p.add_argument("--vote-min", type=int, default=3, help="投票最少票数才输出动作")
    return p.parse_args()


if __name__ == "__main__":
    opt = parse_opt()
    run(opt)