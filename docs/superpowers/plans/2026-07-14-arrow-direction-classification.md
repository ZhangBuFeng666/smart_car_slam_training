# 箭头方向二阶段识别 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 保留停车场九类检测，为方向箭头增加直行、左转、右转分类并展示到 Android APP。

**Architecture:** 原检测 ONNX 负责全部九类目标；仅将 `direction_arrow` 原图裁剪送入分类 ONNX。视觉服务输出可选方向字段并进行五帧三票稳定，APP 兼容解析并显示中文方向。

**Tech Stack:** Python 3、YOLOv5、ONNX、OpenCV DNN、Kotlin、Android Canvas、JUnit、unittest

---

### Task 1: 分类模型 ONNX 转换与验证

**Files:**
- Input: `E:/小车实训指导/model/arrow_cls_best.pt`
- Create: `E:/小车实训指导/model/arrow_cls_best.onnx`

- [ ] 检查权重类别名称与输入尺寸，确认三类顺序。
- [ ] 使用 YOLOv5 `export.py --include onnx --imgsz 128` 转换分类模型。
- [ ] 通过 OpenCV DNN 加载 ONNX，并验证输出维度为三类。

### Task 2: 独立箭头分类后端

**Files:**
- Create: `jetson_perception/arrow_classifier.py`
- Create: `jetson_perception/test_arrow_classifier.py`

- [ ] 先写失败测试，覆盖 8% 裁剪扩展、softmax、置信度阈值与三类名称。
- [ ] 运行 `python -m unittest jetson_perception.test_arrow_classifier`，确认因实现缺失而失败。
- [ ] 实现 OpenCV DNN 分类器，输入 BGR 裁剪并返回方向和置信度。
- [ ] 重跑测试并确认通过。

### Task 3: 多帧方向稳定与视觉接口

**Files:**
- Modify: `jetson_perception/vision_service.py`
- Modify: `jetson_perception/test_vision_service.py`

- [ ] 先写失败测试，定义箭头检测项的 `direction`、`direction_confidence`、`stable_direction` 字段及五帧三票行为。
- [ ] 扩展 `Detection` 和视觉状态序列化，非箭头字段保持缺省。
- [ ] 在检测循环中只对 `direction_arrow` 裁剪分类，并在无有效箭头时清理旧投票。
- [ ] 添加 `--arrow-weights`、`--arrow-img-size`、`--arrow-confidence` 参数；分类模型失败时主检测继续运行。
- [ ] 运行全部 `jetson_perception` 测试。

### Task 4: Android 数据模型与中文显示

**Files:**
- Modify: `app/src/main/java/com/example/icarcontroller/VisionApi.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/VisionUiSpec.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/VisionDetectionOverlayView.kt`
- Modify: `app/src/main/java/com/example/icarcontroller/MainActivity.kt`
- Modify: `app/src/test/java/com/example/icarcontroller/VisionApiTest.java`
- Modify: `app/src/test/java/com/example/icarcontroller/VisionUiSpecTest.java`
- Modify: `app/src/test/java/com/example/icarcontroller/VisionOverlaySpecTest.java`

- [ ] 先写失败测试，验证三个可选方向字段及中文名称。
- [ ] 扩展 JSON 解析，旧服务响应继续可用。
- [ ] 检测列表显示“方向箭头 · 左转 · 93%”。
- [ ] 叠加框优先显示稳定方向，其次显示单帧方向。
- [ ] 运行 Android 单元测试并构建 APK。

### Task 5: Jetson 部署与实机验证

**Files:**
- Modify: `jetson_perception/icar-vision.service`
- Modify: `jetson_perception/README.md`

- [ ] 上传分类 ONNX 和视觉服务文件到 `/home/jetson/icar_vision`。
- [ ] 服务命令增加 `--arrow-weights /home/jetson/icar_vision/models/arrow_cls_best.onnx`。
- [ ] 重启 `icar-vision.service`，确认 `/health` 同时报告主模型和分类模型就绪。
- [ ] 安装新版 APK，使用三类箭头实测 APP 文本和叠加框。
- [ ] 视觉运行时测量前进、停止 HTTP 往返，目标低于 20 ms；检查视觉服务内存低于 300 MB。

### Task 6: 完整回归与清理

**Files:**
- Review all files above

- [ ] 运行 Jetson 控制服务 117 项回归测试。
- [ ] 运行视觉服务全部测试和 Android 全部单元测试。
- [ ] 删除临时转换与诊断文件，确认不包含凭据。
- [ ] 汇总实测结果；未经用户要求不提交或推送 Git。
