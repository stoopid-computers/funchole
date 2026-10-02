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
import sys
import time
import urllib.request


ROOT = Path(__file__).resolve().parents[2]
BASE = "http://127.0.0.1:7080"
COMPOSE = ["docker", "compose", "-p", "funchole-vm", "--env-file", ".vm/private.env",
           "-f", "docker-compose.yml", "-f", ".vm/compose.yml"]
spec = importlib.util.spec_from_file_location("mcp_smoke", ROOT / "scripts/dev/mcp-smoke.py")
smoke = importlib.util.module_from_spec(spec)
# Import the local client without leaving cache files in the checkout.
sys.dont_write_bytecode = True
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
    return smoke.operation(connection, tool_name, **arguments)


def compose(*args, **kwargs):
    return subprocess.run(COMPOSE + list(args), check=True, cwd=ROOT, **kwargs)


def publish(connection, gateway, runtime, files, entrypoint, database=None):
    key = "vm_" + secrets.token_hex(5)
    request = {"key": key, "name": key, "runtime": runtime, "entrypoint": entrypoint,
                "files": [{"path": path, "content": content} for path, content in files.items()],
                "databases": [database["reference"]] if database else [], "env": {"VM_REVISION": "initial"}}
    if runtime == "NODE":
        request["handler"] = "handler"
    version = tool(connection, "build_function", request=request)
    version_ref = version["reference"]
    smoke.require(version_ref.startswith("funchole://function-versions/"), "Build returned an invalid reference")
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        state = tool(connection, "read", reference=version_ref)["data"]
        if state["status"] == "READY":
            break
        smoke.require(state["status"] != "FAILED", "Build failed for " + runtime)
        time.sleep(3)
    else:
        raise RuntimeError("Build did not become READY for " + runtime)
    flow_version = tool(connection, "compose_flow", request={
        "key": key, "name": key, "gatewayRef": gateway["reference"], "runtime": runtime,
        "httpMethod": "GET" if runtime == "STATIC" else "POST",
        "path": "/*" if runtime == "STATIC" else "/state", "priority": 0, "componentRef": version_ref})
    flow_ref = flow_version["reference"]
    smoke.require(flow_ref.startswith("funchole://flow-versions/"), "Composition returned an invalid reference")
    if runtime == "NODE":
        # Intentional schema initialization only. No move is inserted before HTTP checks.
        payload = {"method": "POST", "path": "/state", "rawUri": "/state",
                   "headers": {"Content-Type": ["application/json"]}, "cookies": {},
                   "body": json.dumps({"player": "draft-check"})}
        invocation = tool(connection, "invoke", reference=flow_ref, input=json.dumps(payload))
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            inspected = tool(connection, "read", reference=invocation["reference"])["data"]
            smoke.require(inspected["status"] != "FAILED", "Draft NODE invocation failed")
            if inspected["status"] == "COMPLETED":
                result = json.loads(inspected["result"])
                smoke.require(result["status"] == 200 and result["body"]["events"] == [],
                              "Draft NODE invocation returned an unexpected result or inserted a move")
                break
            time.sleep(3)
        else:
            raise RuntimeError("Draft NODE invocation did not complete")
        print("PASS draft NODE invocation: schema initialized, no move inserted")
    tool(connection, "publish_flow", request={"reference": flow_ref, "expectedActiveVersionRef": None})
    time.sleep(6)
    return {"functionVersion": version_ref, "flowVersion": flow_ref}


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
    body: { events: rows, player: body.player, cookiePlayer: input.cookies?.player ?? null,
      sharedConfig: process.env.VM_SHARED ?? null, revisionConfig: process.env.VM_REVISION ?? null } };
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
    listed = connection.list_all("tools/list", "tools")
    smoke.require(len(listed) == 11 and {item["name"] for item in listed} == smoke.CORE_TOOLS,
                  "Refusing deployment checks without the exact eleven-tool production catalog")
    for topic in ["static", "node", "data", "multiplayer", "evolve"]:
        tool(connection, "read", reference="funchole://guides/" + topic)
    domain_name = "vm-" + secrets.token_hex(4) + ".test"
    domain_receipt = tool(connection, "claim_domain", request={"kind": "BASE", "hostname": domain_name})
    domain = domain_receipt["data"].get("claim", domain_receipt["data"])
    # Prefer exact returned DNS metadata; retain DTO compatibility during migration.
    requirements = domain_receipt["data"].get("dnsRequirements", [])
    txt = next((record for record in requirements if record["type"] == "TXT"), None)
    txt_name = txt["name"] if txt else f'funchole-{domain["id"]}.{domain_name}'
    txt_value = txt["value"] if txt else domain["verificationCode"]
    with Path(".vm/dns.conf").open("a") as out:
        out.write(f'txt-record={txt_name},"{txt_value}"\n')
    compose("restart", "dns", stdout=subprocess.DEVNULL)
    time.sleep(2)
    verified_data = tool(connection, "claim_domain", request={"reference": domain_receipt["reference"], "check": True})["data"]
    verified = verified_data.get("claim", verified_data)
    smoke.require(verified["status"] == "VERIFIED", "Real DNS TXT verification failed")
    gateway_receipt = tool(connection, "configure_gateway", request={
        "name": "VM journey", "domainRef": domain_receipt["reference"], "status": "ACTIVE"})
    gateway = gateway_receipt["data"]
    hostname = gateway["uniqueKey"] + "." + domain_name
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        gateway = tool(connection, "read", reference=gateway_receipt["reference"])["data"]
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
    static_versions = publish(connection, gateway_receipt, "STATIC", {
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
    database = tool(connection, "connect_database", request={
        "name": database_name, "host": "tenant-db", "port": 5432, "databaseName": database_name,
        "username": database_name, "password": database_password, "sslEnabled": False})
    source = NODE_SOURCE.replace("context.db('primary')", "context.db('" + database_name + "')")
    node_versions = publish(connection, gateway_receipt, "NODE", {"index.mjs": source}, "index.mjs", database)

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
    b = play(opener_b, "bob", "right")
    smoke.require(b["cookiePlayer"] == "bob", "Bob's HTTP cookie session was not retained")
    a = play(opener_a, "alice")
    smoke.require(a["cookiePlayer"] == "alice", "Alice's HTTP cookie session was not retained independently")
    smoke.require([event["player"] for event in a["events"]] == ["alice", "bob"], "Alice did not observe Bob's move")
    compose("restart", "runtime", stdout=subprocess.DEVNULL)
    time.sleep(5)
    restored = play(opener_b, "bob")
    smoke.require(restored["events"] == a["events"], "State did not survive Runtime restart")
    smoke.require(restored["cookiePlayer"] == "bob", "HTTP cookie session was lost after Runtime restart")
    print("PASS deployed NODE: JSON HTTP contract, two cookies, two HTTP cookie sessions and Postgres persistence after Runtime restart")

    # Shared configuration is explicitly live, not smuggled into composition.
    profiles = []
    for label in ["low", "high"]:
        profile_key = "vm_" + label + "_" + secrets.token_hex(4)
        profiles.append(tool(connection, "configure", request={"key": profile_key, "name": profile_key,
                                                              "env": {"VM_SHARED": label}}))
    node_flow_ref = "funchole://flows/" + node_versions["flowVersion"].split("/")[-2]
    tool(connection, "configure", request={"flowRef": node_flow_ref, "allowLiveChanges": True,
         "addEnvironments": [{"reference": p["reference"], "priority": n} for p, n in zip(profiles, [1, 10])]})
    time.sleep(6)
    configured = play(opener_a, "alice")
    smoke.require(configured["sharedConfig"] == "high" and configured["revisionConfig"] == "initial",
                  "Shared configuration priority or version config was lost")
    print("PASS explicit shared configuration: higher profile priority and version-local env preserved")

    def wait_version(reference, expected):
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            state = tool(connection, "read", reference=reference)["data"]
            if state["status"] == expected:
                return state
            smoke.require(state["status"] not in {"READY", "FAILED"}, "Unexpected terminal build status")
            time.sleep(3)
        raise RuntimeError("Build polling timed out")

    base_ref = static_versions["functionVersion"]
    function_ref = "funchole://functions/" + base_ref.split("/")[-2]
    manifest = tool(connection, "read", reference=base_ref, view="source")["data"]
    original_files = [{"path": path, "content": tool(connection, "read", reference=base_ref,
                      view="source", file=path)["data"]} for path in manifest["files"]]
    failed_files = [{"path": entry["path"], "content": json.dumps({"name": "vm-failure", "scripts": {"build": "exit 7"}})
                    if entry["path"] == "package.json" else entry["content"]} for entry in original_files]
    failed = tool(connection, "build_function", request={"functionRef": function_ref, "baseVersionRef": base_ref,
                  "entrypoint": "package.json", "files": failed_files, "env": {"VM_FAILED_ONLY": "bad-experiment"}})
    wait_version(failed["reference"], "FAILED")
    logs = tool(connection, "read", reference=failed["reference"], view="logs", maxChars=100)
    smoke.require(logs["data"].get("nextOffset") is not None, "Build logs did not provide bounded continuation")
    # Choose the live base explicitly, not the newer failed experiment.
    repaired = tool(connection, "build_function", request={"functionRef": function_ref, "baseVersionRef": base_ref,
                    "entrypoint": "package.json", "files": original_files})
    wait_version(repaired["reference"], "READY")
    config = tool(connection, "read", reference=repaired["reference"], view="config")["data"]["configuration"]
    smoke.require({entry["key"]: entry["value"] for entry in config["envVars"]} == {"VM_REVISION": "initial"},
                  "Fix-forward did not inherit the explicit live base configuration")
    static_flow_ref = "funchole://flows/" + static_versions["flowVersion"].split("/")[-2]
    before = tool(connection, "read", reference=static_flow_ref)["data"]
    bindings_before = tool(connection, "read", reference=static_flow_ref, view="config")["data"]
    replacement = tool(connection, "compose_flow", request={"flowRef": static_flow_ref, "componentRef": repaired["reference"]})
    smoke.require(tool(connection, "read", reference=static_flow_ref)["data"] == before and
                  tool(connection, "read", reference=static_flow_ref, view="config")["data"] == bindings_before,
                  "Preparation changed live route, version or shared bindings")
    conflict = connection.call("tools/call", {"name": "publish_flow", "arguments": {"request": {
        "reference": replacement["reference"], "expectedActiveVersionRef": None}}})
    smoke.require(conflict.get("isError") and conflict.get("structuredContent", {}).get("code") == "CONFLICT",
                  "Stale publication did not return a typed conflict")
    tool(connection, "publish_flow", request={"reference": replacement["reference"],
                                             "expectedActiveVersionRef": static_versions["flowVersion"]})
    reuse_key = "vm_reuse_" + secrets.token_hex(4)
    reused = tool(connection, "compose_flow", request={"key": reuse_key, "name": reuse_key,
        "gatewayRef": gateway_receipt["reference"], "httpMethod": "GET", "path": "/reuse/*",
        "componentRef": repaired["reference"]})
    tool(connection, "publish_flow", request={"reference": reused["reference"], "expectedActiveVersionRef": None})
    nested_key = "vm_nested_" + secrets.token_hex(4)
    nested = tool(connection, "compose_flow", request={"key": nested_key, "name": nested_key,
        "gatewayRef": gateway_receipt["reference"], "httpMethod": "POST", "path": "/nested", "runtime": "NODE",
        "steps": [{"key": "nested", "type": "SUB_FLOW", "reference": node_versions["flowVersion"]}]})
    tool(connection, "publish_flow", request={"reference": nested["reference"], "expectedActiveVersionRef": None})
    time.sleep(6)
    with opener_a.open("https://" + hostname + "/reuse/about.html", timeout=20) as response:
        smoke.require(response.status == 200 and "Second page" in response.read().decode(), "Reused STATIC artifact failed")
    request = urllib.request.Request("https://" + hostname + "/nested", json.dumps({"player": "alice"}).encode(),
                                     {"Content-Type": "application/json"})
    with opener_a.open(request, timeout=20) as response:
        smoke.require(json.load(response)["events"] == restored["events"], "Nested adopted subflow lost shared state")
    print("PASS fix-forward, bounded failed-build logs, explicit live-base config, stale publication, component reuse and SUB_FLOW HTTPS")
    report = {"gateway": "https://" + hostname, "protocols": smoke.VERSIONS,
              "static": ["/", "/about.html", "/app.css"], "node": "/state", "players": 2,
              "persistedEvents": len(restored["events"]), "tls": "locally pinned self-signed certificate; hostname checked",
              "replacement": replacement["reference"], "reuse": reused["reference"], "subflow": nested["reference"],
              "liveBasePreserved": True, "configPriority": "high", "stalePublish": "CONFLICT"}
    Path(".vm/verification.json").write_text(json.dumps(report, indent=2) + "\n")
    # Project-local evidence belongs to this disposable VM, not MCP-owned memory.
    Path(".vm/mcp-runbook.md").write_text(
        "# Disposable VM app checks\n\n"
        "Trigger: repeat the STATIC/NODE HTTP and Postgres deployment journey in funchole-test only.\n"
        "Order: discover/read, claim_domain with external TXT and DNS check, configure_gateway, "
        "connect_database, build_function, poll read to READY, compose_flow, draft NODE invoke/read, "
        "publish_flow with expectedActiveVersionRef=null, then real HTTPS checks.\n"
        "Contracts: funchole://guides/start, funchole://guides/evolve, funchole://guides/node, "
        "funchole://guides/static, funchole://guides/data.\n"
        f"Gateway: {gateway_receipt['reference']} at https://{hostname}\n"
        f"Database: {database['reference']}\n"
        f"STATIC versions: {json.dumps(static_versions)}\n"
        f"NODE versions: {json.dumps(node_versions)}\n"
        "Observed: root/second page/asset, draft invocation without a move, two independent HTTP "
        "cookie sessions, both Set-Cookie headers, shared moves, persistence after Runtime restart, "
        "explicit live-base fix-forward after FAILED, config priority, unchanged live state while preparing, "
        "stale publish CONFLICT, STATIC reuse and adopted SUB_FLOW HTTP, "
        "and hostname-validated TLS with the disposable local certificate pinned.\n"
        "Pending: production CA/DNS, authorization/concurrency/expired-session tests, and production hardening.\n"
        "No credentials are recorded. MCP does not own host memory or global skills.\n")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Tool/HTTP bodies can contain sensitive connection data. Do not print them.
        print("FAIL deployment verification:", type(error).__name__)
        if isinstance(error, RuntimeError):
            print(str(error))
        raise SystemExit(1)
