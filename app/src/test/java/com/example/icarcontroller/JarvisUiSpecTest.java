package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JarvisUiSpecTest {
    @Test
    public void headerNamesJarvisAndAgentPort() {
        assertEquals("B2 / JARVIS AGENT", JarvisUiSpec.headerKicker());
        assertEquals("贾维斯巡检", JarvisUiSpec.headerTitle());
        assertTrue(JarvisUiSpec.headerSubtitle().contains("8100"));
    }

    @Test
    public void primaryActionsMatchMissionWorkflow() {
        assertEquals("生成计划", JarvisUiSpec.primaryActions().get(0));
        assertEquals("确认执行", JarvisUiSpec.primaryActions().get(1));
        assertEquals("刷新任务", JarvisUiSpec.secondaryActions().get(0));
        assertEquals("查看报告", JarvisUiSpec.secondaryActions().get(1));
        assertEquals("急停", JarvisUiSpec.dangerAction());
    }
}
