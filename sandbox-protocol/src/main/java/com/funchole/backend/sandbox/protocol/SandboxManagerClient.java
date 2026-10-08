package com.funchole.backend.sandbox.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Runs a command in the sandbox manager against a local directory: uploads the directory as the job's
 * workspace, runs the command inside the sandbox, and (when asked) brings the changed workspace back.
 *
 * <p>A job stays open across calls while {@link SyncBack#NONE} is requested, so a multi-step build
 * ({@code npm ci} then {@code npm run build}) uploads once and does not shuttle {@code node_modules}
 * back and forth. A failed or timed-out command closes the job.
 */
public final class SandboxManagerClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration RUN_GRACE = Duration.ofSeconds(60);

    private final URI baseUrl;
    private final String token;
    private final HttpClient http;
    private final SafeTar.Limits extractLimits;
    private final Map<Path, String> openJobs = new ConcurrentHashMap<>();

    public SandboxManagerClient(URI baseUrl, String token) {
        this(baseUrl, token, SafeTar.Limits.DEFAULT);
    }

    public SandboxManagerClient(URI baseUrl, String token, SafeTar.Limits extractLimits) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.extractLimits = extractLimits;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public ExecResult exec(Path workingDirectory, List<String> command, Duration timeout, SyncBack syncBack) {
        Path key = workingDirectory.toAbsolutePath().normalize();
        String jobId = openJobs.get(key);
        try {
            if (jobId == null) {
                jobId = createJob();
                openJobs.put(key, jobId);
                upload(jobId, key);
            }
            ExecResult result = run(jobId, new ExecRequest(command, (int) Math.max(1, timeout.toSeconds())), timeout);
            boolean succeeded = !result.timedOut() && result.exitCode() != null && result.exitCode() == 0;
            if (succeeded && syncBack != SyncBack.NONE) {
                download(jobId, key, syncBack);
            }
            if (!succeeded || syncBack != SyncBack.NONE) {
                closeJob(key, jobId);
            }
            return result;
        } catch (RuntimeException failure) {
            if (jobId != null) {
                closeJob(key, jobId);
            }
            throw failure;
        }
    }

    /** Discards a job left open (best effort); the manager also expires idle jobs by itself. */
    public void release(Path workingDirectory) {
        Path key = workingDirectory.toAbsolutePath().normalize();
        String jobId = openJobs.get(key);
        if (jobId != null) {
            closeJob(key, jobId);
        }
    }

    private String createJob() {
        HttpResponse<String> response = send(request("/v1/jobs").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        expect(response.statusCode(), 201, response.body());
        try {
            return JSON.readTree(response.body()).path("id").asText();
        } catch (IOException exception) {
            throw new SandboxManagerException("Unreadable response creating a sandbox job", exception);
        }
    }

    private void upload(String jobId, Path directory) {
        Path archive = null;
        try {
            archive = Files.createTempFile("sandbox-upload-", ".tar.gz");
            try (var out = Files.newOutputStream(archive)) {
                SafeTar.pack(directory, out);
            }
            HttpResponse<String> response = send(
                    request("/v1/jobs/" + jobId + "/workspace").PUT(HttpRequest.BodyPublishers.ofFile(archive)).build(),
                    HttpResponse.BodyHandlers.ofString());
            expect(response.statusCode(), 204, response.body());
        } catch (IOException exception) {
            throw new SandboxManagerException("Failed to upload the workspace to the sandbox", exception);
        } finally {
            deleteQuietly(archive);
        }
    }

    private ExecResult run(String jobId, ExecRequest execRequest, Duration timeout) {
        try {
            HttpRequest request = request("/v1/jobs/" + jobId + "/run")
                    .timeout(timeout.plus(RUN_GRACE))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(execRequest)))
                    .build();
            HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
            expect(response.statusCode(), 200, response.body());
            return JSON.readValue(response.body(), ExecResult.class);
        } catch (IOException exception) {
            throw new SandboxManagerException("Unreadable response running a sandbox command", exception);
        }
    }

    private void download(String jobId, Path directory, SyncBack syncBack) {
        String query = syncBack == SyncBack.EXCLUDING_NODE_MODULES ? "?exclude=node_modules" : "";
        HttpResponse<InputStream> response = send(request("/v1/jobs/" + jobId + "/workspace" + query).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                expect(response.statusCode(), 200, new String(body.readAllBytes(), StandardCharsets.UTF_8));
            }
            // Extract aside first, so a rejected archive leaves the local workspace untouched.
            Path staging = Files.createTempDirectory(directory.getParent() != null ? directory.getParent() : directory, ".sandbox-staging-");
            try {
                SafeTar.extract(body, staging, extractLimits);
                replaceContents(directory, staging, syncBack == SyncBack.EXCLUDING_NODE_MODULES);
            } finally {
                deleteTree(staging);
            }
        } catch (IOException exception) {
            throw new SandboxManagerException("The workspace returned by the sandbox was rejected: " + exception.getMessage(), exception);
        }
    }

    private static void replaceContents(Path directory, Path staging, boolean keepNodeModules) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (!(keepNodeModules && child.getFileName().toString().equals("node_modules"))) {
                    deleteTree(child);
                }
            }
        }
        try (Stream<Path> children = Files.list(staging)) {
            for (Path child : children.toList()) {
                Files.move(child, directory.resolve(child.getFileName().toString()));
            }
        }
    }

    private void closeJob(Path key, String jobId) {
        openJobs.remove(key, jobId);
        try {
            send(request("/v1/jobs/" + jobId).DELETE().build(), HttpResponse.BodyHandlers.discarding());
        } catch (SandboxManagerException ignored) {
            // The manager expires idle jobs on its own.
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(Duration.ofMinutes(10)).header("Authorization", "Bearer " + token);
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException exception) {
            throw new SandboxManagerException("Sandbox manager unreachable (" + request.method() + " " + request.uri().getPath() + "): " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SandboxManagerException("Interrupted while talking to the sandbox manager", exception);
        }
    }

    private static void expect(int actual, int expected, String body) {
        if (actual != expected) {
            throw new SandboxManagerException("Sandbox manager returned HTTP " + actual + ": " + body);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // Temp file; the OS will reclaim it.
            }
        }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path candidate : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        }
    }
}
