package de.nikey.witherboss.boss;

import java.util.*;

public final class BossDirector {
    public enum Kind {
        HEAVY,
        SUPPORT,
        ZONE
    }

    public enum Attack {
        SWEEP("Ash Slash", "get behind the boss!", Kind.HEAVY, true, 120, 0),
        SLAM("Grave Fist", "jump over the wave!", Kind.HEAVY, false, 300, 0),
        BEAM("Soul Beam", "leave the lane or hide behind a pillar!", Kind.HEAVY, false, 300, 0),
        SKULLS("Consuming Skulls", "smash them or dodge!", Kind.SUPPORT, false, 400, 0),
        CHAIN("Soul Chain", "break the golden lock!", Kind.SUPPORT, false, 600, 0),
        SPIKES("Bone Spikes", "sidestep the spike line!", Kind.HEAVY, false, 360, 0),
        DEBRIS("Forge Collapse", "boulders fall from the pillars, avoid the circles!", Kind.ZONE, false, 640, 700),
        BLADE("Chain Wheel", "jump over the chain!", Kind.HEAVY, false, 560, 1100),
        MINIONS("Ash Sentinel", "take out the sentinels!", Kind.SUPPORT, false, 1300, 500),
        HAMMER("Hammerfall", "leave the lane!", Kind.HEAVY, false, 260, 0),
        SPIN("Ember Whirl", "get close!", Kind.HEAVY, true, 300, 0),
        NOVA("Core Nova", "keep your distance!", Kind.HEAVY, false, 300, 0),
        RAIN("Ember Rain", "dodge the circles!", Kind.ZONE, false, 500, 0),
        COMET("Consuming Comet", "gather in the golden circle!", Kind.ZONE, false, 700, 0),
        BRAND("Brand Mark", "spread out!", Kind.ZONE, false, 600, 0),
        GRID("Ember Grid", "stand between the lanes!", Kind.ZONE, false, 780, 500),
        MIASMA("Consuming Miasma", "avoid the mist, leave the circles!", Kind.ZONE, false, 800, 800),
        BEACONS("Consuming Beacons", "destroy the beacons together!", Kind.ZONE, false, 1900, 1100),
        STORM("Consuming Storm", "run with the rotation!", Kind.ZONE, false, 800, 0),
        MAW("Consuming Maw", "run against the pull or hide behind a pillar!", Kind.ZONE, false, 900, 600),
        RETRIBUTION("Retribution", "get away from the boss!", Kind.HEAVY, false, 320, 0);

        public final String label, hint;
        public final Kind kind;
        public final boolean melee;
        public final int cooldown, unlock;

        Attack(String label, String hint, Kind kind, boolean melee, int cooldown, int unlock) {
            this.label = label;
            this.hint = hint;
            this.kind = kind;
            this.melee = melee;
            this.cooldown = cooldown;
            this.unlock = unlock;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public RaidState.Phase phase() {
            return switch (this) {
                case HAMMER, RAIN, COMET, BRAND, SPIN, NOVA, GRID, MIASMA, BEACONS, BLADE -> RaidState.Phase.HAMMER;
                case STORM, MAW -> RaidState.Phase.CORE;
                default -> RaidState.Phase.GLUT;
            };
        }

        public boolean reaction() {
            return this == RETRIBUTION;
        }
    }

    public static final Set<Attack> ESCORTABLE = EnumSet.of(Attack.SWEEP, Attack.SLAM, Attack.HAMMER, Attack.SPIN, Attack.BEAM, Attack.SPIKES, Attack.BLADE);

    public record Context(RaidState.Phase phase, int now, int near, int alive, boolean soloTest, int phaseStart, int stage, int minions) {
        public Context(RaidState.Phase phase, int now, int near, int alive, boolean soloTest, int phaseStart) {
            this(phase, now, near, alive, soloTest, phaseStart, 0, 0);
        }
    }

    public record Choice(Attack primary, Attack escort, int escortDelay) {}

    private final Random random;
    private final Deque<Attack> history = new ArrayDeque<>();
    private final Map<Attack, Integer> readyAt = new EnumMap<>(Attack.class);
    private final Set<String> disabled = new HashSet<>();

