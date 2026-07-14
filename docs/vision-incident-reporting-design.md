# 视觉检测与灾情/违停上报设计

本文档整理当前**方向箭头检测**的实现方式，以及接下来要做的两项业务能力：

1. **火焰灾情上报**（置信度 + 连续帧确认 → 带时间与带框截图推到 APP；按 `track_id` 去重）
2. **车辆长时间停留禁停区**（车框与禁停区框持续重叠 → 上报；同样按身份去重）

并说明与现有小车系统（控制服务、视觉服务、Android APP、可选 Jarvis）如何衔接。

---

## 1. 现有系统全景

```text
┌─────────────┐     MJPEG      ┌──────────────────┐
│ Android APP │◄── :8000 ──────│ icar-control     │
│             │   camera/stream│ (jetson_server)  │
│ 本地叠框    │                │ Docker ROS 桥接  │
│ 事件列表    │                └────────┬─────────┘
└──────▲──────┘                         │ 取流
       │ JSON :8200                     ▼
       │ detections              /dev/video* 或相机
       │                        ┌──────────────────┐
       └────────────────────────│ icar-vision      │
                                │ vision_service   │
                                │ YOLO + 箭头分类  │
                                │ IoUTracker       │
                                └──────────────────┘
                                         │
                         可选事件上报 →  jarvis_agent(:8100)
                                         或新增 /vision/incidents
```

| 组件 | 路径 / 端口 | 职责 |
|------|-------------|------|
| 控制服务 | `jetson_server/`，`:8000` | 运动、任务、底盘、主机 MJPEG 原图 |
| 视觉服务 | `jetson_perception/`，`:8200` | YOLO 九类、箭头方向、`track_id`、检测 JSON/叠框图 |
| Android APP | `app/` | 播原图 + 拉 JSON 本地画框；后续展示灾情/违停卡片 |
| 箭头转向演示 | `arrow_turn_demo.py` | 读检测 + 深度，经 `:8000/move/*` 控车 |
| Jarvis（可选） | `jarvis_agent/`，`:8100` | 任务级 `vision_events` 入库，已按 `track_id` 查重 |

现场目录约定：

- 控制服务：`/home/jetson/icar_app_server/`
- 视觉服务：`/home/jetson/icar_vision/`
- ROS 容器：`fd7ba18044cd`（`icar-runtime`），不要默认使用已停的 `8b98`

---

## 2. 当前九类目标

训练与线上推理（`parking_detect` / `best.onnx`）类别：

| id | label | 说明 |
|----|--------|------|
| 0 | `parking_slot` | 车位 |
| 1 | `car` | 车辆 |
| 2 | `no_parking_sign` | **禁停标志牌**（不是地面禁停区） |
| 3 | `entrance_sign` | 入口牌 |
| 4 | `exit_sign` | 出口牌 |
| 5 | `direction_arrow` | 地面方向箭头（粗检） |
| 6 | `stop_line` | 停止线 |
| 7 | `roadblock` | 路障 |
| 8 | `danger_sign` | 危险标志牌（也就是火焰图片） |



---

## 3. 方向箭头方案（已实现）

### 3.1 目标

在保留九类检测的前提下，对 `direction_arrow` 再判方向：`go_straight` / `turn_left` / `turn_right`，并跨帧稳定后给 APP / 转向脚本使用。

### 3.2 两段式流水线

```text
相机帧
  → YOLOv5 ONNX（九类，含 direction_arrow + box）
  → 仅对 direction_arrow：裁剪框（外扩约 8%）
  → 箭头分类 ONNX（三类 softmax）
  → DirectionVoteBuffer（默认五帧窗口、至少 3 票同向）
  → IoUTracker 分配 track_id / confirmed / hits
  → JSON（/vision/detections）+ 可选叠框 MJPEG（/vision/stream）
```

关键代码：

- `jetson_perception/vision_service.py`：检测循环、`classify_arrow_detections`、序列化
- `jetson_perception/arrow_classifier.py`：裁剪、分类、投票缓冲
- `jetson_perception/tracker.py`：`IoUTracker`
- APP：`VisionApi.kt` / `VisionUiSpec.kt` / `VisionDetectionOverlayView.kt`

