package com.chat.util;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 64 位雪花 ID 生成器，与 NeuroDB（int64 Key）一致，避免截断溢出。
 * 1 bit 符号(0) + 41 bit 毫秒时间戳 + 10 bit 机器(0) + 12 bit 序列号。
 */
public final class SnowflakeId {
    private static final long EPOCH_2020 = 1577836800000L; // 2020-01-01 00:00:00 UTC ms
    private static final int BITS_SEQ = 12;
    private static final int MAX_SEQ = 1 << BITS_SEQ;

    private long lastMs = 0;
    private final AtomicInteger seq = new AtomicInteger(0);

    /** 返回 64 位正数 long，用作 NeuroDB 的 message key。 */
    public synchronized long nextId() {
        long ms = System.currentTimeMillis();
        if (ms != lastMs) {
            lastMs = ms;
            seq.set(0);
        }
        int s = seq.getAndIncrement() % MAX_SEQ;
        long id = ((ms - EPOCH_2020) << (10 + BITS_SEQ)) | s;
        return id & 0x7FFF_FFFF_FFFF_FFFFL;
    }

    /** 将毫秒时间戳转换为该毫秒对应的最小 key，用于 SYNC 按时间拉取增量。 */
    public static long timestampToStartKey(long timeMs) {
        long id = ((timeMs - EPOCH_2020) << (10 + BITS_SEQ)) & 0x7FFF_FFFF_FFFF_FFFFL;
        return id < 0 ? 0 : id;
    }
}
