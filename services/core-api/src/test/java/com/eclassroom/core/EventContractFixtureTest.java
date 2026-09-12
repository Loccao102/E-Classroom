package com.eclassroom.core;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventContractFixtureTest {
    @Test
    void javaAndGoShareAttendanceChangedV1Fixture() throws Exception {
        Path fixture = Path.of("../../contracts/events/student-attendance-changed-v1.json");
        assertTrue(Files.exists(fixture), "shared event fixture must exist at repository contracts path");

        @SuppressWarnings("unchecked")
        Map<String, Object> event = JsonMapper.builder().build()
                .readValue(Files.readString(fixture), Map.class);

        assertEquals("student.attendance.changed", event.get("eventType"));
        assertEquals(1, ((Number) event.get("eventVersion")).intValue());
        assertEquals("request-2026-09-13-demo", event.get("correlationId"));
        assertTrue(event.containsKey("eventId"));
        assertTrue(event.containsKey("recipients"));
        assertTrue(event.containsKey("data"));
    }
}
