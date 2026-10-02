#!/usr/bin/env python3
"""Read-only checks of discovery, tools, resources and prompts across MCP revisions."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


MODERN = "2026-07-28"
VERSIONS = [MODERN, "2025-11-25", "2025-06-18", "2025-03-26"]
MAX_RESPONSE = 4 * 1024 * 1024
CORE_TOOLS = {"discover", "read", "build_function", "compose_flow", "invoke", "publish_flow",
              "configure", "connect_database", "configure_gateway", "claim_domain", "retire"}
GUIDES = {"start", "static", "node", "flows", "data", "multiplayer", "troubleshooting", "evolve"}
EXAMPLES = {"NODE_BASIC", "NODE_DATABASE", "NODE_ENV_VARS", "NODE_REQUEST_HEADERS", "STATIC_MULTIPAGE"}


class Connection:
    def __init__(self, url, token, version):
        self.url = url
        self.token = token
        self.version = version
        self.session = None
        self.request_id = 0

    def call(self, method, params=None, notification=False):
        self.request_id += 1
        params = dict(params or {})
        headers = {
            "Authorization": "Bearer " + self.token,
            "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream",
        }
        message = {"jsonrpc": "2.0", "method": method, "params": params}
        if not notification:
            message["id"] = self.request_id
        if self.version == MODERN:
            params["_meta"] = {
                "io.modelcontextprotocol/protocolVersion": self.version,
                "io.modelcontextprotocol/clientInfo": {"name": "funchole-smoke", "version": "1"},
                "io.modelcontextprotocol/clientCapabilities": {},
            }
            headers["Mcp-Method"] = method
            if method in {"tools/call", "prompts/get", "resources/read"}:
                headers["Mcp-Name"] = params["uri" if method == "resources/read" else "name"]
        if method != "initialize":
            headers["MCP-Protocol-Version"] = self.version
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        request = urllib.request.Request(self.url, json.dumps(message).encode(), headers, method="POST")
        with urllib.request.urlopen(request, timeout=20) as response:
            if method == "initialize":
                self.session = response.headers.get("Mcp-Session-Id")
                require(self.session, "Legacy initialization did not create a session")
            if self.version == MODERN:
                require(not response.headers.get("Mcp-Session-Id"), "Modern response created a session")
            body = response.read(MAX_RESPONSE + 1)
            require(len(body) <= MAX_RESPONSE, "Response exceeds the smoke check's 4 MiB limit")
            if notification:
                require(response.status == 202 and not body, "Notification was not accepted with an empty 202")
                return None
            content_type = response.headers.get("Content-Type", "")
        if "text/event-stream" in content_type:
            payloads = [line[5:].strip() for line in body.decode().splitlines() if line.startswith("data:")]
            require(payloads, "SSE stream had no response data")
            body = payloads[-1]
        message = json.loads(body)
        require(message.get("id") == self.request_id, "Response ID does not match the request")
        require("result" in message, "RPC failed for " + method)
        result = message["result"]
        if self.version == MODERN:
            require(result.get("resultType") == "complete", "Missing modern complete result envelope")
            if method in {"server/discover", "tools/list", "resources/list", "prompts/list"} or (
                    method == "resources/read" and params.get("uri", "").startswith(
                        ("funchole://guides/", "funchole://tools/", "funchole://examples/"))):
                require("ttlMs" in result and "cacheScope" in result, "Missing modern cache hints")
        return result

    def list_all(self, method, key):
        result = self.call(method)
        values = list(result[key])
        seen = set()
        while "nextCursor" in result:
            cursor = result["nextCursor"]
            require(cursor not in seen and len(seen) < 100, "Pagination did not terminate")
            seen.add(cursor)
            result = self.call(method, {"cursor": cursor})
            values.extend(result[key])
        return values


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def operation(connection, name, **arguments):
    """Keep the reference and DTO together; never print server bodies on failure."""
    result = connection.call("tools/call", {"name": name, "arguments": arguments})
    require(not result.get("isError"), "Tool failed: " + name)
    receipt = result.get("structuredContent")
    if receipt is None:
        receipt = json.loads(result["content"][0]["text"])
    require(isinstance(receipt, dict) and
            {"ok", "code", "message", "reference", "data", "links", "warnings"} <= receipt.keys(),
            "Missing operation receipt: " + name)
    require(receipt["ok"] is True, "Operation failed: " + name)
    return receipt


def smoke(connection):
    if connection.version == MODERN:
        discovery = connection.call("server/discover")
        require(all(version in discovery["supportedVersions"] for version in VERSIONS), "A required revision is not advertised")
    else:
        initialized = connection.call("initialize", {
            "protocolVersion": connection.version,
            "capabilities": {},
            "clientInfo": {"name": "funchole-smoke", "version": "1"},
        })
        require(initialized["protocolVersion"] == connection.version, "Requested legacy revision was not negotiated")
        connection.call("notifications/initialized", notification=True)
    tools = connection.list_all("tools/list", "tools")
    names = {tool["name"] for tool in tools}
    require(CORE_TOOLS <= names, "An expected core tool is missing")
    require(len(names) == len(tools), "Duplicate tool names")
    # The HTTP fixture adds pagination/identity callbacks. Nothing else is public.
    extras = names - CORE_TOOLS
    require(all(name.startswith("fixture_") for name in extras), "Unexpected or legacy public tool")
    require(extras or len(tools) == 11, "Production catalog must contain exactly eleven tools")
    resources = connection.list_all("resources/list", "resources")
    uris = {item["uri"] for item in resources}
    expected = ({"funchole://guides/" + topic for topic in GUIDES} |
                {"funchole://tools/" + name for name in CORE_TOOLS} |
                {"funchole://examples/" + scenario for scenario in EXAMPLES})
    require(expected <= uris, "Guide, contract or example resources are missing")
    for topic in sorted(GUIDES):
        uri = "funchole://guides/" + topic
        guide = connection.call("resources/read", {"uri": uri})["contents"][0]["text"]
        require(operation(connection, "read", reference=uri)["data"] == guide,
                "Native/read guide bodies differ: " + topic)
    for listed in tools:
        if listed["name"] in CORE_TOOLS:
            contract = operation(connection, "read", reference="funchole://tools/" + listed["name"])["data"]
            require(contract == listed, "Read contract differs from tools/list: " + listed["name"])
    offset = 0
    seen = set()
    knowledge = []
    while True:
        page = operation(connection, "discover", offset=offset, limit=20)["data"]
        require(len(page["items"]) <= 20 and isinstance(page["total"], int), "Unbounded knowledge page")
        require(all({"kind", "name", "description", "pointer"} <= item.keys() for item in page["items"]),
                "Knowledge entry contract differs")
        knowledge.extend(page["items"])
        next_offset = page["nextOffset"]
        if next_offset is None:
            require(len(knowledge) == page["total"], "Knowledge total differs")
            break
        require(isinstance(next_offset, int) and next_offset > offset and next_offset not in seen and len(seen) < 100,
                "Knowledge pagination did not terminate")
        seen.add(next_offset)
        offset = next_offset
    require(expected <= {item["pointer"] for item in knowledge}, "Knowledge pointers are missing")
    example = operation(connection, "read", reference="funchole://examples/NODE_BASIC")["data"]
    require(example["scenario"] == "NODE_BASIC" and example["files"], "Executable example contract differs")
    prompts = connection.list_all("prompts/list", "prompts")
    require(len(prompts) == 2 and {"build_application", "repair_application"} == {item["name"] for item in prompts},
            "Expected exactly two app prompts")
    prompt = connection.call("prompts/get", {"name": "build_application", "arguments": {"idea": "A read-only smoke check"}})
    require(prompt.get("messages"), "Build prompt has no messages")
    print(f"PASS {connection.version}: {len(tools)} tools, {len(resources)} resources, {len(prompts)} prompts")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://localhost:7080/api/mcp", help="Authenticated Streamable HTTP endpoint")
    args = parser.parse_args()
    token = os.environ.get("FUNCHOLE_MCP_API_KEY")
    if not token:
        parser.error("Set FUNCHOLE_MCP_API_KEY. It is never printed or accepted as a command-line argument.")
    # Redirects could send credentials to a different host. Fail instead of following them.
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None
    urllib.request.install_opener(urllib.request.build_opener(NoRedirect()))
    for version in VERSIONS:
        smoke(Connection(args.url, token, version))


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print(f"FAIL: HTTP {error.code}. Check credentials, endpoint, origin policy and operator logs.", file=sys.stderr)
        sys.exit(1)
    except (RuntimeError, ValueError, KeyError, OSError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
