package de.nikey.witherboss.config;

import de.nikey.witherboss.boss.RaidState;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

public final class Tuning {
    public static final String[] ATTACK_DAMAGE = {"sweep", "slam", "wave", "beam", "skull", "chain", "hammer", "fissure", "spin", "nova", "ember", "comet", "pulse", "brand", "storm", "maw", "boundary", "minion", "debris", "grid", "spike", "blade", "miasma", "retribution", "beacon", "beaconblast"};

    public enum Preset {
        EASY(0.80, 0.70, 0.80, 0.70, 0.50, 2),
        NORMAL(1.0, 1.0, 1.0, 1.0, 1.0, 0),
        HARD(1.25, 1.30, 1.25, 1.30, 1.50, -1),
        NIGHTMARE(1.50, 1.75, 1.50, 1.60, 2.00, -2);

        public final double health, damage, minionHealth, minionDamage, enrage;
        public final int revives;

        Preset(double health, double damage, double minionHealth, double minionDamage, double enrage, int revives) {
            this.health = health;
            this.damage = damage;
            this.minionHealth = minionHealth;
            this.minionDamage = minionDamage;
            this.enrage = enrage;
            this.revives = revives;
        }

        public static Preset parse(String name) {
            if (name != null)
                for (Preset p : values()) if (p.name().equalsIgnoreCase(name.trim())) return p;
            return NORMAL;
        }
    }

    private final Supplier<FileConfiguration> source;

    public Tuning(Supplier<FileConfiguration> source) {
        this.source = source;
    }

    private FileConfiguration c() {
        return source.get();
    }

    private double num(String path, double min, double max) {
        double value = c().getDouble(path);
        if (!Double.isFinite(value)) value = c().getDefaults() == null ? min : c().getDefaults().getDouble(path);
        return Math.clamp(value, min, max);
    }

    private int whole(String path, int min, int max) {
        return Math.clamp(c().getInt(path), min, max);
    }

    public Preset preset() {
        return Preset.parse(c().getString("difficulty.preset", "NORMAL"));
    }

    public double healthPerPlayer() {
        return num("health.per-player", 50, 50_000) * num("health.multiplier", 0.05, 20) * preset().health;
    }

    public int minScalingPlayers() {
        return whole("health.min-scaling-players", 1, 50);
    }

    public double testHealthFactor() {
        return num("test.health-factor", 0.05, 10);
    }

    public double attackDamage(String attack) {
        return num("damage.attacks." + attack, 0, 400);
    }

    public double globalDamage() {
        return num("damage.global-multiplier", 0, 10) * preset().damage;
    }

    public double phaseDamage(RaidState.Phase phase) {
        String key = switch (phase) {
            case HAMMER -> "hammer";
            case CORE, DYING -> "core";
            default -> "glut";
        };
        return num("damage.phase-multipliers." + key, 0, 10);
    }

    public double damage(String attack, RaidState.Phase phase, int stage) {
        return attackDamage(attack) * globalDamage() * phaseDamage(phase) * enrageFactor(stage);
    }

    public double enrageFactor(int stage) {
        return 1 + num("enrage.damage-per-stage", 0, 0.5) * preset().enrage * stage;
    }

    public int enrageStageTicks() {
        return Math.max(200, (int) (num("enrage.stage-seconds", 10, 600) * 20));
    }

    public int enrageMaxStages() {
        return whole("enrage.max-stages", 0, 30);
    }

    public int timeLimitTicks() {
        return (int) (num("fight.time-limit-minutes", 3, 120) * 1200);
    }

    public boolean meltdownEnabled() {
        return c().getBoolean("fight.meltdown.enabled", true);
    }

    public int meltdownAfterTicks() {
        return (int) (num("fight.meltdown.after-seconds", 60, 1800) * 20);
    }

    public int reviveCharges() {
        return Math.max(1, whole("fight.revive.charges", 0, 50) + preset().revives);
    }

    public int reviveChannelTicks() {
        return (int) (num("fight.revive.channel-seconds", 1, 30) * 20);
    }

    public double pauseMultiplier() {
        return num("fight.pause-multiplier", 0.2, 5);
    }

    public Set<String> disabledAttacks() {
        Set<String> out = new HashSet<>();
        for (String s : c().getStringList("fight.disabled-attacks")) out.add(s.toLowerCase(Locale.ROOT));
        return out;
    }

    public double bodyMultiplier() {
        return num("player-damage.body-multiplier", 0.01, 10);
    }

    public double coreBodyMultiplier() {
        return num("player-damage.core-phase-body-multiplier", 0.01, 10);
    }

    public double heartMultiplier() {
        return num("player-damage.heart-multiplier", 0.01, 10);
    }

    public boolean retributionEnabled() {
        return c().getBoolean("retribution.enabled", true);
    }

    public double retributionBurstPercent() {
        return num("retribution.burst-percent", 0.05, 10);
    }

    public int retributionAttackers() {
        return whole("retribution.attackers", 2, 8);
    }

    public double minionHealth() {
        return num("minions.health-multiplier", 0.05, 20) * preset().minionHealth;
    }

    public double minionDamage() {
        return num("minions.damage-multiplier", 0, 10) * preset().minionDamage;
    }

    public double minionCount() {
        return num("minions.count-multiplier", 0, 5);
    }

    public int minionCap() {
        return whole("minions.max-alive", 1, 40);
    }

    public double beaconHealth() {
        return num("minions.beacon-health-multiplier", 0.05, 20) * preset().minionHealth;
    }

    public double minionBaseHealth(boolean brute) {
        return num(brute ? "minions.brute-health" : "minions.warrior-health", 1, 5000);
    }

    public boolean keepInventoryOnRaidDeath() {
        return c().getBoolean("death.keep-inventory", true);
    }

    public boolean loseInventoryOnDefeat() {
        return c().getBoolean("death.lose-inventory-on-defeat", false);
    }

    public boolean loseInventoryOnLogout() {
        return c().getBoolean("death.lose-inventory-on-logout", false);
    }

    public double minimumTps() {
        return num("event.minimum-tps", 5, 19.5);
    }

    public int minOnline() {
        return whole("event.min-online", 1, 500);
    }

    public int minParticipants() {
        return whole("event.min-participants", 1, 100);
    }

    public int lobbySeconds() {
        return whole("event.lobby-seconds", 5, 600);
    }

    public double joinRadius() {
        return num("event.join-radius", 5, 1000);
    }

    public boolean oneTimeOnly() {
        return c().getBoolean("event.one-time-only", false);
    }

    public int cooldownMinutes() {
        return whole("event.cooldown-minutes", 0, 100_000);
    }

    public boolean refundOnFailedStart() {
        return c().getBoolean("event.refund-on-failed-start", true);
    }

    public String describe(int players) {
        double perPlayer = healthPerPlayer();
        double hp = perPlayer * Math.max(minScalingPlayers(), players);
        StringBuilder out = new StringBuilder();
        out.append("Preset ").append(preset().name()).append(" · boss HP for ").append(players).append(" players: ").append(Math.round(hp));
        out.append(" · sweep damage I/II/III: ");
        for (RaidState.Phase phase : new RaidState.Phase[] {RaidState.Phase.GLUT, RaidState.Phase.HAMMER, RaidState.Phase.CORE}) {
            out.append(Math.round(damage("sweep", phase, 0) * 10) / 10.0).append(phase == RaidState.Phase.CORE ? "" : "/");
        }
        out.append(" · revive charges ").append(reviveCharges());
        out.append(" · time limit ").append(timeLimitTicks() / 1200).append(" min");
        return out.toString();
    }
}
