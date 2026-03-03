package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 服务端在消息落库后回给发送者的确认包，用于前端 QoS。 */
public class AckPacket {
    @SerializedName("type")
    public String type = "ack";
    @SerializedName("localId")
    public String localId;
    @SerializedName("messageId")
    public String messageId;
}
