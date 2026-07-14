# Jetson 边缘视觉服务

该服务使用 `parking_detect` 训练得到的 YOLOv5 模型，从小车控制服务的 MJPEG 画面读取帧，并输出 JSON 检测结果和可选标注视频。

4GB Jetson 默认部署固定 416 输入的 `best.onnx`，使用 OpenCV DNN、2 个 CPU 线程和最多 3 FPS 检测。Android APP 直接播放 `8000/camera/stream` 原始视频，并根据 JSON 在本地叠加检测框与跨帧 `track_id`，使视频帧率与模型推理频率解耦。`best.pt` 保留为 CUDA 回退方案，但不作为常驻服务默认值。同一帧可返回多个检测目标（ONNX/PyTorch 路径均为多目标 NMS）。

## 接口

- `GET /health`
- `GET /vision/detections`（每条 detection 可含 `track_id` / `confirmed` / `hits`）
- `GET /vision/stream`

## 部署目录

```text
/home/jetson/icar_vision/
├── vision_service.py
├── opencv_yolo_backend.py
├── tracker.py
└── models/
    ├── best.pt
    └── best.onnx
```

YOLOv5 代码位于 `/home/jetson/yolov5-7.0`，控制服务必须先监听 `8000`。

## 箭头转向演示脚本

`arrow_turn_demo.py`：启动后低速直行搜寻；当视觉服务检出稳定的左/右转箭头且深度约 1 m 时，再直行 0.5 m，原地转 90°，然后立即停车。

状态写入 `/tmp/arrow_turn_status.json`，由控制服务 `GET /arrow_turn/status` 读出；APP 视觉页「箭头转向演示」按钮走与其它实训任务相同的 `GET /start/arrow_turn`、`GET /stop/arrow_turn`。

```bash
# 部署脚本到小车（控制服务启动任务前也会 docker cp）
scp arrow_turn_demo.py jetson@<IP>:/home/jetson/icar_vision/

# 命令行调试（容器内，需 camera + base）
source /opt/ros/foxy/setup.bash
cd /root   # 或 /home/jetson/icar_vision
python3 arrow_turn_demo.py --dry-run
python3 arrow_turn_demo.py --seek-speed 0.12 --linear-speed 0.12 --angular-speed 0.30
python3 arrow_turn_demo.py --simulate-distance 1.0 --dry-run
```

APP：打开「视觉」页 →「开始转向演示」（会先拉起 camera/base）→ 状态栏显示搜寻/直行/转向；运行中勿用摇杆。