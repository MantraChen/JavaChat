package com.chat.protocol;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** 服务端 -> 客户端：同步结果。type 必须为 "sync_result"，target 标识信箱(PUBLIC/INBOX)便于前端游标隔离。 */
public class SyncResultPacket {
    @SerializedName("type")
    public String type = "sync_result";
    @SerializedName("target")
    public String target;
    @SerializedName("messages")
    public List<ChatMessagePacket> messages;
}
