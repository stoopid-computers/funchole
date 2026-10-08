package com.funchole.backend.runtime;

import java.io.IOException;

/**
 * Starts and force-stops one sandbox. The returned process speaks the Node executor protocol
 * on stdin/stdout; everything about how it is isolated lives behind this interface.
 */
interface SandboxLauncher {

    Process launch(String sandboxName) throws IOException;

    /** Must make sure nothing keeps running under {@code sandboxName}, even if the process handle is gone. */
    void destroy(String sandboxName);
}
