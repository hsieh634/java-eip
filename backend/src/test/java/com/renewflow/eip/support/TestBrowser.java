package com.renewflow.eip.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

/**
 * 模擬瀏覽器：對「真的啟動的 Tomcat」發 HTTP 請求，並像瀏覽器一樣保存／回送 Cookie。
 * 不用 MockMvc 的原因：MockMvc 沒有 Servlet 容器，不會產生真正的 JSESSIONID Set-Cookie，
 * 也就無法驗證 HttpOnly／SameSite 等 Cookie 屬性。
 */
public class TestBrowser {

    public record Reply(int status, String body, List<String> setCookies) {
        public JsonNode json() {
            return new JsonMapper().readTree(body);
        }

        public String code() {
            return json().get("code").asString();
        }

        /** 回傳指定名稱的 Set-Cookie 整行（含屬性）；沒有則 null。 */
        public String setCookie(String name) {
            return setCookies.stream().filter(c -> c.startsWith(name + "=")).reduce((a, b) -> b).orElse(null);
        }
    }

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();

    public TestBrowser(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Reply get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    /** 像前端一樣：從 XSRF-TOKEN Cookie 讀值，放進 X-XSRF-TOKEN 標頭。 */
    public Reply post(String path, String jsonBody) {
        return postWithCsrf(path, jsonBody, cookie("XSRF-TOKEN"));
    }

    public Reply postWithCsrf(String path, String jsonBody, String csrfHeader) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "" : jsonBody));
        if (csrfHeader != null) {
            builder.header("X-XSRF-TOKEN", csrfHeader);
        }
        return send(builder.build());
    }

    /** 直接指定 Cookie 標頭（用來模擬拿著舊 Session ID 的人）。 */
    public Reply getWithRawCookie(String path, String cookieHeader) {
        // 使用獨立的 client，避免 CookieManager 的內容混進來
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Cookie", cookieHeader).GET().build();
        return send(HttpClient.newHttpClient(), request);
    }

    public String cookie(String name) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(c -> c.getName().equals(name)).map(HttpCookie::getValue).findFirst().orElse(null);
    }

    private Reply send(HttpRequest request) {
        return send(client, request);
    }

    private Reply send(HttpClient httpClient, HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            Map<String, List<String>> headers = response.headers().map();
            List<String> setCookies = headers.getOrDefault("set-cookie", List.of());
            return new Reply(response.statusCode(), response.body(), setCookies);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
