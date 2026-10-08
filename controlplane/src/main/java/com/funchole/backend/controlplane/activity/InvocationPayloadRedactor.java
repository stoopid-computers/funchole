package com.funchole.backend.controlplane.activity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * An invocation's input is the visitor's HTTP request, so it contains their
 * credentials: Authorization and Cookie headers, session cookies. The
 * function needs those at run time, but whoever inspects an invocation
 * afterwards does not. Redacts them on the way out; input that is not a
 * JSON object (a hand-written test payload) is returned untouched.
 */
@Component
public class InvocationPayloadRedactor {

    static final String HIDDEN = "[hidden]";

    private static final Set<String> SENSITIVE_HEADERS = Set.of(
            "authorization", "proxy-authorization", "cookie", "set-cookie",
            "x-api-key", "x-auth-token", "x-csrf-token", "x-xsrf-token");

    // Like the other services here: this project has no shared ObjectMapper bean.
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String redact(String inputPayload) {
        if (inputPayload == null || inputPayload.isBlank()) {
            return inputPayload;
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(inputPayload);
        } catch (JsonProcessingException exception) {
            return inputPayload;
        }
        if (!(root instanceof ObjectNode object)) {
            return inputPayload;
        }

        JsonNode headers = object.get("headers");
        if (headers instanceof ObjectNode headerNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = headerNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (SENSITIVE_HEADERS.contains(field.getKey().toLowerCase())) {
                    field.setValue(objectMapper.getNodeFactory().textNode(HIDDEN));
                }
            }
        }
        JsonNode cookies = object.get("cookies");
        if (cookies instanceof ObjectNode cookieNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = cookieNode.fields();
            while (fields.hasNext()) {
                fields.next().setValue(objectMapper.getNodeFactory().textNode(HIDDEN));
            }
        }

        try {
            return objectMapper.writeValueAsString(object);
        } catch (JsonProcessingException exception) {
            return inputPayload;
        }
    }
}
