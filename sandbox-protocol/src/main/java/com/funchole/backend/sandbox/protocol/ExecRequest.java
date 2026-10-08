package com.funchole.backend.sandbox.protocol;

import java.util.List;

/** One command to run inside a job's sandbox, with the workspace mounted as the working directory. */
public record ExecRequest(List<String> command, int timeoutSeconds) {
}
