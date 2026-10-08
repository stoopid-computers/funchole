package com.funchole.backend.sandbox.manager;

import java.io.IOException;
import java.util.Map;

/** The running manager. {@link SandboxManagerMain} uses it, and so can tests that need a real one. */
public final class SandboxManager implements AutoCloseable {

    private final ManagerServer server;

    private SandboxManager(ManagerServer server) {
        this.server = server;
    }

    /** Starts a manager configured from {@code environment} (see {@code ManagerConfig}). */
    public static SandboxManager start(Map<String, String> environment) throws IOException {
        ManagerServer server = new ManagerServer(ManagerConfig.from(environment));
        server.start();
        return new SandboxManager(server);
    }

    public int port() {
        return server.port();
    }

    @Override
    public void close() {
        server.close();
    }
}
