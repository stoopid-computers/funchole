package com.funchole.backend.controlplane.functionbuild.runtime.staticsite;

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
 * {@link RuntimeBuilder} for the {@code STATIC} runtime - a frontend web app
 * (Vite/CRA/any static-export bundler) deployed through the exact same
 * Function/FunctionVersion lifecycle as a Node function, just built
 * differently. Convention over configuration, deliberately: every JS
 * frontend bundler already defines an npm {@code build} script by
 * convention, so this always runs {@code npm ci}/{@code npm install} then
 * {@code npm run build} - no per-version build-command field needed. The
 * output directory is auto-detected ({@value #DIST}, {@value #BUILD}, or
 * {@value #OUT}, in that order) rather than configured, covering Vite, CRA,
 * and Next's static export without any new submission-time field.
 *
 * <p>Unlike {@code NodeRuntimeBuilder}, the published artifact is NOT the
 * whole workspace (source + node_modules) - it is only the build output's
 * contents, promoted to the artifact root. A future static-file-serving
 * Gateway path can then always assume "the artifact root is the site root,"
 * and the published artifact stays small.
 *
 * <p>{@code BuildWorkspaceService} requires a version's declared
 * {@code entrypoint} to already exist in the raw submitted source (it is
 * runtime-neutral and cannot special-case STATIC) - submit
 * {@code package.json} as the entrypoint for a STATIC version to satisfy
 * that precondition; this builder never reads {@link BuildWorkspace#entrypoint()}
 * for anything beyond what already happened before {@link #build} is even
 * called.
 */
@Component
public class StaticRuntimeBuilder implements RuntimeBuilder {

    static final String STAGE_DEPENDENCY_INSTALL = "dependency-install";
    static final String STAGE_BUILD = "build";

    private static final String RUNTIME_TYPE = "STATIC";
    private static final String BUILD_DIRECTORY_PREFIX = "funchole-static-build-";
    private static final String ARTIFACT_DIRECTORY_PREFIX = "funchole-prepared-static-";
    private static final String PACKAGE_JSON = "package.json";
    private static final String PACKAGE_LOCK_JSON = "package-lock.json";
    private static final String INDEX_HTML = "index.html";
    private static final List<String> CANDIDATE_OUTPUT_DIRECTORIES = List.of("dist", "build", "out");
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);

    private final ProcessExecutor processExecutor;

    public StaticRuntimeBuilder(ProcessExecutor processExecutor) {
        this.processExecutor = processExecutor;
    }

    @Override
    public boolean supports(String runtimeType) {
        return RUNTIME_TYPE.equalsIgnoreCase(runtimeType);
    }

    @Override
    public PreparedArtifact build(BuildWorkspace workspace, BuildLogRecorder logRecorder) {
        Path buildDirectory = copyToNewDirectory(workspace.root(), BUILD_DIRECTORY_PREFIX + workspace.functionVersionId() + "-");
        Path artifactDirectory = null;
        try {
            installDependencies(workspace.functionVersionId(), buildDirectory, logRecorder);
            runBuildScript(workspace.functionVersionId(), buildDirectory, logRecorder);
            Path outputDirectory = locateOutputDirectory(workspace.functionVersionId(), buildDirectory);

            artifactDirectory = copyToNewDirectory(outputDirectory, ARTIFACT_DIRECTORY_PREFIX + workspace.functionVersionId() + "-");
            verifyIndexHtmlExists(workspace.functionVersionId(), artifactDirectory);
            // Written into the artifact itself - the only copy of this the
            // Gateway will ever see, same contract as NodeRuntimeBuilder's
            // manifest. handler is empty: a static site has no function to
            // call, the Gateway serves files directly for this runtime type.
            new ArtifactManifest(INDEX_HTML, "").writeInto(artifactDirectory);

            return new PreparedArtifact(
                    workspace.functionVersionId(),
                    artifactDirectory,
                    INDEX_HTML,
                    "",
                    workspace.runtimeType(),
                    workspace.runtimeVersion()
            );
        } catch (RuntimeException exception) {
            deleteRecursively(artifactDirectory);
            throw exception;
        } finally {
            deleteRecursively(buildDirectory);
        }
    }

    private void installDependencies(UUID functionVersionId, Path buildDirectory, BuildLogRecorder logRecorder) {
        Path packageJson = buildDirectory.resolve(PACKAGE_JSON);
        if (!Files.isRegularFile(packageJson)) {
            throw new IllegalStateException(
                    "A STATIC FunctionVersion's source must include a package.json with a 'build' script: "
                            + functionVersionId);
        }
        boolean hasLockfile = Files.isRegularFile(buildDirectory.resolve(PACKAGE_LOCK_JSON));
        List<String> command = hasLockfile ? List.of("npm", "ci") : List.of("npm", "install");
        runCommand(functionVersionId, STAGE_DEPENDENCY_INSTALL, command, buildDirectory, INSTALL_TIMEOUT, WorkspaceSync.NONE, logRecorder);
    }

    private void runBuildScript(UUID functionVersionId, Path buildDirectory, BuildLogRecorder logRecorder) {
        List<String> command = List.of("npm", "run", "build");
        runCommand(functionVersionId, STAGE_BUILD, command, buildDirectory, BUILD_TIMEOUT, WorkspaceSync.ALL_BUT_DEPENDENCIES, logRecorder);
    }

    private void runCommand(
            UUID functionVersionId, String stage, List<String> command, Path workingDirectory, Duration timeout,
            WorkspaceSync sync, BuildLogRecorder logRecorder
    ) {
        ProcessResult result = processExecutor.execute(command, workingDirectory, timeout, sync);
        logRecorder.record(stage, command, result);
        if (result.timedOut()) {
            throw new StaticBuildException(functionVersionId, stage, command, null, result.stdout(), result.stderr(), true);
        }
        if (!result.succeeded()) {
            throw new StaticBuildException(
                    functionVersionId, stage, command, result.exitCode(), result.stdout(), result.stderr(), false);
        }
    }

    private Path locateOutputDirectory(UUID functionVersionId, Path buildDirectory) {
        for (String candidate : CANDIDATE_OUTPUT_DIRECTORIES) {
            Path candidatePath = buildDirectory.resolve(candidate);
            if (Files.isRegularFile(candidatePath.resolve(INDEX_HTML))) {
                return candidatePath;
            }
        }
        throw new IllegalStateException(
                "Build script completed but no output directory with an index.html was found (looked for "
                        + String.join(", ", CANDIDATE_OUTPUT_DIRECTORIES) + ") for function version: " + functionVersionId);
    }

    private void verifyIndexHtmlExists(UUID functionVersionId, Path artifactDirectory) {
        if (!Files.isRegularFile(artifactDirectory.resolve(INDEX_HTML))) {
            throw new IllegalStateException(
                    "Prepared static artifact is missing index.html for function version: " + functionVersionId);
        }
    }

    private Path copyToNewDirectory(Path source, String targetPrefix) {
        try {
            Path target = Files.createTempDirectory(targetPrefix);
            try (var paths = Files.walk(source)) {
                for (Path candidate : (Iterable<Path>) paths::iterator) {
                    Path destination = target.resolve(source.relativize(candidate));
                    if (Files.isDirectory(candidate)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(candidate, destination);
                    }
                }
            }
            return target;
        } catch (IOException exception) {
            throw new UncheckedIOException("Failed to copy " + source + " into a fresh directory", exception);
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
                    // Cleanup of a build/artifact directory is best-effort.
                }
            }
        } catch (IOException ignored) {
            // Cleanup of a build/artifact directory is best-effort.
        }
    }
}
