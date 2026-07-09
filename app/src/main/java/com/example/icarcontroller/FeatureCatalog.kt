package com.example.icarcontroller

object FeatureCatalog {
    @JvmStatic
    fun primaryPages(): List<AppPage> = listOf(
        AppPage("home", "首页", "小车状态与常用入口"),
        AppPage("drive", "驾驶", "手动遥控小车"),
        AppPage("tasks", "任务", "实训功能集合"),
        AppPage("vision", "视觉", "识别与检测能力"),
        AppPage("nav", "导航", "建图、规划与导航")
    )

    @JvmStatic
    fun homeHighlights(): List<FeatureItem> = listOf(
        FeatureItem("drive", "开始驾驶", "进入遥控页面，按住方向即可控制小车", "可用"),
        FeatureItem("avoidance", "自动避障", "启动雷达避障实验，适合快速展示", "可用"),
        FeatureItem("ai", "AI 智能助手", "预留自然语言任务规划入口", "规划中"),
        FeatureItem("vision", "视觉能力", "模型训练完成后接入识别与检测", "训练中")
    )

    @JvmStatic
    fun trainingTasks(): List<RobotTask> = listOf(
        RobotTask("base", "底盘驱动", "让 APP 的移动指令真正发送到底盘", "基础"),
        RobotTask("lidar", "激光雷达", "启动雷达数据采集，为避障和跟随提供距离信息", "基础"),
        RobotTask("avoidance", "自动避障", "小车根据雷达距离自动绕开障碍", "实训"),
        RobotTask("follow", "自动跟随", "小车跟随前方目标移动", "实训"),
        RobotTask("warning", "自动警卫", "检测靠近物体并触发警卫响应", "实训"),
        RobotTask("camera", "Astra 相机", "启动深度相机，为视觉功能做准备", "视觉"),
        RobotTask("hsv", "HSV 调参", "调整颜色阈值，辅助颜色识别实验", "视觉"),
        RobotTask("color_track", "颜色追踪", "追踪指定颜色目标", "视觉")
    )

    @JvmStatic
    fun visionFeatures(): List<FeatureItem> = listOf(
        FeatureItem("camera", "摄像头预览", "后续接入视频流，显示小车实时画面", "待接入"),
        FeatureItem("recognition", "视觉识别", "模型训练完成后展示识别类别和结果", "训练中"),
        FeatureItem("detection", "目标检测", "显示检测框、类别和置信度", "训练中"),
        FeatureItem("tracking", "视觉追踪", "结合检测结果控制小车追踪目标", "待接入")
    )

    @JvmStatic
    fun navigationFeatures(): List<FeatureItem> = listOf(
        FeatureItem("mapping", "开始建图", "连接 SLAM 后记录环境地图", "待接入"),
        FeatureItem("save_map", "保存地图", "建图完成后保存地图文件", "待接入"),
        FeatureItem("planning", "路径规划", "选择目标点并生成路线", "待接入"),
        FeatureItem("auto_nav", "自动导航", "让小车按规划路线自主行驶", "待接入")
    )

    @JvmStatic
    fun aiExamples(): List<String> = listOf(
        "检查小车状态，并帮我进入自动避障模式",
        "启动摄像头，识别到红色目标后提醒我",
        "开始建图，完成后保存地图",
        "让小车巡逻，遇到障碍物自动绕开"
    )
}

data class AppPage(
    val key: String,
    val title: String,
    val subtitle: String
)

data class FeatureItem(
    val key: String,
    val title: String,
    val description: String,
    val status: String
)

data class RobotTask(
    val key: String,
    val title: String,
    val description: String,
    val category: String
)
