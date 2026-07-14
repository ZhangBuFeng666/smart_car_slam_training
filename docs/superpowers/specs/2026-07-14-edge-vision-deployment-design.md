# 小车边缘视觉部署设计

## 目标

将 `model/best.pt` 部署到小车 Jetson，使小车独立完成停车场九类目标检测，并将实时标注画面和检测摘要反馈到 Android APP 视觉页面。

## 模型

模型为 YOLOv5 权重，输入尺寸 640，类别固定为：

1. parking_slot
2. car
3. no_parking_sign
4. entrance_sign
5. exit_sign
6. direction_arrow
7. stop_line
8. roadblock
9. danger_sign

推理代码使用仓库 `parking_detect/`，模型文件不提交 Git，部署到 `/home/jetson/icar_vision/models/best.pt`。

## 架构

Jetson 主机运行独立视觉服务，监听 `0.0.0.0:8200`。视觉服务从现有控制服务 `http://127.0.0.1:8000/camera/stream` 读取 MJPEG，避免与控制服务同时打开 `/dev/video0`。

视觉服务提供：

- `GET /health`：服务、模型、CUDA 和视频源状态；
- `GET /vision/detections`：最新帧检测框、类别、置信度、时间和统计；
- `GET /vision/stream`：带检测框的 MJPEG 视频；
- `POST /vision/reload`：重新加载模型。

服务使用 systemd 开机启动。模型推理异常不能影响底盘、导航、急停或原始摄像头服务。

## APP

APP 的视觉页面使用 `MjpegStreamView` 播放 `8200/vision/stream`，并定时读取 `8200/vision/detections`。页面显示：

- 当前模型状态与推理 FPS；
- 九类检测标签；
- 当前车辆、车位、标志和障碍物数量；
- 最近检测结果及置信度；
- 服务不可用时的明确错误状态。

APP 不生成模拟结果。视觉服务无数据时显示零值或等待状态。

## 安全与性能

- 默认置信度阈值 0.35，NMS IoU 0.45；
- 推理使用 CUDA FP16（可用时），否则回退 CPU FP32；
- 只保留最新一帧，网络或推理变慢时丢弃旧帧；
- 输出流限制帧率和 JPEG 质量，避免占满局域网；
- 服务跨域开放只读 GET 接口，模型重载限制为本机来源。

## 验收

- Jetson 能成功加载 `best.pt` 并识别九个类别；
- `8200/health` 返回模型和 CUDA 就绪；
- `8200/vision/stream` 有非空实时画面；
- `8200/vision/detections` 返回真实检测数据结构；
- APP 视觉页能显示标注视频、统计和状态；
- 视觉服务停止后，底盘控制与原始视频仍可运行。
