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