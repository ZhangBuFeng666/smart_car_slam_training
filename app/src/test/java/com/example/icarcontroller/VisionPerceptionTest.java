package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VisionPerceptionTest {
    @Test
    public void disconnectedSnapshotUsesEmptyMetrics() {
        VisionPerceptionSnapshot snapshot = VisionMockDataSource.snapshot(false);

        assertEquals(0, snapshot.getMetrics().getVisibleSlots());
        assertEquals(0, snapshot.getMetrics().getOccupiedSlots());
        assertEquals(0, snapshot.getMetrics().getAlertCount());
        assertTrue(snapshot.getDetections().isEmpty());
        assertTrue(snapshot.getClassLabels().isEmpty());
    }

    @Test
    public void connectedMockSnapshotProvidesDetectionsAndMetrics() {
        VisionPerceptionSnapshot snapshot = VisionMockDataSource.snapshot(true);

        assertEquals(4, snapshot.getMetrics().getVisibleSlots());
        assertEquals(1, snapshot.getMetrics().getOccupiedSlots());
        assertEquals(3, snapshot.getMetrics().getAlertCount());
        assertEquals(4, snapshot.getDetections().size());
        assertTrue(snapshot.getClassLabels().contains("积水"));
    }
}
