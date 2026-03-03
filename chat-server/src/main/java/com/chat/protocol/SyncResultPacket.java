package com.chat.protocol;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** 服务端 -> 客户端：同步结果。type 必须为 "sync_result"，前端靠此字段识别并渲染历史消息。 */
public class SyncResultPacket {
    @SerializedName("type")
    public String type = "sync_result";
    @SerializedName("messages")
    public List<ChatMessagePacket> messages;
}