### 3.3 输出字段（箭头相关）

| 字段 | 含义 |
|------|------|
| `label` | 固定为 `direction_arrow` |
| `direction` | 当前帧分类结果 |
| `direction_confidence` | 分类置信度 |
| `stable_direction` | 投票稳定后的方向；未稳定可为空 |
| `track_id` | 跨帧同一目标 ID |
| `confirmed` / `hits` | 跟踪是否确认、命中次数 |
| `box` | 归一化坐标 `left/top/right/bottom` ∈ [0,1] |

### 3.4 与小车运动的连接（箭头转向演示）

`arrow_turn_demo.py`（在 ROS 容器内跑）：

1. 轮询 `http://127.0.0.1:8200/vision/detections`，挑选稳定左右转箭头。
2. 订阅 `/camera/depth/image_raw`（16UC1，单位 mm），在箭头框底部附近取中值深度。
3. 距离约 **1.0 ± 0.2 m** 且连续确认后：再直行约 0.5 m → 转 90° → 停车。
4. **运动经控制服务 HTTP**（`http://127.0.0.1:8000/move/front|turn_*|stop`），不要依赖脚本直发 `/cmd_vel`（底盘实际服从 icar 运动桥）。

浏览器可同时看带框流：`http://<小车IP>:8200/vision/stream`。APP 驾驶摇杆请勿同时抢控。

### 3.5 APP 显示侧

- 视频：`:8000/camera/stream` 原图（高帧率）。
- 检测：轮询 `:8200/vision/detections`，本地 Canvas 叠框与中文标签（含方向、`#track_id`）。
- 因此推理 FPS（约 2～3）与预览帧率解耦。

---

## 4. 跨帧跟踪 `track_id`（上报去重的基础）

`IoUTracker`（`tracker.py`）：

- 同类检测框按 IoU 关联，分配递增 `track_id`。
- `hits` 累积；达到确认阈值后 `confirmed=true`。
- 丢失若干帧后删除轨迹。

上报去重原则（两项新业务共用）：

> **同一 `track_id` + 同一事件类型，在冷却时间内只生成一条上报记录。**  
> 目标重新出现且拿不到旧 track（或已超过冷却）时可再次上报。

`tracker` 已预留面向 Jarvis 的字符串形式：`trk-<label>-<id>`（`to_event_track_id`）。

Jarvis 侧已有类似能力：`vision_events` 表按 `(mission_id, track_id, label, since)` 查近期是否已存在（`find_recent_event`）。新方案可复用该思路，不必每次都经过 LLM。

---

## 5. 待实现：火焰灾情上报

### 5.1 产品行为（按你的设想）

1. 画面出现火焰类目标。
2. 单帧置信度 ≥ 阈值（建议默认 **0.55～0.65**，可配置）。
3. **同一 `track_id` 连续满足 N 帧**（建议默认 **5** 帧；按视觉约 2～3 FPS，约 2 秒量级）。
4. 首次满足条件时生成一条灾情：
   - 事件类型：`fire`
   - 时间戳（设备本地 / UTC ISO8601）
   - `track_id`
   - 置信度（触发时刻或峰值）
   - **带检测框的 JPEG 截图**
5. 推到 APP 端展示（列表 + 大图）；冷却期内**不因同 track 反复刷事件**。

### 5.2 检测模型前提（必须先定）

当前九类**不含火焰**。推荐路径：

| 方案 | 做法 | 评价 |
|------|------|------|
| **A. 新增类 `flame`（推荐）** | 补标注 → 重训 / 增量训 → 导出 `best.onnx`，`PARKING_CLASSES` 同步扩展 | 语义清晰，和 `danger_sign` 不混 |
| B. 复用 `danger_sign` | 把火焰样本标进 danger | 易与危险牌混淆，不推荐长期 |

文档后续描述默认采用 **方案 A：`flame`**。在未上线新权重前，业务模块可先用假检测联调上报链路。

