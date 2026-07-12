package com.example.icarcontroller;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import kotlin.Unit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DriveCameraPanelTest {
    @Test
    public void cameraStatesMapToExactChinesePresentation() {
        assertPresentation(CameraViewState.CONNECTING, 0, "正在连接", false);
        assertPresentation(CameraViewState.LIVE, 18, "实时 · 18 FPS", false);
        assertPresentation(CameraViewState.BUSY, 0, "摄像头正在被其他任务使用", true);
        assertPresentation(CameraViewState.MISSING, 0, "未检测到小车摄像头", true);
        assertPresentation(CameraViewState.DISCONNECTED, 0, "连接中断", true);
        assertPresentation(CameraViewState.IDLE, 0, "视频未启动", true);
    }

    @Test
    public void errorOverlayIsCenteredWithoutAsymmetricInsets() {
        CameraOverlayGeometry geometry = DriveCameraPanelKt.cameraErrorOverlayGeometry();

        assertTrue(geometry.getCentered());
        assertEquals(0, geometry.getStartInsetDp());
        assertEquals(0, geometry.getTopInsetDp());
        assertEquals(0, geometry.getEndInsetDp());
        assertEquals(0, geometry.getBottomInsetDp());
    }

    @Test
    public void controlledReparentBeginsBeforeTheViewIsRemoved() {
        List<String> calls = new ArrayList<>();

        DriveCameraPanelKt.performControlledReparent(
                () -> {
                    calls.add("beginReparent");
                    return Unit.INSTANCE;
                },
                () -> {
                    calls.add("removeView");
                    return Unit.INSTANCE;
                }
        );

        assertEquals(Arrays.asList("beginReparent", "removeView"), calls);
    }

    @Test
    public void snapshotRelayExposesCurrentStateAndForwardsWithoutRestartingStream() {
        CameraSnapshotRelay relay = new CameraSnapshotRelay(
                new CameraViewSnapshot(CameraViewState.IDLE, 0, null)
        );
        List<CameraViewSnapshot> observed = new ArrayList<>();

        relay.setObserver(snapshot -> {
            observed.add(snapshot);
            return Unit.INSTANCE;
        });
        CameraViewSnapshot live = new CameraViewSnapshot(CameraViewState.LIVE, 21, null);
        relay.publish(live);

        assertEquals(live, relay.currentSnapshot());
        assertEquals(Arrays.asList(
                new CameraViewSnapshot(CameraViewState.IDLE, 0, null),
                live
        ), observed);
    }

    private static void assertPresentation(
            CameraViewState state,
            int fps,
            String expectedText,
            boolean expectedRetryVisible
    ) {
        CameraPresentation presentation = DriveCameraPanelKt.cameraStatePresentation(
                new CameraViewSnapshot(state, fps, null)
        );

        assertEquals(expectedText, presentation.getText());
        if (expectedRetryVisible) {
            assertTrue(presentation.getRetryVisible());
        } else {
            assertFalse(presentation.getRetryVisible());
        }
    }
}
