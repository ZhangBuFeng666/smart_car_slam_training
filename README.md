# Smart Car SLAM Training

基于 Jetson Orin、ROS 2、激光雷达、深度相机和 Android APP 的智能小车实训项目。

当前仓库第一版先提交 Android 控制端 APP：`ICarControllerApp`。

## APP 功能

- 连接 Jetson 端 Python HTTP 控制服务，默认地址 `10.161.57.230:8000`。
- 启动/停止 ROS 实训任务，例如底盘驱动、自动避障、自动跟随、警卫、相机、HSV 调参、颜色追踪。
- 提供遥控器页面，按住方向键持续移动，松手自动发送停止指令。
- 提供急停入口，请求 `/emergency_stop`。
- 预留传感器、视觉识别、视觉检测、路径规划、自动导航和 LLM 复杂任务助手页面。

## 架构

```text
Android APP
    -> HTTP
Jetson Python Service
    -> docker exec / ROS 2
Docker container 8b98
    -> 小车底盘、雷达、相机等硬件
```

## 构建

本项目没有提交本机 `local.properties`。首次构建前，需要在项目根目录创建它，内容示例：

```properties
sdk.dir=C\:\\Users\\lenovo\\AppData\\Local\\Android\\Sdk
```

在当前实训电脑上可直接使用脚本：

```powershell
cd E:\ICarControllerApp
.\build_app.ps1
```

