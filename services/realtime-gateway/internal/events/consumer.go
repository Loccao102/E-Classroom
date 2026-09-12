package events

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/nats-io/nats.go"
	"github.com/redis/go-redis/v9"
)

const (
	StreamName          = "ECLASSROOM_EVENTS"
	DurableName         = "realtime-gateway-v1"
	QueueGroup          = "realtime-gateway"
	EventSubject        = "eclassroom.>"
	FanoutSubject       = "_eclassroom.realtime.delivery"
	DeadLetterSubject   = "_eclassroom.dlq"
	processingTTL       = 60 * time.Second
	completedTTL        = 7 * 24 * time.Hour
	maxDeliveryAttempts = uint64(5)
)

type Envelope struct {
	EventID    string   `json:"eventId"`
	EventType  string   `json:"eventType"`
	Recipients []string `json:"recipients"`
}

type Metrics struct {
	processed   atomic.Uint64
	duplicates  atomic.Uint64
	retries     atomic.Uint64
	deadLetters atomic.Uint64
	fanouts     atomic.Uint64
}

func (m *Metrics) Snapshot() map[string]uint64 {
	return map[string]uint64{
		"processed":    m.processed.Load(),
		"duplicates":   m.duplicates.Load(),
		"retries":      m.retries.Load(),
		"dead_letters": m.deadLetters.Load(),
		"fanouts":      m.fanouts.Load(),
	}
}

type Consumer struct {
	nc      *nats.Conn
	js      nats.JetStreamContext
	redis   *redis.Client
	metrics *Metrics
}

func NewConsumer(nc *nats.Conn, rdb *redis.Client, metrics *Metrics) (*Consumer, error) {
	js, err := nc.JetStream()
	if err != nil {
		return nil, fmt.Errorf("create jetstream context: %w", err)
	}
	if metrics == nil {
		metrics = &Metrics{}
	}
	return &Consumer{nc: nc, js: js, redis: rdb, metrics: metrics}, nil
}

func (c *Consumer) EnsureStream() error {
	if _, err := c.js.StreamInfo(StreamName); err == nil {
		return nil
	} else if !errors.Is(err, nats.ErrStreamNotFound) {
		return fmt.Errorf("inspect jetstream stream: %w", err)
	}

	_, err := c.js.AddStream(&nats.StreamConfig{
		Name:      StreamName,
		Subjects:  []string{EventSubject},
		Retention: nats.LimitsPolicy,
		Storage:   nats.FileStorage,
		MaxAge:    14 * 24 * time.Hour,
		Discard:   nats.DiscardOld,
	})
	if err != nil {
		return fmt.Errorf("create jetstream stream: %w", err)
	}
	return nil
}

func (c *Consumer) Subscribe() (*nats.Subscription, error) {
	sub, err := c.js.QueueSubscribe(
		EventSubject,
		QueueGroup,
		c.handle,
		nats.Durable(DurableName),
		nats.ManualAck(),
		nats.AckExplicit(),
		nats.AckWait(30*time.Second),
		nats.MaxDeliver(int(maxDeliveryAttempts)),
		nats.DeliverAll(),
	)
	if err != nil {
		return nil, fmt.Errorf("subscribe durable jetstream consumer: %w", err)
	}
	return sub, nil
}

func (c *Consumer) handle(msg *nats.Msg) {
	envelope, err := ParseEnvelope(msg.Data)
	if err != nil {
		c.deadLetter(msg, "invalid_event_envelope", err)
		return
	}

	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()

	state, err := c.claim(ctx, envelope.EventID)
	if err != nil {
		c.retryOrDeadLetter(msg, envelope.EventID, "inbox_claim_failed", err)
		return
	}
	switch state {
	case claimDone:
		c.metrics.duplicates.Add(1)
		_ = msg.Ack()
		return
	case claimBusy:
		c.metrics.retries.Add(1)
		_ = msg.NakWithDelay(2 * time.Second)
		return
	}

	if err := c.nc.Publish(FanoutSubject, msg.Data); err != nil {
		_ = c.release(ctx, envelope.EventID)
		c.retryOrDeadLetter(msg, envelope.EventID, "fanout_publish_failed", err)
		return
	}
	if err := c.nc.FlushTimeout(2 * time.Second); err != nil {
		_ = c.release(ctx, envelope.EventID)
		c.retryOrDeadLetter(msg, envelope.EventID, "fanout_flush_failed", err)
		return
	}
	c.metrics.fanouts.Add(1)

	if err := c.complete(ctx, envelope.EventID); err != nil {
		c.retryOrDeadLetter(msg, envelope.EventID, "inbox_complete_failed", err)
		return
	}
	c.metrics.processed.Add(1)
	if err := msg.Ack(); err != nil {
		slog.Warn("jetstream ack failed", "event_id", envelope.EventID, "error", err)
	}
}

