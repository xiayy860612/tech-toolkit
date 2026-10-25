package com.s2u2m.uam.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.OptionalLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 集成测试用的 HTTP 客户端：带服务密钥调 UAM，返回状态、响应头与 JSON。 */
public final class ApiClient {

  /** 一次响应。 */
  public record Response(int status, HttpHeaders headers, String body) {

    public JsonNode json() {
      return JSON.readTree(body);
    }

    public String contentType() {
      return headers.firstValue("Content-Type").orElse("");
    }

    public OptionalLong retryAfter() {
      return headers.firstValueAsLong("Retry-After");
    }
  }

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  private final String baseUrl;
  private final String serviceKey;

  public ApiClient(int port, String serviceKey) {
    this.baseUrl = "http://localhost:" + port;
    this.serviceKey = serviceKey;
  }

  public Response postJson(String path, Object body) {
    return postJson(path, body, Map.of());
  }

  public Response postJson(String path, Object body, Map<String, String> headers) {
    String json = body instanceof String text ? text : JSON.writeValueAsString(body);
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (serviceKey != null) {
      builder.header("Authorization", "Bearer " + serviceKey);
    }
    headers.forEach(builder::header);
    return send(builder.build());
  }

  public Response get(String pathAndQuery) {
    return get(pathAndQuery, Map.of());
  }

  public Response get(String pathAndQuery, Map<String, String> headers) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + pathAndQuery)).GET();
    if (serviceKey != null) {
      builder.header("Authorization", "Bearer " + serviceKey);
    }
    headers.forEach(builder::header);
    return send(builder.build());
  }

  public Response delete(String path, Map<String, String> headers) {
    HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE();
    if (serviceKey != null) {
      builder.header("Authorization", "Bearer " + serviceKey);
    }
    headers.forEach(builder::header);
    return send(builder.build());
  }

  /** 任意方法的请求（契约遍历用）；{@code body} 为 {@code null} 时不带请求体。 */
  public Response request(String method, String path, String body, Map<String, String> headers) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create(baseUrl + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    if (body != null) {
      builder.header("Content-Type", "application/json");
    }
    if (serviceKey != null) {
      builder.header("Authorization", "Bearer " + serviceKey);
    }
    headers.forEach(builder::header);
    return send(builder.build());
  }

  public Response login(String accountName, String password, String clientIp) {
    return postJson(
        "/v1/auth/login",
        Map.of("accountName", accountName, "password", password, "clientIp", clientIp));
  }

  public Response verify(String sessionId) {
    return postJson("/v1/auth/sessions/verify", Map.of("sessionId", sessionId));
  }

  public Response logout(String sessionId) {
    return postJson("/v1/auth/logout", Map.of("sessionId", sessionId));
  }

  private static Response send(HttpRequest request) {
    try {
      HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
      return new Response(response.statusCode(), response.headers(), response.body());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
