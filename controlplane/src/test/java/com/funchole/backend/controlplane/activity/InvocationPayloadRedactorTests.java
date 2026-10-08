package com.funchole.backend.controlplane.activity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvocationPayloadRedactorTests {

    private final InvocationPayloadRedactor redactor = new InvocationPayloadRedactor();

    @Test
    void hidesCredentialHeadersAndAllCookiesButKeepsTheRest() {
        String input = """
                {"method":"GET","path":"/orders","headers":{"Authorization":"Bearer secret-token","Cookie":"sid=abc",
                "accept":"application/json","X-Api-Key":"k"},"cookies":{"sid":"abc","theme":"dark"},"body":"hi"}""";

        String redacted = redactor.redact(input);

        assertThat(redacted).doesNotContain("secret-token").doesNotContain("sid=abc").doesNotContain("\"abc\"");
        assertThat(redacted).contains("\"accept\":\"application/json\"").contains("\"path\":\"/orders\"").contains("\"body\":\"hi\"");
        assertThat(redacted).contains("[hidden]");
    }

    @Test
    void leavesNonRequestInputUntouched() {
        assertThat(redactor.redact(null)).isNull();
        assertThat(redactor.redact("")).isEmpty();
        assertThat(redactor.redact("not json")).isEqualTo("not json");
        assertThat(redactor.redact("[1,2]")).isEqualTo("[1,2]");
        assertThat(redactor.redact("{\"name\":\"Maya\"}")).isEqualTo("{\"name\":\"Maya\"}");
    }
}
