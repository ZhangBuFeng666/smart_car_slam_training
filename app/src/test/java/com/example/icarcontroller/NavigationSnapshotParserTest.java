package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class NavigationSnapshotParserTest {
    @Test
    public void parsesMapPoseGoalAndPath() {
        NavigationSnapshot snapshot = NavigationSnapshotParser.parse(
                "{\"map_generation\":3,\"map\":{" +
                        "\"generation\":3,\"width\":3,\"height\":2,\"resolution\":0.05," +
                        "\"origin\":{\"x\":-1,\"y\":-2,\"yaw\":0}," +
                        "\"data_rle\":[-1,2,0,3,100,1]}," +
                        "\"pose\":{\"x\":1.2,\"y\":2.3,\"yaw\":0.4}," +
                        "\"goal\":{\"x\":4,\"y\":5,\"yaw\":1.57}," +
                        "\"path\":[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]," +
                        "\"waypoints\":{\"state\":\"active\",\"total\":4,\"current_index\":1," +
                        "\"missed\":[],\"message\":\"巡逻执行中\"}}"
        );

        assertEquals(3L, snapshot.getMapGeneration());
        assertEquals(false, snapshot.getMapReset());
        assertNotNull(snapshot.getMap());
        assertEquals(3, snapshot.getMap().getWidth());
        assertArrayEquals(new int[]{-1, -1, 0, 0, 0, 100}, snapshot.getMap().getCells());
        assertEquals(1.2, snapshot.getPose().getX(), 0.0001);
        assertEquals(4.0, snapshot.getGoal().getX(), 0.0001);
        assertEquals(2, snapshot.getPath().size());
        assertEquals("active", snapshot.getWaypoints().getState());
        assertEquals(4, snapshot.getWaypoints().getTotal());
        assertEquals(1, snapshot.getWaypoints().getCurrentIndex());
    }

    @Test
    public void acceptsSnapshotWithoutRepeatedMapPayload() {
        NavigationSnapshot snapshot = NavigationSnapshotParser.parse(
                "{\"map_generation\":8,\"map_reset\":true,\"map\":null,\"pose\":null,\"goal\":null,\"path\":[]}"
        );

        assertEquals(8L, snapshot.getMapGeneration());
        assertEquals(true, snapshot.getMapReset());
        assertNull(snapshot.getMap());
        assertNull(snapshot.getPose());
        assertEquals("idle", snapshot.getWaypoints().getState());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsRleThatDoesNotFillMap() {
        NavigationSnapshotParser.parse(
                "{\"map_generation\":1,\"map\":{" +
                        "\"generation\":1,\"width\":2,\"height\":2,\"resolution\":1," +
                        "\"origin\":{\"x\":0,\"y\":0,\"yaw\":0},\"data_rle\":[0,3]}}"
        );
    }
}
