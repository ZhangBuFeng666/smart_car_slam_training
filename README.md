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

APP 需要小车端先启动 Python 控制服务：

```bash
cd ~/icar_app_server
python3 server.py --container 8b98 --host 0.0.0.0 --port 8000
```

手机和小车需要处于同一个热点或局域网。
