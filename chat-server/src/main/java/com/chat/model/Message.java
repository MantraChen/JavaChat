package com.chat.model;

import com.google.gson.annotations.SerializedName;
import java.util.List;

/**
 * 聊天消息实体，存入 NeuroDB 的 value（JSON）。
 * Key 为 Snowflake 生成的 long，保证按时间有序。
 */
public class Message {
    @SerializedName(value = "message_id", alternate = {"messageId"})
    private long messageId;      // 与 NeuroDB key 一致（Snowflake）
    @SerializedName(value = "sender_id", alternate = {"senderId"})
    private String senderId;     // 发送者，如 "A"
    @SerializedName("content")
    private String content;      // 文本内容或图片 URL
    @SerializedName(value = "msg_type", alternate = {"msgType"})
    private String msgType;      // "text" | "image"，默认 text
    @SerializedName("timestamp")
    private long timestamp;      // 毫秒时间戳，便于 SYNC 按时间拉取
    @SerializedName(value = "is_recalled", alternate = {"isRecalled"})
    private boolean isRecalled;   // 撤回标记（tombstone）
    @SerializedName(value = "reply_to_id", alternate = {"replyToId"})
    private Long replyToId;      // 引用消息 id
    @SerializedName(value = "reply_to_user", alternate = {"replyToUser"})
    private String replyToUser;
    @SerializedName(value = "reply_to_content", alternate = {"replyToContent"})
    private String replyToContent;
    @SerializedName("mentions")
    private List<String> mentions; // 被 @ 的用户名列表
    @SerializedName(value = "receiver_id", alternate = {"receiverId"})
    private String receiverId;     // 空或 "PUBLIC" 为大厅；否则为私信目标 userId

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
    public String getMsgType() { return msgType == null ? "text" : msgType; }
    public void setMsgType(String msgType) { this.msgType = msgType; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public boolean isRecalled() { return isRecalled; }
    public void setRecalled(boolean recalled) { isRecalled = recalled; }
    public Long getReplyToId() { return replyToId; }
    public void setReplyToId(Long replyToId) { this.replyToId = replyToId; }
    public String getReplyToUser() { return replyToUser; }
    public void setReplyToUser(String replyToUser) { this.replyToUser = replyToUser; }
    public String getReplyToContent() { return replyToContent; }
    public void setReplyToContent(String replyToContent) { this.replyToContent = replyToContent; }
    public List<String> getMentions() { return mentions; }
    public void setMentions(List<String> mentions) { this.mentions = mentions; }
    public String getReceiverId() { return receiverId; }
    public void setReceiverId(String receiverId) { this.receiverId = receiverId; }
}
