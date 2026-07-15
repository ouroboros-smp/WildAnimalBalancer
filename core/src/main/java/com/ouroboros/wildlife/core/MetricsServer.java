package com.ouroboros.wildlife.core;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Tiny built-in Prometheus endpoint: GET /metrics on a configurable bind and
 * port, serving the text exposition format. Uses the JDK's own HTTP server so
 * nothing is shaded, and a single daemon thread so it costs nothing when no
 * scraper is polling.
 *
 * The scrape supplier must only read atomics and concurrent-map sizes; it runs
 * on the metrics thread and must NEVER touch world or entity state.
 */
public final class MetricsServer {

    private final HttpServer server;
    private final ExecutorService executor;

    private MetricsServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static MetricsServer start(String bind, int port, Supplier<String> scrape) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        server.createContext("/metrics", exchange -> {
            try {
                if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                // createContext matches by prefix, so /metrics/anything lands here too
                if (!"/metrics".equals(exchange.getRequestURI().getPath())) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = scrape.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } finally {
                exchange.close();
            }
        });
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "wildlife-metrics");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.start();
        return new MetricsServer(server, executor);
    }

    public void stop() {
        server.stop(0);
        executor.shutdown();
    }
}
