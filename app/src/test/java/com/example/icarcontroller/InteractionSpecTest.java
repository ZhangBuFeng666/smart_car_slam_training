package com.example.icarcontroller;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class InteractionSpecTest {
    @Test
    public void aiAssistantUsesAFirstClassPage() {
        assertEquals("full_page", InteractionSpec.aiPresentation());
    }

    @Test
    public void productShellHasMotionTiming() {
        assertTrue(InteractionSpec.pageTransitionMillis() >= 180);
        assertTrue(InteractionSpec.pressFeedbackScale() < 1.0f);
        assertTrue(InteractionSpec.pressFeedbackScale() > 0.90f);
    }

    @Test
    public void productShellKeepsControlsClearOfEdgesAndBottomNavigation() {
        assertTrue((Integer) requiredSpec("contentBottomClearanceDp") >= 96);
        assertTrue((Integer) requiredSpec("homeRailSideInsetDp") >= 16);
        assertTrue((Integer) requiredSpec("remoteButtonSizeDp") >= 68);
    }

    @Test
    public void matureInteractionsUseLayeredMotion() {
        assertTrue((Float) requiredSpec("navSelectionScale") > 1.0f);
        assertTrue((Integer) requiredSpec("statusPulseMillis") >= 260);
        assertTrue((Integer) requiredSpec("remoteRepeatMillis") <= 220);
    }

    @Test
    public void digitalKeyShellHasDedicatedStageMetrics() {
        assertTrue((Integer) requiredSpec("keyHeroHeightDp") >= 360);
        assertTrue((Integer) requiredSpec("sheetPeekHeightDp") >= 240);
        assertTrue((Integer) requiredSpec("modeTileHeightDp") >= 92);
    }

    @Test
    public void obsidianHomeReservesSpaceForTheDynamicVehicleStage() {
        assertTrue((Integer) requiredSpec("obsidianHeroHeightDp") >= 600);
        assertTrue((Integer) requiredSpec("vehicleStageHeightDp") >= 300);
        assertTrue((Integer) requiredSpec("obsidianPrimaryHeightDp") >= 54);
        assertTrue((Integer) requiredSpec("obsidianActionGapDp") >= 16);
        assertTrue((Integer) requiredSpec("obsidianBottomClearanceDp") <= 16);
        assertEquals(true, requiredSpec("homeUsesDarkSystemBars"));
    }

    @Test
    public void parkingPatrolPagesPrioritizeOperationalSurfaces() {
        assertTrue((Integer) requiredSpec("parkingDriveControlSizeDp") >= 76);
        assertTrue((Integer) requiredSpec("parkingTaskRailHeightDp") >= 108);
        assertTrue((Integer) requiredSpec("parkingVisionStageHeightDp") >= 280);
        assertTrue((Integer) requiredSpec("parkingMapStageHeightDp") >= 300);
        assertTrue((Integer) requiredSpec("parkingPatrolCheckpointCount") >= 5);
    }

    @Test
    public void redesignedDriveUsesTactileButtonsAndAccessibleThemeControl() {
        assertEquals(true, requiredSpec("driveUsesExplicitDirectionButtons"));
        assertTrue((Integer) requiredSpec("parkingDriveButtonElevationDp") >= 4);
        assertTrue((Integer) requiredSpec("parkingThemeToggleSizeDp") >= 44);
        assertEquals("global_chrome", requiredSpec("parkingThemeControlPlacement"));
    }

    private Object requiredSpec(String methodName) {
        try {
            Method method = InteractionSpec.class.getMethod(methodName);
            return method.invoke(null);
        } catch (NoSuchMethodException missing) {
            fail("InteractionSpec is missing " + methodName);
        } catch (Exception error) {
            fail("InteractionSpec method failed: " + methodName + " " + error.getMessage());
        }
        return null;
    }
}
