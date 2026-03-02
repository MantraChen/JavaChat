package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 服务端 -> 客户端：单条聊天消息（广播或历史） */
public class ChatMessagePacket {
    @SerializedName("type")
    public String type = "chat";
    @SerializedName("messageId")
    public long messageId;
    @SerializedName("senderId")
    public String senderId;
    @SerializedName("content")
    public String content;
    @SerializedName("timestamp")
    public long timestamp;
}
