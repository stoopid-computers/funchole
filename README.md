<p align="center">
  <img alt="FuncHole logo" src="./docs/funchole-logo.svg" width="250">
</p>

FuncHole is a self-hosted platform where coding agents can build and operate
real app capabilities through MCP. It gives agents a place to create functions,
compose flows, publish artifacts, expose routes, run test invocations, and
inspect what they shipped.

FuncHole also gives humans the control plane around that workflow: domains,
gateways, secrets, environments, databases, logs, and manual operations. The
goal is simple: let the agent do the build work, while you keep the production
controls.

<p align="center">
  <video src="./funchole-explainer-demo.mp4" controls muted playsinline width="760"></video>
</p>

[Watch the demo](./funchole-explainer-demo.mp4)

[Join the Discord](https://discord.gg/yS8etyU7p)

## Getting Started

Run the local development stack:

```bash
docker compose -f docker-compose.dev.yml up --build
```

Then open the control-plane web UI at [http://localhost:3000](http://localhost:3000).
The controlplane API runs at [http://localhost:7080](http://localhost:7080), and
the local Gateway listens at [https://localhost](https://localhost).

For the full local workflow, see [docs/development.md](docs/development.md).

Connect a coding agent to `/api/mcp` with an MCP API key, then let it read the
start guide before building. See [agent connection and compatibility checks](docs/mcp.md).

## Self Hosting

The development stack includes the controlplane, web UI, raw Netty Gateway,
Dispatcher, Runtime Registry, Node.js Runtime worker, PostgreSQL, OpenBao,
NATS + JetStream, and RustFS.

Self-hosting is the main direction of the project. The current Docker Compose
setup is intended for development and product validation; production hardening
is still evolving. Configuration details are tracked in
[docs/environment-variables.md](docs/environment-variables.md), and known
limits are tracked in [docs/limitations.md](docs/limitations.md).

## Community & Support

- Join the [FuncHole Discord](https://discord.gg/yS8etyU7p) for feedback,
  product discussion, and help.
- Open issues for bugs, confusing workflows, and small improvement ideas.
- Read [CONTRIBUTING.md](CONTRIBUTING.md) before making architecture,
  persistence, or runtime changes.

## Building from Source

FuncHole uses Java 25 and Gradle:

```bash
./gradlew build
```

Docker Compose is recommended for daily development because it starts the
supporting services alongside the Java modules.

## Disclaimers

- FuncHole is under active development.
- Development certificates are self-signed, so browser warnings are expected.
- Runtime Registry state is currently in-memory and not a distributed scheduler.
- Custom local domains still require host-machine DNS setup.
- FuncHole is licensed under `FSL-1.1-Apache-2.0`; see [LICENSE.md](LICENSE.md).

## Repository Layout

- `controlplane/` contains the Spring Boot API, MCP server, and build pipeline.
- `control-plane-web/` contains the Next.js control-plane UI.
- `gateway/` contains the raw Netty HTTPS ingress service.
- `invocation/` contains invocation persistence and immutable snapshots.
- `dispatcher/` consumes JetStream events and plans Flow step execution.
- `runtime-registry/` selects and reserves runtime capacity.
- `runtime/` contains the Node.js runtime worker.
- `artifact/` contains artifact publishing, fetching, and caching contracts.
- `certificate/` contains certificate contracts and generation support.
- `docs/` contains architecture, development, and operations notes.
