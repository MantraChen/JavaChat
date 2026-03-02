package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 客户端 -> 服务端：撤回请求；服务端 -> 客户端：广播撤回（含 senderId 便于展示）。 */
public class RecallPacket {
    @SerializedName("type")
    public String type = "RECALL";
    @SerializedName("messageId")
    public long messageId;
    @SerializedName("senderId")
    public String senderId;  // 仅服务端下发时带
}
