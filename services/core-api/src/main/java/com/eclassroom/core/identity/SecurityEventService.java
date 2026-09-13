package com.eclassroom.core.identity;

import com.eclassroom.core.shared.api.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

@Service
public class SecurityEventService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public SecurityEventService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void record(UUID userId, UUID schoolId, String eventType, String outcome, UUID sessionId, Map<String, ?> metadata) {
        try {
            String metadataJson = metadata == null || metadata.isEmpty() ? null : json.writeValueAsString(metadata);
            jdbc.update(
                    "INSERT INTO identity.security_events(id,user_id,school_id,event_type,outcome,session_id,correlation_id,metadata) " +
                            "VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb))",
                    UUID.randomUUID(), userId, schoolId, eventType, outcome, sessionId,
                    MDC.get(CorrelationIdFilter.MDC_KEY), metadataJson);
        } catch (Exception e) {
            throw new IllegalStateException("Security event persistence failed", e);
        }
    }
}
