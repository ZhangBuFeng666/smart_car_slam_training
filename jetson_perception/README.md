# Jetson 边缘视觉服务

该服务使用 `parking_detect` 训练得到的 YOLOv5 `best.pt`，从小车控制服务的 MJPEG 画面读取帧，在 Jetson CUDA 上推理，并输出标注视频和 JSON 检测结果。

## 接口

- `GET /health`
- `GET /vision/detections`
- `GET /vision/stream`

## 部署目录

```text
/home/jetson/icar_vision/
├── vision_service.py
└── models/best.pt
```

YOLOv5 代码位于 `/home/jetson/yolov5-7.0`，控制服务必须先监听 `8000`。
