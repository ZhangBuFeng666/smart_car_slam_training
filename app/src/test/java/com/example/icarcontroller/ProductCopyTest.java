package com.example.icarcontroller;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProductCopyTest {
    @Test
    public void appCopyFeelsLikeAConsumerProduct() {
        assertEquals("智能小车助手", ProductCopy.appTitle());
        assertEquals("开始驾驶", ProductCopy.primaryAction());
        assertFalse(ProductCopy.appTitle().contains("控制台"));
    }

    @Test
    public void homeCopyEmphasizesDeviceAndSimpleActions() {
        assertTrue(ProductCopy.homeHeadline().contains("iCar"));
        assertTrue(ProductCopy.homeSubtitle().contains("驾驶"));
        assertFalse(ProductCopy.homeSubtitle().contains("ROS"));
        assertFalse(ProductCopy.homeSubtitle().contains("Docker"));
    }
}
