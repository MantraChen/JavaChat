package com.chat.protocol;

import com.google.gson.annotations.SerializedName;

/** 服务端 -> 客户端：认证成功 */
public class AuthOkPacket {
    @SerializedName("type")
    public String type = "auth_ok";
    @SerializedName("userId")
    public String userId;
}
