package com.example.icarcontroller;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class HttpResponseBodyReaderTest {
    @Test
    public void skipsBodyWithoutTouchingStreamForLatencySensitiveRequests() {
        InputStream blockingStream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("body must not be read");
            }
        };

        assertEquals("", HttpResponseBodyReader.INSTANCE.read(blockingStream, -1, false));
    }

    @Test
    public void readsOnlyDeclaredContentLength() {
        byte[] bytes = "oktrailing".getBytes(StandardCharsets.UTF_8);

        assertEquals(
                "ok",
                HttpResponseBodyReader.INSTANCE.read(new ByteArrayInputStream(bytes), 2, true)
        );
    }
}
