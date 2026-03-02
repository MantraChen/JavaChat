package com.chat.model;

import com.google.gson.annotations.SerializedName;

/**
 * 聊天消息实体，存入 NeuroDB 的 value（JSON）。
 * Key 为 Snowflake 生成的 long，保证按时间有序。
 */
public class Message {
    @SerializedName("message_id")
    private long messageId;      // 与 NeuroDB key 一致（Snowflake）
    @SerializedName("sender_id")
    private String senderId;     // 发送者，如 "A"
    @SerializedName("content")
    private String content;      // 文本内容
    @SerializedName("timestamp")
    private long timestamp;      // 毫秒时间戳，便于 SYNC 按时间拉取

    public Message() {}

    public Message(long messageId, String senderId, String content, long timestamp) {
        this.messageId = messageId;
        this.senderId = senderId;
        this.content = content;
        this.timestamp = timestamp;
    }

    public long getMessageId() { return messageId; }
    public void setMessageId(long messageId) { this.messageId = messageId; }
    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
