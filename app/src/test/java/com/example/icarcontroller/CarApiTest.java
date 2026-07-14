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
        assertEquals(
                "http://10.161.57.230:8000/navigation/state?map_generation=7",
                api.navigationStateUrl(7)
        );
        assertEquals(
                "http://10.161.57.230:8000/navigation/waypoints",
                api.navigationWaypointsUrl()
        );
        assertEquals(
                "http://10.161.57.230:8000/navigation/waypoints/cancel",
                api.navigationWaypointsCancelUrl()
        );
    }

    @Test
    public void buildsAutomaticMappingAndNavigationUrls() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals("http://10.161.57.230:8000/automation/status", api.automationStatusUrl());
        assertEquals("http://10.161.57.230:8000/automation/mapping/start", api.automaticMappingStartUrl());
        assertEquals("http://10.161.57.230:8000/automation/mapping/save", api.automaticMappingSaveUrl());
        assertEquals(
                "http://10.161.57.230:8000/automation/navigation/start?algorithm=dwa",
                api.automaticNavigationStartUrl("dwa")
        );
        assertEquals(
                "http://10.161.57.230:8000/automation/navigation/stop",
                api.automaticNavigationStopUrl()
        );
        assertEquals(
                "http://10.161.57.230:8000/automation/navigation/start?algorithm=astar_rpp",
                api.automaticNavigationStartUrl("astar_rpp")
        );
    }

    @Test
    public void buildsContainerSelectionUrls() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals("http://10.161.57.230:8000/container/status", api.containerStatusUrl());
        assertEquals(
                "http://10.161.57.230:8000/container/select?id=icar-foxy",
                api.selectContainerUrl(" icar-foxy ")
        );
    }

    @Test
    public void buildsCameraUrlsFromConfiguredHostAndPort() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals("http://10.161.57.230:8000/camera/stream", api.cameraStreamUrl());
        assertEquals("http://10.161.57.230:8000/camera/status", api.cameraStatusUrl());
        assertEquals("http://10.161.57.230:8000/camera/restart", api.cameraRestartUrl());
        assertEquals("http://10.161.57.230:8000/arrow_turn/status", api.arrowTurnStatusUrl());
    }

    @Test
    public void buildsVoiceNotificationUrls() {
        CarApi api = new CarApi("10.161.57.230", 8000);

        assertEquals("http://10.161.57.230:8000/speak", api.speakUrl());
        assertEquals("http://10.161.57.230:8000/speech/enqueue", api.speechEnqueueUrl());
        assertEquals("http://10.161.57.230:8000/notify/patrol_start", api.notifyUrl("patrol_start"));
    }

    @Test
    public void cameraUrlsShareTheNormalizedBaseUrl() {
        CarApi api = new CarApi("  https://10.161.57.230///  ", 8000);

        assertEquals("http://10.161.57.230:8000/camera/stream", api.cameraStreamUrl());
        assertEquals("http://10.161.57.230:8000/camera/status", api.cameraStatusUrl());
        assertEquals("http://10.161.57.230:8000/camera/restart", api.cameraRestartUrl());
    }
}
