package com.eclassroom.core.notification;

import com.eclassroom.core.integration.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationService {
    private final JdbcTemplate jdbc;
    private final OutboxService outbox;

    public NotificationService(JdbcTemplate jdbc, OutboxService outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    public void notifyGuardians(UUID schoolId, UUID studentId, String eventType, String title, String body,
                                String entityType, UUID entityId) {
        notifyGuardians(schoolId, studentId, eventType, title, body, entityType, entityId, Map.of());
    }

    public void notifyGuardians(UUID schoolId, UUID studentId, String eventType, String title, String body,
                                String entityType, UUID entityId, Map<String, Object> extraData) {
        List<UUID> recipients = jdbc.query(
                "SELECT DISTINCT g.user_id FROM academic.student_guardians sg " +
                        "JOIN academic.guardians g ON g.id=sg.guardian_id " +
                        "WHERE sg.school_id=? AND sg.student_id=? AND sg.notifications_enabled=TRUE " +
                        "AND g.user_id IS NOT NULL AND g.status='ACTIVE'",
                (rs, i) -> UUID.fromString(rs.getString(1)), schoolId, studentId);
        notifyUsers(schoolId, recipients, eventType, title, body, entityType, entityId, extraData);
    }

    public void notifyUsers(UUID schoolId, List<UUID> recipients, String eventType, String title, String body,
                            String entityType, UUID entityId) {
        notifyUsers(schoolId, recipients, eventType, title, body, entityType, entityId, Map.of());
    }

    public void notifyUsers(UUID schoolId, List<UUID> recipients, String eventType, String title, String body,
                            String entityType, UUID entityId, Map<String, Object> extraData) {
        if (recipients == null || recipients.isEmpty()) {
            return;
        }
        List<UUID> ids = new ArrayList<>();
        for (UUID userId : recipients) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            jdbc.update(
                    "INSERT INTO notification.notifications(id,school_id,recipient_user_id,type,title,body,entity_type,entity_id) VALUES (?,?,?,?,?,?,?,?)",
                    id, schoolId, userId, eventType, title, body, entityType, entityId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        if (extraData != null) {
            data.putAll(extraData);
        }
        data.put("entityId", entityId);
        data.put("entityType", entityType);
        data.put("title", title);
        data.put("body", body);
        data.put("notificationIds", ids);
        outbox.emit(schoolId, eventType, 1, data, recipients);
    }

    public List<Map<String, Object>> feed(UUID userId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList(
                "SELECT id,school_id,type,title,body,entity_type,entity_id,read_at,created_at FROM notification.notifications " +
                        "WHERE recipient_user_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
                userId, bounded);
    }

    public void markRead(UUID userId, UUID notificationId) {
        jdbc.update(
                "UPDATE notification.notifications SET read_at=COALESCE(read_at,NOW()) WHERE id=? AND recipient_user_id=?",
                notificationId, userId);
    }
}
