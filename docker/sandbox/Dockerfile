# The only thing tenant code ever runs inside (docs/SANDBOX_ISOLATION_PRD.md, E2).
# Node + the unchanged executor + its one dependency (pg). No platform code, no credentials,
# no build tools, no package manager at runtime, non-root.
#
#   docker build -f docker/sandbox/Dockerfile -t funchole-sandbox:dev .
# Debian, like the build image and today's runtime, so native modules built for glibc keep working.
FROM node:22-bookworm-slim
RUN groupadd -g 10001 sandbox && useradd -u 10001 -g sandbox -M -s /usr/sbin/nologin sandbox
WORKDIR /app
COPY runtime/node/package.json runtime/node/package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && rm -rf /root/.npm && chmod -R a-w /app
COPY runtime/node/executor.mjs runtime/node/database-pool-cache.mjs ./
RUN chmod -R a-w /app
USER 10001:10001
ENTRYPOINT ["node", "/app/executor.mjs"]