### 5.3 车端视觉侧实现要点

建议在 `vision_service` 旁增加**事件引擎**（可单文件 `incident_engine.py`，由 `VisionWorker` 每帧调用）：

```text
每帧 detections（已带 track_id）
  → 过滤 label==flame 且 conf>=FIRE_CONF
  → 按 track_id 累加 consecutive_hits；否则清零或衰减
  → consecutive_hits >= FIRE_CONFIRM_FRAMES 且该 track 不在 cooldown
  → 在当前帧上 draw boxes → JPEG
  → 写入事件队列 / 落盘 + HTTP POST
  → 标记 track_id 已上报，冷却例如 120～300 s
```

建议配置项：

| 参数 | 建议默认 | 说明 |
|------|----------|------|
| `fire_confidence` | 0.6 | 单帧阈值 |
| `fire_confirm_frames` | 5 | 连续帧 |
| `fire_cooldown_s` | 180 | 同 track 去重冷却 |
| `fire_require_confirmed_track` | true | 仅 `confirmed` 轨迹可上报，降低闪框误报 |

截图：用推理帧（与检测同源）画框后 `cv2.imencode`，保存例如：

```text
/home/jetson/icar_vision/incidents/fire/<timestamp>_trk<id>.jpg
```

### 5.4 与小车系统 / APP 的连接

推荐两条通道（可并存）：

**通道 1：视觉服务直接提供事件 API（APP 首选）**

| 接口 | 说明 |
|------|------|
| `GET /vision/incidents` | 近期事件列表（含类型、时间、`track_id`、置信度、截图 URL） |
| `GET /vision/incidents/{id}/image` | 带框 JPEG |
| （可选）`GET /vision/incidents/stream` | SSE / 长轮询，便于及时弹窗 |

APP：

- 巡检/告警页轮询或订阅上述接口。
- 列表：时间、类型「火灾」、置信度、`#track_id`。
- 点击看带框图。
- 本地也可再按 `track_id` + 类型做二次去重，防止网络重试重复展示。

**通道 2：经控制服务转发（可选）**

若希望 APP 只连 `:8000`：由 `icar-control` 增加 `/incidents/*` 代理到 `:8200`，与现有相机流同源入口。

**通道 3：Jarvis `vision_events`（任务报告用）**

巡检任务开启时，视觉事件引擎向 `:8100` 投递；Jarvis 用现有 `track_id` 去重写库，生成任务报告。可与 APP 实时告警并存。

### 5.5 事件 JSON 草案

```json
{
  "id": "inc-20260715T022300-fire-12",
  "type": "fire",
  "label": "flame",
  "track_id": 12,
  "confidence": 0.87,
  "timestamp": "2026-07-15T02:23:00+08:00",
  "box": {"left": 0.32, "top": 0.41, "right": 0.48, "bottom": 0.66},
  "image_url": "/vision/incidents/inc-20260715T022300-fire-12/image",
  "source": "icar-vision"
}
```

---

## 6. 待实现：车辆长时间停留禁停区

### 6.1 产品行为（按你的设想）

1. 同时检出**车辆**与**禁停区**（地面区域框）。
2. 计算车框与禁停区框的重叠（建议 **IoU** 或 **车框中心点落入禁停区**；中心点法对「车在区内」更稳）。
3. **同一车 `track_id` 与区 `track_id`（或区静态 id）持续重叠时间 ≥ T**（建议默认 **30～60 s**，可配置）。
4. 首次超时生成违停事件：时间、车辆 `track_id`、区域信息、带框截图（车+区一起画）。
5. 冷却期内同一车+同一区不再反复上报。

### 6.2 区域从哪里来（推荐决策）

| 方案 | 做法 | 评价 |
|------|------|------|
| **A. 新增检测类 `no_parking_zone`（推荐）** | 标注地面禁停区域框，与 `car` 做重叠计时 | 与你说的「两个方框重叠」一致 |
| B. 继续用 `no_parking_sign` | 用「牌→扇形/矩形伪禁停区」启发式 | 误差大，不建议作为唯一方案 |
| C. 地图 / 手动标定多边形 | 导航坐标系投影到图像 | 精度高但标定与定位依赖重 |

