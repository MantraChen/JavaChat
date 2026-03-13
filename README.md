# Chat — Netty WebSocket IM with NeuroDB Backend

[![Java](https://img.shields.io/badge/Java-17+-blue)](https://openjdk.org/)
[![Vue](https://img.shields.io/badge/Vue-3-green)](https://vuejs.org/)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

A WebSocket-based chat server built with **Netty**, backed by **NeuroDB** (LSM-Tree storage). Supports public rooms and private messages, JWT auth, optional Redis pub/sub for multi-node broadcast, and a Vue 3 frontend.

**Disclaimer:** This is a **personal learning project**. The code is written for practicing architecture and implementation skills—features are added as needed. You are welcome to **clone, fork, and modify** it for your own use or study.

---

## Features

### Backend (Java / Netty)

- **Storage:** Composite keys and write-fanout over NeuroDB. Timeline keys `[OwnerID 22bit | Timestamp 42bit]` keep messages per “mailbox” (PUBLIC / INBOX) physically contiguous; sync is an O(log N) range scan per mailbox.
- **Transport & auth:** WebSocket (JSON). First frame must be `auth` with JWT; then `chat`, `sync`, `RECALL`, `typing`. HTTP on the same port: `/api/login`, `/api/register`, `/api/upload`, `/api/admin/*`, `/api/online`; static pages and `/files/*`.
- **Scale-out:** Optional Redis Pub/Sub for cross-node broadcast; without Redis, single-node in-memory broadcast.

### Frontend (Vue 3 + Vite)

- **Stack:** Vue 3, Pinia, Vite. Build output is served by the Java app from `/`.
- **UI:** Login, public room, private sessions (sidebar), message list, reply/quote, @mentions, typing indicator, image upload, recall. Theme toggle (light/dark).

---

## Quick Start

### Prerequisites

- **Java 17+** (for chat-server)
- **Node 18+** (for chat-client)
- **NeuroDB** (Go) — message storage backend
- **Redis** (optional) — for multi-node message broadcast

### 1. Start NeuroDB

Chat-server talks to NeuroDB over HTTP. Start NeuroDB first (default: `http://127.0.0.1:8080`).

```bash
cd neurodb
go run cmd/server/main.go
```

### 2. Start Chat Server

Default port: **9091** (HTTP + WebSocket).

```bash
cd chat-server
./mvnw compile exec:java -Dexec.mainClass="com.chat.ChatServerMain"

# Or package and run
./mvnw package -q && java -jar target/chat-server-1.0.0-SNAPSHOT.jar
```

### 3. Frontend (choose one)

**Option A — Use built frontend (after building once):**

```bash
cd chat-client
npm install
npm run build
```

Then open `http://localhost:9091`. The server serves the built assets from `chat-server/src/main/resources/static/`.

**Option B — Dev mode with hot reload:**

```bash
cd chat-client
npm install
npm run dev
```

Open `http://localhost:5173`. Vite proxies `/api`, `/ws`, and `/files` to the backend (default port 9091).

### 4. Environment variables (optional)

| Variable         | Description                    | Default |
|------------------|--------------------------------|---------|
| `WEBSOCKET_PORT` | HTTP/WebSocket listen port     | 9091    |
| `REDIS_HOST`     | Redis host; leave empty for single-node | (empty) |
| `REDIS_PORT`     | Redis port                     | 6379    |

NeuroDB URL is in `com.chat.config.AppConfig` (default `http://127.0.0.1:8080`).

### 5. Default accounts

- Users: **A** / passA, **B** / passB, **C** / passC (created on first run if missing)
- Admin: **admin** / admin123
- Register at `/register.html`; approve at `/admin.html`

---

## Project layout

```
chat/
├── chat-server/          # Java Netty backend
│   ├── src/main/java/com/chat/
│   │   ├── ChatServerMain.java
│   │   ├── config/       # AppConfig
│   │   ├── auth/         # AuthService, JwtUtil
│   │   ├── core/         # ProtocolConsts
│   │   ├── network/
│   │   │   ├── netty/    # Pipeline, ChannelRegistry, HttpStatic, ChatWebSocketHandler
│   │   │   ├── http/     # HttpDispatcherHandler, HttpLoginHandler
│   │   │   └── ws/       # MessageHandler implementations (chat, sync, recall, typing)
│   │   ├── service/      # MessageService
│   │   ├── neurodb/      # NeuroDbClient
│   │   ├── redis/        # RedisChatBus, RedisChatSubscriber
│   │   ├── protocol/     # Packet DTOs
│   │   └── model/       # Message, User
│   ├── src/main/resources/static/   # Built frontend + register.html, admin.html
│   └── pom.xml
├── chat-client/          # Vue 3 + Vite frontend
│   ├── src/
│   │   ├── components/   # LoginCard, Sidebar, ChatWindow, MessageBubble, ChatInput
│   │   ├── stores/       # auth, chat (Pinia)
│   │   ├── styles/
│   │   └── api.js
│   ├── index.html
│   └── package.json
├── neurodb/              # LSM-Tree storage (Go; see neurodb/README.md)
└── README.md
```

---

## WebSocket protocol (JSON)

**Client → Server**

- `auth`: `{ "type": "auth", "token": "<JWT>" }` — required first; then other frames allowed.
- `chat`: `{ "type": "chat", "content": "...", "receiverId": ""|"PUBLIC"|"userId", "localId": "...", "mentions": [], "replyToId", "replyToUser", "replyToContent", "msgType": "text"|"image" }`
- `sync`: `{ "type": "sync", "target": "PUBLIC"|"INBOX", "lastTimestamp": 0 }`
- `RECALL`: `{ "type": "RECALL", "messageId": "<long>" }`
- `typing`: `{ "type": "typing", "status": true|false, "receiverId": ""|"userId" }`

**Server → Client**

- `auth_ok` / `auth_fail`, `ack`, `chat`, `sync_result`, `RECALL`, `typing`, `error`

---

## License

MIT License. You may use, copy, and modify this project freely.
