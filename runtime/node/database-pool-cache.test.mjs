import { test } from "node:test";
import assert from "node:assert/strict";
import { DatabasePoolCache } from "./database-pool-cache.mjs";

function fakeDatabase(overrides = {}) {
  return {
    type: "POSTGRES",
    host: "tenant-db",
    port: 5432,
    databaseName: "fh_example",
    username: "app",
    password: "secret",
    sslEnabled: true,
    ...overrides,
  };
}

function fakePoolFactory() {
  let created = 0;
  const ended = [];
  const createPool = (database) => {
    created += 1;
    const pool = { id: created, database, ended: false };
    pool.end = async () => {
      pool.ended = true;
      ended.push(pool.id);
    };
    return pool;
  };
  return { createPool, ended, createdCount: () => created };
}

test("returns the same pool for repeated calls with identical config", () => {
  const { createPool, createdCount } = fakePoolFactory();
  const cache = new DatabasePoolCache(createPool);
  const database = fakeDatabase();

  const first = cache.getOrCreate(database);
  const second = cache.getOrCreate({ ...database });

  assert.equal(second, first);
  assert.equal(createdCount(), 1);
});

test("rebuilds the pool when sslEnabled changes for the same connection identity", () => {
  const { createPool, ended, createdCount } = fakePoolFactory();
  const cache = new DatabasePoolCache(createPool);
  const database = fakeDatabase({ sslEnabled: true });

  const withSsl = cache.getOrCreate(database);
  const withoutSsl = cache.getOrCreate({ ...database, sslEnabled: false });

  assert.notEqual(withoutSsl, withSsl, "a stale SSL-enabled pool must not be reused after sslEnabled is turned off");
  assert.equal(createdCount(), 2);
  assert.deepEqual(ended, [withSsl.id], "the stale pool must be closed, not leaked");
});

test("rebuilds the pool when the password changes for the same connection identity", () => {
  const { createPool, ended, createdCount } = fakePoolFactory();
  const cache = new DatabasePoolCache(createPool);
  const database = fakeDatabase({ password: "old-secret" });

  const withOldPassword = cache.getOrCreate(database);
  const withNewPassword = cache.getOrCreate({ ...database, password: "rotated-secret" });

  assert.notEqual(withNewPassword, withOldPassword, "a stale pool built with the old password must not be reused after rotation");
  assert.equal(createdCount(), 2);
  assert.deepEqual(ended, [withOldPassword.id]);
});

test("keeps separate pools for different connection identities", () => {
  const { createPool, ended, createdCount } = fakePoolFactory();
  const cache = new DatabasePoolCache(createPool);

  const primary = cache.getOrCreate(fakeDatabase({ databaseName: "fh_primary" }));
  const secondary = cache.getOrCreate(fakeDatabase({ databaseName: "fh_secondary" }));

  assert.notEqual(secondary, primary);
  assert.equal(createdCount(), 2);
  assert.deepEqual(ended, [], "distinct identities must not evict each other");

  // Both stay independently cached and reusable afterward.
  assert.equal(cache.getOrCreate(fakeDatabase({ databaseName: "fh_primary" })), primary);
  assert.equal(cache.getOrCreate(fakeDatabase({ databaseName: "fh_secondary" })), secondary);
  assert.equal(createdCount(), 2);
});
