package com.example.icarcontroller

object JarvisUiSpec {
    @JvmStatic
    fun headerKicker(): String = "B2 / JARVIS AGENT"

    @JvmStatic
    fun headerTitle(): String = "贾维斯巡检"

    @JvmStatic
    fun headerSubtitle(): String = "连接 Jetson 8100 智能体，生成安全计划、确认执行并查看巡检报告。"

    @JvmStatic
    fun primaryActions(): List<String> = listOf("生成计划", "确认执行")

    @JvmStatic
    fun secondaryActions(): List<String> = listOf("刷新任务", "查看报告")

    @JvmStatic
    fun dangerAction(): String = "急停"
}
