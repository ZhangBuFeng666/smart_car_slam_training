package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class JarvisConversationPolicyTest {
    @Test
    public void asksOnlyForStartingOrRunningTasks() {
        assertEquals(SwitchRequirement.ASK_USER,
                JarvisConversationPolicy.switchRequirement(JarvisControlTaskState.STARTING));
        assertEquals(SwitchRequirement.ASK_USER,
                JarvisConversationPolicy.switchRequirement(JarvisControlTaskState.RUNNING));
        assertEquals(SwitchRequirement.SWITCH_NOW,
                JarvisConversationPolicy.switchRequirement(JarvisControlTaskState.PREPARING));
        assertEquals(SwitchRequirement.SWITCH_NOW,
                JarvisConversationPolicy.switchRequirement(JarvisControlTaskState.READY));
        assertEquals(SwitchRequirement.SWITCH_NOW,
                JarvisConversationPolicy.switchRequirement(null));
    }
}
