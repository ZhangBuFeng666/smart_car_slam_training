# 小车边缘视觉部署实施计划

## 目标

在 Jetson 主机部署 YOLOv5 视觉服务，并将标注视频和真实检测结果接入 Android APP 视觉页。

## 任务

1. 新建 `jetson_perception` Python 服务，测试检测结果序列化、统计和 HTTP 路由。
2. 使用 `parking_detect` YOLOv5 代码实现 CUDA FP16 推理、NMS、标注和最新帧缓存。
3. 添加 `icar-vision.service`、部署说明和模型校验。
4. 新增 Android `VisionApi`、检测模型解析测试和 `CarApi` 视觉 URL 测试。
5. 将 APP 视觉页替换为真实 MJPEG 标注流、模型状态、统计与检测列表。
6. 在 Jetson 上传服务，启动 8000 与 8200，验证健康、视频像素和检测接口。
7. 构建并安装 APK，在手机上验证视觉页。

## 验证命令

```powershell
python -m unittest discover -s jetson_perception -p "test_*.py"
python -m unittest discover -s jetson_server -p "test_*.py"
./gradlew.bat testDebugUnitTest assembleDebug
```

Jetson 验证：

```bash
curl http://127.0.0.1:8200/health
curl http://127.0.0.1:8200/vision/detections
curl --max-time 3 http://127.0.0.1:8200/vision/stream -o /tmp/vision.mjpeg
systemctl status icar-vision.service
```
