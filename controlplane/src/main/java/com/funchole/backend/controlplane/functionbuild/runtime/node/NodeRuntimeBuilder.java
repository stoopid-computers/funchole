package com.funchole.backend.controlplane.functionbuild.runtime.node;

import com.funchole.backend.artifact.ArtifactManifest;
import com.funchole.backend.controlplane.functionbuild.BuildLogRecorder;
import com.funchole.backend.controlplane.functionbuild.BuildWorkspace;
import com.funchole.backend.controlplane.functionbuild.PreparedArtifact;
import com.funchole.backend.controlplane.functionbuild.RuntimeBuilder;
import com.funchole.backend.controlplane.functionbuild.process.ProcessExecutor;
import com.funchole.backend.controlplane.functionbuild.process.ProcessResult;
import com.funchole.backend.controlplane.functionbuild.process.WorkspaceSync;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * {@link RuntimeBuilder} for the {@code NODE} runtime: copies the
 * materialized {@link BuildWorkspace} into its own prepared-artifact
 * directory (the original workspace is never written to), installs
 * dependencies there if a {@code package.json} is present, then re-verifies
 * the configured entrypoint still exists. No TypeScript compilation,
 * bundling, or custom build scripts - dependency installation only.
 */
@Component
public class NodeRuntimeBuilder implements RuntimeBuilder {

    static final String STAGE_DEPENDENCY_INSTALL = "dependency-install";

    private static final String RUNTIME_TYPE = "NODE";
    private static final String ARTIFACT_DIRECTORY_PREFIX = "funchole-prepared-";
    private static final String PACKAGE_JSON = "package.json";
    private static final String PACKAGE_LOCK_JSON = "package-lock.json";
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);

    private final ProcessExecutor processExecutor;

    public NodeRuntimeBuilder(ProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    @Override
    public boolean supports(String runtimeType) {
        return RUNTIME_TYPE.equalsIgnoreCase(runtimeType);
    }

    @Override
    public PreparedArtifact build(BuildWorkspace workspace, BuildLogRecorder logRecorder) {
        Path artifactDirectory = copyToNewArtifactDirectory(workspace);
        try {
            installDependenciesIfNeeded(workspace.functionVersionId(), artifactDirectory, logRecorder);
            verifyEntrypointStillExists(artifactDirectory, workspace.entrypoint());
            // Written into the artifact itself (not just returned here) because
            // this is the only copy of this information the Runtime Worker will
            // ever see - it never queries the FuncHole database.
            new ArtifactManifest(workspace.entrypoint(), workspace.handler()).writeInto(artifactDirectory);
            return new PreparedArtifact(
                    workspace.functionVersionId(),
                    artifactDirectory,
                    workspace.entrypoint(),
                    workspace.handler(),
                    workspace.runtimeType(),
                    workspace.runtimeVersion()
            );
        } catch (RuntimeException exception) {
            deleteRecursively(artifactDirectory);
            throw exception;
        }
    }

    private void installDependenciesIfNeeded(UUID functionVersionId, Path artifactDirectory, BuildLogRecorder logRecorder) {
        Path packageJson = artifactDirectory.resolve(PACKAGE_JSON);
        if (!Files.isRegularFile(packageJson)) {
            // Dependency-free function: no package.json, so npm is never invoked.
            return;
        }
        boolean hasLockfile = Files.isRegularFile(artifactDirectory.resolve(PACKAGE_LOCK_JSON));
        List<String> command = hasLockfile ? List.of("npm", "ci") : List.of("npm", "install");

        // The whole directory, node_modules included, is the artifact, so everything must come back.
        ProcessResult result = processExecutor.execute(command, artifactDirectory, INSTALL_TIMEOUT, WorkspaceSync.ALL);
        logRecorder.record(STAGE_DEPENDENCY_INSTALL, command, result);
        if (result.timedOut()) {
            throw new NodeBuildException(
                    functionVersionId, STAGE_DEPENDENCY_INSTALL, command, null, result.stdout(), result.stderr(), true);
        }
        if (!result.succeeded()) {
            throw new NodeBuildException(
                    functionVersionId, STAGE_DEPENDENCY_INSTALL, command, result.exitCode(), result.stdout(), result.stderr(), false);
        }
    }

    private void verifyEntrypointStillExists(Path artifactDirectory, String entrypoint) {
        Path entrypointPath = artifactDirectory.resolve(entrypoint);
        if (!Files.isRegularFile(entrypointPath)) {
            throw new IllegalStateException(
                    "Configured entrypoint no longer exists after dependency preparation: " + entrypoint);
        }
    }

    private Path copyToNewArtifactDirectory(BuildWorkspace workspace) {
        try {
            Path artifactDirectory = Files.createTempDirectory(
                    ARTIFACT_DIRECTORY_PREFIX + workspace.functionVersionId() + "-");
            try (var paths = Files.walk(workspace.root())) {
                for (Path source : (Iterable<Path>) paths::iterator) {
                    Path target = artifactDirectory.resolve(workspace.root().relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(source, target);
                    }
                }
            }
            return artifactDirectory;
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Failed to prepare Node artifact for function version: " + workspace.functionVersionId(), exception);
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            for (Path candidate : paths.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ignored) {
                    // Cleanup of a failed Node build's artifact directory is best-effort.
                }
            }
        } catch (IOException ignored) {
            // Cleanup of a failed Node build's artifact directory is best-effort.
        }
    }
}
