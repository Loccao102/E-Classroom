package com.eclassroom.core.integration;

import com.eclassroom.core.shared.api.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public OutboxService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID emit(UUID schoolId, String eventType, int version, Map<String, Object> data, List<UUID> recipients) {
        UUID eventId = UUID.randomUUID();
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = eventId.toString();
        }

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("correlationId", correlationId);
        envelope.put("eventType", eventType);
        envelope.put("eventVersion", version);
        envelope.put("occurredAt", Instant.now());
        envelope.put("schoolId", schoolId);
        envelope.put("recipients", recipients);
        envelope.put("data", data);

        try {
            String payload = json.writeValueAsString(envelope);
            jdbc.update(
                    "INSERT INTO integration.outbox_events(id,aggregate_type,aggregate_id,event_type,event_version,school_id,correlation_id,payload,occurred_at) " +
                            "VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb),NOW())",
                    eventId,
                    eventType,
                    data.containsKey("entityId") ? data.get("entityId") : eventId,
                    eventType,
                    version,
                    schoolId,
                    correlationId,
                    payload);
            return eventId;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize domain event", e);
        }
    }
}
