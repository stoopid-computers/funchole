# Use databases and configuration

`connect_database` registers an existing external Postgres connection. It does not create a database server or schema. Prefer a suitable resource from `discover(scope="databases")`, including a default Database already provisioned for a hosted account. Request missing connection details through the host's secure workflow. Its request contains `name`, `host`, `port`, `databaseName`, `username`, `password`, and `sslEnabled`. Set `sslEnabled=false` only for an approved connection without TLS, such as the isolated test VM; use database TLS for production. Passwords are stored in OpenBao and excluded from normal discovery/state results. The public MCP catalog has no password-reveal operation.

Pass database references in `build_function.request.databases` for version attachments. For shared Flow attachments read `funchole://tools/configure` before making a change. The resource's name is the `context.db(name)` lookup key. Read `funchole://examples/NODE_DATABASE`. It demonstrates `const pool = context.db('primary'); await pool.query(sql, params)` using a real reusable pg.Pool. Parameterize SQL; browser code must use your authenticated NODE API rather than receive database credentials.

## Schema and migrations

There is no dedicated SQL/migration tool. Write a one-off NODE Function that uses the attached database, deploy it, and invoke it once. Prefer idempotent migrations with an explicit migration version, a transaction, and a lock where concurrent execution is possible. Inspect the Invocation and query the intended result before declaring success. The fixture's CREATE TABLE IF NOT EXISTS is an illustration, not a production migration system. Get explicit approval for destructive migrations; preserve existing data on updates.

## Shared configuration

Use `configure` for reusable EnvironmentProfile values, secrets, and Flow attachments. Read its exact contract first and inspect existing state; do not guess replacement or attachment fields. Within plain values and within secrets, higher attachment priority overrides lower priority. All profile secrets then override profile plain values. Function Version plain values override that shared result, and Function Version secrets override its plain values. Read `funchole://examples/NODE_ENV_VARS` for `process.env` usage.

Function Versions can also have their own env vars/secrets. Updates require an explicit `baseVersionRef`; omitted env/secrets inherit from that base. Inspect source/config/database attachments before replacing values. Keep secret values out of submitted source, static bundles, runtime logging, and project learning notes. Process-level execution is not sandboxed; see funchole://guides/start for the safety boundary.

For shared multiplayer state and concurrency read funchole://guides/multiplayer. For source/config preservation when extending an app read funchole://guides/evolve.
