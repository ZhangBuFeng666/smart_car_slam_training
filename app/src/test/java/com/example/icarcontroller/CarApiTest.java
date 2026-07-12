package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CarApiTest {
    @Test
    public void buildsHealthUrlFromHostAndPort() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals("http://10.161.57.230:8000/health", api.healthUrl());
    }

    @Test
    public void buildsMoveUrlWithDirectionAndSpeed() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals(
                "http://10.161.57.230:8000/move/front?speed=0.18",
                api.moveUrl("front", 0.18, null)
        );
    }

    @Test
    public void buildsTaskStartAndStopUrls() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals("http://10.161.57.230:8000/start/base", api.startTaskUrl("base"));
        assertEquals("http://10.161.57.230:8000/stop/base", api.stopTaskUrl("base"));
    }

    @Test
    public void buildsNavigationPoseUrls() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals(
                "http://10.161.57.230:8000/navigation/initial_pose?x=1.2&y=-0.5&yaw=1.57",
                api.navigationInitialPoseUrl(1.2, -0.5, 1.57)
        );
        assertEquals(
                "http://10.161.57.230:8000/navigation/goal?x=2&y=3.45&yaw=0",
                api.navigationGoalUrl(2.0, 3.45, 0.0)
        );
    }

    @Test
    public void buildsCameraUrlsFromConfiguredHostAndPort() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals("http://10.161.57.230:8000/camera/stream", api.cameraStreamUrl());
        assertEquals("http://10.161.57.230:8000/camera/status", api.cameraStatusUrl());
        assertEquals("http://10.161.57.230:8000/camera/restart", api.cameraRestartUrl());
    }

    @Test
    public void cameraUrlsShareTheNormalizedBaseUrl() {
        CarApi api = new CarApi("  https://10.161.57.230///  ", 8000);

        assertEquals("http://10.161.57.230:8000/camera/stream", api.cameraStreamUrl());
        assertEquals("http://10.161.57.230:8000/camera/status", api.cameraStatusUrl());
        assertEquals("http://10.161.57.230:8000/camera/restart", api.cameraRestartUrl());
    }
}
