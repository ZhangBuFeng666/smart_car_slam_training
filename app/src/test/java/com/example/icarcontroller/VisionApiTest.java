package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class VisionApiTest {
    @Test
    public void buildsVisionUrlsOnTheDedicatedPort() {
        VisionApi api = new VisionApi(" https://10.161.57.230/// ", 8200);

        assertEquals("http://10.161.57.230:8200/health", api.healthUrl());
        assertEquals("http://10.161.57.230:8200/vision/detections", api.detectionsUrl());
        assertEquals("http://10.161.57.230:8200/vision/stream", api.streamUrl());
    }

    @Test
    public void parsesLiveDetectionSnapshot() {
        VisionSnapshot snapshot = VisionSnapshotParser.parse("{\n" +
                "  \"state\": \"live\",\n" +
                "  \"error\": null,\n" +
                "  \"model\": {\"ready\": true, \"device\": \"cuda:0\", \"fp16\": true},\n" +
                "  \"inference_ms\": 32.23,\n" +
                "  \"source_fps\": 21.53,\n" +
                "  \"updated_at\": 1783996367.95,\n" +
                "  \"summary\": {\"total\": 2, \"parking_slots\": 1, \"cars\": 1, \"signs\": 0, \"obstacles\": 0},\n" +
                "  \"detections\": [\n" +
                "    {\"class_id\": 5, \"label\": \"direction_arrow\", \"confidence\": 0.563,\n" +
                "     \"direction\": \"turn_left\", \"direction_confidence\": 0.934, \"stable_direction\": \"turn_left\",\n" +
                "     \"track_id\": 3, \"confirmed\": true, \"hits\": 4,\n" +
                "     \"box\": {\"left\": 0.1, \"top\": 0.2, \"right\": 0.6, \"bottom\": 0.9}}\n" +
                "  ]\n" +
                "}");

        assertEquals("live", snapshot.getState());
        assertNull(snapshot.getError());
        assertTrue(snapshot.getModel().getReady());
        assertEquals("cuda:0", snapshot.getModel().getDevice());
        assertTrue(snapshot.getModel().getFp16());
        assertEquals(32.23, snapshot.getInferenceMs(), 0.001);
        assertEquals(21.53, snapshot.getSourceFps(), 0.001);
        assertEquals(2, snapshot.getSummary().getTotal());
        assertEquals(1, snapshot.getSummary().getParkingSlots());
        assertEquals(1, snapshot.getSummary().getCars());
        assertEquals("direction_arrow", snapshot.getDetections().get(0).getLabel());
        assertEquals(0.563, snapshot.getDetections().get(0).getConfidence(), 0.001);
        assertEquals("turn_left", snapshot.getDetections().get(0).getDirection());
        assertEquals(0.934, snapshot.getDetections().get(0).getDirectionConfidence(), 0.001);
        assertEquals("turn_left", snapshot.getDetections().get(0).getStableDirection());
        assertEquals(Integer.valueOf(3), snapshot.getDetections().get(0).getTrackId());
        assertTrue(snapshot.getDetections().get(0).getConfirmed());
        assertEquals(4, snapshot.getDetections().get(0).getHits());
        assertEquals(0.6, snapshot.getDetections().get(0).getBox().getRight(), 0.001);
    }

    @Test
    public void keepsDirectionFieldsNullForLegacyDetectionPayloads() {
        VisionSnapshot snapshot = VisionSnapshotParser.parse(
                "{\"state\":\"live\",\"model\":{},\"summary\":{},\"detections\":["
                        + "{\"class_id\":1,\"label\":\"car\",\"confidence\":0.8,\"box\":{}}]}"
        );

        assertNull(snapshot.getDetections().get(0).getDirection());
        assertNull(snapshot.getDetections().get(0).getDirectionConfidence());
        assertNull(snapshot.getDetections().get(0).getStableDirection());
        assertNull(snapshot.getDetections().get(0).getTrackId());
        assertFalse(snapshot.getDetections().get(0).getConfirmed());
        assertEquals(0, snapshot.getDetections().get(0).getHits());
    }

    @Test
    public void preservesServiceErrorWithoutInventingDetections() {
        VisionSnapshot snapshot = VisionSnapshotParser.parse(
                "{\"state\":\"error\",\"error\":\"video source unavailable\","
                        + "\"model\":{\"ready\":false},\"summary\":{},\"detections\":[]}"
        );

        assertEquals("error", snapshot.getState());
        assertEquals("video source unavailable", snapshot.getError());
        assertFalse(snapshot.getModel().getReady());
        assertEquals(0, snapshot.getSummary().getTotal());
        assertTrue(snapshot.getDetections().isEmpty());
    }
}
