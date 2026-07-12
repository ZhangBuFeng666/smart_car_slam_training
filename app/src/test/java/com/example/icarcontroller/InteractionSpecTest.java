package com.example.icarcontroller;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        int repeatMillis = (Integer) requiredSpec("remoteRepeatMillis");
        assertTrue(repeatMillis >= 80);
        assertTrue(repeatMillis <= 150);
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

    @Test
    public void cameraStreamUsesBoundedProductSettings() {
        assertEquals(2_000_000, requiredSpec("cameraMaxFrameBytes"));
        assertEquals(640, requiredSpec("cameraTargetWidth"));
        assertEquals(480, requiredSpec("cameraTargetHeight"));
        assertEquals(300, requiredSpec("cameraLatencyTargetMillis"));
        assertArrayEquals(
                new int[] {1000, 2000, 4000, 5000},
                (int[]) requiredSpec("cameraReconnectDelaysMillis")
        );
    }

    @Test
    public void cameraFullscreenKeepsControlsAtTheLandscapeEdges() {
        assertEquals("landscape", requiredSpec("cameraFullscreenOrientation"));
        assertEquals("edge_floating", requiredSpec("cameraFullscreenControlLayout"));
        assertFalse((Boolean) requiredSpec("cameraFullscreenUsesControlPanels"));
        assertTrue((Float) requiredSpec("cameraGlassButtonAlpha") <= 0.18f);
    }

    @Test
    public void cameraDecoderKeepsFramesAtOrBelowTargetSize() {
        assertEquals(1, InteractionSpec.cameraDecodeSampleSize(640, 480));
        assertEquals(1, InteractionSpec.cameraDecodeSampleSize(320, 240));
        assertEquals(1, InteractionSpec.cameraDecodeSampleSize(0, 0));
    }

    @Test
    public void cameraDecoderUsesPowerOfTwoSamplingForLargeFrames() {
        assertEquals(2, InteractionSpec.cameraDecodeSampleSize(1920, 1080));
        assertEquals(4, InteractionSpec.cameraDecodeSampleSize(4000, 3000));
    }

    @Test
    public void cameraUnavailableStateReadsExplicitJsonState() {
        assertEquals("busy", InteractionSpec.cameraHttp503State("{\"state\": \"busy\"}"));
        assertEquals("missing", InteractionSpec.cameraHttp503State("{\n\"state\" : \"MISSING\"\n}"));
    }

    @Test
    public void cameraUnavailableStateRejectsAmbiguousOrNestedText() {
        assertEquals("disconnected", InteractionSpec.cameraHttp503State("{\"message\":\"camera busy\"}"));
        assertEquals("disconnected", InteractionSpec.cameraHttp503State("{\"error\":{\"state\":\"missing\"}}"));
        assertEquals("disconnected", InteractionSpec.cameraHttp503State("not json: state=busy"));
        assertEquals("disconnected", InteractionSpec.cameraHttp503State("{\"state\":\"busy\",\"invalid\":}"));
        assertEquals("disconnected", InteractionSpec.cameraHttp503State("{\"state\":\"busy\"} trailing"));
    }

    @Test
    public void cameraReparentGuardOnlySkipsTheArmedDetach() {
        CameraReparentGuard guard = new CameraReparentGuard();

        assertTrue(guard.shouldReleaseOnDetach());
        guard.beginReparent();
        assertFalse(guard.shouldReleaseOnDetach());
        assertTrue(guard.shouldReleaseOnDetach());
    }

    @Test
    public void cameraReparentGuardClearsOnAttachOrExplicitEnd() {
        CameraReparentGuard guard = new CameraReparentGuard();

        guard.beginReparent();
        guard.onAttached();
        assertTrue(guard.shouldReleaseOnDetach());

        guard.beginReparent();
        guard.endReparent();
        assertTrue(guard.shouldReleaseOnDetach());
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
