package de.nikey.witherboss.boss;

import de.nikey.witherboss.util.AtomicFiles;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class WitherStore implements AutoCloseable {
    public enum Delivery {
        PENDING,
        DELIVERING,
        DELIVERED
    }

    public static final class Claim {
        public final String id, kind;
        public final UUID owner;
        public Delivery state;

        public Claim(String id, UUID owner, String kind, Delivery state) {
            this.id = id;
            this.owner = owner;
            this.kind = kind;
            this.state = state;
        }
    }

    public record Materials(int skulls, int sand, int soil, int sigil) {
        public Materials {
            if (skulls < 0 || skulls > 3 || sand < 0 || soil < 0 || sand + soil > 4 || sigil < 0 || sigil > 1)
                throw new IllegalArgumentException("Invalid summoning materials.");
        }

        public Materials(int skulls, int sand, int soil) {
            this(skulls, sand, soil, 0);
        }
    }

    public record Attempt(
            UUID id,
            UUID leader,
            List<UUID> participants,
            String arena,
            String status,
            boolean preview,
            Materials materials) {}

    public final Map<String, WitherArena> arenas = new TreeMap<>();
    public final Set<String> managedArenas = new HashSet<>();
    public final Map<UUID, Attempt> attempts = new LinkedHashMap<>();
    public final Map<String, Claim> claims = new LinkedHashMap<>();
    public final Set<UUID> titles = new HashSet<>();
    private int victories;
    private long lastFinished;
    private final Path path;
    private final ExecutorService writer =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "witherboss-store");
                        t.setDaemon(true);
                        return t;
                    });
    private CompletableFuture<Void> pending = CompletableFuture.completedFuture(null);

    public WitherStore(Path directory) throws Exception {
        Files.createDirectories(directory);
        path = directory.resolve("state.yml");
        if (!Files.exists(path)) return;
        YamlConfiguration y = new YamlConfiguration();
        y.load(path.toFile());
        int version = y.getInt("version");
        if (version != 1) throw new IllegalArgumentException("Unknown state.yml version " + version + ".");
        var a = y.getConfigurationSection("arenas");
        if (a != null)
            for (String key : a.getKeys(false))
                arenas.put(key, WitherArena.read(key, Objects.requireNonNull(a.getConfigurationSection(key))));
        managedArenas.addAll(y.getStringList("managed-arenas"));
        var runs = y.getConfigurationSection("attempts");
        if (runs != null)
            for (String key : runs.getKeys(false)) {
                UUID id = UUID.fromString(key);
                String p = "attempts." + key;
                attempts.put(
                        id,
                        new Attempt(
                                id,
                                UUID.fromString(y.getString(p + ".leader")),
                                y.getStringList(p + ".participants").stream().map(UUID::fromString).toList(),
                                y.getString(p + ".arena"),
                                y.getString(p + ".status"),
                                y.getBoolean(p + ".preview"),
                                new Materials(
                                        y.getInt(p + ".materials.skulls"),
                                        y.getInt(p + ".materials.sand"),
                                        y.getInt(p + ".materials.soil"),
                                        y.getInt(p + ".materials.sigil"))));
            }
        var c = y.getConfigurationSection("claims");
        if (c != null)
            for (String id : c.getKeys(false)) {
                String p = "claims." + id;
                claims.put(
                        id,
                        new Claim(
                                id,
                                UUID.fromString(y.getString(p + ".owner")),
                                y.getString(p + ".kind"),
                                Delivery.valueOf(y.getString(p + ".state"))));
            }
        y.getStringList("titles").forEach(id -> titles.add(UUID.fromString(id)));
        victories = y.getInt("victories");
        lastFinished = y.getLong("last-finished");
    }

    public boolean defeated() {
        return victories > 0;
    }

    public int victories() {
        return victories;
    }

    public long lastFinished() {
        return lastFinished;
    }

    public void finished(long now) {
        lastFinished = now;
        save(true);
    }

    public void participant(UUID id, UUID player) {
        Attempt a = attempts.get(id);
        if (a == null || !"RUNNING".equals(a.status()) || a.participants().contains(player)) return;
        List<UUID> participants = new ArrayList<>(a.participants());
        participants.add(player);
        attempt(new Attempt(id, a.leader(), List.copyOf(participants), a.arena(), a.status(), a.preview(), a.materials()));
    }

    public void attempt(Attempt a) {
        attempts.put(a.id(), a);
        save(true);
    }

    public void status(UUID id, String status) {
        Attempt a = attempts.get(id);
        attempt(new Attempt(a.id(), a.leader(), a.participants(), a.arena(), status, a.preview(), a.materials()));
    }

    public void forfeit(UUID id) {
        Attempt a = attempts.get(id);
        if (a == null || a.status().equals("VICTORY") || a.status().equals("CANCELLED") || a.status().equals("REFUND")) return;
        status(id, a.preview() ? "TECHNICAL" : "CANCELLED");
    }

    public boolean refund(UUID id) {
        Attempt a = attempts.get(id);
        if (a == null || !a.status().equals("RUNNING") && !a.status().equals("PREPARED")) return false;
        if (a.preview()) {
            status(id, "TECHNICAL");
            return false;
        }
        if (a.materials().skulls() > 0) add(id + "_refund_skulls", a.leader(), "skulls:" + a.materials().skulls());
        if (a.materials().sand() > 0) add(id + "_refund_sand", a.leader(), "sand:" + a.materials().sand());
        if (a.materials().soil() > 0) add(id + "_refund_soil", a.leader(), "soil:" + a.materials().soil());
        if (a.materials().sigil() > 0) add(id + "_refund_sigil", a.leader(), "sigil");
        status(id, "REFUND");
        return true;
    }

    public Claim add(String id, UUID owner, String kind) {
        Claim existing = claims.get(id);
        if (existing != null) return existing;
        Claim claim = new Claim(id, owner, kind, Delivery.PENDING);
        claims.put(id, claim);
        return claim;
    }

    public boolean victory(UUID id, Collection<UUID> eligible) {
        Attempt a = attempts.get(id);
        if (a == null || a.preview() || !"RUNNING".equals(a.status())) return false;
        titles.addAll(eligible);
        boolean first = victories == 0;
        victories++;
        status(id, "VICTORY");
        return first;
    }

    public List<Claim> pending(UUID owner) {
        return claims.values().stream().filter(c -> c.owner.equals(owner) && c.state == Delivery.PENDING).toList();
    }

    public void delivery(Claim claim, Delivery next) {
        claim.state = next;
        save(true);
    }

    public void recover(boolean refundPrepared) {
        for (Attempt a : List.copyOf(attempts.values())) {
            if ("RUNNING".equals(a.status())) forfeit(a.id());
            else if ("PREPARED".equals(a.status())) {
                if (refundPrepared) refund(a.id());
                else status(a.id(), "CANCELLED");
            }
        }
        save(true);
    }

    public void save(boolean durable) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("version", 1);
        y.set("victories", victories);
        y.set("last-finished", lastFinished);
        arenas.forEach((name, a) -> a.write(y.createSection("arenas." + name)));
        y.set("managed-arenas", managedArenas.stream().sorted().toList());
        attempts.forEach(
                (id, a) -> {
                    String p = "attempts." + id;
                    y.set(p + ".leader", a.leader().toString());
                    y.set(p + ".participants", a.participants().stream().map(UUID::toString).toList());
                    y.set(p + ".arena", a.arena());
                    y.set(p + ".status", a.status());
                    y.set(p + ".preview", a.preview());
                    y.set(p + ".materials.skulls", a.materials().skulls());
                    y.set(p + ".materials.sand", a.materials().sand());
                    y.set(p + ".materials.soil", a.materials().soil());
                    y.set(p + ".materials.sigil", a.materials().sigil());
                });
        claims.forEach(
                (id, c) -> {
                    String p = "claims." + id;
                    y.set(p + ".owner", c.owner.toString());
                    y.set(p + ".kind", c.kind);
                    y.set(p + ".state", c.state.name());
                });
        y.set("titles", titles.stream().map(UUID::toString).sorted().toList());
        byte[] snapshot = y.saveToString().getBytes(StandardCharsets.UTF_8);
        pending =
                pending.handle((ignored, failure) -> (Void) null).thenRunAsync(
                        () -> {
                            try {
                                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                                try (FileChannel f = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                                    ByteBuffer buffer = ByteBuffer.wrap(snapshot);
                                    while (buffer.hasRemaining()) f.write(buffer);
                                    f.force(true);
                                }
                                if (Files.exists(path))
                                    Files.copy(path, path.resolveSibling(path.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                                AtomicFiles.replace(temp, path);
                            } catch (Exception e) {
                                throw new CompletionException("Saving state.yml failed.", e);
                            }
                        },
                        writer);
        if (durable) pending.join();
    }

    @Override
    public void close() {
        try {
            pending.join();
        } finally {
            writer.shutdown();
        }
    }
}
