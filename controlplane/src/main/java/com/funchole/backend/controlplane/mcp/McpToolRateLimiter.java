package com.funchole.backend.controlplane.mcp;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Per-user fixed-window budget, independent of MCP sessions and bounded in memory. */
@Component
public class McpToolRateLimiter {
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final int MAX_USERS = 10000;
    private final Map<UUID, Window> windows = new HashMap<>();
    private final int callsPerMinute;
    private final LongSupplier clock;

    @Autowired
    public McpToolRateLimiter(@Value("${app.mcp.tool-calls-per-minute:120}") int callsPerMinute) {
        this(callsPerMinute, System::nanoTime);
    }

    McpToolRateLimiter(int callsPerMinute, LongSupplier clock) {
        if (callsPerMinute < 1) throw new IllegalArgumentException("MCP tool-calls-per-minute must be positive");
        this.callsPerMinute = callsPerMinute;
        this.clock = clock;
    }

    public synchronized boolean tryAcquire(UUID userId) {
        long now = clock.getAsLong();
        Window window = windows.get(userId);
        if (window == null || now - window.started >= WINDOW_NANOS) {
            if (windows.size() >= MAX_USERS) {
                windows.entrySet().removeIf(entry -> now - entry.getValue().started >= WINDOW_NANOS);
                if (!windows.containsKey(userId) && windows.size() >= MAX_USERS) return false;
            }
            window = new Window(now);
            windows.put(userId, window);
        }
        if (window.calls >= callsPerMinute) return false;
        window.calls++;
        return true;
    }

    private static final class Window {
        final long started;
        int calls;
        Window(long started) { this.started = started; }
    }
}
