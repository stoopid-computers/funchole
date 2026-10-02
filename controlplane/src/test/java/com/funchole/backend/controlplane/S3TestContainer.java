package com.funchole.backend.controlplane;

import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/** Real S3 storage using the same server as the deployment, pinned for repeatable tests. */
final class S3TestContainer extends GenericContainer<S3TestContainer> {

    private static final String ACCESS_KEY = "funchole-test";
    private static final String SECRET_KEY = "funchole-test-secret";

    S3TestContainer() {
        super(DockerImageName.parse("rustfs/rustfs@sha256:8cc9801755448b71a786705ce76692c77e14936cccd87cf2fc31842e58f4d1ff"));
        withExposedPorts(9000);
        withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY);
        withEnv("RUSTFS_SECRET_KEY", SECRET_KEY);
        withEnv("RUSTFS_CONSOLE_ENABLE", "false");
        withCommand("/data");
        waitingFor(Wait.forHttp("/health").forPort(9000).withStartupTimeout(Duration.ofMinutes(2)));
    }

    String getS3URL() {
        return "http://" + getHost() + ":" + getMappedPort(9000);
    }

    String getUserName() {
        return ACCESS_KEY;
    }

    String getPassword() {
        return SECRET_KEY;
    }
}
