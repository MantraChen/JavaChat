package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 服务端 -> 客户端：认证失败 */
public class AuthFailPacket {
    @SerializedName("type")
    public String type = "auth_fail";
    @SerializedName("reason")
    public String reason;
}
