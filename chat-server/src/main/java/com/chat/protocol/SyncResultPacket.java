package com.chat.protocol;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** 服务端 -> 客户端：同步结果 */
public class SyncResultPacket {
    @SerializedName("type")
    public String type = "sync_result";
    @SerializedName("messages")
    public List<ChatMessagePacket> messages;
}
