package de.nikey.witherboss.boss;

import org.bukkit.Location;

import java.util.*;

public final class WitherLobby {
    public enum Result {
        WAITING,
        START,
        TOO_FEW,
        CANCELLED
    }

    private final UUID id, leader;
    private final String arena;
    private final WitherStore.Materials materials;
    private final int minimum;
    private final Map<UUID, Location> origins = new LinkedHashMap<>();
    private final Set<UUID> confirmed = new HashSet<>();
    private final int total;
    private int remaining;
    private boolean left, rehearsal;

    public WitherLobby(UUID id, UUID leader, String arena, WitherStore.Materials materials, int ticks, int minimum) {
        if (ticks < 1 || minimum < 1) throw new IllegalArgumentException("Invalid preparation.");
        this.id = id;
        this.leader = leader;
        this.arena = arena;
        this.materials = materials;
        this.remaining = ticks;
        this.total = ticks;
        this.minimum = minimum;
    }

    public boolean rehearsal() {
        return rehearsal;
    }

    public WitherLobby rehearsal(boolean value) {
        rehearsal = value;
        return this;
    }

    public UUID id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public String arena() {
        return arena;
    }

    public WitherStore.Materials materials() {
        return materials;
    }

    public int minimum() {
        return minimum;
    }

    public int total() {
        return total;
    }

    public int remaining() {
        return remaining;
    }

    public boolean has(UUID player) {
        return origins.containsKey(player);
    }

    public int size() {
        return origins.size();
    }

    public List<UUID> members() {
        return List.copyOf(origins.keySet());
    }

    public Map<UUID, Location> origins() {
        return Collections.unmodifiableMap(origins);
    }

    public boolean add(UUID player, Location origin) {
        return add(player, origin, true);
    }

    public boolean add(UUID player, Location origin, boolean agreed) {
        if (origins.containsKey(player)) return false;
        origins.put(player, origin.clone());
        if (agreed) confirmed.add(player);
        return true;
    }

    public boolean confirm(UUID player) {
        return origins.containsKey(player) && confirmed.add(player);
    }

    public boolean confirmed(UUID player) {
        return confirmed.contains(player);
    }

    public int confirmedCount() {
        return confirmed.size();
    }

    public List<UUID> confirmedMembers() {
        return origins.keySet().stream().filter(confirmed::contains).toList();
    }

    public List<UUID> undecided() {
        return origins.keySet().stream().filter(id -> !confirmed.contains(id)).toList();
    }

    public Location remove(UUID player) {
        Location origin = origins.remove(player);
        confirmed.remove(player);
        if (origin != null && player.equals(leader)) left = true;
        return origin;
    }

    public Location drop(UUID player) {
        confirmed.remove(player);
        return origins.remove(player);
    }

    public boolean someoneLeft() {
        return left;
    }

    public Result tick() {
        if (origins.isEmpty()) return left ? Result.CANCELLED : Result.TOO_FEW;
        if (--remaining > 0) return Result.WAITING;
        if (confirmed.size() >= minimum) return Result.START;
        return left ? Result.CANCELLED : Result.TOO_FEW;
    }
}
