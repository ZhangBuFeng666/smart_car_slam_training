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
    public void chatMessageAppendsUserAndLoadingEvent() {
        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.UserMessageSubmitted("巡检 B2 东侧停车区")
        );

        assertEquals(2, state.getChatItems().size());
        assertTrue(state.getChatItems().get(0) instanceof JarvisChatItem.UserMessage);
        assertTrue(state.getChatItems().get(1) instanceof JarvisChatItem.SystemEvent);
        assertTrue(state.getLoading());
    }

    @Test
    public void planReadyAppendsAssistantAndPlanCard() {
        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.UserMessageSubmitted("打开摄像头")
        );
        JarvisMissionPlan plan = TestFixtures.plan();

        JarvisViewState next = JarvisReducer.reduce(state, new JarvisEvent.PlanReady(plan));

        assertFalse(next.getLoading());
        assertEquals(plan, next.getPlan());
        assertTrue(next.getChatItems().get(next.getChatItems().size() - 2) instanceof JarvisChatItem.AssistantMessage);
        assertTrue(next.getChatItems().get(next.getChatItems().size() - 1) instanceof JarvisChatItem.PlanCard);
    }

    @Test
    public void chatReplyAppendsOnlyAssistantMessage() {
        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.UserMessageSubmitted("你好")
        );

        JarvisViewState next = JarvisReducer.reduce(
                state,
                new JarvisEvent.ChatReply("你好，我在。有什么可以帮你？")
        );

        assertFalse(next.getLoading());
        assertEquals(2, next.getChatItems().size());
        assertTrue(next.getChatItems().get(1) instanceof JarvisChatItem.AssistantMessage);
    }

    @Test
    public void chatReplyStoresSpokenTextForReplay() {
        JarvisViewState state = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.UserMessageSubmitted("介绍一下当前画面")
        );

        JarvisViewState next = JarvisReducer.reduce(
                state,
                new JarvisEvent.ChatReply("屏幕显示完整回答。", "适合播报的回答。")
        );

        JarvisChatItem.AssistantMessage message =
                (JarvisChatItem.AssistantMessage) next.getChatItems().get(1);
        assertEquals("适合播报的回答。", message.getSpokenText());
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
        assertTrue(state.getChatItems().get(state.getChatItems().size() - 1) instanceof JarvisChatItem.ProgressCard);
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
        assertTrue(state.getChatItems().get(state.getChatItems().size() - 1) instanceof JarvisChatItem.ReportCard);
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
        assertTrue(state.getChatItems().get(state.getChatItems().size() - 1) instanceof JarvisChatItem.ErrorMessage);
    }

    @Test
    public void locallyStoppedControlTaskIgnoresStaleRunningPollResult() {
        JarvisControlTask running = TestFixtures.controlTask(JarvisControlTaskState.RUNNING);
        JarvisViewState withTask = JarvisReducer.reduce(
                JarvisViewState.Companion.initial(),
                new JarvisEvent.ControlTaskReady(running)
        );

        JarvisViewState stopped = JarvisReducer.reduce(
                withTask,
                new JarvisEvent.ControlTaskLocallyStopped("task-001")
        );
        JarvisViewState afterStalePoll = JarvisReducer.reduce(
                stopped,
                new JarvisEvent.ControlTaskUpdated(running)
        );

        JarvisChatItem.ControlTaskCard card =
                (JarvisChatItem.ControlTaskCard) afterStalePoll.getChatItems().get(0);
        assertEquals(JarvisControlTaskState.STOPPED, card.getTask().getState());
    }

    @Test
    public void explicitRestartAllowsFreshPreparingProgressAfterLocalStop() {
        JarvisControlTask running = TestFixtures.controlTask(JarvisControlTaskState.RUNNING);
        JarvisControlTask preparing = TestFixtures.controlTask(JarvisControlTaskState.PREPARING);
        JarvisViewState state = JarvisReducer.reduce(
                JarvisReducer.reduce(
                        JarvisReducer.reduce(
                                JarvisViewState.Companion.initial(),
                                new JarvisEvent.ControlTaskReady(running)
                        ),
                        new JarvisEvent.ControlTaskLocallyStopped("task-001")
                ),
                new JarvisEvent.ControlTaskRestartRequested("task-001")
        );

        JarvisViewState restarted = JarvisReducer.reduce(
                state,
                new JarvisEvent.ControlTaskUpdated(preparing)
        );

        JarvisChatItem.ControlTaskCard card =
                (JarvisChatItem.ControlTaskCard) restarted.getChatItems().get(0);
        assertEquals(JarvisControlTaskState.PREPARING, card.getTask().getState());
        assertFalse(restarted.getLocallyStoppedControlTaskIds().contains("task-001"));
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

        static JarvisControlTask controlTask(JarvisControlTaskState state) {
            return new JarvisControlTask(
                    "task-001",
                    "自动跟随",
                    "feature",
                    state,
                    java.util.Collections.singletonList("启动自动跟随"),
                    1,
                    "自动跟随运行中",
                    1.0,
                    1.0,
                    "",
                    null
            );
        }
    }
}
