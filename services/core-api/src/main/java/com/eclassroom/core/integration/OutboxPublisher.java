package com.eclassroom.core.integration;

import io.nats.client.JetStream;
import io.nats.client.PublishOptions;
import io.nats.client.api.PublishAck;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxPublisher {
    private static final String STREAM_NAME = "ECLASSROOM_EVENTS";

    private final JdbcTemplate jdbc;
    private final JetStream jetStream;

    public OutboxPublisher(JdbcTemplate jdbc, JetStream jetStream) {
        this.jdbc = jdbc;
        this.jetStream = jetStream;
    }

    @Scheduled(fixedDelayString = "${app.outbox.delay-ms:500}")
    @Transactional
    public void publish() {
        List<Row> rows = jdbc.query(
                "SELECT id,event_type,event_version,payload::text payload,attempt_count FROM integration.outbox_events " +
                        "WHERE published_at IS NULL AND next_attempt_at<=NOW() ORDER BY occurred_at LIMIT 100 FOR UPDATE SKIP LOCKED",
                (rs, i) -> new Row(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getString("payload"),
                        rs.getInt("attempt_count")));

        for (Row row : rows) {
            try {
                String subject = "eclassroom." + row.type() + ".v" + row.version();
                PublishOptions options = PublishOptions.builder()
                        .expectedStream(STREAM_NAME)
                        .messageId(row.id().toString())
                        .build();
                PublishAck ack = jetStream.publish(
                        subject,
                        row.payload().getBytes(StandardCharsets.UTF_8),
                        options);
                if (!STREAM_NAME.equals(ack.getStream())) {
                    throw new IllegalStateException("Unexpected JetStream acknowledgement from " + ack.getStream());
                }
                jdbc.update(
                        "UPDATE integration.outbox_events SET published_at=NOW(),last_error=NULL WHERE id=?",
                        row.id());
            } catch (Exception e) {
                int delaySeconds = retryDelaySeconds(row.attemptCount() + 1);
                jdbc.update(
                        "UPDATE integration.outbox_events SET attempt_count=attempt_count+1," +
                                "next_attempt_at=NOW()+(? * INTERVAL '1 second'),last_error=? WHERE id=?",
                        delaySeconds,
                        truncate(e.getMessage(), 2000),
                        row.id());
            }
        }
    }

    static int retryDelaySeconds(int attempt) {
        if (attempt <= 1) return 1;
        if (attempt >= 9) return 300;
        return Math.min(300, 1 << (attempt - 1));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return "unknown publish error";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private record Row(UUID id, String type, int version, String payload, int attemptCount) {}
}
