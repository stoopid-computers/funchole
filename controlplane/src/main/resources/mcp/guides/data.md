# Use databases and configuration

`create_database` registers an existing external Postgres connection. It does not create a database server or schema. Prefer a suitable resource from `list_databases`, including a default Database already provisioned for a hosted account. Request missing connection details through the host's secure workflow. Passwords are stored in OpenBao, excluded from normal list/get results, and only returned by the explicit `reveal_database_password` tool. Reveal them only when the user's task actually requires it.

Attach with `attach_function_version_database` for one version or `attach_flow_database` for all steps of a Flow. The resource's name is the `context.db(name)` lookup key. Fetch `get_function_example("NODE_DATABASE")`. It demonstrates `const pool = context.db('primary'); await pool.query(sql, params)` using a real reusable pg.Pool. Parameterize SQL; browser code must use your authenticated NODE API rather than receive database credentials.

## Schema and migrations

There is no dedicated SQL/migration tool. Write a one-off NODE Function that uses the attached database, deploy it, and invoke it once. Prefer idempotent migrations with an explicit migration version, a transaction, and a lock where concurrent execution is possible. Inspect the Invocation and query the intended result before declaring success. The fixture's CREATE TABLE IF NOT EXISTS is an illustration, not a production migration system. Get explicit approval for destructive migrations; preserve existing data on updates.

## Shared configuration

Create an EnvironmentProfile for reusable values, set values with `set_environment_env_var` or `set_environment_secret`, and attach it to Flows with `attach_flow_environment`. Within plain values and within secrets, higher attachment priority overrides lower priority. All profile secrets then override profile plain values. Function Version plain values override that shared result, and Function Version secrets override its plain values. Fetch `get_function_example("NODE_ENV_VARS")` for `process.env` usage.

Function Versions can also have their own env vars/secrets. New DRAFT versions clone source/config/database attachments by default. Inspect before replacing values. Keep secret values out of submitted source, static bundles, runtime logging, and project learning notes. Process-level execution is not sandboxed; see funchole://guides/start for the safety boundary.

For shared multiplayer state and concurrency read funchole://guides/multiplayer. For source/config preservation when extending an app read funchole://guides/evolve.
