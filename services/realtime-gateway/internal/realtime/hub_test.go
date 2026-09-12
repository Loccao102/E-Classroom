package realtime

import (
	"testing"
	"time"
)

func TestParseRecipients(t *testing.T) {
	payload := []byte(`{"eventId":"1","eventType":"notification.created","recipients":["u1","u2"]}`)
	got := ParseRecipients(payload)
	if len(got) != 2 || got[0] != "u1" {
		t.Fatalf("unexpected recipients %#v", got)
	}
}

func TestDeliverTargetsOnlyRecipientUser(t *testing.T) {
	hub := NewHub(nil, "test-instance")
	guardian := &Client{ID: "g-1", UserID: "guardian-1", Send: make(chan []byte, 1), done: make(chan struct{})}
	other := &Client{ID: "o-1", UserID: "guardian-2", Send: make(chan []byte, 1), done: make(chan struct{})}
	hub.clients[guardian.UserID] = map[*Client]struct{}{guardian: {}}
	hub.clients[other.UserID] = map[*Client]struct{}{other: {}}

	payload := []byte(`{"eventId":"evt-1","eventType":"student.attendance.changed","recipients":["guardian-1"]}`)
	hub.Deliver([]string{"guardian-1"}, payload)

	select {
	case got := <-guardian.Send:
		if string(got) != string(payload) {
			t.Fatalf("unexpected guardian payload %s", got)
		}
	case <-time.After(100 * time.Millisecond):
		t.Fatal("target guardian did not receive payload")
	}

	select {
	case got := <-other.Send:
		t.Fatalf("non-recipient received payload %s", got)
	default:
	}
}
