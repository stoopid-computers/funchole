package com.funchole.backend.controlplane.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.TypeRef;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Runs only inside the authenticated security chain; legacy bodies pass through unchanged. */
@Component
public class McpProtocolFilter extends OncePerRequestFilter {
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final Set<String> LEGACY = Set.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
    private final ModernMcpProtocol protocol;
    private final McpToolRateLimiter rateLimiter;
    private final String endpoint;

    public McpProtocolFilter(ModernMcpProtocol protocol, McpToolRateLimiter rateLimiter,
            @Value("${spring.ai.mcp.server.streamable-http.mcp-endpoint:/api/mcp}") String endpoint) {
        this.protocol = protocol;
        this.rateLimiter = rateLimiter;
        this.endpoint = endpoint;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(request.getContextPath() + endpoint);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String headerVersion = request.getHeader("MCP-Protocol-Version");
        boolean modernHeader = headerVersion != null && !LEGACY.contains(headerVersion);
        if (!"POST".equals(request.getMethod())) {
            if (modernHeader) {
                response.setHeader("Allow", "POST");
                response.setStatus(405);
            } else chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            write(response, ModernMcpProtocol.error(null, 413, -32600, "MCP request exceeds 8 MiB", Map.of()));
            return;
        }
        Map<String, Object> message;
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body)).toString();
            Object root = McpJsonDefaults.getMapper().readValue(json, new TypeRef<>() { });
            message = root instanceof Map<?, ?> ? ModernMcpProtocol.object(root, "request") : null;
        } catch (Exception exception) {
            if (modernHeader || request.getHeader("Mcp-Method") != null) {
                write(response, ModernMcpProtocol.error(null, 400, -32700, "Invalid JSON request", Map.of()));
            } else chain.doFilter(new BodyRequest(request, body), response);
            return;
        }
        boolean modernBody = message != null && message.get("params") instanceof Map<?, ?> params
                && params.get("_meta") instanceof Map<?, ?> meta && meta.containsKey(ModernMcpProtocol.VERSION_META);
        if (!modernHeader && !modernBody && request.getHeader("Mcp-Method") == null) {
            if (!allowToolCall(message, response)) return;
            chain.doFilter(new BodyRequest(request, body), response);
            return;
        }
        Object id = message == null ? null : message.get("id");
        try {
            if (message == null) throw new ModernMcpProtocol.Fault(400, -32600, "Expected request object", Map.of());
            ModernMcpProtocol.validateEnvelope(message);
            Map<String, Object> params = ModernMcpProtocol.object(message.get("params"), "params");
            Map<String, Object> meta = ModernMcpProtocol.object(params.get("_meta"), "_meta");
            String version = ModernMcpProtocol.text(meta.get(ModernMcpProtocol.VERSION_META), ModernMcpProtocol.VERSION_META);
            ModernMcpProtocol.object(meta.get(ModernMcpProtocol.CAPABILITIES_META), ModernMcpProtocol.CAPABILITIES_META);
            checkHeader(request, "MCP-Protocol-Version", version, false);
            String method = ModernMcpProtocol.text(message.get("method"), "method");
            checkHeader(request, "Mcp-Method", method, false);
            if (Set.of("tools/call", "prompts/get", "resources/read").contains(method)) {
                checkHeader(request, "Mcp-Name", ModernMcpProtocol.text(
                        params.get(method.equals("resources/read") ? "uri" : "name"), "name/uri"), true);
            }
            String accept = request.getHeader("Accept");
            if (accept == null || !accept.contains("application/json") || !accept.contains("text/event-stream")) {
                throw new ModernMcpProtocol.Fault(406, -32600, "Accept must include application/json and text/event-stream", Map.of());
            }
            String contentType = request.getContentType();
            if (contentType == null || !contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw new ModernMcpProtocol.Fault(415, -32600, "Content-Type must be application/json", Map.of());
            }
            if (allowToolCall(message, response)) write(response, protocol.dispatch(message));
        } catch (ModernMcpProtocol.Fault fault) {
            write(response, ModernMcpProtocol.error(id, fault.status, fault.code, fault.getMessage(), fault.data));
        }
    }

    private boolean allowToolCall(Map<String, Object> message, HttpServletResponse response) throws IOException {
        if (message == null || !"tools/call".equals(message.get("method"))
                || rateLimiter.tryAcquire(CurrentMcpUser.id())) return true;
        response.setHeader("Retry-After", "60");
        write(response, ModernMcpProtocol.error(message.get("id"), 429, 1001,
                "MCP tool call budget exceeded. Wait up to 60 seconds before retrying.", Map.of()));
        return false;
    }

    private static void checkHeader(HttpServletRequest request, String name, String expected, boolean encoded) {
        String actual = request.getHeader(name);
        // Reject duplicate routing headers instead of letting intermediaries pick a different value.
        if (java.util.Collections.list(request.getHeaders(name)).size() != 1
                || actual == null || !actual.equals(actual.trim())
                || actual.chars().anyMatch(c -> (c < 32 && c != 9) || c > 126)) {
            throw new ModernMcpProtocol.Fault(400, -32020, "Missing or malformed header: " + name, Map.of());
        }
        if (actual != null && encoded && actual.startsWith("=?base64?") && actual.endsWith("?=")) {
            try {
                byte[] decoded = Base64.getDecoder().decode(actual.substring(9, actual.length() - 2));
                actual = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(decoded)).toString();
            } catch (Exception exception) { actual = null; }
        }
        if (!expected.equals(actual)) {
            throw new ModernMcpProtocol.Fault(400, -32020, "Missing or mismatched header: " + name, Map.of());
        }
    }

    private static void write(HttpServletResponse response, ModernMcpProtocol.Reply reply) throws IOException {
        response.setStatus(reply.status());
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(McpJsonDefaults.getMapper().writeValueAsString(reply.body()));
    }

    private static final class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BodyRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }
        @Override public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    try {
                        if (!isFinished()) listener.onDataAvailable();
                        if (isFinished()) listener.onAllDataRead();
                    } catch (IOException exception) { listener.onError(exception); }
                }
            };
        }
    }
}
