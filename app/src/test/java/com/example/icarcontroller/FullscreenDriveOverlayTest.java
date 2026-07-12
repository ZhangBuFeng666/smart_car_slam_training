package com.example.icarcontroller;

import org.junit.Test;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FullscreenDriveOverlayTest {
    @Test
    public void directionButtonsMapToApprovedEdgesAndMovementCommands() {
        List<FullscreenDriveButtonSpec> buttons = FullscreenDriveOverlayKt.fullscreenDriveButtonSpecs();

        assertEquals(Arrays.asList("front", "back", "left", "right"), commandsAt(buttons, FullscreenDriveEdge.LEFT));
        assertEquals(Arrays.asList("turn_left", "stop", "turn_right"), commandsAt(buttons, FullscreenDriveEdge.RIGHT));
    }

    @Test
    public void layoutKeepsVideoCenterClearWithoutOuterGroupPanels() {
        FullscreenDriveLayoutContract contract = FullscreenDriveOverlayKt.fullscreenDriveLayoutContract();

        assertFalse(contract.getUsesOuterGroupPanels());
        assertFalse(contract.getUsesGroupTitles());
        assertTrue(contract.getKeepsCenterUnobstructed());
        assertEquals("edge_floating", contract.getControlLayout());
    }

    @Test
    public void glassControlsStayWithinInteractionSpecAlpha() {
        FullscreenDriveVisualContract contract = FullscreenDriveOverlayKt.fullscreenDriveVisualContract();

        assertTrue(contract.getGlassAlpha() > 0f);
        assertTrue(contract.getGlassAlpha() <= 0.18f);
        assertEquals(InteractionSpec.cameraGlassButtonAlpha(), contract.getGlassAlpha(), 0.0001f);
        assertTrue(contract.getPressedAlpha() > contract.getGlassAlpha());
        assertTrue(contract.getFixedTouchSizeDp() >= 48);
        assertTrue(contract.getStopUsesTranslucentRed());
    }

    @Test
    public void exitContractStopsBeforeRestoringUi() {
        assertEquals(
                Arrays.asList(
                        FullscreenDriveExitAction.FORCE_STOP,
                        FullscreenDriveExitAction.RESTORE_STREAM,
                        FullscreenDriveExitAction.REMOVE_OVERLAY,
                        FullscreenDriveExitAction.RESTORE_PORTRAIT,
                        FullscreenDriveExitAction.RESTORE_SYSTEM_UI
                ),
                FullscreenDriveOverlayKt.fullscreenDriveExitActions()
        );
    }

    @Test
    public void speedProgressMapsToTheExistingDriveSpeedAndTurnRange() {
        FullscreenSpeedSelection low = FullscreenDriveOverlayKt.fullscreenSpeedSelection(-4);
        FullscreenSpeedSelection current = FullscreenDriveOverlayKt.fullscreenSpeedSelection(13);
        FullscreenSpeedSelection high = FullscreenDriveOverlayKt.fullscreenSpeedSelection(99);

        assertEquals(0, low.getProgress());
        assertEquals(0.05, low.getSpeed(), 0.0001);
        assertEquals(13, current.getProgress());
        assertEquals(0.18, current.getSpeed(), 0.0001);
        assertEquals(0.81, current.getTurn(), 0.0001);
        assertEquals(30, high.getProgress());
        assertEquals(0.35, high.getSpeed(), 0.0001);
        assertEquals(1.15, high.getTurn(), 0.0001);
    }

    @Test
    public void snapshotHudUsesActualStateAndFps() {
        assertEquals(
                "LIVE / 18 FPS",
                FullscreenDriveOverlayKt.fullscreenCameraHudText(
                        new CameraViewSnapshot(CameraViewState.LIVE, 18, null)
                )
        );
        assertEquals(
                "LIVE / FPS --",
                FullscreenDriveOverlayKt.fullscreenCameraHudText(
                        new CameraViewSnapshot(CameraViewState.LIVE, 0, null)
                )
        );
        assertEquals(
                "CONNECTING / FPS --",
                FullscreenDriveOverlayKt.fullscreenCameraHudText(
                        new CameraViewSnapshot(CameraViewState.CONNECTING, 0, null)
                )
        );
        assertEquals(
                "DISCONNECTED / FPS --",
                FullscreenDriveOverlayKt.fullscreenCameraHudText(
                        new CameraViewSnapshot(CameraViewState.DISCONNECTED, 0, "timeout")
                )
        );
    }

    @Test
    public void latencyUpdatesOnlyWhenAsyncRequestCompletes() {
        List<Long> updates = new ArrayList<>();
        RequestLatencyMeasurement measurement = new RequestLatencyMeasurement(1_000L, value -> {
            updates.add(value);
            return kotlin.Unit.INSTANCE;
        });

        assertTrue(updates.isEmpty());
        measurement.complete(1_127L);
        measurement.complete(1_300L);

        assertEquals(Arrays.asList(127L), updates);
    }

    @Test
    public void holdGestureStopsWhenActivePointerSlidesOutsideOrDisappears() {
        HoldGestureTracker tracker = new HoldGestureTracker();

        assertEquals(HoldGestureAction.START, tracker.onDown(7));
        assertEquals(HoldGestureAction.NONE, tracker.onMove(true, true));
        assertEquals(HoldGestureAction.STOP, tracker.onMove(true, false));
        assertEquals(HoldGestureAction.NONE, tracker.onUp());

        assertEquals(HoldGestureAction.START, tracker.onDown(8));
        assertEquals(HoldGestureAction.STOP, tracker.onMove(false, true));
    }

    @Test
    public void holdGestureStopsOnlyWhenTheInitiatingPointerLifts() {
        HoldGestureTracker tracker = new HoldGestureTracker();

        assertEquals(HoldGestureAction.START, tracker.onDown(4));
        assertEquals(HoldGestureAction.NONE, tracker.onPointerUp(9));
        assertEquals(HoldGestureAction.STOP, tracker.onPointerUp(4));
        assertEquals(HoldGestureAction.NONE, tracker.onCancel());
    }

    private static List<String> commandsAt(
            List<FullscreenDriveButtonSpec> buttons,
            FullscreenDriveEdge edge
    ) {
        java.util.ArrayList<String> commands = new java.util.ArrayList<>();
        for (FullscreenDriveButtonSpec button : buttons) {
            if (button.getEdge() == edge) {
                commands.add(button.getCommand());
            }
        }
        return commands;
    }
}
