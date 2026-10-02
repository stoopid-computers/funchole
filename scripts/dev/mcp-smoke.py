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
            if method in {"server/discover", "tools/list", "resources/list", "resources/read", "prompts/list"}:
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
    require({"get_funchole_guide", "get_funchole_tool", "search_funchole", "plan_application", "get_function_example"} <= names,
            "Discovery tools or executable examples are missing")
    resources = connection.list_all("resources/list", "resources")
    require("funchole://guides/start" in {item["uri"] for item in resources}, "Start resource is missing")
    guide = connection.call("resources/read", {"uri": "funchole://guides/start"})["contents"][0]["text"]
    fallback = connection.call("tools/call", {"name": "get_funchole_guide", "arguments": {"topic": "start"}})
    require(not fallback.get("isError") and fallback["content"][0]["text"] == guide, "Resource/tool guide bodies differ")
    prompts = connection.list_all("prompts/list", "prompts")
    require({"build_application", "repair_application"} <= {item["name"] for item in prompts}, "App prompts are missing")
    prompt = connection.call("prompts/get", {"name": "build_application", "arguments": {"idea": "A read-only smoke check"}})
    require(prompt.get("messages"), "Build prompt has no messages")
    plan = connection.call("tools/call", {"name": "plan_application", "arguments": {"kind": "MULTIPLAYER"}})
    require(not plan.get("isError") and plan.get("structuredContent", {}).get("shippingChecks"), "App plan has no shipping checks")
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
