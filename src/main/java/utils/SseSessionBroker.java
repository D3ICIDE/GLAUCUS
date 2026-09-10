package utils;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SseSessionBroker {
    private final Map<Object, OutputStream> streams = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();

    public void register(Object sessionId, OutputStream out) {
        OutputStream old = streams.put(sessionId, out);
        if (old != null) {
            try { old.close(); } catch (IOException ignored) {}
        }
    }

    public void publish(Object sessionId, Map<String, Object> event) {
        OutputStream out = streams.get(sessionId);
        if (out == null) return;
        try {
            String json = gson.toJson(event);
            byte[] frame = ("data: " + json + "\n\n").getBytes(StandardCharsets.UTF_8);
            synchronized (out) {
                out.write(frame);
                out.flush();
            }
        } catch (IOException e) {
            streams.remove(sessionId);
        }
    }

    public void close(Object sessionId) {
        OutputStream out = streams.remove(sessionId);
        if (out != null) {
            try { out.flush(); out.close(); } catch (IOException ignored) {}
        }
    }
}