FROM eclipse-temurin:25-jdk AS build-base
WORKDIR /workspace

COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties /workspace/
COPY gradle /workspace/gradle
COPY certificate/build.gradle /workspace/certificate/build.gradle
COPY core/build.gradle /workspace/core/build.gradle
COPY controlplane/build.gradle /workspace/controlplane/build.gradle
COPY gateway/build.gradle /workspace/gateway/build.gradle
COPY invocation/build.gradle /workspace/invocation/build.gradle
COPY invocation-contract/build.gradle /workspace/invocation-contract/build.gradle
COPY artifact/build.gradle /workspace/artifact/build.gradle
COPY runtime-registry/build.gradle /workspace/runtime-registry/build.gradle
COPY dispatcher/build.gradle /workspace/dispatcher/build.gradle
COPY runtime/build.gradle /workspace/runtime/build.gradle
COPY sandbox-protocol/build.gradle /workspace/sandbox-protocol/build.gradle
COPY sandbox-manager/build.gradle /workspace/sandbox-manager/build.gradle
RUN chmod +x gradlew
RUN --mount=type=cache,target=/root/.gradle,id=gradle,sharing=locked \
    ./gradlew :controlplane:dependencies :gateway:dependencies :dispatcher:dependencies :runtime:dependencies :sandbox-manager:dependencies --no-daemon >/dev/null 2>&1 || true

COPY certificate/src /workspace/certificate/src
COPY core/src /workspace/core/src
COPY controlplane/src /workspace/controlplane/src
COPY gateway/src /workspace/gateway/src
COPY invocation/src /workspace/invocation/src
COPY invocation-contract/src /workspace/invocation-contract/src
COPY artifact/src /workspace/artifact/src
COPY runtime-registry/src /workspace/runtime-registry/src
COPY dispatcher/src /workspace/dispatcher/src
COPY runtime/src /workspace/runtime/src
COPY sandbox-protocol/src /workspace/sandbox-protocol/src
COPY sandbox-manager/src /workspace/sandbox-manager/src
COPY docker /workspace/docker

FROM build-base AS build-controlplane
# id=gradle,sharing=locked: this stage builds concurrently with the other
# build-* stages below (docker compose builds all services in parallel).
# sharing=locked makes BuildKit serialize their access to the *same* cache
# instead of letting them all touch it at once - the earlier fix of giving
# each stage its own id avoided the lock contention but also gave each one
# an empty cache, so all four had to redownload the whole Gradle
# distribution concurrently and one connection died under the load. A
# shared, lock-serialized cache keeps the warm distribution/dependency
# downloads without the concurrent-writer race on Gradle's journal-1.lock.
RUN --mount=type=cache,target=/root/.gradle,id=gradle,sharing=locked \
    ./gradlew :controlplane:bootJar --no-daemon

FROM build-base AS build-gateway
RUN --mount=type=cache,target=/root/.gradle,id=gradle,sharing=locked \
    ./gradlew :gateway:fatJar --no-daemon

FROM build-base AS build-dispatcher
RUN --mount=type=cache,target=/root/.gradle,id=gradle,sharing=locked \
    ./gradlew :dispatcher:fatJar --no-daemon

FROM build-base AS build-runtime
RUN --mount=type=cache,target=/root/.gradle,id=gradle,sharing=locked \
    ./gradlew :runtime:fatJar --no-daemon

FROM build-base AS build-sandbox-manager
RUN --mount=type=cache,target=/root/.gradle,id=gradle,sharing=locked \
    ./gradlew :sandbox-manager:fatJar --no-daemon

