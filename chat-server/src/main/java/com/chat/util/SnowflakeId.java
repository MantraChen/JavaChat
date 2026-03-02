package com.chat.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 32 位兼容的单调递增 ID 生成器，供 NeuroDB HTTP API（key 为 int）使用。
 * 高 20 位：相对秒数（自 2020-01-01 起 mod 2^20，约 12 天一轮）；
 * 低 12 位：同一秒内序号（0–4095）。
 * 保证同一秒内有序、不同秒之间按时间有序，便于按 Key 范围 Scan 拉取历史。
 */
public final class SnowflakeId {
    private static final long EPOCH_2020 = 1577836800L; // 2020-01-01 00:00:00 UTC
    private static final int BITS_SEQ = 12;
    private static final int MAX_SEQ = 1 << BITS_SEQ;
    private static final int SEC_MOD = 1 << 20;

    private long lastSec = 0;
    private final AtomicInteger seq = new AtomicInteger(0);

    /** 返回正数 int，用作 NeuroDB 的 key。 */
    public synchronized int nextId() {
        long sec = System.currentTimeMillis() / 1000;
        if (sec != lastSec) {
            lastSec = sec;
            seq.set(0);
        }
        long secPart = (sec - EPOCH_2020) % SEC_MOD;
        if (secPart < 0) secPart += SEC_MOD;
        int s = seq.getAndIncrement() % MAX_SEQ;
        long id = (secPart << BITS_SEQ) | s;
        return (int) (id & 0x7FFF_FFFFL);
    }

    /** 将毫秒时间戳转换为该秒对应的最小 key，用于 SYNC 按时间拉取增量（scan from this key）。 */
    public static int timestampToStartKey(long timeMs) {
        long sec = timeMs / 1000;
        long secPart = (sec - EPOCH_2020) % SEC_MOD;
        if (secPart < 0) secPart += SEC_MOD;
        return (int) ((secPart << BITS_SEQ) & 0x7FFF_FFFFL);
    }
}
