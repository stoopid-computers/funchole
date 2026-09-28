# Note (2026-09-29): Headers/cookies are not passed to Functions

## Finding

HTTP headers (including `Cookie`) are never forwarded into a Function
invocation, and a Function has no way to set response headers (including
`Set-Cookie`) back to the client. This is a missing feature, not a subtle
bug - the `headers` concept doesn't exist anywhere in the pipeline.

## Where it breaks

**Request side** - `GatewayHttpHandler.buildInvocationInput()`
(`gateway/src/main/java/com/funchole/backend/gateway/server/GatewayHttpHandler.java:515`)
builds the invocation JSON with only `method`, `hostname`, `path`, `rawUri`,
`body`, `pathParameters`. Netty's incoming `request.headers()` is read once,
only to extract `Host` - the rest, including `Cookie`, is never forwarded.
Nothing downstream (invocation-contract, Dispatcher IPC, Runtime IPC, or the
Node executor's `handler(input, context)` signature) carries a headers
concept at all. Confirmed in `runtime/node/executor.mjs`: the context built
for a Function only exposes `context.db(name)`, nothing HTTP-shaped.

**Response side** - `GatewayHttpHandler.writeFinalResponse()`
(same file, line 459) only reads `status` and `body` out of a Function's
return value. `Content-Type` is hardcoded to `application/json`. Even if a
Function returned `{ status, body, headers: { "Set-Cookie": [...] } }`
today, it would be silently ignored.

## Reference implementation already in the codebase

`FixedHostProxyForwarder.java` (used only for the unrelated admin-webapp
reverse-proxy path, not Function invocation) already copies headers
correctly, stripping hop-by-hop headers - see `copyHeaders()`, tested by
`GatewayHttpHandlerFixedHostProxyTest.java`. That pattern was never wired
into the Flow/Function invocation path.

## Fix sketch (not yet implemented)

No Dispatcher/Runtime IPC DTO changes needed - `input`/`output` already
travel as opaque JSON strings end-to-end. Only these need to change:

1. `GatewayHttpHandler.buildInvocationInput()` - add a `headers` field as
   `Map<String, List<String>>` (not `Map<String,String>` - that would
   collapse multiple `Set-Cookie` values into one comma-joined header).
2. `GatewayHttpHandler.writeFinalResponse()` - read `headers` back out of
   the Function's response and apply each value individually via
   `response.headers().add(name, value)`.
3. `runtime/node/executor.mjs` - expose `request.headers` (and optionally a
   parsed `request.cookies` convenience) to Function authors.
4. Tests mirroring `GatewayHttpHandlerFixedHostProxyTest`, but against the
   actual Flow-invocation path, including a multi-`Set-Cookie` case to guard
   against the comma-join collapse bug.

No existing tests cover header/cookie passthrough on the invocation path
today (`GatewayHttpHandlerInvocationTest.java` only asserts on
flow id/status/body).
