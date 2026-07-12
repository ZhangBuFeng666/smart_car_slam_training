package com.example.icarcontroller

object JarvisUiSpec {
    @JvmStatic
    fun headerKicker(): String = "B2 / JARVIS AGENT"

    @JvmStatic
    fun headerTitle(): String = "贾维斯巡检"

    @JvmStatic
    fun headerSubtitle(): String = "连接 Jetson 8100，用自然语言生成安全计划，确认后再控制小车执行。"

    @JvmStatic
    fun primaryActions(): List<String> = listOf("发送", "确认执行")

    @JvmStatic
    fun secondaryActions(): List<String> = listOf("刷新任务", "查看报告")

    @JvmStatic
    fun dangerAction(): String = "急停"

    @JvmStatic
    fun quickPrompts(): List<String> = listOf("检查状态", "打开摄像头", "开始巡检", "生成报告")
}
