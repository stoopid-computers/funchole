package com.funchole.backend.controlplane.functionbuild.process;

/**
 * What a build step needs back in the local working directory once it has run. Only matters when the
 * command runs elsewhere (the sandbox): locally the directory is always up to date, so the default
 * executor ignores it.
 */
public enum WorkspaceSync {
    /** Nothing yet: the next step continues on the same workspace, so it is not shipped back and forth. */
    NONE,
    /** Everything the command changed. */
    ALL,
    /** Everything except top-level {@code node_modules}: a finished build only needs its output. */
    ALL_BUT_DEPENDENCIES
}