或手动执行：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:GRADLE_USER_HOME='E:\android-gradle-cache'
& 'E:\android-tools\gradle-8.7\bin\gradle.bat' testDebugUnitTest assembleDebug --no-daemon --console=plain
```

## 安装到手机

手机打开 USB 调试后执行：

```powershell
cd E:\ICarControllerApp
.\install_app.ps1
```

## 小车端前置服务

APP 需要小车端先启动 Python 控制服务。仓库内提供了模板：

```bash
cd jetson_server
python3 server.py --container 8b98 --host 0.0.0.0 --port 8000
```

默认使用包含完整 `icar_nav` 的 `8b98`。服务会检查 install 空间中的 launch 文件；其他容器可自动回退到 `yahboomcar_nav`。

旧版环境中如果服务位于 `~/icar_app_server`，也可以按原路径启动：

```bash
cd ~/icar_app_server
python3 server.py --container 8b98 --host 0.0.0.0 --port 8000
```

手机和小车需要处于同一个热点或局域网。

需要开机自启时，将 `jetson_server/icar-control.service` 安装到
`/etc/systemd/system/icar-control.service`。该 unit 固定使用 `8b98`，启动前应停止手动运行的 `server.py`，避免端口 8000 冲突。

## 建图、路径规划与自动导航

APP 的“导航”页已按第 3 章手册接入一键自动工作流：

1. 一键自动建图：服务自动停止独立底盘驱动和上一轮 Gmapping/RViz，清空 APP 地图缓存，再按 `m1 → m2` 启动全新的 Gmapping 会话；因此第二次建图不会接着修改第一次的栅格地图。`m1` 已内置底盘驱动，不会重复占用串口。建图节点会在页面切换后继续运行，可通过底部“驾驶”页按住方向键低速扫描环境。
2. 保存地图并结束建图：调用 `m4` 保存 `icar.pgm` 和 `icar.yaml`，成功后关闭建图节点。导航启动时会把同一份 `icar.yaml` 显式传给 Nav2，避免厂商 launch 文件默认的 `yahboomcar.yaml` 与保存文件名不一致。
3. 一键自动导航：服务自动停止独立底盘驱动，再按 `n1 → n2 → n3`（DWA）、`n1 → n2 → n4`（TEB）或 `n1 → n2 → n5`（A* + RPP）启动完整导航链路；`n1` 已内置底盘驱动。驾驶页首次按方向键时会自动恢复独立底盘驱动，不需要运行 Jetson 命令。
4. 路径规划：APP 发布初始位姿和目标点，Nav2 的 `planner_server` 生成路径，`controller_server` 控制小车跟随路径。
5. 实时路线图：APP 轮询 `/navigation/state`，显示 ROS `/map` 栅格地图、TF 小车位姿、`/goal_pose` 目标点，以及 `/plan` 或 `/global_plan` 规划路径。未连接小车时自动保留本地路线草图。
6. 手机多点巡逻：先点“设置起点”并在地图上放置 `S`，再点“添加目标点”按顺序生成编号目标点；提交时 APP 会根据起点到第一个目标点计算朝向，服务端先发布 `/initialpose`，再优先使用 Nav2 `/follow_waypoints` 顺序执行。若厂商 Nav2 没有提供该 action server，导航桥会自动将整条路线转换成连续的 `/navigate_to_pose` 目标，仍按 `1 → 2 → 3…` 自动巡逻。支持撤销、清空、追加返回起点、双指缩放、单指拖动，以及放大/缩小/视图复位按钮。APP 显示当前点、完成状态和失败点，障碍物或未知栅格上的触点会被拒绝。

手册里强调：RViz 地图显示节点要先于导航节点启动，否则可能订阅不到只发布一次的 `/map`。

Jetson 服务新增的工作流接口如下：

```text
GET /automation/status
GET /automation/mapping/start
GET /automation/mapping/save
GET /automation/navigation/start?algorithm=dwa
GET /automation/navigation/start?algorithm=teb
GET /automation/navigation/start?algorithm=astar_rpp
GET /automation/navigation/stop
GET /container/status
GET /container/select?id=8b98
GET /navigation/state?map_generation=-1
POST /navigation/waypoints
GET /navigation/waypoints/cancel
```

切换到建图会先停止导航节点，切换到导航会先停止建图节点；“停止自动导航”还会先发布零速度，避免小车保留上一次速度指令。

容器可以在 APP“路线导航”页手动输入并切换。服务端只接受 Docker 容器 ID/名称允许的字符，切换前会停止旧容器中的建图与导航节点并校验新容器是否正在运行。`8b98` 默认使用 `icar_nav`；其他容器自动探测 `icar_nav` 和 `yahboomcar_nav`，且优先选择文件完整的 `icar_nav`。

服务端始终执行完整 ROS 2 命令，不依赖 m1–m4、n1–n5 alias。需要调试自定义 RPP 启动文件时仍可覆盖：

```bash
python3 server.py --container 8b98 --n5-command "<实际的A*与RPP启动命令>"
```

运行状态检查读取完整的容器进程列表，避免长命令输出被截断后把实际运行中的 DWA、TEB 或 A*+RPP 错报为未启动。

## Low-latency manual control

Jetson deployment now requires `jetson_server/server.py`,
`jetson_server/motion_bridge.py`, and `jetson_server/navigation_bridge.py`.
The HTTP service keeps one ROS 2 publisher
alive inside the selected container, so Android movement requests no longer
start a new Docker and ROS process for every touch event. Manual velocity is
held continuously until Android sends an explicit stop on release, page exit,
the stop button, or emergency stop.

## Jarvis patrol agent

Jarvis is a separate FastAPI service for natural-language patrol planning,
mission persistence, simulated vision events, and patrol reports. It listens on
port `8100` and calls the existing car control service on port `8000`.

The deployed Jetson layout keeps runtime data outside the source directory:

- service root: `/home/jetson/jarvis-agent`
- source package: `/home/jetson/jarvis-agent/jarvis_agent`
- secrets: `/home/jetson/jarvis-agent/.env`
- database: `/home/jetson/jarvis-agent/data/jarvis.db`

Create and install the Python environment on Jetson:

```bash
cd /home/jetson/jarvis-agent
python3 -m venv .venv
.venv/bin/python -m pip install -e "./jarvis_agent[test]"
cp jarvis_agent/.env.example .env
```

When updating an existing installation, replace only the `jarvis_agent`
source directory and reinstall the editable package. Preserve `.env`, `.venv`,
`data/jarvis.db`, and `contracts`; they contain the device configuration,
credentials, mission history, and API contract.

Edit `.env` and set `JARVIS_APP_TOKEN` and `DEEPSEEK_API_KEY`. Real secrets
must never be committed to Git, copied into Android resources, or printed in
logs.

Run in the foreground:

```bash
.venv/bin/python -m uvicorn jarvis_agent.api:create_app --factory --host 0.0.0.0 --port 8100
curl http://127.0.0.1:8100/health
```

Install as a systemd service:

```bash
sudo cp /home/jetson/jarvis-agent/jarvis_agent/deploy/jarvis-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now jarvis-agent
sudo systemctl status jarvis-agent
```

Run the simulated parking-lot event flow after creating a mission:

```bash
.venv/bin/python -m jarvis_agent.simulator \
  --base-url http://127.0.0.1:8100 \
  --token "$JARVIS_APP_TOKEN" \
  --mission-id "$MISSION_ID" \
  --scenario parking-lot
```

For a complete phase-one demo sequence, see `docs/jarvis-demo-runbook.md`.