FROM eclipse-temurin:25-jre AS runtime-base
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl jq \
    && rm -rf /var/lib/apt/lists/*
COPY docker/openbao-common.sh /opt/funchole/openbao-common.sh
RUN chmod +x /opt/funchole/openbao-common.sh

FROM runtime-base AS runtime-node-base
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

FROM runtime-node-base AS controlplane
COPY --from=build-controlplane /workspace/controlplane/build/libs/funchole-controlplane.jar app.jar
COPY docker/controlplane-entrypoint.sh /opt/funchole/entrypoint.sh
RUN chmod +x /opt/funchole/entrypoint.sh
EXPOSE 7080
ENTRYPOINT ["/opt/funchole/entrypoint.sh"]

FROM runtime-base AS gateway
COPY --from=build-gateway /workspace/gateway/build/libs/funchole-gateway.jar app.jar
COPY docker/gateway-entrypoint.sh /opt/funchole/entrypoint.sh
RUN chmod +x /opt/funchole/entrypoint.sh
EXPOSE 443
ENTRYPOINT ["/opt/funchole/entrypoint.sh"]

FROM runtime-base AS dispatcher
COPY --from=build-dispatcher /workspace/dispatcher/build/libs/funchole-dispatcher.jar app.jar
COPY docker/dispatcher-entrypoint.sh /opt/funchole/entrypoint.sh
RUN chmod +x /opt/funchole/entrypoint.sh
ENTRYPOINT ["/opt/funchole/entrypoint.sh"]

FROM runtime-node-base AS runtime-worker
COPY --from=build-runtime /workspace/runtime/build/libs/funchole-runtime.jar app.jar
COPY runtime/node /app/node
RUN cd /app/node && npm ci --no-audit --no-fund --omit=dev
COPY runtime/artifacts /app/artifacts
ENV NODE_EXECUTOR_SCRIPT_PATH=/app/node/executor.mjs
ENV ARTIFACT_DIR=/app/artifacts/dev
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Sandbox manager (docs/SANDBOX_ISOLATION_PRD.md, E4): runs tenant BUILDS in locked-down containers for the
# controlplane. It talks to the Docker daemon, so it holds NO platform credentials, only its own shared
# token; the controlplane reaches it over HTTP and never gets Docker access itself.
FROM runtime-base AS sandbox-manager
COPY --from=build-sandbox-manager /workspace/sandbox-manager/build/libs/funchole-sandbox-manager.jar app.jar
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
COPY docker/sandbox/build-launch.sh /opt/funchole/sandbox/build-launch.sh
RUN chmod +x /opt/funchole/sandbox/build-launch.sh
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Sandbox mode (docs/SANDBOX_ISOLATION_PRD.md, E2): the runtime worker no longer executes tenant
# code itself; it starts a locked-down container per tenant through the Docker CLI and launch.sh.
# Use with docker-compose.sandbox.yml. Never run tenant code in this image in legacy mode.
FROM runtime-worker AS runtime-worker-sandbox
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
COPY docker/sandbox/launch.sh /opt/funchole/sandbox/launch.sh
RUN chmod +x /opt/funchole/sandbox/launch.sh

FROM debian:13-slim AS rustfs-init
RUN apt-get update \
    && apt-get install -y --no-install-recommends awscli ca-certificates gzip tar \
    && rm -rf /var/lib/apt/lists/*

FROM node:22-alpine AS build-web
WORKDIR /workspace/web
# NEXT_PUBLIC_* values are inlined into the client bundle at this build step,
# not read again at container-start time - a runtime `environment:` entry in
# docker-compose.yml has no effect on them, so this one has to arrive as a
# real build ARG instead. Empty by default: the login page's Google button
# simply doesn't render when this is unset (see app/login/page.tsx).
ARG NEXT_PUBLIC_GOOGLE_CLIENT_ID=""
ENV NEXT_PUBLIC_GOOGLE_CLIENT_ID=${NEXT_PUBLIC_GOOGLE_CLIENT_ID}
# Same build-time-only inlining as above - the public URL the browser calls
# the controlplane API at (e.g. https://api-controlplane.funchole.dev in
# production, behind the Gateway's fixed-host proxy). Defaults to the dev
# value so an untouched build keeps working exactly as before.
ARG NEXT_PUBLIC_CONTROLPLANE_URL="http://localhost:7080"
ENV NEXT_PUBLIC_CONTROLPLANE_URL=${NEXT_PUBLIC_CONTROLPLANE_URL}
# Same build-time-only inlining - the web app's own public URL (e.g.
# https://app.funchole.dev), used to show the shorter <ADMIN_WEB_PROXY_HOST>/mcp
# connect command instead of the controlplane API domain's /api/mcp once the
# Gateway's own /mcp shortcut is configured (see FixedHostProxy.PathOverride).
# Empty by default: the per-agent connect commands just fall back to
# NEXT_PUBLIC_CONTROLPLANE_URL/api/mcp, which always works regardless.
ARG NEXT_PUBLIC_APP_URL=""
ENV NEXT_PUBLIC_APP_URL=${NEXT_PUBLIC_APP_URL}
# Same build-time-only inlining - the Google Analytics measurement ID for the
# hosted cloud dashboard. Empty by default, so self-hosted builds ship no
# analytics at all (see components/GoogleAnalytics.tsx).
ARG NEXT_PUBLIC_GA_MEASUREMENT_ID=""
ENV NEXT_PUBLIC_GA_MEASUREMENT_ID=${NEXT_PUBLIC_GA_MEASUREMENT_ID}
COPY control-plane-web/package.json control-plane-web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY control-plane-web/ ./
RUN npm run build

FROM node:22-alpine AS web
WORKDIR /app
ENV NODE_ENV=production HOSTNAME=0.0.0.0 PORT=3000
COPY --from=build-web /workspace/web/.next/standalone ./
COPY --from=build-web /workspace/web/.next/static ./.next/static
COPY --from=build-web /workspace/web/public ./public
EXPOSE 3000
CMD ["node", "server.js"]

FROM node:22-alpine AS dev-web
WORKDIR /workspace/web
CMD ["npm", "run", "dev"]

FROM eclipse-temurin:25-jdk AS dev-base-common
WORKDIR /workspace
ENV GRADLE_USER_HOME=/opt/gradle-home
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl jq \
    && curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
    && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties /workspace/
COPY gradle /workspace/gradle
COPY core/build.gradle /workspace/core/build.gradle
COPY controlplane/build.gradle /workspace/controlplane/build.gradle
COPY gateway/build.gradle /workspace/gateway/build.gradle
COPY invocation/build.gradle /workspace/invocation/build.gradle
COPY runtime-registry/build.gradle /workspace/runtime-registry/build.gradle
COPY dispatcher/build.gradle /workspace/dispatcher/build.gradle
COPY runtime/build.gradle /workspace/runtime/build.gradle
COPY core/src /workspace/core/src
COPY controlplane/src /workspace/controlplane/src
COPY gateway/src /workspace/gateway/src
COPY invocation/src /workspace/invocation/src
COPY runtime-registry/src /workspace/runtime-registry/src
COPY dispatcher/src /workspace/dispatcher/src
COPY runtime/src /workspace/runtime/src
COPY docker /workspace/docker
COPY docker/openbao-common.sh /opt/funchole/openbao-common.sh
COPY docker/watch-and-run.sh /opt/funchole/watch-and-run.sh
RUN chmod +x /workspace/gradlew \
    /opt/funchole/openbao-common.sh \
    /opt/funchole/watch-and-run.sh \
    && mkdir -p /opt/gradle-home \
    && ./gradlew --version \
    && ./gradlew :controlplane:dependencies :gateway:dependencies :dispatcher:dependencies :runtime:dependencies --no-daemon >/dev/null 2>&1 || true

FROM dev-base-common AS dev-controlplane
COPY docker/controlplane-dev-entrypoint.sh /opt/funchole/controlplane-dev-entrypoint.sh
RUN chmod +x /opt/funchole/controlplane-dev-entrypoint.sh

FROM dev-base-common AS dev-gateway
COPY docker/gateway-dev-entrypoint.sh /opt/funchole/gateway-dev-entrypoint.sh
RUN chmod +x /opt/funchole/gateway-dev-entrypoint.sh

FROM dev-base-common AS dev-dispatcher
COPY docker/dispatcher-dev-entrypoint.sh /opt/funchole/dispatcher-dev-entrypoint.sh
RUN chmod +x /opt/funchole/dispatcher-dev-entrypoint.sh

FROM dev-base-common AS dev-runtime
COPY docker/runtime-dev-entrypoint.sh /opt/funchole/runtime-dev-entrypoint.sh
RUN chmod +x /opt/funchole/runtime-dev-entrypoint.sh

FROM rustfs-init AS dev-rustfs-init
