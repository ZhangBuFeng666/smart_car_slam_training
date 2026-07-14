package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ArrowTurnStatusTest {
    @Test
    public void parsesRunningStatusPayload() {
        ArrowTurnStatus status = ArrowTurnStatusParser.parse(
                "{"
                        + "\"running\":true,"
                        + "\"phase\":\"seek\","
                        + "\"note\":\"confirming_2/3_creep\","
                        + "\"direction\":\"turn_left\","
                        + "\"distance_m\":0.92,"
                        + "\"track_id\":7"
                        + "}"
        );

        assertTrue(status.getRunning());
        assertEquals("seek", status.getPhase());
        assertEquals("turn_left", status.getDirection());
        assertEquals(0.92, status.getDistanceM(), 1e-6);
        assertEquals(Integer.valueOf(7), status.getTrackId());
        assertNull(status.getError());
    }

    @Test
    public void parsesIdlePayloadWithoutPhase() {
        ArrowTurnStatus status = ArrowTurnStatusParser.parse("{\"running\":false}");

        assertFalse(status.getRunning());
        assertNull(status.getPhase());
        assertEquals("未运行", ArrowTurnUiSpec.statusText(status));
    }

    @Test
    public void formatsChineseStatusLine() {
        ArrowTurnStatus status = new ArrowTurnStatus(
                true,
                "advance",
                "advance_0.50m",
                "turn_right",
                0.85,
                3,
                null
        );

        assertEquals("再直行 0.5 m · 右转 · 0.85 m", ArrowTurnUiSpec.statusText(status));
    }
}
