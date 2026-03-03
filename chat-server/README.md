# Chat Server: Netty WebSocket IM with NeuroDB Backend

![Build Status](https://img.shields.io/badge/build-passing-success)
![Java Version](https://img.shields.io/badge/java-17-blue)
![Architecture](https://img.shields.io/badge/arch-Netty%20%2B%20NeuroDB-blueviolet)
![Protocol](https://img.shields.io/badge/protocol-WebSocket%20JSON-orange)
![License](https://img.shields.io/badge/license-MIT-green)

**Chat Server** 是基于 **Netty** 的 WebSocket 即时通讯服务端，面向公共大厅与私信场景。消息持久化依托 **NeuroDB**（LSM-Tree 存储引擎），采用 **组合键 + 写扩散** 架构，将同步从全表扫描降维为按信箱前缀的 $O(\log N)$ 范围扫描，单机可承载十万级用户的海量历史记录并保持亚毫秒级同步延迟。

> **架构要点**：时间线按「信箱」物理隔离（PUBLIC / INBOX），前端游标分离（`syncCursors.PUBLIC` / `syncCursors.INBOX`），避免时间线跳跃；私信写扩散至发件人/收件人双信箱，统一收件箱一次 Sync 拉取全部私信。

---

## Key Features

### 1. 存储层：组合键与写扩散（NeuroDB）
* **TimelineKeyUtil**：64 位组合键 `[OwnerID 22bit | Timestamp 42bit]`，同一信箱内消息在 LSM-Tree 中物理连续。
* **写扩散 (Write-Fanout)**：公共消息写入 Owner=0 信箱；私信同时写入发件人信箱与收件人信箱，收件方一次 `target: "INBOX"` Sync 即可拉取全部私信。
* **O(log N) Sync**：`handleSync` 按 `target`（PUBLIC/INBOX）仅扫描对应信箱的 key 范围，无全表 Scan、无应用层 `isSender/isReceiver` 过滤。

### 2. 传输与认证
* **WebSocket (JSON)**：首包必须为 `auth`（携带 JWT），通过后注册 Channel；支持 `chat`、`sync`、`RECALL`、`typing` 等。
* **JWT + BCrypt**：登录/注册签发 JWT，密码 BCrypt 存储；用户信息与管理员审批状态存 NeuroDB。
* **HTTP API**：同端口提供 `/api/login`、`/api/register`、`/api/upload`、`/api/admin/*`、`/online`；静态页 `index.html`、`register.html`、`admin.html`。

### 3. 多节点与扩展
* **Redis Pub/Sub**：可选；配置 `REDIS_HOST` 后启用跨节点消息/撤回/输入状态广播，未配置则为单机内存广播。
* **统一收件箱**：前端对 INBOX 消息按 `senderId`/`receiverId` 分组展示「最近联系人」，点击会话仅做前端过滤，无额外请求。

---

## Quick Start

### 1. 启动 NeuroDB（前置依赖）
Chat Server 通过 HTTP 访问 NeuroDB，需先启动 NeuroDB 服务（默认 HTTP :8080）。

```bash
# 在 neurodb 项目根目录
go run cmd/server/main.go
```

### 2. 启动 Chat Server
默认 WebSocket 端口 **9091**，HTTP 登录/注册/静态资源同端口。

```bash
cd chat-server
source ../env.sh   # 可选：设置 JAVA_HOME
./mvnw compile exec:java -Dexec.mainClass="com.chat.ChatServerMain"

# 或先打包再运行
./mvnw package -q && java -jar target/chat-server-1.0.0-SNAPSHOT.jar
```

### 3. 环境变量（可选）
| 变量 | 说明 | 默认 |
|------|------|------|
| `WEBSOCKET_PORT` | WebSocket/HTTP 监听端口 | 9091 |
| `REDIS_HOST` | Redis 地址，空则单机模式 | 空 |
| `REDIS_PORT` | Redis 端口 | 6379 |

NeuroDB 地址在代码中为 `AppConfig.neuroDbHttpUrl`（默认 `http://127.0.0.1:8080`），可按需扩展为环境变量或配置文件。

### 4. 打开前端
浏览器访问：`http://localhost:9091`（或你配置的端口）
* **登录/注册**：默认会初始化用户 A/B/C（密码 passA / passB / passC）及管理员 admin/admin123。
* **公共大厅**：登录后默认进入，Sync 使用 `target: "PUBLIC"`。
* **私信**：点击消息发送者发起私聊，Sync 使用 `target: "INBOX"`，游标与大厅分离。
* **管理后台**：`/admin.html`，审批注册用户等。

---

## Configuration

配置集中在 `com.chat.config.AppConfig`，可通过代码或后续扩展的配置文件/环境变量覆盖。

| 配置项 | 说明 | 默认 |
|--------|------|------|
| `neuroDbHttpUrl` | NeuroDB HTTP API 地址 | http://127.0.0.1:8080 |
| `websocketPort` | 本机监听端口 | 9091 |
| `jwtSecret` | JWT 签名密钥 | （需在生产环境修改） |
| `jwtExpirationMs` | JWT 过期时间 | 7 天 |
| `redisHost` / `redisPort` | Redis 地址与端口，空则禁用 | 空 / 6379 |
| `uploadDir` | 图片上传目录，GET /files/xxx 从此提供 | upload |
| `adminUsername` / `adminPassword` | 首次创建的管理员账号 | admin / admin123 |

---

## Protocol Reference (WebSocket JSON)

### 客户端 → 服务端
* **auth**：`{ "type": "auth", "token": "<JWT>" }` — 首包必发，通过后才能发其他包。
* **chat**：`{ "type": "chat", "content": "...", "receiverId": ""|"PUBLIC"|"userId", "localId": "...", "mentions": [], "replyToId", "replyToUser", "replyToContent", "msgType": "text"|"image" }`
* **sync**：`{ "type": "sync", "target": "PUBLIC"|"INBOX", "lastTimestamp": 0 }` — 按信箱游标拉取增量。
* **RECALL**：`{ "type": "RECALL", "messageId": "<long 组合键>" }`
* **typing**：`{ "type": "typing", "status": true|false, "receiverId": ""|"userId" }`

### 服务端 → 客户端
* **auth_ok** / **auth_fail**：认证结果。
* **ack**：`{ "type": "ack", "localId", "messageId" }` — 消息落库成功。
* **chat**：广播或单推的消息体（与客户端 chat 字段一致）。
* **sync_result**：`{ "type": "sync_result", "target": "PUBLIC"|"INBOX", "messages": [...] }` — 带 `target` 便于前端更新对应游标。
* **RECALL** / **typing** / **error**：撤回、输入状态、错误信息。

---

## Architecture

```text
[ Browser: index.html ]
       |
       | HTTP: /api/login, /api/register, /files/*   WebSocket: /ws
       v
[ Netty: HTTP + WebSocket ]
       |
       +---> [ AuthService + JwtUtil ]  用户/管理员存 NeuroDB
       |
       +---> [ ChatWebSocketHandler ]
       |         |
       |         +---> [ TimelineKeyUtil ]  buildKey(ownerId, ts) / userIdToOwnerId
       |         |
       |         +---> [ NeuroDbClient ]     Put / Scan(startKey, endKey) 按信箱前缀
       |         |
       |         +---> [ RedisChatBus ]      可选，跨节点 Pub/Sub
       |         |
       |         +---> [ ChannelRegistry ]   userId -> Channel 本机广播
       v
[ NeuroDB (LSM-Tree) ]
   PUBLIC 信箱 key ∈ [buildKey(0,0), buildKey(0,∞)]
   INBOX  信箱 key ∈ [buildKey(ownerId,0), buildKey(ownerId,∞)]
```

---

## Project Structure

```text
chat-server/
├── src/main/java/com/chat/
│   ├── ChatServerMain.java          # 入口：NeuroDB 客户端、Auth、Netty、Redis
│   ├── config/
│   │   └── AppConfig.java           # 端口、JWT、NeuroDB URL、Redis、上传目录
│   ├── auth/
│   │   ├── AuthService.java        # 登录/注册/管理员/用户存储
│   │   └── JwtUtil.java            # JWT 签发与校验
│   ├── netty/
│   │   ├── ChatServerInitializer.java
│   │   ├── ChatWebSocketHandler.java   # auth/chat/sync/RECALL/typing
│   │   ├── ChannelRegistry.java
│   │   ├── HttpStaticHandler.java     # 静态页与 /files/*
│   │   └── HttpLoginHandler.java      # /api/login, /api/register 等
│   ├── neurodb/
│   │   └── NeuroDbClient.java      # HTTP 封装 Put/Get/Delete/Scan
│   ├── redis/
│   │   ├── RedisChatBus.java       # Pub/Sub 广播
│   │   └── RedisChatSubscriber.java
│   ├── protocol/                   # 各类 Packet DTO
│   ├── model/                      # Message, User
│   └── util/
│       └── TimelineKeyUtil.java    # 组合键与 ownerId 映射
├── src/main/resources/
│   ├── static/
│   │   ├── index.html              # 主聊天界面（游标隔离 syncCursors）
│   │   ├── register.html
│   │   └── admin.html
│   └── logback.xml
├── pom.xml
├── env.sh                          # JAVA_HOME 等
└── README.md
```

---

## License

MIT License. Copyright (c) 2026 HowieSun.
