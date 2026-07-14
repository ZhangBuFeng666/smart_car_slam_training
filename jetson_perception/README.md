# Jetson 边缘视觉服务

该服务使用 `parking_detect` 训练得到的 YOLOv5 模型，从小车控制服务的 MJPEG 画面读取帧，并输出 JSON 检测结果和可选标注视频。

4GB Jetson 默认部署固定 416 输入的 `best.onnx`，使用 OpenCV DNN、2 个 CPU 线程和最多 3 FPS 检测。Android APP 直接播放 `8000/camera/stream` 原始视频，并根据 JSON 在本地叠加检测框，使视频帧率与模型推理频率解耦。`best.pt` 保留为 CUDA 回退方案，但不作为常驻服务默认值。

## 接口

- `GET /health`
- `GET /vision/detections`
- `GET /vision/stream`

## 部署目录

```text
/home/jetson/icar_vision/
├── vision_service.py
├── opencv_yolo_backend.py
└── models/
    ├── best.pt
    └── best.onnx
```

YOLOv5 代码位于 `/home/jetson/yolov5-7.0`，控制服务必须先监听 `8000`。
