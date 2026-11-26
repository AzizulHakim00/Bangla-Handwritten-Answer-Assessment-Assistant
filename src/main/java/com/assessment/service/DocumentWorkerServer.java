package com.assessment.service;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.springframework.web.server.ResponseStatusException;

public final class DocumentWorkerServer {
    private static final int MAX_UPLOAD = 20 * 1024 * 1024;
    private static final Set<String> MIME = Set.of("application/pdf", "image/png", "image/jpeg");
    private final byte[] authorization;

    private DocumentWorkerServer(String token) {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("Set ASSESSMENT_WORKER_TOKEN");
        authorization = ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
    }

    public static void start(String token, int port) throws IOException {
        var worker = new DocumentWorkerServer(token);
        var server = HttpServer.create(new InetSocketAddress(port), 16);
        server.createContext("/", worker::handle);
        server.setExecutor(new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), new ThreadPoolExecutor.AbortPolicy()));
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String header = Objects.toString(exchange.getRequestHeaders().getFirst("Authorization"), "");
            if (!MessageDigest.isEqual(authorization, header.getBytes(StandardCharsets.US_ASCII))) {
                send(exchange, 403, new byte[0]);
                return;
            }
            String operation = exchange.getRequestURI().getPath();
            if (!"POST".equals(exchange.getRequestMethod()) || !Set.of("/validate", "/page", "/ocr").contains(operation)) {
                send(exchange, 404, new byte[0]);
                return;
            }
            var headers = exchange.getRequestHeaders();
            String mime = headers.getFirst("Content-Type");
            if (mime == null || !MIME.contains(mime)) {
                send(exchange, 422, new byte[0]);
                return;
            }
            byte[] input = exchange.getRequestBody().readNBytes(MAX_UPLOAD + 1);
            if (input.length > MAX_UPLOAD) {
                send(exchange, 413, new byte[0]);
                return;
            }
            try {
                byte[] output;
                if ("/validate".equals(operation)) {
                    output = Integer.toString(DocumentService.localValidate(input, mime)).getBytes(StandardCharsets.US_ASCII);
                } else {
                    int page = Integer.parseInt(headers.getFirst("X-Page"));
                    var image = DocumentService.localPage(input, mime, page, "/ocr".equals(operation));
                    if ("/page".equals(operation)) {
                        var stream = new ByteArrayOutputStream();
                        ImageIO.write(image, "png", stream);
                        output = stream.toByteArray();
                    } else {
                        var region = new HashMap<String, Object>();
                        for (String key : List.of("x", "y", "width", "height"))
                            region.put(key, Double.parseDouble(Objects.toString(headers.getFirst("X-Region-" + key), "")));
                        String text = NativeOcr.read("tesseract", DocumentService.crop(image, region),
                            System.nanoTime() + TimeUnit.SECONDS.toNanos(45));
                        output = text.getBytes(StandardCharsets.UTF_8);
                    }
                }
                send(exchange, 200, output);
            } catch (ResponseStatusException | IllegalArgumentException ex) {
                send(exchange, 422, new byte[0]);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                send(exchange, 503, new byte[0]);
            } catch (IOException ex) {
                send(exchange, "/ocr".equals(operation) ? 503 : 422, new byte[0]);
            }
        }
    }

    private static void send(HttpExchange exchange, int code, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(code, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) exchange.getResponseBody().write(bytes);
    }
}
