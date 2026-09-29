// Caches one pg.Pool per distinct database connection, reused across every
// execution that attaches the same Database resource so context.db(name)
// returns a warm client without a per-invocation connection cost.
//
// A pool is rebuilt - and the stale one closed - whenever the resolved
// connection config for that database actually changes. The connection's
// identity (host/port/database/username) alone is not enough to key on:
// sslEnabled or password can change independently (an SSL toggle, a
// credential rotation) while identity stays the same, and a cache keyed
// only on identity would keep serving connections built under the old
// config indefinitely, since this process is started once and stays alive
// for the life of the Runtime Worker container - no function deploy or
// re-attach restarts it.
export class DatabasePoolCache {
  constructor(createPool) {
    this.createPool = createPool;
    this.entries = new Map(); // identityKey -> { configKey, pool }
  }

  static identityKey(database) {
    return `${database.type}:${database.host}:${database.port}:${database.databaseName}:${database.username}`;
  }

  static configKey(database) {
    return `${DatabasePoolCache.identityKey(database)}:${database.sslEnabled}:${database.password}`;
  }

  getOrCreate(database) {
    const identityKey = DatabasePoolCache.identityKey(database);
    const configKey = DatabasePoolCache.configKey(database);
    const existing = this.entries.get(identityKey);
    if (existing && existing.configKey === configKey) {
      return existing.pool;
    }
    if (existing) {
      // Config changed under the same identity - the old pool's connections
      // were built with settings that no longer match, so it must not be
      // reused. Close it in the background rather than leaking it.
      existing.pool.end().catch(() => {});
    }
    const pool = this.createPool(database);
    this.entries.set(identityKey, { configKey, pool });
    return pool;
  }
}
