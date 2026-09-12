FROM golang:1.27-alpine AS build
WORKDIR /workspace

COPY go.mod go.sum ./
RUN go mod download && go mod verify

COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/realtime-gateway ./cmd/server

FROM alpine:3.22
RUN addgroup -S eclassroom && adduser -S -G eclassroom -u 10001 eclassroom
WORKDIR /app
COPY --from=build /out/realtime-gateway /app/realtime-gateway

USER eclassroom
EXPOSE 8090
ENTRYPOINT ["/app/realtime-gateway"]
