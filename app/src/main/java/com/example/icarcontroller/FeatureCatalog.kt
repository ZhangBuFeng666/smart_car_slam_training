package com.example.icarcontroller

object FeatureCatalog {
    @JvmStatic
    fun primaryPages(): List<AppPage> = listOf(
        AppPage("home", "钥匙", "数字车钥匙与常用入口"),
        AppPage("drive", "驾驶", "停车场人工接管"),
        AppPage("ai", "AI", "停车场复合任务"),
        AppPage("vision", "视觉", "车位、车辆与禁停检测"),
        AppPage("nav", "导航", "B2 地图与巡逻路线")
    )

    @JvmStatic
    fun keyActions(): List<FeatureItem> = listOf(
        FeatureItem("drive", "驾驶", "进入遥控盘", "主控"),
        FeatureItem("base", "底盘", "启动驱动", "必要"),
        FeatureItem("avoidance", "避障", "雷达避障", "实训"),
        FeatureItem("nav", "建图", "SLAM 流程", "流程"),
        FeatureItem("ai", "AI", "任务规划", "预留")
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
        RobotTask("base", "人工接管底盘", "让 APP 的低速控制指令发送到底盘", "基础"),
        RobotTask("lidar", "通道雷达", "采集通道距离，为避障和限制区检测提供数据", "基础"),
        RobotTask("avoidance", "通道避障", "巡逻时根据雷达距离绕开车辆、路障和行人", "实训"),
        RobotTask("follow", "动态目标跟随", "在教学区域跟随指定目标移动", "实训"),
        RobotTask("warning", "限制区警卫", "检测有人靠近设备间或限制区域", "实训"),
        RobotTask("camera", "停车场相机", "启动深度相机，为车位与车辆识别做准备", "视觉"),
        RobotTask("hsv", "车辆颜色标定", "调整颜色阈值，辅助车辆颜色识别实验", "视觉"),
        RobotTask("color_track", "目标车辆追踪", "追踪指定颜色的教学目标车辆", "视觉")
    )

    @JvmStatic
    fun visionFeatures(): List<FeatureItem> = listOf(
        FeatureItem("camera", "停车场预览", "接入相机流，显示小车前方通道与车位", "待接入"),
        FeatureItem("recognition", "车位识别", "识别车位编号、占用状态和禁停标志", "训练中"),
        FeatureItem("detection", "异常检测", "显示车辆、行人、烟雾和路障检测框", "训练中"),
        FeatureItem("tracking", "目标复查", "结合检测结果控制小车靠近目标复查", "待接入")
    )

    @JvmStatic
    fun navigationFeatures(): List<FeatureItem> = listOf(
        FeatureItem("mapping", "SLAM 建图", "启动 Gmapping，缓慢移动小车扫描环境", "可接入"),
        FeatureItem("save_map", "保存地图", "保存 yahboomcar.pgm 和 yahboomcar.yaml", "可接入"),
        FeatureItem("planning", "路径规划", "Nav2 收到目标点后由 planner_server 自动生成路线", "可接入"),
        FeatureItem("auto_nav", "自动导航", "DWA 或 TEB 控制器跟随规划路线行驶", "可接入")
    )

    @JvmStatic
    fun navigationTasks(): List<RobotTask> = listOf(
        RobotTask("map_gmapping", "启动建图", "ros2 launch yahboomcar_nav map_gmapping_launch.py", "建图"),
        RobotTask("map_display", "显示建图地图", "ros2 launch yahboomcar_nav display_map_launch.py", "建图"),
        RobotTask("map_save", "保存地图", "ros2 launch yahboomcar_nav save_map_launch.py", "建图"),
        RobotTask("nav_laser", "雷达与底盘", "ros2 launch yahboomcar_nav laser_bringup_launch.py", "导航"),
        RobotTask("nav_display", "显示导航地图", "ros2 launch yahboomcar_nav display_nav_launch.py", "导航"),
        RobotTask("nav_dwa", "DWA 导航", "ros2 launch yahboomcar_nav navigation_dwa_launch.py", "导航"),
        RobotTask("nav_teb", "TEB 导航", "ros2 launch yahboomcar_nav navigation_teb_launch.py", "导航")
    )

    @JvmStatic
    fun aiExamples(): List<String> = listOf(
        "巡检 B2 东区车位，发现禁停车辆后提醒我",
        "沿主通道巡逻，遇到车辆或行人自动绕开",
        "检查设备间限制区域，有人靠近时触发警卫",
        "完成停车场建图，保存地图并生成巡逻路线"
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
