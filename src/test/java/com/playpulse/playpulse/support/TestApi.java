package com.playpulse.playpulse.support;

import org.springframework.boot.json.JsonParserFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** A tiny HTTP client for talking to the running application the way a real caller would. */
public final class TestApi {

    public record Response(int status, String body) {
        public Map<String, Object> map() {
            return JsonParserFactory.getJsonParser().parseMap(body);
        }

        public List<Object> list() {
            return JsonParserFactory.getJsonParser().parseList(body);
        }
    }

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;

    public TestApi(int port) {
        this.base = "http://localhost:" + port;
    }

    public Response post(String path, String json) {
        HttpRequest.BodyPublisher body = json == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json);
        return send(HttpRequest.newBuilder(URI.create(base + path))
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30)).POST(body).build());
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).GET().build());
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("HTTP call failed: " + request.uri(), e);
        }
    }
}
