package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 客户端 -> 服务端：同步历史，lastTimestamp 为本地最新一条消息的时间戳(ms) */
public class SyncPacket {
    @SerializedName("type")
    public String type = "sync";
    @SerializedName("lastTimestamp")
    public long lastTimestamp;
}
