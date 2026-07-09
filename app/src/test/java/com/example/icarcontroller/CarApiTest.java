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
}
