package com.example.icarcontroller;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

public class MjpegFrameReaderTest {
    @Test
    public void extractsTwoConsecutiveFrames() throws Exception {
        byte[] first = jpeg(1);
        byte[] second = jpeg(2);
        MjpegFrameReader reader = reader(concat(first, second), 100);

        assertArrayEquals(first, reader.nextFrame());
        assertArrayEquals(second, reader.nextFrame());
    }

    @Test
    public void skipsLeadingNoiseBeforeAFrame() throws Exception {
        byte[] frame = jpeg(7);
        MjpegFrameReader reader = reader(
                concat("multipart boundary".getBytes(StandardCharsets.US_ASCII), frame),
                100
        );

        assertArrayEquals(frame, reader.nextFrame());
    }

    @Test
    public void returnsNullOnCleanEofBeforeAFrame() throws Exception {
        MjpegFrameReader emptyReader = reader(new byte[0], 100);
        MjpegFrameReader noiseReader = reader("noise".getBytes(StandardCharsets.US_ASCII), 100);

        assertNull(emptyReader.nextFrame());
        assertNull(noiseReader.nextFrame());
    }

    @Test(expected = IOException.class)
    public void rejectsTruncatedFrameAtEof() throws Exception {
        MjpegFrameReader reader = reader(
                new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, 3},
                100
        );

        reader.nextFrame();
    }

    @Test(expected = IOException.class)
    public void rejectsFrameLargerThanConfiguredMaximum() throws Exception {
        MjpegFrameReader reader = reader(jpeg(1, 2, 3), 6);

        reader.nextFrame();
    }

    private static MjpegFrameReader reader(byte[] bytes, int maxFrameBytes) {
        return new MjpegFrameReader(new ByteArrayInputStream(bytes), maxFrameBytes);
    }

    private static byte[] jpeg(int... payload) {
        byte[] frame = new byte[payload.length + 4];
        frame[0] = (byte) 0xff;
        frame[1] = (byte) 0xd8;
        for (int i = 0; i < payload.length; i++) {
            frame[i + 2] = (byte) payload[i];
        }
        frame[frame.length - 2] = (byte) 0xff;
        frame[frame.length - 1] = (byte) 0xd9;
        return frame;
    }

    private static byte[] concat(byte[]... chunks) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            output.write(chunk);
        }
        return output.toByteArray();
    }
}
