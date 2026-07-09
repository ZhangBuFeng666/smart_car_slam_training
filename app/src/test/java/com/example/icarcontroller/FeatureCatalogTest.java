package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeatureCatalogTest {
    @Test
    public void bottomNavigationContainsFivePrimaryPages() {
        assertEquals(5, FeatureCatalog.primaryPages().size());
        assertEquals("首页", FeatureCatalog.primaryPages().get(0).getTitle());
        assertEquals("驾驶", FeatureCatalog.primaryPages().get(1).getTitle());
        assertEquals("任务", FeatureCatalog.primaryPages().get(2).getTitle());
        assertEquals("视觉", FeatureCatalog.primaryPages().get(3).getTitle());
        assertEquals("导航", FeatureCatalog.primaryPages().get(4).getTitle());
    }

    @Test
    public void aiAssistantIsAHomeEntryNotBottomNavigationItem() {
        assertTrue(FeatureCatalog.homeHighlights().stream().anyMatch(item -> item.getKey().equals("ai")));
        assertFalse(FeatureCatalog.primaryPages().stream().anyMatch(page -> page.getTitle().contains("AI")));
    }

    @Test
    public void taskCatalogKeepsRealTrainingTasks() {
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("avoidance")));
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("follow")));
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("warning")));
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("color_track")));
    }
}
