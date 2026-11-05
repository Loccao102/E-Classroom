package realtime

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
)

func websocketForHubTest(t *testing.T) *websocket.Conn {
	t.Helper()
	ready := make(chan *websocket.Conn, 1)
	stop := make(chan struct{})
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := (&websocket.Upgrader{}).Upgrade(w, r, nil)
		if err != nil {
			return
		}
		ready <- conn
		<-stop
		_ = conn.Close()
	}))
	t.Cleanup(func() {
		close(stop)
		server.Close()
	})
	url := "ws" + strings.TrimPrefix(server.URL, "http")
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatalf("websocket dial: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	select {
	case <-ready:
	case <-time.After(time.Second):
		t.Fatal("test websocket was not accepted")
	}
	return conn
}

func TestHubActiveConnectionCountAndIdempotentUnregister(t *testing.T) {
	hub := NewHub(nil, "count-test")
	first := NewClient("first", "guardian", websocketForHubTest(t))
	second := NewClient("second", "guardian", websocketForHubTest(t))

	hub.Register(first)
	hub.Register(first) // Must not launch a second writer or increment twice.
	if count := hub.Count(); count != 1 {
		t.Fatalf("registered duplicate client: expected 1, got %d", count)
	}
	hub.Register(second)
	if count := hub.Count(); count != 2 {
		t.Fatalf("expected 2 connections for one guardian, got %d", count)
	}
	hub.Unregister(first)
	hub.Unregister(first) // Concurrent reader/writer cleanup must be harmless.
	if count := hub.Count(); count != 1 {
		t.Fatalf("expected 1 remaining connection, got %d", count)
	}
	hub.Unregister(second)
	if count := hub.Count(); count != 0 {
		t.Fatalf("expected 0 remaining connections, got %d", count)
	}
}

func TestSlowClientScheduledCleanupIsIdempotent(t *testing.T) {
	hub := NewHub(nil, "slow-test")
	client := &Client{
		ID: "slow", UserID: "guardian", Send: make(chan []byte, 1), done: make(chan struct{}),
	}
	hub.clients[client.UserID] = map[*Client]struct{}{client: {}}
	hub.active = 1
	hub.Deliver([]string{"guardian"}, []byte("first"))
	for i := 0; i < 100; i++ {
		hub.Deliver([]string{"guardian"}, []byte("overflow"))
	}
	select {
	case <-client.done:
	case <-time.After(time.Second):
		t.Fatal("full client channel was never disconnected")
	}
	if !client.dropPending.Load() {
		t.Fatal("slow client cleanup was never scheduled")
	}
	if count := hub.Count(); count != 0 {
		t.Fatalf("expected no active connections after slow cleanup, got %d", count)
	}
	hub.Unregister(client)
}
