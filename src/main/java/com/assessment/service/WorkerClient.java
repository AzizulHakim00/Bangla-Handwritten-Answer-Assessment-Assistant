package com.assessment.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

final class WorkerClient {
    private final URI endpoint;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    WorkerClient(String endpoint, String token) {
        this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint : endpoint + "/");
        if (!List.of("http", "https").contains(this.endpoint.getScheme()) || token.length() < 32)
            throw new IllegalArgumentException("Configure the private document worker URL and a token of at least 32 characters");
        this.token = token;
    }

    byte[] call(String operation, byte[] bytes, String mime, int page, Map<String, Object> region, long seconds) throws IOException {
        var builder = HttpRequest.newBuilder(endpoint.resolve(operation))
            .timeout(Duration.ofSeconds(seconds)).header("Authorization", "Bearer " + token)
            .header("Content-Type", mime).header("X-Page", Integer.toString(page));
        if (!region.isEmpty()) {
            for (String key : List.of("x", "y", "width", "height")) builder.header("X-Region-" + key, region.get(key).toString());
        }
        try {
            var response = client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
            try (var stream = response.body()) {
                if (response.statusCode() == 422 || response.statusCode() == 413)
                    throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Document or page could not be processed");
                if (response.statusCode() != 200) throw new IOException("Document worker is unavailable");
                int limit = switch (operation) {
                    case "page" -> 65 * 1024 * 1024;
                    case "ocr" -> 120_000;
                    default -> 8;
                };
                byte[] responseBytes = stream.readNBytes(limit + 1);
                if (responseBytes.length > limit) throw new IOException("Document worker response exceeded its limit");
                return responseBytes;
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Document processing interrupted", ex);
        }
    }
}
