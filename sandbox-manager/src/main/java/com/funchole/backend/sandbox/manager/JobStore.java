package com.funchole.backend.sandbox.manager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Job workspaces on disk, with a cap on how many exist and automatic expiry of forgotten ones. */
final class JobStore {
    private static final Logger logger = LoggerFactory.getLogger(JobStore.class);

    static final class Job {
        final String id;
        final Path root;
        final Path workspace;
        volatile long lastUsedNanos = System.nanoTime();
        volatile boolean running;
        volatile boolean prepared;

        Job(String id, Path root) {
            this.id = id;
            this.root = root;
            this.workspace = root.resolve("workspace");
        }

        void touch() {
            lastUsedNanos = System.nanoTime();
        }
    }

    private final Path jobsDir;
    private final int maxJobs;
    private final Duration ttl;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    JobStore(Path jobsDir, int maxJobs, Duration ttl) {
        this.jobsDir = jobsDir;
        this.maxJobs = maxJobs;
        this.ttl = ttl;
        try {
            Files.createDirectories(jobsDir);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    synchronized Job create() {
        if (jobs.size() >= maxJobs) {
            throw new IllegalStateException("too many open jobs (" + maxJobs + ")");
        }
        String id = UUID.randomUUID().toString();
        Path root = jobsDir.resolve(id);
        try {
            Files.createDirectories(root.resolve("workspace"));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        Job job = new Job(id, root);
        jobs.put(id, job);
        return job;
    }

    /** Looks up by id; ids are UUIDs we generated, so anything else is simply "not found" (no path tricks). */
    Job find(String id) {
        try {
            UUID.fromString(id);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        Job job = jobs.get(id);
        if (job != null) {
            job.touch();
        }
        return job;
    }

    void delete(String id) {
        Job job = jobs.remove(id);
        if (job != null) {
            deleteTree(job.root);
        }
    }

    void expireIdle() {
        long limit = ttl.toNanos();
        for (Job job : jobs.values()) {
            if (!job.running && System.nanoTime() - job.lastUsedNanos > limit) {
                logger.info("Expiring idle job {}", job.id);
                delete(job.id);
            }
        }
    }

    int size() {
        return jobs.size();
    }

    void deleteAll() {
        for (String id : jobs.keySet()) {
            delete(id);
        }
    }

    static void deleteTree(Path path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path candidate : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(candidate);
            }
        } catch (IOException exception) {
            logger.warn("Could not fully delete {}: {}", path, exception.getMessage());
        }
    }
}
