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

如果建图和导航功能实际在自动导航专用容器中，请把 `8b98` 换成该容器 ID。

旧版环境中如果服务位于 `~/icar_app_server`，也可以按原路径启动：

```bash
cd ~/icar_app_server
python3 server.py --container 8b98 --host 0.0.0.0 --port 8000
```

手机和小车需要处于同一个热点或局域网。

## 建图、路径规划与自动导航

APP 的“导航”页按第 3 章手册接入以下流程：

1. SLAM 建图：启动 Gmapping，使用遥控页低速移动小车扫描环境。
2. 保存地图：调用 `save_map_launch.py` 保存 `yahboomcar.pgm` 和 `yahboomcar.yaml`。
3. 自动导航：先启动地图显示，再启动 DWA 或 TEB 导航节点。
4. 路径规划：APP 发布初始位姿和目标点，Nav2 的 `planner_server` 生成路径，`controller_server` 控制小车跟随路径。

手册里强调：RViz 地图显示节点要先于导航节点启动，否则可能订阅不到只发布一次的 `/map`。

## Low-latency manual control

Jetson deployment now requires both `jetson_server/server.py` and
`jetson_server/motion_bridge.py`. The HTTP service keeps one ROS 2 publisher
alive inside the selected container, so Android movement requests no longer
start a new Docker and ROS process for every touch event. The bridge includes
a 350 ms automatic-stop watchdog, while the Android hold control refreshes at
120 ms.

## Jarvis patrol agent

Jarvis is a separate FastAPI service for natural-language patrol planning,
mission persistence, simulated vision events, and patrol reports. It listens on
port `8100` and calls the existing car control service on port `8000`.

Create and install the Python environment on Jetson:

```bash
cd /opt/jarvis-agent
python3 -m venv .venv
.venv/bin/python -m pip install -e "jarvis_agent[test]"
cp jarvis_agent/.env.example .env
```

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
sudo cp jarvis_agent/deploy/jarvis-agent.service /etc/systemd/system/
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
