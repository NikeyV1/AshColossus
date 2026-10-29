package de.nikey.witherboss.boss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

class PackHostTest {
    @TempDir Path directory;

    @Test
    void servesOnlyTheVerifiedArchiveAndReleasesItsPort() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        String url = "http://127.0.0.1:" + port + PackHost.PATH;
        byte[] bytes = {80, 75, 3, 4, 5};
        Path file = directory.resolve("pack.zip");
        Files.write(file, bytes);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        try (HttpClient client = HttpClient.newHttpClient();
                PackHost host = PackHost.start("127.0.0.1", port, hash, file)) {
            var download = client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());
            assertArrayEquals(bytes, download.body());
            var missing = client.send(HttpRequest.newBuilder(URI.create(url + "/other")).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(404, missing.statusCode());
            var head = client.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, head.statusCode());
            assertEquals(0, head.body().length);
            var post = client.send(HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(405, post.statusCode());
        }
        try (PackHost restarted = PackHost.start("127.0.0.1", port, hash, file)) {
            assertEquals(port, restarted.port());
        }
    }

    @Test
    void refusesAMismatchingChecksumAndInvalidPorts() throws Exception {
        Path file = directory.resolve("pack.zip");
        Files.write(file, new byte[] {1, 2, 3});
        assertThrows(java.io.IOException.class, () -> PackHost.start("127.0.0.1", 25663, "wrong", file));
        assertThrows(java.io.IOException.class, () -> PackHost.start("127.0.0.1", 80, "wrong", file));
    }
}
