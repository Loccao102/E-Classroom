package com.eclassroom.core.notification;

import com.eclassroom.core.integration.OutboxService;
import com.eclassroom.core.shared.api.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {
    public static final List<String> CATEGORIES = List.of(
            "ATTENDANCE", "LEAVE", "SCORE", "ANNOUNCEMENT", "COMMENT", "MESSAGE", "SYSTEM");

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
        List<UUID> recipients = guardianRecipients(schoolId, studentId);
        notifyUsers(schoolId, recipients, eventType, title, body, entityType, entityId, extraData);
    }

    public List<UUID> guardianRecipients(UUID schoolId, UUID studentId) {
        return jdbc.query(
                "SELECT DISTINCT g.user_id FROM academic.student_guardians sg " +
                        "JOIN academic.guardians g ON g.id=sg.guardian_id " +
                        "WHERE sg.school_id=? AND sg.student_id=? AND sg.notifications_enabled=TRUE " +
                        "AND g.user_id IS NOT NULL AND g.status='ACTIVE'",
                (rs, i) -> UUID.fromString(rs.getString(1)), schoolId, studentId);
    }

    public void notifyUsers(UUID schoolId, List<UUID> recipients, String eventType, String title, String body,
                            String entityType, UUID entityId) {
        notifyUsers(schoolId, recipients, eventType, title, body, entityType, entityId, Map.of());
    }

    public void notifyUsers(UUID schoolId, List<UUID> recipients, String eventType, String title, String body,
                            String entityType, UUID entityId, Map<String, Object> extraData) {
        List<UUID> targets = recipients == null
                ? List.of()
                : new ArrayList<>(new LinkedHashSet<>(recipients));
        String category = categoryFor(eventType);
        Map<UUID, Preference> preferences = preferencesFor(schoolId, targets, category);

        List<UUID> durableRecipients = targets.stream()
                .filter(userId -> preferences.getOrDefault(userId, Preference.enabled()).inAppEnabled())
                .toList();
        List<UUID> realtimeRecipients = targets.stream()
                .filter(userId -> preferences.getOrDefault(userId, Preference.enabled()).realtimeEnabled())
                .toList();

        List<UUID> notificationIds = new ArrayList<>();
        List<Object[]> rows = new ArrayList<>();
        for (UUID userId : durableRecipients) {
            UUID id = UUID.randomUUID();
            notificationIds.add(id);
            rows.add(new Object[]{id, schoolId, userId, eventType, category, title, body, entityType, entityId});
        }
        if (!rows.isEmpty()) {
            jdbc.batchUpdate(
                    "INSERT INTO notification.notifications" +
                            "(id,school_id,recipient_user_id,type,category,title,body,entity_type,entity_id) " +
                            "VALUES (?,?,?,?,?,?,?,?,?)",
                    rows);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        if (extraData != null) {
            data.putAll(extraData);
        }
        data.put("entityId", entityId);
        data.put("entityType", entityType);
        data.put("title", title);
        data.put("body", body);
        data.put("category", category);
        data.put("notificationIds", notificationIds);
        data.put("targetRecipientIds", targets);
        outbox.emit(schoolId, eventType, 1, data, realtimeRecipients);
    }

    /** Backward-compatible feed used by the current web app. */
    public List<Map<String, Object>> feed(UUID userId, int limit) {
        return page(userId, null, null, false, limit, null, null).items();
    }

    public NotificationPage page(UUID userId, UUID schoolId, String category, boolean unreadOnly, int limit,
                                 OffsetDateTime beforeCreatedAt, UUID beforeId) {
        int bounded = Math.max(1, Math.min(limit, 100));
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw ApiException.badRequest("INVALID_CURSOR", "Both beforeCreatedAt and beforeId are required");
        }
        if (category != null && !category.isBlank()) {
            category = normalizeCategory(category);
        }

        StringBuilder sql = new StringBuilder(
                "SELECT id,school_id,type,category,title,body,entity_type,entity_id,delivery_status,read_at,created_at " +
                        "FROM notification.notifications WHERE recipient_user_id=?");
        List<Object> args = new ArrayList<>();
        args.add(userId);
        if (schoolId != null) {
            sql.append(" AND school_id=?");
            args.add(schoolId);
        }
        if (category != null && !category.isBlank()) {
            sql.append(" AND category=?");
            args.add(category);
        }
        if (unreadOnly) {
            sql.append(" AND read_at IS NULL");
        }
        if (beforeCreatedAt != null) {
            sql.append(" AND (created_at,id) < (?,?)");
            args.add(beforeCreatedAt);
            args.add(beforeId);
        }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?");
        args.add(bounded + 1);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        boolean hasMore = rows.size() > bounded;
        List<Map<String, Object>> items = hasMore ? List.copyOf(rows.subList(0, bounded)) : List.copyOf(rows);
        NotificationCursor next = null;
        if (hasMore && !items.isEmpty()) {
            Map<String, Object> last = items.getLast();
            next = new NotificationCursor(toOffsetDateTime(last.get("created_at")), (UUID) last.get("id"));
        }
        return new NotificationPage(items, next);
    }

    public long unreadCount(UUID userId, UUID schoolId) {
        Long count;
        if (schoolId == null) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? AND read_at IS NULL",
                    Long.class, userId);
        } else {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM notification.notifications WHERE recipient_user_id=? AND school_id=? AND read_at IS NULL",
                    Long.class, userId, schoolId);
        }
        return count == null ? 0 : count;
    }

    public void markRead(UUID userId, UUID notificationId) {
        jdbc.update(
                "UPDATE notification.notifications SET read_at=COALESCE(read_at,NOW()) WHERE id=? AND recipient_user_id=?",
                notificationId, userId);
    }

    public int markAllRead(UUID userId, UUID schoolId) {
        if (schoolId == null) {
            return jdbc.update(
                    "UPDATE notification.notifications SET read_at=NOW() WHERE recipient_user_id=? AND read_at IS NULL",
                    userId);
        }
        return jdbc.update(
                "UPDATE notification.notifications SET read_at=NOW() WHERE recipient_user_id=? AND school_id=? AND read_at IS NULL",
                userId, schoolId);
    }

    public List<PreferenceView> preferences(UUID userId, UUID schoolId) {
        requireMembership(userId, schoolId);
        Map<String, Preference> stored = jdbc.query(
                "SELECT category,in_app_enabled,realtime_enabled FROM notification.preferences WHERE user_id=? AND school_id=?",
                rs -> {
                    Map<String, Preference> map = new LinkedHashMap<>();
                    while (rs.next()) {
                        map.put(rs.getString("category"), new Preference(
                                rs.getBoolean("in_app_enabled"), rs.getBoolean("realtime_enabled")));
                    }
                    return map;
                }, userId, schoolId);
        return CATEGORIES.stream().map(category -> {
            Preference value = stored.getOrDefault(category, Preference.enabled());
            return new PreferenceView(category, value.inAppEnabled(), value.realtimeEnabled());
        }).toList();
    }

    public PreferenceView updatePreference(UUID userId, UUID schoolId, String category,
                                           boolean inAppEnabled, boolean realtimeEnabled) {
        requireMembership(userId, schoolId);
        String normalized = normalizeCategory(category);
        jdbc.update(
                "INSERT INTO notification.preferences(school_id,user_id,category,in_app_enabled,realtime_enabled,updated_at) " +
                        "VALUES (?,?,?,?,?,NOW()) ON CONFLICT(school_id,user_id,category) DO UPDATE SET " +
                        "in_app_enabled=EXCLUDED.in_app_enabled,realtime_enabled=EXCLUDED.realtime_enabled,updated_at=NOW()",
                schoolId, userId, normalized, inAppEnabled, realtimeEnabled);
        return new PreferenceView(normalized, inAppEnabled, realtimeEnabled);
    }

    private Map<UUID, Preference> preferencesFor(UUID schoolId, List<UUID> recipients, String category) {
        if (recipients.isEmpty()) {
            return Map.of();
        }
        String placeholders = recipients.stream().map(id -> "?").collect(Collectors.joining(","));
        String sql = "SELECT user_id,in_app_enabled,realtime_enabled FROM notification.preferences " +
                "WHERE school_id=? AND category=? AND user_id IN (" + placeholders + ")";
        List<Object> args = new ArrayList<>();
        args.add(schoolId);
        args.add(category);
        args.addAll(recipients);
        return jdbc.query(sql, rs -> {
            Map<UUID, Preference> map = new LinkedHashMap<>();
            while (rs.next()) {
                map.put(UUID.fromString(rs.getString("user_id")), new Preference(
                        rs.getBoolean("in_app_enabled"), rs.getBoolean("realtime_enabled")));
            }
            return map;
        }, args.toArray());
    }

    private void requireMembership(UUID userId, UUID schoolId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM identity.school_memberships WHERE user_id=? AND school_id=? AND status='ACTIVE'",
                Integer.class, userId, schoolId);
        if (count == null || count == 0) {
            throw ApiException.forbidden("No active membership for this school");
        }
    }

    private String categoryFor(String eventType) {
        String value = eventType == null ? "" : eventType.toLowerCase();
        if (value.contains("attendance")) return "ATTENDANCE";
        if (value.contains("leave")) return "LEAVE";
        if (value.contains("score") || value.contains("assessment")) return "SCORE";
        if (value.contains("announcement")) return "ANNOUNCEMENT";
        if (value.contains("comment")) return "COMMENT";
        if (value.contains("message") || value.contains("conversation")) return "MESSAGE";
        return "SYSTEM";
    }

    private String normalizeCategory(String category) {
        String normalized = category == null ? "" : category.trim().toUpperCase();
        if (!CATEGORIES.contains(normalized)) {
            throw ApiException.badRequest("INVALID_NOTIFICATION_CATEGORY", "Unsupported notification category");
        }
        return normalized;
    }

    private OffsetDateTime toOffsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        return OffsetDateTime.parse(String.valueOf(value));
    }

    private record Preference(boolean inAppEnabled, boolean realtimeEnabled) {
        private static Preference enabled() {
            return new Preference(true, true);
        }
    }

    public record PreferenceView(String category, boolean inAppEnabled, boolean realtimeEnabled) {}
    public record NotificationCursor(OffsetDateTime beforeCreatedAt, UUID beforeId) {}
    public record NotificationPage(List<Map<String, Object>> items, NotificationCursor nextCursor) {}
}