func ParseEnvelope(payload []byte) (Envelope, error) {
	var envelope Envelope
	if err := json.Unmarshal(payload, &envelope); err != nil {
		return Envelope{}, fmt.Errorf("decode event: %w", err)
	}
	if envelope.EventID == "" || envelope.EventType == "" {
		return Envelope{}, errors.New("eventId and eventType are required")
	}
	return envelope, nil
}

type claimState int

const (
	claimNew claimState = iota
	claimBusy
	claimDone
)

func (c *Consumer) claim(ctx context.Context, eventID string) (claimState, error) {
	key := inboxKey(eventID)
	claimed, err := c.redis.SetNX(ctx, key, "processing", processingTTL).Result()
	if err != nil {
		return claimBusy, err
	}
	if claimed {
		return claimNew, nil
	}
	value, err := c.redis.Get(ctx, key).Result()
	if errors.Is(err, redis.Nil) {
		return claimBusy, nil
	}
	if err != nil {
		return claimBusy, err
	}
	if value == "done" {
		return claimDone, nil
	}
	return claimBusy, nil
}

func (c *Consumer) complete(ctx context.Context, eventID string) error {
	return c.redis.Set(ctx, inboxKey(eventID), "done", completedTTL).Err()
}

func (c *Consumer) release(ctx context.Context, eventID string) error {
	return c.redis.Del(ctx, inboxKey(eventID)).Err()
}

func (c *Consumer) retryOrDeadLetter(msg *nats.Msg, eventID, reason string, cause error) {
	attempt := deliveryAttempt(msg)
	if attempt >= maxDeliveryAttempts {
		c.deadLetter(msg, reason, cause)
		return
	}
	c.metrics.retries.Add(1)
	delay := RetryDelay(attempt)
	slog.Warn("jetstream event retry", "event_id", eventID, "reason", reason, "attempt", attempt, "delay", delay, "error", cause)
	_ = msg.NakWithDelay(delay)
}

func (c *Consumer) deadLetter(msg *nats.Msg, reason string, cause error) {
	c.metrics.deadLetters.Add(1)
	headers := nats.Header{}
	headers.Set("X-E-Classroom-DLQ-Reason", reason)
	if cause != nil {
		headers.Set("X-E-Classroom-DLQ-Error", cause.Error())
	}
	dlq := &nats.Msg{Subject: DeadLetterSubject, Header: headers, Data: msg.Data}
	if err := c.nc.PublishMsg(dlq); err != nil {
		slog.Error("dead-letter publish failed", "reason", reason, "error", err)
		_ = msg.NakWithDelay(30 * time.Second)
		return
	}
	if err := c.nc.FlushTimeout(2 * time.Second); err != nil {
		slog.Error("dead-letter flush failed", "reason", reason, "error", err)
		_ = msg.NakWithDelay(30 * time.Second)
		return
	}
	slog.Error("event moved to dead letter", "reason", reason, "error", cause)
	_ = msg.Term()
}

func deliveryAttempt(msg *nats.Msg) uint64 {
	metadata, err := msg.Metadata()
	if err != nil || metadata == nil || metadata.NumDelivered == 0 {
		return 1
	}
	return metadata.NumDelivered
}

func RetryDelay(attempt uint64) time.Duration {
	switch {
	case attempt <= 1:
		return time.Second
	case attempt == 2:
		return 5 * time.Second
	case attempt == 3:
		return 15 * time.Second
	default:
		return 30 * time.Second
	}
}

func inboxKey(eventID string) string {
	return "eclassroom:inbox:" + eventID
}
