# Build multiplayer within available capabilities

FuncHole currently exposes request/response HTTP, STATIC sites, NODE Functions and external Postgres attachments. It does not expose native application WebSocket sessions, room broadcasts, or persistent actor instances. Function process memory is not durable shared game state.

## HTTP polling path

For turn-based games, shared boards, lobbies, or collaboration where polling latency is acceptable:

1. Ship the browser UI as STATIC. Read funchole://guides/static.
2. Use a supplied external Postgres database for rooms, memberships, sessions, and authoritative state. Read funchole://guides/data.
3. Write NODE APIs for joining, reading updates, and applying actions. Authenticate the player and check room membership on every request. Read funchole://guides/node.
4. Apply actions in a transaction using row locks or version-based optimistic concurrency. Give retryable client actions idempotency keys so a network retry does not apply an action twice.
5. Poll an HTTP state endpoint from the UI with a revision/sequence. Bound polling frequency and response size. Do not keep a worker busy waiting for future updates.
6. Test with two independent sessions, concurrent actions, disconnect/rejoin, expired sessions, and unauthorized room access. Publish only after state and access checks work. A draft `invoke` receives a raw JSON string, not an automatic HTTP envelope; real cookie sessions still need actual HTTP checks.

For low-latency realtime needs, explain the gap and ask whether an approved external realtime service is acceptable. Integrate that service from NODE using protected credentials and have the STATIC client use only scoped client tokens. Provisioning the external service needs user authorization and is not supplied by FuncHole's MCP. Do not silently replace a requested realtime game with polling.

Current runtime/build sandboxing and Gateway single-instance limitations still apply. Use funchole://guides/troubleshooting for observed failures and funchole://guides/evolve to capture a tested app-specific integration procedure.
