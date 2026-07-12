package com.example.icarcontroller;

import org.junit.Test;

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
