package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/Loccao102/E-Classroom/services/realtime-gateway/internal/auth"
	"github.com/Loccao102/E-Classroom/services/realtime-gateway/internal/realtime"
	"github.com/gin-gonic/gin"
	"github.com/gorilla/websocket"
	"github.com/nats-io/nats.go"
	"github.com/redis/go-redis/v9"
)

func main() {
	slog.SetDefault(slog.New(slog.NewJSONHandler(os.Stdout, nil)))
	if env("GIN_MODE", "release") == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	secret := mustSecret("APP_JWT_SECRET")
	issuer := env("APP_JWT_ISSUER", "eclassroom")
	instanceID := env("INSTANCE_ID", hostName())

	rdb := redis.NewClient(&redis.Options{Addr: env("REDIS_ADDR", "localhost:6379")})
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	if err := rdb.Ping(ctx).Err(); err != nil {
		cancel()
		slog.Error("redis connection failed", "error", err)
		os.Exit(1)
	}
	cancel()

	nc, err := nats.Connect(
		env("NATS_URL", nats.DefaultURL),
		nats.Name("eclassroom-realtime-"+instanceID),
		nats.MaxReconnects(-1),
		nats.ReconnectWait(time.Second),
	)
	if err != nil {
		slog.Error("nats connection failed", "error", err)
		os.Exit(1)
	}

	hub := realtime.NewHub(rdb, instanceID)
	if _, err := nc.Subscribe("eclassroom.>", func(m *nats.Msg) {
		recipients := realtime.ParseRecipients(m.Data)
		if len(recipients) > 0 {
			hub.Deliver(recipients, m.Data)
		}
	}); err != nil {
		slog.Error("nats subscribe failed", "error", err)
		os.Exit(1)
	}
	if err := nc.FlushTimeout(3 * time.Second); err != nil {
		slog.Error("nats flush failed", "error", err)
		os.Exit(1)
	}

	allowed := allowedOrigins(env("ALLOWED_ORIGINS", "http://localhost:3000,http://localhost:5173"))
	upgrader := websocket.Upgrader{
		ReadBufferSize:   4096,
		WriteBufferSize:  4096,
		HandshakeTimeout: 5 * time.Second,
		CheckOrigin: func(r *http.Request) bool {
			return originAllowed(r.Header.Get("Origin"), allowed)
		},
	}

	router := gin.New()
	router.Use(gin.Recovery(), requestLogger())
	router.GET("/live", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok", "connections": hub.Count()})
	})
	router.GET("/ready", func(c *gin.Context) {
		ctx, cancel := context.WithTimeout(c.Request.Context(), time.Second)
		defer cancel()
		if nc.Status() != nats.CONNECTED || rdb.Ping(ctx).Err() != nil {
			c.JSON(http.StatusServiceUnavailable, gin.H{"status": "not_ready"})
			return
		}
		c.JSON(http.StatusOK, gin.H{"status": "ready", "connections": hub.Count()})
	})
	router.GET("/realtime/v1/ws", func(c *gin.Context) {
		token := c.Query("access_token")
		if token == "" {
			token = strings.TrimPrefix(c.GetHeader("Authorization"), "Bearer ")
		}
		claims, err := auth.Verify(token, secret, issuer)
		if err != nil {
			c.JSON(http.StatusUnauthorized, gin.H{"error": "invalid_token"})
			return
		}
		conn, err := upgrader.Upgrade(c.Writer, c.Request, nil)
		if err != nil {
			return
		}
		client := realtime.NewClient(time.Now().UTC().Format("20060102T150405.000000000"), claims.Subject, conn)
		hub.Register(client)
		slog.Info("ws connected", "user_id", claims.Subject, "client_id", client.ID)
		realtime.ReadLoop(hub, client)
	})

	server := &http.Server{
		Addr:              ":" + env("PORT", "8090"),
		Handler:           router,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	errs := make(chan error, 1)
	go func() {
		slog.Info("realtime gateway listening", "addr", server.Addr)
		errs <- server.ListenAndServe()
	}()

	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	select {
	case sig := <-signals:
		slog.Info("shutdown", "signal", sig.String())
	case err := <-errs:
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			slog.Error("server failed", "error", err)
		}
	}

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer shutdownCancel()
	_ = server.Shutdown(shutdownCtx)
	hub.CloseAll()
	_ = nc.Drain()
	_ = rdb.Close()
	slog.Info("realtime gateway stopped")
}

func requestLogger() gin.HandlerFunc {
	return func(c *gin.Context) {
		start := time.Now()
		c.Next()
		slog.Info(
			"http",
			"method", c.Request.Method,
			"path", c.Request.URL.Path,
			"status", c.Writer.Status(),
			"duration_ms", time.Since(start).Milliseconds(),
		)
	}
}

func env(key, fallback string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return fallback
}

func mustSecret(key string) string {
	value := os.Getenv(key)
	if len(value) < 32 {
		panic(key + " must be at least 32 bytes")
	}
	return value
}

func hostName() string {
	value, _ := os.Hostname()
	if value == "" {
		return "gateway"
	}
	return value
}

func allowedOrigins(raw string) map[string]struct{} {
	out := map[string]struct{}{}
	for _, value := range strings.Split(raw, ",") {
		out[strings.TrimSpace(value)] = struct{}{}
	}
	return out
}

func originAllowed(origin string, allowed map[string]struct{}) bool {
	if _, ok := allowed["*"]; ok {
		return true
	}
	_, ok := allowed[origin]
	return ok
}
