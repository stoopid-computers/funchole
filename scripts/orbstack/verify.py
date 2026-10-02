#!/usr/bin/env python3
"""Mutating deployment checks. Run only inside the disposable funchole-test machine."""

import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:7080"
COMPOSE = ["docker", "compose", "-p", "funchole-vm", "--env-file", ".vm/private.env",
           "-f", "docker-compose.yml", "-f", ".vm/compose.yml"]
spec = importlib.util.spec_from_file_location("mcp_smoke", ROOT / "scripts/dev/mcp-smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def rest(path, body, token=None):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    request = urllib.request.Request(BASE + path, json.dumps(body).encode(), headers)
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["data"]


def tool(connection, tool_name, **arguments):
    arguments = {key: value for key, value in arguments.items() if value is not None}
    result = connection.call("tools/call", {"name": tool_name, "arguments": arguments})
    smoke.require(not result.get("isError"), "Tool failed: " + tool_name)
    if "structuredContent" in result:
        return result["structuredContent"]
    text = result["content"][0]["text"]
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text


def compose(*args, **kwargs):
    return subprocess.run(COMPOSE + list(args), check=True, cwd=ROOT, **kwargs)


def publish(connection, gateway, runtime, files, entrypoint, database=None):
    key = "vm_" + secrets.token_hex(5)
    function = tool(connection, "create_function", functionKey=key, name=key, runtime=runtime)
    version = tool(connection, "create_function_version", functionId=function["id"])
    ids = {"functionId": function["id"], "versionId": version["id"]}
    if database:
        tool(connection, "attach_function_version_database", **ids, databaseId=database["id"])
    tool(connection, "submit_function_version_source", **ids, entrypoint=entrypoint,
         handler="handler" if runtime == "NODE" else None,
         files=[{"path": path, "content": content} for path, content in files.items()])
    tool(connection, "deploy_function_version", **ids)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        state = tool(connection, "get_function_version", **ids)
        if state["status"] == "READY":
            break
        smoke.require(state["status"] != "FAILED", "Build failed for " + runtime)
        time.sleep(3)
    else:
        raise RuntimeError("Build did not become READY for " + runtime)
    flow = tool(connection, "create_flow", flowKey=key, name=key, gatewayId=gateway["id"],
                httpMethod="GET" if runtime == "STATIC" else "POST",
                path="/*" if runtime == "STATIC" else "/state")
    flow_version = tool(connection, "create_flow_version", flowId=flow["id"], runtime=runtime)
    flow_ids = {"flowId": flow["id"], "versionId": flow_version["id"]}
    tool(connection, "create_flow_step", **flow_ids, stepKey="serve", position=1,
         componentType="FUNCTION" if runtime == "STATIC" else "RESPONSE",
         componentId=function["id"], componentVersionId=version["id"])
    tool(connection, "adopt_flow_version", **flow_ids)
    time.sleep(6)
    return function


NODE_SOURCE = """
export async function handler(input, context) {
  const body = typeof input.body === 'string' ? JSON.parse(input.body) : input.body;
  const pool = context.db('primary');
  await pool.query('CREATE TABLE IF NOT EXISTS game_events (id SERIAL PRIMARY KEY, player TEXT NOT NULL, move TEXT NOT NULL)');
  if (body.move) {
    await pool.query('INSERT INTO game_events(player, move) VALUES ($1, $2)', [body.player, body.move]);
  }
  const { rows } = await pool.query('SELECT id, player, move FROM game_events ORDER BY id');
  return { status: 200, headers: { 'Content-Type': 'application/json',
    'Set-Cookie': ['player=' + body.player + '; Secure; HttpOnly; SameSite=Lax', 'game=vm; Secure; SameSite=Lax'] },
    body: { events: rows, player: body.player } };
}
"""


def main():
    smoke.require(socket.gethostname() == "funchole-test", "Refusing to mutate a non-test machine")
    os.chdir(ROOT)
    private = dict(line.split("=", 1) for line in Path(".vm/private.env").read_text().splitlines())
    token = rest("/api/v1/auth/token", {"username": private["BOOTSTRAP_USERNAME"],
                                    "password": private["BOOTSTRAP_PASSWORD"]})["accessToken"]
    key = rest("/api/v1/api-keys", {"name": "vm-verification-" + secrets.token_hex(4)}, token)["rawKey"]
    for revision in smoke.VERSIONS:
        smoke.smoke(smoke.Connection(BASE + "/api/mcp", key, revision))
    connection = smoke.Connection(BASE + "/api/mcp", key, smoke.MODERN)
    tool(connection, "get_funchole_guide", topic="static")
    tool(connection, "get_funchole_guide", topic="node")
    tool(connection, "get_funchole_guide", topic="data")
    tool(connection, "get_funchole_guide", topic="multiplayer")
    domain_name = "vm-" + secrets.token_hex(4) + ".test"
    domain = tool(connection, "create_domain", domainName=domain_name)
    with Path(".vm/dns.conf").open("a") as out:
        out.write(f'txt-record=funchole-{domain["id"]}.{domain_name},"{domain["verificationCode"]}"\n')
    compose("restart", "dns", stdout=subprocess.DEVNULL)
    time.sleep(2)
    verified = tool(connection, "initiate_domain_verification", domainId=domain["id"])
    smoke.require(verified["status"] == "VERIFIED", "Real DNS TXT verification failed")
    gateway = tool(connection, "create_gateway", name="VM journey", appDomainId=domain["id"], status="ACTIVE")
    hostname = gateway["uniqueKey"] + "." + domain_name
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        gateway = tool(connection, "get_gateway", gatewayId=gateway["id"])
        if (gateway.get("certificate") or {}).get("status") == "ACTIVE":
            break
        time.sleep(3)
    else:
        raise RuntimeError("Gateway certificate did not become ACTIVE")
    # Allow the deployed Gateway's five-second registry poll to load the certificate.
    time.sleep(6)
    # Local test DNS points the generated hostname at the actual deployed Gateway.
    with Path("/etc/hosts").open("a") as out:
        out.write("\n127.0.0.1 " + hostname + "\n")
    # This disposable stack deliberately uses SELF_SIGNED. Pin its local certificate
    # once, then enforce certificate and hostname validation on every app request.
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    with socket.create_connection(("127.0.0.1", 443), timeout=10) as raw:
        bootstrap = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        bootstrap.check_hostname = False
        bootstrap.verify_mode = ssl.CERT_NONE
        with bootstrap.wrap_socket(raw, server_hostname=hostname) as tls:
            certificate = ssl.DER_cert_to_PEM_cert(tls.getpeercert(binary_form=True))
    context.load_verify_locations(cadata=certificate)
    opener_a = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context),
                                           urllib.request.HTTPCookieProcessor())
    opener_b = urllib.request.build_opener(urllib.request.HTTPSHandler(context=context),
                                           urllib.request.HTTPCookieProcessor())
    publish(connection, gateway, "STATIC", {
        "index.html": "<!doctype html><title>VM home</title><h1>FuncHole VM</h1>",
        "about.html": "<!doctype html><title>VM about</title><h1>Second page</h1>",
        "app.css": "body { color: #222; }",
        "package.json": json.dumps({"name": "vm-static", "private": True,
                                    "scripts": {"build": "mkdir -p dist && cp index.html about.html app.css dist/"}})}, "package.json")
    for path, expected in [("/", "FuncHole VM"), ("/about.html", "Second page"), ("/app.css", "#222")]:
        with opener_a.open("https://" + hostname + path, timeout=20) as response:
            smoke.require(response.status == 200 and expected in response.read().decode(), "STATIC failed: " + path)
    print("PASS deployed STATIC: index, second page and asset over hostname-validated HTTPS")
    # Create a dedicated real Postgres database, not a connection to the platform metadata database.
    database_password = secrets.token_hex(24)
    database_name = "game_" + secrets.token_hex(4)
    sql = f"CREATE USER {database_name} WITH PASSWORD '{database_password}'; CREATE DATABASE {database_name} OWNER {database_name};"
    compose("exec", "-T", "tenant-db", "psql", "-U", "tenant_admin", "-d", "postgres", "-v", "ON_ERROR_STOP=1",
            input=sql.encode(), stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    database = tool(connection, "create_database", name=database_name, host="tenant-db", port=5432,
                    databaseName=database_name, username=database_name, password=database_password, sslEnabled=False)
    source = NODE_SOURCE.replace("context.db('primary')", "context.db('" + database_name + "')")
    publish(connection, gateway, "NODE", {"index.mjs": source}, "index.mjs", database)

    def play(opener, player, move=None):
        body = {"player": player}
        if move:
            body["move"] = move
        request = urllib.request.Request("https://" + hostname + "/state", json.dumps(body).encode(),
                                         {"Content-Type": "application/json"})
        with opener.open(request, timeout=30) as response:
            smoke.require(response.status == 200, "NODE response was not 200")
            smoke.require(len(response.headers.get_all("Set-Cookie", [])) == 2, "Multiple cookies lost")
            return json.load(response)

    a = play(opener_a, "alice", "left")
    b = play(opener_b, "bob")
    smoke.require(a["events"] == b["events"] and len(b["events"]) == 1, "Bob did not observe Alice's move")
    play(opener_b, "bob", "right")
    a = play(opener_a, "alice")
    smoke.require([event["player"] for event in a["events"]] == ["alice", "bob"], "Alice did not observe Bob's move")
    compose("restart", "runtime", stdout=subprocess.DEVNULL)
    time.sleep(5)
    restored = play(opener_b, "bob")
    smoke.require(restored["events"] == a["events"], "State did not survive Runtime restart")
    print("PASS deployed NODE: JSON HTTP contract, two cookies, two HTTP cookie sessions and Postgres persistence after Runtime restart")
    report = {"gateway": "https://" + hostname, "protocols": smoke.VERSIONS,
              "static": ["/", "/about.html", "/app.css"], "node": "/state", "players": 2,
              "persistedEvents": len(restored["events"]), "tls": "locally pinned self-signed certificate; hostname checked"}
    Path(".vm/verification.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Tool/HTTP bodies can contain sensitive connection data. Do not print them.
        print("FAIL deployment verification:", type(error).__name__)
        if isinstance(error, RuntimeError):
            print(str(error))
        raise SystemExit(1)
