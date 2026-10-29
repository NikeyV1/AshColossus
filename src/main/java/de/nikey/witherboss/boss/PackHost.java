package de.nikey.witherboss.boss;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PackHost implements AutoCloseable {
    public static final String PATH = "/wither-boss-pack.zip";
    private final HttpServer server;
    private final ExecutorService executor;

    public static PackHost start(String bind, int port, String hash, Path file) throws Exception {
        if (port < 1024 || port > 65535) throw new IOException("resource-pack.self-host.port must be between 1024 and 65535.");
        if (Files.size(file) > 64 * 1024 * 1024) throw new IOException("The resource pack is larger than 64 MB.");
        byte[] bytes = Files.readAllBytes(file);
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        if (!actual.equalsIgnoreCase(hash)) throw new IOException("The hosted resource pack does not match the configured SHA1.");
        return new PackHost(bind, port, bytes);
    }

    private PackHost(String bind, int port, byte[] bytes) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread thread = new Thread(r, "witherboss-pack-host");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext(PATH, exchange -> {
            try (exchange) {
                if (!PATH.equals(exchange.getRequestURI().getRawPath())) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                String method = exchange.getRequestMethod();
                if (!method.equals("GET") && !method.equals("HEAD")) {
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/zip");
                exchange.getResponseHeaders().set("Content-Length", Integer.toString(bytes.length));
                exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : bytes.length);
                if (method.equals("GET")) exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
