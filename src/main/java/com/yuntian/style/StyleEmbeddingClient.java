package com.yuntian.style;

import com.fasterxml.jackson.databind.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 不记录第三方响应/请求头；不强行传 dimensions，首次成功响应确定该实例的向量配置。 */
public class StyleEmbeddingClient {
    private final StyleProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private volatile Integer dimensions;
    public StyleEmbeddingClient(StyleProperties properties, ObjectMapper json) {
        this.properties = properties; this.json = json;
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public record Vector(float[] values, String profile, Integer inputTokens) {}
    public Vector embed(String text) {
        return embed(text, properties.getModelTimeout());
    }
    public Vector embed(String text, Duration timeout) {
        if (text == null || text.isBlank() || text.length() > 8000) throw new IllegalArgumentException("STYLE_INVALID_EMBEDDING_TEXT");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) throw new IllegalStateException("STYLE_REPLY_TIMEOUT");
        var cfg = properties.getEmbedding();
        try {
            String body = json.writeValueAsString(Map.of("model", cfg.getModelName(), "input", List.of(text), "encoding_format", "float"));
            HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.getBaseUrl().replaceAll("/+$", "") + "/embeddings"))
                    .timeout(timeout).header("Authorization", "Bearer " + cfg.getApiKey())
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("STYLE_EMBEDDING_HTTP_" + response.statusCode());
            JsonNode root = json.readTree(response.body()); JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != 1) throw new IllegalStateException("STYLE_INVALID_EMBEDDING_RESPONSE");
            JsonNode vector = data.get(0).path("embedding");
            if (!vector.isArray() || vector.isEmpty() || vector.size() > 16000) throw new IllegalStateException("STYLE_INVALID_EMBEDDING_VECTOR");
            float[] values = new float[vector.size()]; double norm = 0;
            for (int i = 0; i < values.length; i++) {
                if (!vector.get(i).isNumber()) throw new IllegalStateException("STYLE_INVALID_EMBEDDING_VECTOR");
                values[i] = (float) vector.get(i).asDouble();
                if (!Float.isFinite(values[i])) throw new IllegalStateException("STYLE_INVALID_EMBEDDING_VECTOR");
                norm += (double) values[i] * values[i];
            }
            if (norm == 0) throw new IllegalStateException("STYLE_ZERO_EMBEDDING_VECTOR");
            synchronized (this) {
                if (dimensions != null && dimensions != values.length) throw new IllegalStateException("STYLE_EMBEDDING_DIMENSION_CHANGED");
                dimensions = values.length;
            }
            JsonNode usage = root.path("usage").path("prompt_tokens");
            return new Vector(values, profile(values.length), usage.canConvertToInt() ? usage.asInt() : null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("STYLE_EMBEDDING_INTERRUPTED");
        } catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("STYLE_EMBEDDING_TRANSPORT_FAILED"); }
    }
    public String profile(int dimension) {
        return StyleExtractor.hash(requestProfile() + "\n" + dimension);
    }
    public String requestProfile() {
        return StyleExtractor.hash(properties.getEmbedding().getBaseUrl().replaceAll("/+$", "") + "\n"
                + properties.getEmbedding().getModelName() + "\n" + properties.getEmbeddingTemplateVersion());
    }
    public static String literal(float[] values) {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("STYLE_INVALID_EMBEDDING_VECTOR");
            out.add(Float.toString(value));
        }
        return out.toString();
    }
}