阶段一采用 **方案 A**；`no_parking_sign` 仍可做「附近有禁停标识」的辅助线索，不充当区域本体。

### 6.3 算法流程

```text
每帧：
  cars  = detections where label==car 且有 track_id
  zones = detections where label==no_parking_zone 且有 track_id

  for each (car_tid, zone_tid) where overlap(car, zone):
      dwell[car_tid, zone_tid] += dt   # dt ≈ 1/source_fps 或帧间隔
  for pairs no longer overlapping:
      dwell[...] = 0 或进入 grace 后清零

  if dwell >= DWELL_SECONDS and not reported[car_tid, zone_tid]:
      抓拍带框图 → 生成 no_parking_dwell 事件 → 标记已上报
```

重叠判定建议：

- 主判据：车辆框中心点 `(cx,cy)` 落在禁停区框内；或  
- 备选：`IoU(car, zone) >= 0.05` 且 交集面积 / 车框面积 ≥ 0.3（车大部分压在区内）。

去重键：

```text
(event_type=no_parking_dwell, car_track_id, zone_track_id)
```

冷却：建议车驶离区域超过 grace（如 5 s）后清除 dwell；已上报对在 cooldown（如 10 min）内不再报。若同一车长时间停着，**只报一次**，符合「不要反复上报相同信息」。

### 6.4 事件 JSON 草案

```json
{
  "id": "inc-20260715T023100-nopark-3-7",
  "type": "no_parking_dwell",
  "car_track_id": 3,
  "zone_track_id": 7,
  "dwell_seconds": 45.2,
  "car_confidence": 0.81,
  "zone_confidence": 0.76,
  "timestamp": "2026-07-15T02:31:00+08:00",
  "car_box": {"left": 0.20, "top": 0.50, "right": 0.55, "bottom": 0.90},
  "zone_box": {"left": 0.10, "top": 0.55, "right": 0.70, "bottom": 1.0},
  "image_url": "/vision/incidents/inc-20260715T023100-nopark-3-7/image",
  "source": "icar-vision"
}
```

APP 展示：「禁停区违停 · 停留 45s · 车辆#3」，点开看双框截图。

### 6.5 与小车系统的连接

与火焰共用同一 **incident 通道**（第 5.4 节）：

- 生产端：视觉服务事件引擎。
- 消费端：APP 告警页；可选 Jarvis 任务报告；可选 `:8000` 代理。
- 不强制与底盘联动；若以后要「发现违停后停车喊话」，可再调 `:8000/notify` 或 TTS 接口。

实时检测叠框仍走现有 `/vision/detections`；**事件是另一条「稀有、持久」信道**，避免每帧 JSON 里刷屏。

---

## 7. 统一事件引擎与去重状态机

```text
                ┌─────────────┐
 detections ──►│ IncidentEngine │──► incidents[] + JPEG 文件
 (w/ track_id)  └──────┬──────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
     FireRules   DwellRules    (future…)
          │            │
          └──── dedupe by track key + cooldown ────┘
```

### 7.1 去重状态（内存 + 可选落盘）

```text
reported_keys: Map[key → last_report_unix]
pending_hits:  Map[track_id → consecutive_ok_frames]      # fire
dwell_clock:   Map[(car_id, zone_id) → seconds]         # no_parking
```

重启服务后内存清空可能导致重复上报：可将 `reported_keys` 简要追加到 `incidents/index.jsonl`，启动时加载未过期项。

### 7.2 与箭头方案的关系

| 能力 | 是否改 YOLO 主网络 | 是否用 track_id | 是否多帧确认 |
|------|-------------------|-----------------|--------------|
| 箭头方向 | 否（二阶段分类） | 是（显示/脚本） | 投票缓冲 |
| 火焰上报 | 是（新类）或暂代类 | **是（去重键）** | 连续帧计数 |
| 禁停停留 | 是（新区） | **是（车+区复合键）** | 时间积分 |

