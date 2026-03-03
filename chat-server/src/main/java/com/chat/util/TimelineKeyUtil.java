package com.chat.util;

/**
 * 组合键工具：64 位 long 划分为 [OwnerID 22bit | Timestamp 42bit]，
 * 使同一信箱（Owner）的消息在 LSM-Tree 中物理连续，实现 O(log N) 前缀扫描。
 */
public class TimelineKeyUtil {
    /** 低 42 位用于时间戳（约 139 年） */
    private static final int TIMESTAMP_BITS = 42;
    private static final long TIMESTAMP_MASK = (1L << TIMESTAMP_BITS) - 1;

    /**
     * 构建组合键: [OwnerID (22 bit)] | [Timestamp (42 bit)]
     * 同一 Owner 的所有消息在底层 SSTable 中物理连续。
     */
    public static long buildKey(int ownerId, long timestamp) {
        return ((long) ownerId << TIMESTAMP_BITS) | (timestamp & TIMESTAMP_MASK);
    }

    /**
     * 将 userId 映射为 22 位内的数字 ID。PUBLIC/空 恒为 0（公共大厅）。
     */
    public static int userIdToOwnerId(String userId) {
        if (userId == null || userId.isEmpty() || "PUBLIC".equalsIgnoreCase(userId)) {
            return 0;
        }
        return Math.abs(userId.hashCode()) & 0x3FFFFF;
    }

    /** 从组合键解码出时间戳（低 42 位） */
    public static long decodeTimestamp(long key) {
        return key & TIMESTAMP_MASK;
    }

    /** 从组合键解码出 OwnerID（高 22 位） */
    public static int decodeOwnerId(long key) {
        return (int) (key >> TIMESTAMP_BITS);
    }
}
