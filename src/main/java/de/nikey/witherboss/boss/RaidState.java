package de.nikey.witherboss.boss;

import java.util.*;

public final class RaidState {
    public enum Phase {
        INTRO,
        GLUT,
        HAMMER,
        CORE,
        DYING,
        ENDED
    }

    public enum Status {
        ALIVE,
        FALLEN,
        DISCONNECTED
    }

    public enum Part {
        BODY,
        HEART
    }

    public static final double GLUT_FLOOR = 0.66, HAMMER_FLOOR = 0.33;

    public static final class Member {
        public Status status = Status.ALIVE;
        public double damage;
        public int actions, protectionUntil, targeted;
        public long disconnectedAt;

        public boolean eligible() {
            return eligible(20);
        }

        public boolean eligible(double minimumDamage) {
            return damage >= minimumDamage || actions > 0;
        }
    }

    public record Rules(int revives, double body, double coreBody, double heart) {
        public static final Rules DEFAULT = new Rules(3, 0.5, 0.8, 1.5);
    }

    private final double perPlayer;
    private final int floorCount;
    private final Rules rules;
    private double maximum, health;
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    private final Set<UUID> processedProjectiles = new HashSet<>();
    private Phase phase = Phase.INTRO;
    private int revives;

    public RaidState(Collection<UUID> ids, double perPlayer, int floorCount) {
        this(ids, perPlayer, floorCount, Rules.DEFAULT);
    }

    public RaidState(Collection<UUID> ids, double perPlayer, int floorCount, Rules rules) {
        if (ids.isEmpty()
                || !Double.isFinite(perPlayer)
                || perPlayer <= 0
                || floorCount < 1) throw new IllegalArgumentException("Invalid raid scaling.");
        this.perPlayer = perPlayer;
        this.floorCount = floorCount;
        this.rules = rules;
        this.revives = rules.revives();
        ids.forEach(id -> members.put(id, new Member()));
        maximum = scaled(members.size());
        health = maximum;
    }

    private double scaled(int count) {
        return perPlayer * Math.max(floorCount, count);
    }

    public boolean enlist(UUID id) {
        if (members.containsKey(id) || phase == Phase.DYING || phase == Phase.ENDED) return false;
        double previous = maximum;
        members.put(id, new Member());
        maximum = scaled(members.size());
        health *= maximum / previous;
        return true;
    }

    public Map<UUID, Member> members() {
        return Collections.unmodifiableMap(members);
    }

    public Member member(UUID id) {
        return members.get(id);
    }

    public Phase phase() {
        return phase;
    }

    public void begin() {
        if (phase == Phase.INTRO) phase = Phase.GLUT;
    }

    void preview(Phase target) {
        if (target == Phase.HAMMER || target == Phase.CORE) {
            phase = target;
            health = maximum * (target == Phase.HAMMER ? GLUT_FLOOR : HAMMER_FLOOR);
        }
    }

    public double maximum() {
        return maximum;
    }

    public double health() {
        return health;
    }

    public double fraction() {
        return Math.clamp(health / maximum, 0, 1);
    }

    public int revives() {
        return revives;
    }

    public boolean living(UUID id, int tick) {
        Member m = member(id);
        return m != null && m.status == Status.ALIVE && tick >= m.protectionUntil;
    }

    public boolean projectile(UUID id) {
        return id == null || processedProjectiles.add(id);
    }

    public double floor() {
        return switch (phase) {
            case GLUT -> maximum * GLUT_FLOOR;
            case HAMMER -> maximum * HAMMER_FLOOR;
            default -> 0;
        };
    }

    public double ceiling() {
        return switch (phase) {
            case GLUT -> maximum;
            case HAMMER -> maximum * GLUT_FLOOR;
            case CORE -> maximum * HAMMER_FLOOR;
            default -> health;
        };
    }

    public static double multiplier(Rules rules, Phase phase, Part part, boolean exposed) {
        if (part == Part.HEART && exposed) return rules.heart();
        return phase == Phase.CORE ? rules.coreBody() : rules.body();
    }

    public static double multiplier(Phase phase, Part part, boolean exposed) {
        return multiplier(Rules.DEFAULT, phase, part, exposed);
    }

    public double hit(UUID player, Part part, double damage, boolean exposed, int tick, UUID projectile) {
        if (!Double.isFinite(damage)
                || damage <= 0
                || !living(player, tick)
                || phase == Phase.INTRO
                || phase == Phase.DYING
                || phase == Phase.ENDED
                || !projectile(projectile)) return 0;
        Member m = member(player);
        double scaled = damage * multiplier(rules, phase, part, exposed);
        double applied = Math.max(0, Math.min(scaled, health - floor()));
        health -= applied;
        m.damage += applied;
        if (health <= floor() + 1e-7)
            phase =
                    switch (phase) {
                        case GLUT -> Phase.HAMMER;
                        case HAMMER -> Phase.CORE;
                        case CORE -> Phase.DYING;
                        default -> phase;
                    };
        return applied;
    }

    public double heal(double fraction) {
        if (phase != Phase.GLUT && phase != Phase.HAMMER && phase != Phase.CORE) return 0;
        double before = health;
        health = Math.min(ceiling(), health + maximum * fraction);
        return health - before;
    }

    public boolean fallen(UUID id, boolean logout, long now) {
        Member member = member(id);
        if (member == null
                || member.status == Status.DISCONNECTED
                || !logout && member.status != Status.ALIVE) return false;
        member.status = logout ? Status.DISCONNECTED : Status.FALLEN;
        member.disconnectedAt = logout ? now : 0;
        return true;
    }

    public boolean reconnect(UUID id, long now) {
        Member m = member(id);
        if (m == null || m.status != Status.DISCONNECTED || now - m.disconnectedAt > 60_000) return false;
        m.status = Status.FALLEN;
        return true;
    }

    public boolean revive(UUID id, int tick) {
        Member m = member(id);
        if (revives <= 0 || m == null || m.status != Status.FALLEN) return false;
        revives--;
        m.status = Status.ALIVE;
        m.protectionUntil = tick + 60;
        return true;
    }

    public boolean wiped() {
        return members.values().stream().noneMatch(m -> m.status == Status.ALIVE);
    }

    public long alive() {
        return members.values().stream().filter(m -> m.status == Status.ALIVE).count();
    }

    public void end() {
        phase = Phase.ENDED;
        processedProjectiles.clear();
    }
}
