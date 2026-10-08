package com.funchole.backend.sandbox.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.funchole.backend.sandbox.protocol.ExecRequest;
import com.funchole.backend.sandbox.protocol.ExecResult;
import com.funchole.backend.sandbox.protocol.SafeTar;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The manager's HTTP API. Every endpoint except {@code /healthz} requires the shared bearer token.
 *
 * <pre>
 *   POST   /v1/jobs                        create a job                 -> 201 {"id": ...}
 *   PUT    /v1/jobs/{id}/workspace         upload the workspace tar.gz  -> 204
 *   POST   /v1/jobs/{id}/run               run a command                -> 200 ExecResult
 *   GET    /v1/jobs/{id}/workspace[?exclude=node_modules]  tar.gz       -> 200
 *   DELETE /v1/jobs/{id}                   discard                      -> 204
 * </pre>
 */
final class ManagerServer implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(ManagerServer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ManagerConfig config;
    private final JobStore jobs;
    private final BuildRunner runner;
    private final HttpServer server;
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "job-reaper");
        thread.setDaemon(true);
        return thread;
    });

    ManagerServer(ManagerConfig config) throws IOException {
        this.config = config;
        this.jobs = new JobStore(config.jobsDir(), config.maxJobs(), config.jobTtl());
        this.runner = new BuildRunner(config);
        this.server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        this.server.createContext("/", this::handle);
        reaper.scheduleWithFixedDelay(jobs::expireIdle, 1, 1, TimeUnit.MINUTES);
    }

    void start() {
        server.start();
        logger.info("Sandbox manager listening on {}:{}", config.bindAddress(), port());
    }

    int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/healthz")) {
                respond(exchange, 200, Map.of("status", "ok", "jobs", jobs.size()));
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, Map.of("error", "unauthorized"));
                return;
            }
            route(exchange, path, exchange.getRequestMethod());
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, Map.of("error", exception.getMessage()));
        } catch (SafeTar.UnsafeArchiveException exception) {
            respond(exchange, 422, Map.of("error", "rejected archive: " + exception.getMessage()));
        } catch (IllegalStateException exception) {
            respond(exchange, 429, Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            logger.error("Unhandled error for {} {}", exchange.getRequestMethod(), exchange.getRequestURI(), exception);
            respond(exchange, 500, Map.of("error", "internal error"));
        } finally {
            exchange.close();
        }
    }

    private void route(HttpExchange exchange, String path, String method) throws Exception {
        String[] parts = path.split("/");
        // "", "v1", "jobs", [id, [action]]
        if (parts.length < 3 || !parts[1].equals("v1") || !parts[2].equals("jobs")) {
            respond(exchange, 404, Map.of("error", "not found"));
            return;
        }
        if (parts.length == 3 && method.equals("POST")) {
            JobStore.Job job = jobs.create();
            respond(exchange, 201, Map.of("id", job.id));
            return;
        }
        JobStore.Job job = parts.length >= 4 ? jobs.find(parts[3]) : null;
        if (job == null) {
            respond(exchange, 404, Map.of("error", "no such job"));
            return;
        }
        String action = parts.length >= 5 ? parts[4] : "";
        if (action.isEmpty() && method.equals("DELETE")) {
            jobs.delete(job.id);
            respond(exchange, 204, null);
        } else if (action.equals("workspace") && method.equals("PUT")) {
            upload(exchange, job);
        } else if (action.equals("workspace") && method.equals("GET")) {
            download(exchange, job);
        } else if (action.equals("run") && method.equals("POST")) {
            run(exchange, job);
        } else {
            respond(exchange, 404, Map.of("error", "not found"));
        }
    }

    private void upload(HttpExchange exchange, JobStore.Job job) throws IOException {
        JobStore.deleteTree(job.workspace);
        try (InputStream body = new LimitedInputStream(exchange.getRequestBody(), config.maxUploadBytes())) {
            SafeTar.extract(body, job.workspace);
        }
        respond(exchange, 204, null);
    }

    private void run(HttpExchange exchange, JobStore.Job job) throws Exception {
        ExecRequest request = JSON.readValue(exchange.getRequestBody(), ExecRequest.class);
        job.running = true;
        try {
            ExecResult result = runner.run(job, request);
            respond(exchange, 200, result);
        } finally {
            job.running = false;
            job.touch();
        }
    }

    private void download(HttpExchange exchange, JobStore.Job job) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        List<String> exclude = query != null && query.contains("exclude=node_modules") ? List.of("node_modules") : List.of();
        exchange.getResponseHeaders().set("Content-Type", "application/gzip");
        exchange.sendResponseHeaders(200, 0);
        SafeTar.pack(job.workspace, exchange.getResponseBody(), exclude);
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        byte[] given = header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(given, config.token().getBytes(StandardCharsets.UTF_8));
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /** Stops a client from streaming an unbounded upload at us. */
    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        LimitedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) consume(1);
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) consume(read);
            return read;
        }

        private void consume(long bytes) throws IOException {
            remaining -= bytes;
            if (remaining < 0) {
                throw new SafeTar.UnsafeArchiveException("upload exceeds the size limit");
            }
        }
    }

    @Override
    public void close() {
        server.stop(1);
        reaper.shutdownNow();
        jobs.deleteAll();
    }
}
