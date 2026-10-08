package com.funchole.backend.sandbox.manager;

import java.util.concurrent.CountDownLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SandboxManagerMain {
    private static final Logger logger = LoggerFactory.getLogger(SandboxManagerMain.class);

    private SandboxManagerMain() {
    }

    public static void main(String[] args) throws Exception {
        SandboxManager manager = SandboxManager.start(System.getenv());
        Runtime.getRuntime().addShutdownHook(new Thread(manager::close));
        logger.info("Sandbox manager running. Press Ctrl+C to stop.");
        new CountDownLatch(1).await();
    }
}
