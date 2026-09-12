package events

import (
	"os"
	"testing"
	"time"
)

func TestParseAttendanceContractFixture(t *testing.T) {
	payload, err := os.ReadFile("../../../../contracts/events/student-attendance-changed-v1.json")
	if err != nil {
		t.Fatal(err)
	}
	envelope, err := ParseEnvelope(payload)
	if err != nil {
		t.Fatal(err)
	}
	if envelope.EventID != "9b6db32a-b7a5-4e63-bb66-747b78ea7af1" {
		t.Fatalf("unexpected event id %q", envelope.EventID)
	}
	if envelope.EventType != "student.attendance.changed" {
		t.Fatalf("unexpected event type %q", envelope.EventType)
	}
	if len(envelope.Recipients) != 1 || envelope.Recipients[0] != "20ce4bad-f6ab-48ac-988a-c94b5940cf77" {
		t.Fatalf("unexpected recipients %#v", envelope.Recipients)
	}
}

func TestParseEnvelopeRejectsMissingIdentity(t *testing.T) {
	if _, err := ParseEnvelope([]byte(`{"recipients":["u1"]}`)); err == nil {
		t.Fatal("expected validation error")
	}
}

func TestRetryDelayIsBounded(t *testing.T) {
	cases := []struct {
		attempt uint64
		want    time.Duration
	}{
		{1, time.Second},
		{2, 5 * time.Second},
		{3, 15 * time.Second},
		{4, 30 * time.Second},
		{99, 30 * time.Second},
	}
	for _, tc := range cases {
		if got := RetryDelay(tc.attempt); got != tc.want {
			t.Fatalf("attempt %d: got %s want %s", tc.attempt, got, tc.want)
		}
	}
}

func TestDeadLetterIDIsDeterministicAndReasonScoped(t *testing.T) {
	payload := []byte(`{"eventId":"evt-1","eventType":"student.attendance.changed"}`)
	first := deadLetterID("fanout_failed", payload)
	second := deadLetterID("fanout_failed", payload)
	otherReason := deadLetterID("invalid_event", payload)
	if first != second {
		t.Fatalf("dead letter id must be deterministic: %q != %q", first, second)
	}
	if first == otherReason {
		t.Fatal("dead letter id must include failure reason")
	}
	if len(first) != 68 || first[:4] != "dlq-" {
		t.Fatalf("unexpected dead letter id format %q", first)
	}
}
