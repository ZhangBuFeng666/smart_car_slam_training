package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class VisionUiSpecTest {
    @Test
    public void translatesAllParkingDetectionLabels() {
        assertEquals("停车位", VisionUiSpec.labelText("parking_slot"));
        assertEquals("车辆", VisionUiSpec.labelText("car"));
        assertEquals("禁停标志", VisionUiSpec.labelText("no_parking_sign"));
        assertEquals("入口标志", VisionUiSpec.labelText("entrance_sign"));
        assertEquals("出口标志", VisionUiSpec.labelText("exit_sign"));
        assertEquals("方向箭头", VisionUiSpec.labelText("direction_arrow"));
        assertEquals("停止线", VisionUiSpec.labelText("stop_line"));
        assertEquals("路障", VisionUiSpec.labelText("roadblock"));
        assertEquals("危险标志", VisionUiSpec.labelText("danger_sign"));
        assertEquals("unknown", VisionUiSpec.labelText("unknown"));
    }

    @Test
    public void formatsModelAndInferenceStateForPeople() {
        assertEquals("CUDA · FP16", VisionUiSpec.modelText(true, "cuda:0", true));
        assertEquals("CPU · FP32", VisionUiSpec.modelText(true, "cpu", false));
        assertEquals("模型加载中", VisionUiSpec.modelText(false, null, false));
        assertEquals("32.2 ms · 21.5 FPS", VisionUiSpec.performanceText(32.23, 21.53));
    }

    @Test
    public void formatsConfidenceAsRoundedPercentage() {
        assertEquals("56%", VisionUiSpec.confidenceText(0.563));
        assertEquals("100%", VisionUiSpec.confidenceText(1.4));
        assertEquals("0%", VisionUiSpec.confidenceText(-0.2));
    }

    @Test
    public void translatesArrowDirectionsAndPrefersStableResult() {
        assertEquals("直行", VisionUiSpec.directionText("go_straight"));
        assertEquals("左转", VisionUiSpec.directionText("turn_left"));
        assertEquals("右转", VisionUiSpec.directionText("turn_right"));
        assertEquals("unknown", VisionUiSpec.directionText("unknown"));

        VisionDetection detection = new VisionDetection(
                5, "direction_arrow", 0.8, new VisionBox(),
                "turn_right", 0.91, "turn_left"
        );
        assertEquals("左转", VisionUiSpec.detectionLabel(detection));
        assertEquals("91%", VisionUiSpec.detectionConfidence(detection));
    }
}
