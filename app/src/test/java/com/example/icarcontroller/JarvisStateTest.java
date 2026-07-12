package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class JarvisStateTest {
    @Test
    public void initialStateIsIdleWithEmergencyStopAvailable() {
        JarvisViewState state = JarvisViewState.Companion.initial();

        assertEquals(JarvisScreenMode.IDLE, state.getMode());
        assertFalse(state.getLoading());
        assertTrue(state.getEmergencyStopAvailable());
    }

    @Test
    public void chatLoadingClearsRecoverableError() {
        JarvisViewState previous = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.NetworkFailed("timeout")
        );

        JarvisViewState state = JarvisReducer.reduce(previous, JarvisEvent.ChatStarted.INSTANCE);

        assertEquals(JarvisScreenMode.CHATTING, state.getMode());
        assertTrue(state.getLoading());
        assertEquals(null, state.getErrorMessage());
    }

    @Test
    public void planReadyStoresPlanAndWaitsForConfirmation() {
        JarvisMissionPlan plan = TestFixtures.plan();

        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.PlanReady(plan)
        );

        assertEquals(JarvisScreenMode.PLAN_READY, state.getMode());
        assertEquals(plan, state.getPlan());
        assertFalse(state.getLoading());
    }

    @Test
    public void missionRunningStoresMissionAndTimeline() {
        JarvisMission mission = TestFixtures.mission(JarvisMissionState.RUNNING);
        JarvisTimelineEntry entry = TestFixtures.timeline();

        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.MissionUpdated(mission, java.util.Collections.singletonList(entry))
        );

        assertEquals(JarvisScreenMode.RUNNING, state.getMode());
        assertEquals("mission-001", state.getMissionId());
        assertEquals(1, state.getTimeline().size());
    }

    @Test
    public void pendingDecisionMovesToDecisionMode() {
        JarvisDecision decision = new JarvisDecision(JarvisDecisionType.PAUSE, "event-001", "pause_or_continue");

        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.DecisionRequired(decision)
        );

        assertEquals(JarvisScreenMode.DECISION_REQUIRED, state.getMode());
        assertEquals(decision, state.getPendingDecision());
    }

    @Test
    public void reportReadyStoresMarkdown() {
        JarvisReport report = new JarvisReport("mission-001", "# Report", "2026-01-15T10:30:00+08:00");

        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.ReportReady(report)
        );

        assertEquals(JarvisScreenMode.REPORT_READY, state.getMode());
        assertEquals(report, state.getReport());
    }

    @Test
    public void networkErrorKeepsEmergencyStopAvailable() {
        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.NetworkFailed("timeout")
        );

        assertTrue(state.getEmergencyStopAvailable());
        assertEquals("timeout", state.getErrorMessage());
        assertFalse(state.getLoading());
    }

    private static class TestFixtures {
        static JarvisMissionPlan plan() {
            return new JarvisMissionPlan(
                    "Start camera patrol",
                    java.util.Collections.singletonList(
                            new JarvisMissionStep(
                                    JarvisAction.START_TASK,
                                    java.util.Collections.singletonMap("task", "camera")
                            )
                    ),
                    java.util.Collections.emptyList(),
                    true
            );
        }

        static JarvisMission mission(JarvisMissionState state) {
            return new JarvisMission(
                    "mission-001",
                    state,
                    plan(),
                    "2026-01-15T10:30:00+08:00",
                    "2026-01-15T10:30:01+08:00",
                    null
            );
        }

        static JarvisTimelineEntry timeline() {
            return new JarvisTimelineEntry(
                    "timeline-001",
                    "mission-001",
                    "2026-01-15T10:30:00+08:00",
                    "control",
                    "Started task: camera",
                    java.util.Collections.emptyMap()
            );
        }
    }
}
