package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 客户端 -> 服务端：聊天消息 */
public class ChatPacket {
    @SerializedName("type")
    public String type = "chat";
    @SerializedName("content")
    public String content;
}
