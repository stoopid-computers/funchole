package com.funchole.backend.sandbox.protocol;

/** What the caller needs back from the sandbox after a command. */
public enum SyncBack {
    /** Nothing: the job stays open so the next command continues on the same workspace. */
    NONE,
    /** The whole workspace, replacing the local one. */
    ALL,
    /** Everything except top-level {@code node_modules} (a finished build only needs its output). */
    EXCLUDING_NODE_MODULES
}