    public BossDirector(Random random) {
        this.random = random;
    }

    public void disable(Collection<String> keys) {
        disabled.clear();
        disabled.addAll(keys);
    }

    public static Map<Attack, Double> weights(RaidState.Phase phase) {
        Map<Attack, Double> w = new EnumMap<>(Attack.class);
        switch (phase) {
            case GLUT -> {
                w.put(Attack.SWEEP, 3.0);
                w.put(Attack.SLAM, 3.0);
                w.put(Attack.BEAM, 3.0);
                w.put(Attack.SKULLS, 2.0);
                w.put(Attack.CHAIN, 2.0);
                w.put(Attack.SPIKES, 2.5);
                w.put(Attack.DEBRIS, 1.5);
                w.put(Attack.BLADE, 1.5);
                w.put(Attack.MINIONS, 2.0);
            }
            case HAMMER -> {
                w.put(Attack.HAMMER, 3.0);
                w.put(Attack.SPIN, 2.0);
                w.put(Attack.NOVA, 2.0);
                w.put(Attack.RAIN, 2.0);
                w.put(Attack.COMET, 1.5);
                w.put(Attack.BRAND, 1.5);
                w.put(Attack.SKULLS, 1.0);
                w.put(Attack.CHAIN, 1.0);
                w.put(Attack.BEAM, 1.0);
                w.put(Attack.SPIKES, 1.5);
                w.put(Attack.DEBRIS, 2.0);
                w.put(Attack.BLADE, 2.0);
                w.put(Attack.MINIONS, 2.0);
                w.put(Attack.GRID, 2.0);
                w.put(Attack.MIASMA, 1.5);
                w.put(Attack.BEACONS, 1.5);
            }
            case CORE -> {
                w.put(Attack.SWEEP, 1.5);
                w.put(Attack.SLAM, 2.0);
                w.put(Attack.BEAM, 1.5);
                w.put(Attack.SKULLS, 1.0);
                w.put(Attack.CHAIN, 1.0);
                w.put(Attack.SPIN, 1.5);
                w.put(Attack.NOVA, 1.5);
                w.put(Attack.RAIN, 1.5);
                w.put(Attack.COMET, 1.0);
                w.put(Attack.BRAND, 1.0);
                w.put(Attack.STORM, 2.0);
                w.put(Attack.MAW, 1.5);
                w.put(Attack.SPIKES, 1.5);
                w.put(Attack.DEBRIS, 2.0);
                w.put(Attack.BLADE, 2.0);
                w.put(Attack.MINIONS, 1.5);
                w.put(Attack.GRID, 2.5);
                w.put(Attack.MIASMA, 2.0);
                w.put(Attack.BEACONS, 2.0);
            }
            default -> {}
        }
        return w;
    }

    public boolean allowed(Attack a, Context c) {
        if (!weights(c.phase()).containsKey(a) || disabled.contains(a.key())) return false;
        if (c.now() < readyAt.getOrDefault(a, 0)) return false;
        if (a.melee && c.near() == 0) return false;
        if (a == Attack.CHAIN && c.alive() < 2 && !c.soloTest()) return false;
        if (c.now() - c.phaseStart() < a.unlock) return false;
        if (a == Attack.MINIONS && c.minions() >= 4) return false;
        if (a == Attack.BEACONS && (c.minions() >= 6 || c.alive() < 2 && !c.soloTest())) return false;
        if (!history.isEmpty() && history.peekFirst() == a) return false;
        return true;
    }

