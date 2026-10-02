# Write Node HTTP logic

Fetch `get_function_example("NODE_BASIC")` for source shaped for `submit_function_version_source`. A dependency-free module can use `entrypoint="index.mjs"`, `handler="handler"` and export `async function handler(input, context)`. A Function identity and Function Version are separate IDs.

## Real HTTP input

The first Flow step receives this envelope from the Gateway:

```json
{"method":"POST","hostname":"app.example.com","path":"/api/notes/42","rawUri":"/api/notes/42?limit=10","body":"{\"text\":\"hello\"}","pathParameters":{"id":"42"},"headers":{"Content-Type":["application/json"]},"cookies":{"session":"opaque-token"}}
```

`input.body` is a string. Parse JSON explicitly, handle invalid input, and validate app fields. Query text is in `rawUri`; there is no promised queryStringParameters field. Header names retain the received casing and values are arrays; find names case-insensitively. Hop-by-hop request headers are stripped. Cookies are parsed into a name/value map.

Later steps receive the preceding step's output. Pass through request fields needed later rather than assuming each step gets the original envelope.

Direct `invoke_function_version` and `invoke_flow_version` calls receive exactly the raw JSON payload you supply. They do not synthesize an HTTP request. To test an HTTP handler directly, supply a serialized envelope with the fields it reads. Then test real HTTP after adoption.

## HTTP response

A terminal RESPONSE handler returns `{status: 200, body: <JSON value>, headers: <optional object>}`. Use `status`, not Lambda's `statusCode`. Body is always JSON-serialized, including strings. Default Content-Type is application/json. Optional response headers work; values can be strings or arrays. Use an array for multiple Set-Cookie values. Content-Length and Transfer-Encoding are controlled by the Gateway.

Setting Content-Type does not disable JSON serialization. Serve real HTML/CSS/JS with STATIC instead of returning an HTML string from NODE. For cookie/header code fetch `get_function_example("NODE_REQUEST_HEADERS")`. Set appropriate HttpOnly, Secure and SameSite attributes for app sessions; FuncHole's control-plane API key is not an end-user session.

## Configuration and data

Read config/secrets from `process.env.KEY_NAME`, not context.env. `context.db(name)` returns a pg.Pool for an attached external Postgres resource. See funchole://guides/data before authoring persistence or migrations.

NODE Flows use matching `runtime="NODE"` and end with RESPONSE, or an adopted SUB_FLOW that ends with RESPONSE. Reference only READY Function Versions. See funchole://guides/flows for composition and publication, and funchole://guides/troubleshooting for failures.
