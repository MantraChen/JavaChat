package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 服务端 -> 客户端：错误 */
public class ErrorPacket {
    @SerializedName("type")
    public String type = "error";
    @SerializedName("reason")
    public String reason;
}