    public Choice choose(Context c) {
        Map<Attack, Double> w = weights(c.phase());
        List<Attack> pool = new ArrayList<>();
        for (Attack a : w.keySet()) if (allowed(a, c)) pool.add(a);
        List<Attack> fresh = pool.stream().filter(a -> !recent(a, 3)).toList();
        if (!fresh.isEmpty()) pool = new ArrayList<>(fresh);
        if (pool.isEmpty())
            for (Attack a : w.keySet())
                if (!disabled.contains(a.key()) && (history.isEmpty() || history.peekFirst() != a) && (!a.melee || c.near() > 0) && (a != Attack.CHAIN || c.alive() >= 2 || c.soloTest())
                        && c.now() - c.phaseStart() >= a.unlock && (a != Attack.MINIONS || c.minions() < 4)
                        && (a != Attack.BEACONS || c.minions() < 6 && (c.alive() >= 2 || c.soloTest())))
                    pool.add(a);
        if (pool.isEmpty()) pool.add(Attack.SLAM);
        double total = 0;
        double[] cumulative = new double[pool.size()];
        for (int i = 0; i < pool.size(); i++) {
            Attack a = pool.get(i);
            double weight = w.getOrDefault(a, 1.0);
            if (a == Attack.NOVA && c.near() >= Math.max(2, c.alive() / 2)) weight *= 1.8;
            if (a.melee && c.near() >= 2) weight *= 1.3;
            total += weight;
            cumulative[i] = total;
        }
        double roll = random.nextDouble() * total;
        Attack primary = pool.getLast();
        for (int i = 0; i < pool.size(); i++)
            if (roll <= cumulative[i]) {
                primary = pool.get(i);
                break;
            }
        record(primary, c.now());
        Attack escort = null;
        int delay = 0;
        double chance = (c.phase() == RaidState.Phase.HAMMER ? 0.25 : c.phase() == RaidState.Phase.CORE ? 0.45 : 0.0)
                + Math.max(0, c.alive() - 4) * 0.06 + c.stage() * 0.025;
        if (c.phase() == RaidState.Phase.GLUT && c.alive() < 5) chance = 0;
        chance = Math.min(0.8, chance);
        if (ESCORTABLE.contains(primary) && random.nextDouble() < chance) {
            List<Attack> options = new ArrayList<>();
            for (Attack a : List.of(Attack.SKULLS, Attack.CHAIN, Attack.MINIONS))
                if (!disabled.contains(a.key()) && c.now() >= readyAt.getOrDefault(a, 0) && (a != Attack.CHAIN || c.alive() >= 2 || c.soloTest())
                        && (a != Attack.MINIONS || c.phase() != RaidState.Phase.GLUT && c.minions() < 2 && c.now() - c.phaseStart() >= a.unlock))
                    options.add(a);
            if (!options.isEmpty()) {
                escort = options.get(random.nextInt(options.size()));
                readyAt.put(escort, c.now() + escort.cooldown);
                delay = 8 + random.nextInt(10);
            }
        }
        return new Choice(primary, escort, delay);
    }

    public static boolean compatible(Attack primary, Attack escort) {
        return escort == null || escort.kind == Kind.SUPPORT && ESCORTABLE.contains(primary);
    }

    private boolean recent(Attack a, int depth) {
        int i = 0;
        for (Attack h : history) {
            if (i++ >= depth) break;
            if (h == a) return true;
        }
        return false;
    }

    public void record(Attack a, int now) {
        history.addFirst(a);
        while (history.size() > 6) history.removeLast();
        readyAt.put(a, now + a.cooldown);
    }

    public void reset() {
        history.clear();
        readyAt.clear();
    }

    public static int gap(RaidState.Phase phase, int fightTicks, Random random) {
        return gap(phase, fightTicks, random, 0, 4);
    }

    public static int gap(RaidState.Phase phase, int fightTicks, Random random, int stage, int alive) {
        return gap(phase, fightTicks, random, stage, alive, 24_000);
    }

    public static int gap(RaidState.Phase phase, int fightTicks, Random random, int stage, int alive, int timeLimit) {
        int[] range =
                switch (phase) {
                    case GLUT -> new int[] {36, 50};
                    case HAMMER -> new int[] {28, 42};
                    default -> new int[] {18, 32};
                };
        int g = range[0] + random.nextInt(range[1] - range[0] + 1);
        double factor = Math.max(0.55, 1 - 0.04 * stage - Math.max(0, alive - 5) * 0.03);
        if (fightTicks >= timeLimit * 0.6) factor *= 0.8;
        return (int) Math.round(g * factor);
    }

    public List<Attack> history() {
        return List.copyOf(history);
    }
}
