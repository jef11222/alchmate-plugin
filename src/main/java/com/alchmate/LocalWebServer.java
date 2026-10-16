package com.alchmate;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalWebServer {
    private static final Logger log = LoggerFactory.getLogger(LocalWebServer.class);

    private final AlchMatePlugin plugin;
    private HttpServer server;
    private int boundPort = -1;

    public LocalWebServer(AlchMatePlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized boolean start(int preferredPort) {
        if (server != null) {
            return true;
        }

        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", preferredPort), 0);
            server.setExecutor(Executors.newFixedThreadPool(4));

            server.createContext("/api/sync", new SyncHandler());
            server.createContext("/api/event", new EventHandler());
            server.createContext("/", new StaticFileHandler());

            server.start();
            this.boundPort = preferredPort;
            log.info("AlchMate embedded web server started as Hub on http://127.0.0.1:{}", preferredPort);
            return true;
        } catch (IOException e) {
            log.info("Port {} is in use (another AlchMate client is acting as Hub): {}", preferredPort, e.getMessage());
            this.boundPort = -1;
            this.server = null;
            return false;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            boundPort = -1;
            log.info("AlchMate embedded web server stopped");
        }
    }

    public boolean isRunning() {
        return server != null;
    }

    public int getBoundPort() {
        return boundPort;
    }

    public String getUrl() {
        return boundPort > 0 ? "http://127.0.0.1:" + boundPort : "http://127.0.0.1:18833";
    }

    private void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
        exchange.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }

    private class SyncHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            try {
                String json = plugin.getLocalSyncJson();
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } catch (Exception e) {
                log.error("Error generating local sync JSON: {}", e.getMessage());
                byte[] err = "{\"error\":\"Internal server error\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            }
        }
    }

    private class EventHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }

            try (InputStream is = exchange.getRequestBody()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[2048];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, read);
                }
                String body = baos.toString(StandardCharsets.UTF_8.name());
                plugin.handleLocalEvent(body);

                byte[] resp = "{\"success\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } catch (Exception e) {
                log.error("Error handling local event: {}", e.getMessage());
                byte[] err = "{\"error\":\"Invalid payload\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            }
        }
    }

    private class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);

            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                return;
            }

            String path = exchange.getRequestURI().getPath();
            if (path == null || path.isEmpty() || path.equals("/")) {
                path = "/index.html";
            }

            // Immediately reject any unhandled /api/* route with 404 JSON to prevent serving index.html
            if (path.startsWith("/api/")) {
                byte[] notFoundJson = "{\"error\":\"Endpoint not found\"}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(404, notFoundJson.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFoundJson);
                }
                return;
            }

            // Normalize trailing slashes (e.g. /alch/ -> /alch)
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            byte[] content = null;
            String mime = getMimeType(path);

            // 1. Load classpath resource inside plugin JAR (/web/*)
            String resourcePath = "/web" + (path.startsWith("/") ? path : ("/" + path));
            try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
                if (is != null) {
                    content = readAllBytes(is);
                }
            } catch (Exception ignored) {}

            if (content == null && !path.contains(".")) {
                try (InputStream is = getClass().getResourceAsStream(resourcePath + ".html")) {
                    if (is != null) {
                        content = readAllBytes(is);
                        mime = "text/html; charset=utf-8";
                    }
                } catch (Exception ignored) {}
            }

            // 2. Fallback for SPA routing: if path doesn't have an extension, serve index.html
            if (content == null && !path.contains(".")) {
                try (InputStream is = getClass().getResourceAsStream("/web/index.html")) {
                    if (is != null) {
                        content = readAllBytes(is);
                        mime = "text/html; charset=utf-8";
                    }
                } catch (Exception ignored) {}
            }

            // 3. Return 404 if asset not found
            if (content == null) {
                byte[] notFound = "<html><body><h1>404 Not Found</h1></body></html>".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound);
                }
                return;
            }

            // Set Caching Headers
            if (path.contains("/_next/static/")) {
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            } else if (path.endsWith(".html") || path.endsWith(".json") || path.endsWith(".txt")) {
                exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store, must-revalidate");
            }

            exchange.getResponseHeaders().set("Content-Type", mime);
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(content);
            }
        }

        private byte[] readAllBytes(InputStream is) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }

        private String getMimeType(String path) {
            String lower = path.toLowerCase();
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
            if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=utf-8";
            if (lower.endsWith(".css")) return "text/css; charset=utf-8";
            if (lower.endsWith(".json") || lower.endsWith(".map")) return "application/json; charset=utf-8";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".svg")) return "image/svg+xml";
            if (lower.endsWith(".ico")) return "image/x-icon";
            if (lower.endsWith(".webp")) return "image/webp";
            if (lower.endsWith(".woff2")) return "font/woff2";
            if (lower.endsWith(".woff")) return "font/woff";
            if (lower.endsWith(".ttf")) return "font/ttf";
            if (lower.endsWith(".otf")) return "font/otf";
            return "text/plain; charset=utf-8";
        }
    }
}
