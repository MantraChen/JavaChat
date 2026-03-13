package com.chat.neurodb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.chat.config.AppConfig;
import com.google.gson.Gson;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 通过 HTTP 调用 NeuroDB 的 /api/put、/api/get、/api/scan。
 * 用于存储用户（key=用户数字 id）、消息（key=Snowflake 消息 id）。
 */
public class NeuroDbClient {
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

    /** POST /api/put 写入一条 key-value，value 为字符串（如 JSON）。Key 为 64 位与 NeuroDB int64 一致。 */
    public void put(long key, String value) throws IOException {
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
    public String get(long key) throws IOException {
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
     * start/end 为 64 位，与 NeuroDB int64 Key 一致，避免负数 key 被排除。
     */
    @SuppressWarnings("unchecked")
    public List<ScanRecord> scan(long startKey, long endKey) throws IOException {
        Request req = new Request.Builder()
                .url(baseUrl + "/api/scan?start=" + startKey + "&end=" + endKey)
                .get()
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("NeuroDB scan failed: " + resp.code());
            String json = Objects.requireNonNull(resp.body()).string();
            List<?> dataList = null;
            ScanResponse r = gson.fromJson(json, ScanResponse.class);
            if (r != null && r.data != null) dataList = r.data;
            if (dataList == null) {
                java.util.Map<?, ?> raw = gson.fromJson(json, java.util.Map.class);
                if (raw != null) {
                    if (raw.containsKey("data")) dataList = (List<?>) raw.get("data");
                    else if (raw.containsKey("Data")) dataList = (List<?>) raw.get("Data");
                }
            }
            if (dataList == null) return new ArrayList<>();
            List<ScanRecord> list = new ArrayList<>();
            for (Object o : dataList) {
                if (o instanceof java.util.Map) {
                    java.util.Map<String, Object> m = (java.util.Map<String, Object>) o;
                    Object k = m.get("Key");
                    if (k == null) k = m.get("key");
                    Object v = m.get("Value");
                    if (v == null) v = m.get("value");
                    long kLong = k instanceof Number ? ((Number) k).longValue() : 0L;
                    String vStr = valueToString(v);
                    list.add(new ScanRecord(kLong, vStr));
                }
            }
            return list;
        }
    }

    /** 将 NeuroDB 返回的 value 转为字符串：可能是 base64、纯字符串或已解析的 JSON 对象。 */
    private String valueToString(Object v) {
        if (v == null) return "";
        if (v instanceof String s) {
            try {
                byte[] decoded = Base64.getDecoder().decode(s);
                return decoded != null && decoded.length > 0
                        ? new String(decoded, StandardCharsets.UTF_8) : s;
            } catch (IllegalArgumentException e) {
                return s;
            }
        }
        return gson.toJson(v);
    }

    public static class ScanRecord {
        public final long key;
        public final String value;

        public ScanRecord(long key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static class PutRequest {
        @SuppressWarnings("unused")
        final long key;
        @SuppressWarnings("unused")
        final String value;

        PutRequest(long key, String value) {
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
