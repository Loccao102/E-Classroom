package realtime

import (
	"context"
	"encoding/json"
	"log/slog"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"
)

type Client struct {
	ID     string
	UserID string
	Conn   *websocket.Conn
	Send   chan []byte
	done   chan struct{}
}

type Hub struct {
	mu         sync.RWMutex
	clients    map[string]map[*Client]struct{}
	redis      *redis.Client
	instanceID string
}

func NewHub(rdb *redis.Client, instanceID string) *Hub {
	return &Hub{clients: map[string]map[*Client]struct{}{}, redis: rdb, instanceID: instanceID}
}

func NewClient(id, userID string, conn *websocket.Conn) *Client {
	return &Client{ID: id, UserID: userID, Conn: conn, Send: make(chan []byte, 64), done: make(chan struct{})}
}

func (h *Hub) Register(c *Client) {
	h.mu.Lock()
	if h.clients[c.UserID] == nil {
		h.clients[c.UserID] = map[*Client]struct{}{}
	}
	h.clients[c.UserID][c] = struct{}{}
	h.mu.Unlock()

	h.setPresence(c)
	go h.writeLoop(c)
	go h.presenceLoop(c)
}

func (h *Hub) Unregister(c *Client) {
	h.mu.Lock()
	if set := h.clients[c.UserID]; set != nil {
		if _, ok := set[c]; ok {
			delete(set, c)
			close(c.Send)
			close(c.done)
		}
		if len(set) == 0 {
			delete(h.clients, c.UserID)
		}
	}
	h.mu.Unlock()

	if h.redis != nil {
		_ = h.redis.Del(context.Background(), h.presenceKey(c)).Err()
	}
	_ = c.Conn.Close()
}

func (h *Hub) Deliver(recipients []string, payload []byte) {
	h.mu.RLock()
	defer h.mu.RUnlock()
	for _, userID := range recipients {
		for c := range h.clients[userID] {
			select {
			case c.Send <- payload:
			default:
				go h.Unregister(c)
			}
		}
	}
}

func (h *Hub) Count() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	count := 0
	for _, set := range h.clients {
		count += len(set)
	}
	return count
}

func (h *Hub) CloseAll() {
	h.mu.RLock()
	all := make([]*Client, 0)
	for _, set := range h.clients {
		for c := range set {
			all = append(all, c)
		}
	}
	h.mu.RUnlock()
	for _, c := range all {
		h.Unregister(c)
	}
}

func (h *Hub) presenceKey(c *Client) string {
	return "eclassroom:presence:" + c.UserID + ":" + c.ID
}

func (h *Hub) setPresence(c *Client) {
	if h.redis != nil {
		_ = h.redis.Set(context.Background(), h.presenceKey(c), h.instanceID, 90*time.Second).Err()
	}
}

func (h *Hub) presenceLoop(c *Client) {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-c.done:
			return
		case <-ticker.C:
			h.setPresence(c)
		}
	}
}

func (h *Hub) writeLoop(c *Client) {
	ticker := time.NewTicker(25 * time.Second)
	defer ticker.Stop()
	defer h.Unregister(c)

	for {
		select {
		case payload, ok := <-c.Send:
			_ = c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if !ok {
				_ = c.Conn.WriteMessage(websocket.CloseMessage, []byte{})
				return
			}
			if err := c.Conn.WriteMessage(websocket.TextMessage, payload); err != nil {
				return
			}
		case <-ticker.C:
			_ = c.Conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
			if err := c.Conn.WriteMessage(websocket.PingMessage, nil); err != nil {
				return
			}
		}
	}
}

func ReadLoop(h *Hub, c *Client) {
	defer h.Unregister(c)
	c.Conn.SetReadLimit(64 * 1024)
	_ = c.Conn.SetReadDeadline(time.Now().Add(70 * time.Second))
	c.Conn.SetPongHandler(func(string) error {
		_ = c.Conn.SetReadDeadline(time.Now().Add(70 * time.Second))
		return nil
	})
	for {
		if _, _, err := c.Conn.ReadMessage(); err != nil {
			return
		}
	}
}

type Envelope struct {
	EventID    string   `json:"eventId"`
	EventType  string   `json:"eventType"`
	Recipients []string `json:"recipients"`
}

func ParseRecipients(payload []byte) []string {
	var envelope Envelope
	if err := json.Unmarshal(payload, &envelope); err != nil {
		slog.Warn("invalid event envelope", "error", err)
		return nil
	}
	return envelope.Recipients
}
