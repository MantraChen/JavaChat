package com.chat.protocol;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/** 服务端 -> 客户端：单条聊天消息（广播或历史），支持撤回、引用、@。messageId/replyToId 用 String 避免前端 JS 53 位精度丢失。 */
public class ChatMessagePacket {
    @SerializedName("type")
    public String type = "chat";
    @SerializedName("messageId")
    public String messageId;
    @SerializedName("senderId")
    public String senderId;
    @SerializedName("content")
    public String content;
    @SerializedName("timestamp")
    public long timestamp;
    @SerializedName("isRecalled")
    public boolean isRecalled;
    @SerializedName("replyToId")
    public String replyToId;
    @SerializedName("replyToUser")
    public String replyToUser;
    @SerializedName("replyToContent")
    public String replyToContent;
    @SerializedName("mentions")
    public List<String> mentions;
    @SerializedName("receiverId")
    public String receiverId;  // 空或 "PUBLIC" 为大厅；否则为私信目标
}
