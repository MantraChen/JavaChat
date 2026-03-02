package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 客户端 -> 服务端：首包认证 */
public class AuthPacket {
    @SerializedName("type")
    public String type = "auth";
    @SerializedName("token")
    public String token;
}
