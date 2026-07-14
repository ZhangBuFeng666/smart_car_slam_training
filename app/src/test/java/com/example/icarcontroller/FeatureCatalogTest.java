package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeatureCatalogTest {
    @Test
    public void bottomNavigationContainsFivePrimaryPages() {
        assertEquals(5, FeatureCatalog.primaryPages().size());
        assertEquals("钥匙", FeatureCatalog.primaryPages().get(0).getTitle());
        assertEquals("驾驶", FeatureCatalog.primaryPages().get(1).getTitle());
        assertEquals("贾维斯", FeatureCatalog.primaryPages().get(2).getTitle());
        assertEquals("ai", FeatureCatalog.primaryPages().get(2).getKey());
        assertEquals("视觉", FeatureCatalog.primaryPages().get(3).getTitle());
        assertEquals("导航", FeatureCatalog.primaryPages().get(4).getTitle());
    }

    @Test
    public void aiAssistantIsBothAHomeEntryAndBottomNavigationItem() {
        assertTrue(FeatureCatalog.keyActions().stream().anyMatch(item -> item.getKey().equals("ai")));
        assertTrue(FeatureCatalog.primaryPages().stream().anyMatch(page -> page.getKey().equals("ai")));
        assertFalse(FeatureCatalog.primaryPages().stream().anyMatch(page -> page.getKey().equals("tasks")));
        assertEquals("可用", FeatureCatalog.homeHighlights().stream()
                .filter(item -> item.getKey().equals("ai"))
                .findFirst()
                .get()
                .getStatus());
    }

    @Test
    public void digitalKeySurfacePrioritizesImmediateCarActions() {
        assertEquals(5, FeatureCatalog.keyActions().size());
        assertEquals("drive", FeatureCatalog.keyActions().get(0).getKey());
        assertTrue(FeatureCatalog.keyActions().stream().anyMatch(item -> item.getKey().equals("base")));
        assertTrue(FeatureCatalog.keyActions().stream().anyMatch(item -> item.getKey().equals("avoidance")));
        assertTrue(FeatureCatalog.keyActions().stream().anyMatch(item -> item.getKey().equals("nav")));
        assertTrue(FeatureCatalog.keyActions().stream().anyMatch(item -> item.getKey().equals("ai")));
    }

    @Test
    public void taskCatalogKeepsRealTrainingTasks() {
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("avoidance")));
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("follow")));
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("warning")));
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("color_track")));
        assertTrue(FeatureCatalog.trainingTasks().stream().anyMatch(task -> task.getKey().equals("arrow_turn")));
    }

    @Test
    public void visionCatalogIncludesArrowTurnDemo() {
        assertTrue(FeatureCatalog.visionFeatures().stream().anyMatch(item -> item.getKey().equals("arrow_turn")));
    }

    @Test
    public void navigationCatalogMatchesManualLaunchCommands() {
        assertTrue(FeatureCatalog.navigationTasks().stream().anyMatch(task -> task.getKey().equals("map_gmapping")));
        assertTrue(FeatureCatalog.navigationTasks().stream().anyMatch(task -> task.getKey().equals("map_display")));
        assertTrue(FeatureCatalog.navigationTasks().stream().anyMatch(task -> task.getKey().equals("map_save")));
        assertTrue(FeatureCatalog.navigationTasks().stream().anyMatch(task -> task.getKey().equals("nav_laser")));
        assertTrue(FeatureCatalog.navigationTasks().stream().anyMatch(task -> task.getKey().equals("nav_display")));
        assertTrue(FeatureCatalog.navigationTasks().stream().anyMatch(task -> task.getKey().equals("nav_dwa")));
        assertTrue(FeatureCatalog.navigationTasks().stream().anyMatch(task -> task.getKey().equals("nav_teb")));
        assertTrue(FeatureCatalog.navigationTasks().stream().anyMatch(task -> task.getKey().equals("nav_astar_rpp")));
    }
}
