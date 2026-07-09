package com.example.icarcontroller;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class InteractionSpecTest {
    @Test
    public void aiAssistantUsesBottomSheetInsteadOfAStaticPage() {
        assertEquals("bottom_sheet", InteractionSpec.aiPresentation());
    }

    @Test
    public void productShellHasMotionTiming() {
        assertTrue(InteractionSpec.pageTransitionMillis() >= 180);
        assertTrue(InteractionSpec.pressFeedbackScale() < 1.0f);
        assertTrue(InteractionSpec.pressFeedbackScale() > 0.90f);
    }

    @Test
    public void productShellKeepsControlsClearOfEdgesAndBottomNavigation() {
        assertTrue((Integer) requiredSpec("contentBottomClearanceDp") >= 96);
        assertTrue((Integer) requiredSpec("homeRailSideInsetDp") >= 16);
        assertTrue((Integer) requiredSpec("remoteButtonSizeDp") >= 68);
    }

    @Test
    public void matureInteractionsUseLayeredMotion() {
        assertTrue((Float) requiredSpec("navSelectionScale") > 1.0f);
        assertTrue((Integer) requiredSpec("statusPulseMillis") >= 260);
        assertTrue((Integer) requiredSpec("remoteRepeatMillis") <= 220);
    }

    private Object requiredSpec(String methodName) {
        try {
            Method method = InteractionSpec.class.getMethod(methodName);
            return method.invoke(null);
        } catch (NoSuchMethodException missing) {
            fail("InteractionSpec is missing " + methodName);
        } catch (Exception error) {
            fail("InteractionSpec method failed: " + methodName + " " + error.getMessage());
        }
        return null;
    }
}
