package com.eclassroom.core.integration;

import io.nats.client.Connection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Component
public class OutboxPublisher {
    private final JdbcTemplate jdbc; private final Connection nats;
    public OutboxPublisher(JdbcTemplate jdbc, Connection nats) { this.jdbc=jdbc; this.nats=nats; }
    @Scheduled(fixedDelayString = "${app.outbox.delay-ms:500}") @Transactional
    public void publish() throws Exception {
        List<Row> rows=jdbc.query("SELECT id,event_type,event_version,payload::text payload FROM integration.outbox_events WHERE published_at IS NULL AND next_attempt_at<=NOW() ORDER BY occurred_at LIMIT 100 FOR UPDATE SKIP LOCKED",
                (rs,i)->new Row(UUID.fromString(rs.getString("id")),rs.getString("event_type"),rs.getInt("event_version"),rs.getString("payload")));
        for (Row r:rows) {
            try { nats.publish("eclassroom."+r.type()+".v"+r.version(),r.payload().getBytes(StandardCharsets.UTF_8)); nats.flush(Duration.ofSeconds(2)); jdbc.update("UPDATE integration.outbox_events SET published_at=NOW() WHERE id=?",r.id()); }
            catch (Exception e) { jdbc.update("UPDATE integration.outbox_events SET attempt_count=attempt_count+1,next_attempt_at=NOW()+INTERVAL '5 seconds',last_error=? WHERE id=?",e.getMessage(),r.id()); }
        }
    }
    private record Row(UUID id,String type,int version,String payload) {}
}
