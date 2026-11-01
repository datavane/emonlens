package io.emonlens.server.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class AuditAspectTest {

    private final AuditAspect aspect = new AuditAspect(null, new ObjectMapper());

    @Test
    void snapshotMasksNotifySenderConfigCredentials() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "ops-mail",
                "channel", "EMAIL",
                "config", "{\"host\":\"smtp.example.com\",\"port\":465,\"username\":\"ops\",\"password\":\"plain-password\"}"
        );
        Object snapshot = aspect.snapshotArgs(new Object[]{ body });

        assertThat(snapshot).isNotNull();
        String json = new ObjectMapper().writeValueAsString(snapshot);
        assertThat(json).contains("smtp.example.com");
        assertThat(json).doesNotContain("plain-password");
        assertThat(json).contains("***");
    }

    @Test
    void snapshotMasksTopLevelSecrets() throws Exception {
        Map<String, Object> body = Map.of(
                "name", "openapi",
                "scopes", "score:read",
                "apiKey", "plain-api-key"
        );
        Object snapshot = aspect.snapshotArgs(new Object[]{ body });

        String json = new ObjectMapper().writeValueAsString(snapshot);
        assertThat(json).doesNotContain("plain-api-key");
        assertThat(json).contains("***");
    }
}
