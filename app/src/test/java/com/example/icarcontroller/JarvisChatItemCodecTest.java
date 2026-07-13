package com.example.icarcontroller;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class JarvisChatItemCodecTest {
    @Test
    public void roundTripsMessagesAndControlTask() {
        JarvisChatItem.UserMessage user = new JarvisChatItem.UserMessage("前进0.1米", "10:20");
        JarvisControlTask task = new JarvisControlTask(
                "task-1",
                "前进 0.1米",
                "motion",
                JarvisControlTaskState.READY,
                Arrays.asList("检查服务", "启动底盘", "执行前进"),
                2,
                "准备就绪，等待启动。",
                0.0,
                0.1,
                "米",
                null
        );
        JarvisChatItem.ControlTaskCard card = new JarvisChatItem.ControlTaskCard(task, "10:21");

        JarvisChatItem restoredUser = JarvisChatItemCodec.decode(JarvisChatItemCodec.encode(user));
        JarvisChatItem restoredCard = JarvisChatItemCodec.decode(JarvisChatItemCodec.encode(card));

        assertEquals(user, restoredUser);
        assertEquals(card, restoredCard);
    }

    @Test
    public void roundTripsPlanProgressAndReportCards() {
        JarvisMissionPlan plan = new JarvisMissionPlan(
                "巡检 B2",
                Collections.singletonList(new JarvisMissionStep(
                        JarvisAction.START_TASK,
                        Collections.<String, Object>singletonMap("task", "camera")
                )),
                Collections.singletonList("完成巡检"),
                true
        );
        JarvisMission mission = new JarvisMission(
                "mission-1", JarvisMissionState.RUNNING, plan,
                "2026-07-13T10:00:00+08:00", "2026-07-13T10:01:00+08:00", null
        );
        JarvisTimelineEntry entry = new JarvisTimelineEntry(
                "event-1", "mission-1", "2026-07-13T10:01:00+08:00",
                "control", "camera started", Collections.emptyMap()
        );
        JarvisReport report = new JarvisReport("mission-1", "# 报告", "2026-07-13T10:02:00+08:00");

        JarvisChatItem.PlanCard planCard = new JarvisChatItem.PlanCard(plan, "10:00");
        JarvisChatItem.ProgressCard progress = new JarvisChatItem.ProgressCard(
                mission, Collections.singletonList(entry), "10:01"
        );
        JarvisChatItem.ReportCard reportCard = new JarvisChatItem.ReportCard(report, "10:02");

        assertEquals(planCard, JarvisChatItemCodec.decode(JarvisChatItemCodec.encode(planCard)));
        assertEquals(progress, JarvisChatItemCodec.decode(JarvisChatItemCodec.encode(progress)));
        assertEquals(reportCard, JarvisChatItemCodec.decode(JarvisChatItemCodec.encode(reportCard)));
    }

    @Test
    public void unknownTypeBecomesRecoverableSystemMessage() {
        JarvisChatItem restored = JarvisChatItemCodec.decode(
                new JarvisEncodedChatItem("future_type", "{}")
        );

        assertTrue(restored instanceof JarvisChatItem.SystemEvent);
    }
}
