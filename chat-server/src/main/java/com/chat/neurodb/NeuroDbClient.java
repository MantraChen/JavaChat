package com.chat.neurodb;

import com.chat.config.AppConfig;
import com.google.gson.Gson;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 通过 HTTP 调用 NeuroDB 的 /api/put、/api/get、/api/scan。
 * 用于存储用户（key=用户数字 id）、消息（key=Snowflake 消息 id）。
 */
public class NeuroDbClient {
    private static final Logger log = LoggerFactory.getLogger(NeuroDbClient.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final String baseUrl;
    private final OkHttpClient http;
    private final Gson gson = new Gson();

    public NeuroDbClient(AppConfig config) {
        this.baseUrl = config.getNeuroDbHttpUrl().replaceAll("/$", "");
        this.http = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    /** POST /api/put 写入一条 key-value，value 为字符串（如 JSON）。 */
    public void put(int key, String value) throws IOException {
        String body = gson.toJson(new PutRequest(key, value));
        Request req = new Request.Builder()
                .url(baseUrl + "/api/put")
                .post(RequestBody.create(body, JSON))
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IOException("NeuroDB put failed: " + resp.code() + " " + resp.message());
            }
        }
    }

    /** GET /api/get?key= 获取一条，不存在返回 null。 */
    public String get(int key) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/get?key=" + key)
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (resp.code() == 404) return null;
            if (!resp.isSuccessful()) throw new IOException("NeuroDB get failed: " + resp.code());
            String json = Objects.requireNonNull(resp.body()).string();
            GetResponse r = gson.fromJson(json, GetResponse.class);
            return r != null && Boolean.TRUE.equals(r.found) ? r.value : null;
        }
    }

    /**
     * GET /api/scan?start=&end= 范围查询，返回 data 列表，每项为 {key, value}。
     * value 在 NeuroDB 返回里是字符串。
     */
    @SuppressWarnings("unchecked")
    public List<ScanRecord> scan(int startKey, int endKey) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/scan?start=" + startKey + "&end=" + endKey)
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("NeuroDB scan failed: " + resp.code());
            String json = Objects.requireNonNull(resp.body()).string();
            ScanResponse r = gson.fromJson(json, ScanResponse.class);
            if (r == null || r.data == null) return new ArrayList<>();
            List<ScanRecord> list = new ArrayList<>();
            for (Object o : r.data) {
                if (o instanceof java.util.Map) {
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                    Object k = m.get("Key");
                    Object v = m.get("Value");
                    int kInt = k instanceof Number ? ((Number) k).intValue() : 0;
                    String vStr = decodeValue(v);
                    list.add(new ScanRecord(kInt, vStr));
                }
            }
            return list;
        }
    }

    /** NeuroDB 返回的 Value 可能是 base64 编码的字节，此处统一解码为 UTF-8 字符串。 */
    private static String decodeValue(Object v) {
        if (v == null) return "";
        String s = v.toString();
        try {
            byte[] decoded = Base64.getDecoder().decode(s);
            return decoded != null && decoded.length > 0
                    ? new String(decoded, StandardCharsets.UTF_8) : s;
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    public static class ScanRecord {
        public final int key;
        public final String value;

        public ScanRecord(int key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static class PutRequest {
        @SuppressWarnings("unused")
        final int key;
        @SuppressWarnings("unused")
        final String value;

        PutRequest(int key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static class GetResponse {
        @SuppressWarnings("unused")
        Boolean found;
        @SuppressWarnings("unused")
        String value;
    }

    private static class ScanResponse {
        @SuppressWarnings("unused")
        List<Object> data;
    }
}
