package com.eclassroom.core.audit;

import com.eclassroom.core.shared.api.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public AuditService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void append(UUID schoolId, UUID actor, String action, String entityType, UUID entityId,
                       Object oldValues, Object newValues, String reason) {
        try {
            String oldJson = oldValues == null ? null : json.writeValueAsString(oldValues);
            String newJson = newValues == null ? null : json.writeValueAsString(newValues);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            jdbc.update(
                    "INSERT INTO audit.audit_entries(id,school_id,actor_user_id,action,entity_type,entity_id,old_values,new_values,reason,correlation_id) " +
                            "VALUES (?,?,?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?)",
                    UUID.randomUUID(), schoolId, actor, action, entityType, entityId,
                    oldJson, newJson, reason, correlationId);
        } catch (Exception e) {
            throw new IllegalStateException("Audit serialization failed", e);
        }
    }

    public java.util.List<Map<String, Object>> entity(UUID schoolId, String type, UUID id) {
        return jdbc.queryForList(
                "SELECT id,actor_user_id,action,old_values,new_values,reason,correlation_id,created_at " +
                        "FROM audit.audit_entries WHERE school_id=? AND entity_type=? AND entity_id=? ORDER BY created_at DESC",
                schoolId, type, id);
    }
}
