# Chat Server（Java + Netty + NeuroDB）

## 从 int Key 迁移到 long Key 后历史不显示？

若在将 messageId/Key 从 int 改为 long 之后，**刷新页面仍无法显示历史记录**，通常是以下两种原因之一：

### 1. 历史脏数据残留（最常见）

修改前写入的消息 Key 可能已是**溢出后的负数**（如 -2147483648）。当前 sync 使用 `scan(0, Long.MAX_VALUE)`，只会返回 ≥0 的 Key，**负数 Key 永远不会被返回**。

**处理步骤：**

1. 停掉 NeuroDB 服务。
2. 进入 NeuroDB 配置的数据目录（一般为项目下的 `data/`）。
3. **删除整个 data 目录**，清空库。
4. 重新启动 NeuroDB，从空库开始测试。

之后新发的消息会使用 64 位 long Key，sync 可正常拉取。

### 2. 代码中残留的 (int) 强转

若 `SnowflakeId.java` 的 `nextId()` 或 `timestampToStartKey()` 的 return 里仍带有 `(int)`，会先把结果截断成 32 位（可能变成负数），再赋给 long，新消息 Key 仍会异常。请确认这两个方法中**没有任何 `(int)` 强转**，保持纯 long 运算。

---

## 历史不显示：四步断点排查

按下面顺序缩小范围，可快速定位问题端。

### 第一步：前端是否收到数据（F12 抓包）

1. 浏览器打开聊天页，F12 → **Network** → 筛选 **WS**。
2. F5 刷新并重新登录，点开 **ws** 连接 → **Messages**。
3. 看：
   - 是否有发出 `{"type":"sync","lastTimestamp":0}`？
   - 是否有收到 `{"type":"sync_result","messages":[...]}`？**messages 是 [] 还是有元素？**

- **若 messages 是空数组** → 后端没查到数据，继续第二步或第四步。
- **若有数据但页面不渲染** → 看 Console 是否有报错，前端解析/渲染有问题。

### 第二步：清空 NeuroDB 脏数据

修改过 int→long 或存储格式后，若没清库，旧数据可能导致解析失败或 key 不在扫描范围。

1. 停掉 NeuroDB。
2. 删除 NeuroDB 数据目录（如 `data/` 或配置里的路径）。
3. 重启 NeuroDB（空库），再新发几条消息并刷新页面测试。

### 第三步：清理 Java 编译缓存

避免运行到旧的 class（例如缺少 `type = "sync_result"`）。

```bash
./mvnw clean compile
./mvnw exec:java -Dexec.mainClass="com.chat.ChatServerMain"
```

### 第四步：后端 handleSync 埋点（已接入）

`ChatWebSocketHandler.handleSync` 里已加调试输出，刷新页面后看**运行 ChatServerMain 的控制台**：

- **「NeuroDB scan 返回的原始记录数: 0」** → scan 没扫到数据（key 范围或 NeuroDB 存的数据不对）。
- **原始记录数 > 0，但「最终准备发送给前端的历史消息数: 0」** → 记录在循环里被全部跳过，多半是 JSON 与 `Message` 字段对不上（Gson 反序列化后 m 为 null 或 senderId 为空），或时间过滤把全部过滤掉了。
- **最终消息数 > 0** → 后端已正确下发，若前端仍不显示，回到第一步看 WS 是否真的收到、Console 是否报错。

排查完可删除或注释掉 `handleSync` 中的 `System.out.println("[Debug] ...")` 语句。