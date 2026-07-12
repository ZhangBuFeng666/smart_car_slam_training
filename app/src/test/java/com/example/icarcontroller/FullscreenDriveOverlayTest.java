package com.example.icarcontroller;

import org.junit.Test;

import java.util.Arrays;
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
