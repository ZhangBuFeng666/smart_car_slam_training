package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JarvisModelsTest {
    private static final String MISSION_JSON = "{"
            + "\"id\":\"mission-001\","
            + "\"state\":\"WAITING_CONFIRMATION\","
            + "\"plan\":{"
            + "\"summary\":\"Start camera patrol\","
            + "\"steps\":[{\"action\":\"START_TASK\",\"arguments\":{\"task\":\"camera\"}}],"
            + "\"completion_criteria\":[\"Camera active\"],"
            + "\"requires_confirmation\":true"
            + "},"
            + "\"created_at\":\"2026-01-15T10:30:00+08:00\","
            + "\"updated_at\":\"2026-01-15T10:30:01+08:00\","
            + "\"pending_decision\":null"
            + "}";

    @Test
    public void parsesMissionView() {
        JarvisMission mission = JarvisJson.parseMission(MISSION_JSON);

        assertEquals("mission-001", mission.getId());
        assertEquals(JarvisMissionState.WAITING_CONFIRMATION, mission.getState());
        assertEquals("Start camera patrol", mission.getPlan().getSummary());
        assertEquals(JarvisAction.START_TASK, mission.getPlan().getSteps().get(0).getAction());
        assertEquals("camera", mission.getPlan().getSteps().get(0).getArguments().get("task"));
        assertTrue(mission.getPlan().getRequiresConfirmation());
    }

    @Test
    public void parsesTimelineEntry() {
        JarvisTimelineEntry entry = JarvisJson.parseTimelineEntry("{"
                + "\"id\":\"timeline-001\","
                + "\"mission_id\":\"mission-001\","
                + "\"timestamp\":\"2026-01-15T10:30:00+08:00\","
                + "\"kind\":\"vision_event\","
                + "\"message\":\"obstacle detected\","
                + "\"metadata\":{\"image_path\":\"/tmp/evidence.jpg\"}"
                + "}");

        assertEquals("timeline-001", entry.getId());
        assertEquals("obstacle detected", entry.getMessage());
        assertEquals("/tmp/evidence.jpg", entry.getMetadata().get("image_path"));
    }

    @Test
    public void parsesReport() {
        JarvisReport report = JarvisJson.parseReport("{"
                + "\"mission_id\":\"mission-001\","
                + "\"markdown\":\"# Jarvis Patrol Report\","
                + "\"created_at\":\"2026-01-15T10:30:00+08:00\""
                + "}");

        assertEquals("mission-001", report.getMissionId());
        assertEquals("# Jarvis Patrol Report", report.getMarkdown());
    }

    @Test
    public void jarvisApiBuildsAgentUrlsFromCarHost() {
        JarvisApi api = new JarvisApi("10.161.57.230", "test-token");

        assertEquals("http://10.161.57.230:8100/health", api.healthUrl());
        assertEquals(
                "http://10.161.57.230:8100/api/v1/missions/mission-001",
                api.missionUrl("mission-001")
        );
        assertEquals("Bearer test-token", api.authorizationHeader());
    }

    @Test(expected = JarvisProtocolException.class)
    public void rejectsUnknownMissionState() {
        JarvisJson.parseMission(MISSION_JSON.replace("WAITING_CONFIRMATION", "BROKEN"));
    }

    @Test(expected = JarvisProtocolException.class)
    public void rejectsInvalidConfidence() {
        JarvisJson.parseVisionEvent("{"
                + "\"mission_id\":\"mission-001\","
                + "\"source\":\"front_camera\","
                + "\"event_type\":\"obstacle\","
                + "\"label\":\"paper box\","
                + "\"confidence\":1.5,"
                + "\"position\":\"center\","
                + "\"track_id\":\"track-001\","
                + "\"image_path\":\"/tmp/evidence.jpg\","
                + "\"timestamp\":\"2026-01-15T10:30:00+08:00\","
                + "\"metadata\":{}"
                + "}");
    }
}