箭头继续服务转向演示与 APP 标签；灾情/违停走 IncidentEngine，互不阻塞。

---

## 8. 建议落地阶段

### 阶段 0：联调骨架（不改模型）

- [ ] `IncidentEngine` + `/vision/incidents` + 本地 JPEG
- [ ] APP 告警页读列表/看图
- [ ] 用注入假检测或手动 POST 测去重与 UI

### 阶段 1：火焰

- [ ] 采集火焰数据集，新增 `flame`，重训导出 ONNX
- [ ] 视觉服务识别新类；FireRules 上线
- [ ] 实车调 `fire_confidence` / `fire_confirm_frames` / cooldown

### 阶段 2：禁停区停留

- [ ] 标注 `no_parking_zone`，重训
- [ ] DwellRules（重叠 + 计时 + 复合 track 去重）
- [ ] APP 展示违停卡片

### 阶段 3：任务融合（可选）

- [ ] 有巡检任务时同步写入 Jarvis `vision_events`
- [ ] 报告中汇总火灾 / 违停时间线

---

## 9. 配置与运维建议

视觉服务建议增加（示例）：

```bash
python3 vision_service.py \
  --weights models/best.onnx \
  --arrow-weights models/arrow_cls_best.onnx \
  --fire-confidence 0.6 \
  --fire-confirm-frames 5 \
  --fire-cooldown-s 180 \
  --dwell-seconds 45 \
  --dwell-cooldown-s 600 \
  --incidents-dir /home/jetson/icar_vision/incidents
```

磁盘：定期清理 `incidents/` 旧图（按天或上限张数）。

安全：截图含现场画面，仅局域网暴露；若出公网需鉴权。

---

## 10. 验收标准（摘要）

**火焰**

- 同一次火焰轨迹（固定 `track_id`）在冷却内只出现 **1** 条 incident。
- 事件含 ISO 时间、置信度、可下载带框 JPEG。
- 置信度不足或不足连续帧时不上报。
- APP 可刷新看到该事件。

**禁停停留**

- 车与区短暂掠过（重叠时间不到 T）不上报。
- 持续重叠 ≥ T 上报恰好一次（同 car+zone）。
- 截图同时可见车框与区框。
- APP 文案能区分火灾与违停。

**回归**

- 箭头方向分类、`arrow_turn_demo`、现有九类（或扩展后名单）检测与 APP 叠框不被破坏。

---

## 11. 相关代码索引

| 主题 | 位置 |
|------|------|
| 视觉主服务 | `jetson_perception/vision_service.py` |
| 箭头分类 / 投票 | `jetson_perception/arrow_classifier.py` |
| IoU 跟踪 | `jetson_perception/tracker.py` |
| 转向演示 | `jetson_perception/arrow_turn_demo.py` |
| 类别定义（训练） | `parking_detect/datasets/parking_dataset/data.yaml` |
| 控制 HTTP | `jetson_server/server.py` |
| APP 视觉 | `app/.../VisionApi.kt` 等 |
| Jarvis 事件去重 | `jarvis_agent/.../repository.py`（`find_recent_event`） |
| 箭头实现计划（历史） | `docs/superpowers/plans/2026-07-14-arrow-direction-classification.md` |

---

## 12. 小结

- **箭头**：YOLO 粗检 + 分类 ONNX + 多帧投票 + `track_id`；运动走 `:8000/move`；APP 本地叠框。
- **火焰 / 违停**：在现有检测与 `track_id` 之上加 **IncidentEngine**（置信度/连续帧或停留计时 → 单次上报 + 带框图 → APP）。
- **模型缺口**：火焰需新类 `flame`；禁停重叠需地面类 `no_parking_zone`（不要用禁停牌子硬撑区域）。
- **去重**：火焰键为 `(fire, track_id)`；违停键为 `(no_parking_dwell, car_track_id, zone_track_id)`，均带 cooldown。

若下一步进入实现，建议先做阶段 0 接口与 APP 列表，再并行补数据集与重训。
