package de.nikey.witherboss.config;

import static org.junit.jupiter.api.Assertions.*;

import de.nikey.witherboss.boss.RaidState;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

class TuningTest {
    private static final double[] ORIGINAL_PHASE = {1.0, 1.08, 1.15};

    private static YamlConfiguration defaults() throws Exception {
        try (InputStream in = TuningTest.class.getResourceAsStream("/config.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    private static Tuning tuning(YamlConfiguration config) {
        return new Tuning(() -> config);
    }

    @Test
    void shippedDefaultsParseAndMatchTheDocumentedNumbers() throws Exception {
        Tuning t = tuning(defaults());
        assertEquals(Tuning.Preset.NORMAL, t.preset());
        assertEquals(2000, t.healthPerPlayer(), 1e-9);
        assertEquals(2, t.minScalingPlayers());
        assertEquals(25 * 1200, t.timeLimitTicks());
        assertEquals(420 * 20, t.meltdownAfterTicks());
        assertEquals(5, t.reviveCharges());
        assertEquals(100, t.reviveChannelTicks());
        assertTrue(t.disabledAttacks().isEmpty());
        assertFalse(t.loseInventoryOnDefeat());
        assertFalse(t.loseInventoryOnLogout());
        for (String attack : Tuning.ATTACK_DAMAGE) assertTrue(t.attackDamage(attack) > 0, attack);
    }

    @Test
    void lateFightDamageIsRoughlyHalfOfTheOriginalBalance() throws Exception {
        Tuning t = tuning(defaults());
        int maxStage = t.enrageMaxStages();
        for (String attack : Tuning.ATTACK_DAMAGE) {
            double base = t.attackDamage(attack);
            double originalCore = base * ORIGINAL_PHASE[2] * (1 + 0.03 * 8);
            double originalHammer = base * ORIGINAL_PHASE[1] * (1 + 0.03 * 8);
            assertTrue(t.damage(attack, RaidState.Phase.CORE, maxStage) <= originalCore * 0.5, attack + " phase III");
            assertTrue(t.damage(attack, RaidState.Phase.HAMMER, maxStage) <= originalHammer * 0.6, attack + " phase II");
            assertTrue(t.damage(attack, RaidState.Phase.GLUT, 0) <= base, attack + " phase I");
        }
    }

    @Test
    void damageNeverGrowsFromPhaseToPhaseByDefault() throws Exception {
        Tuning t = tuning(defaults());
        double one = t.damage("slam", RaidState.Phase.GLUT, 0), two = t.damage("slam", RaidState.Phase.HAMMER, 0), three = t.damage("slam", RaidState.Phase.CORE, 0);
        assertTrue(one > two && two > three);
    }

    @Test
    void presetsScaleTheSameBaseValues() throws Exception {
        YamlConfiguration c = defaults();
        double normal = tuning(c).damage("beam", RaidState.Phase.HAMMER, 2);
        double hp = tuning(c).healthPerPlayer();
        int revives = tuning(c).reviveCharges();
        c.set("difficulty.preset", "easy");
        Tuning easy = tuning(c);
        assertEquals(Tuning.Preset.EASY, easy.preset());
        assertTrue(easy.damage("beam", RaidState.Phase.HAMMER, 2) < normal);
        assertTrue(easy.healthPerPlayer() < hp);
        assertEquals(revives + 2, easy.reviveCharges());
        c.set("difficulty.preset", "NIGHTMARE");
        Tuning nightmare = tuning(c);
        assertTrue(nightmare.damage("beam", RaidState.Phase.HAMMER, 2) > normal * 1.5);
        assertEquals(revives - 2, nightmare.reviveCharges());
        c.set("fight.revive.charges", 1);
        assertEquals(1, nightmare.reviveCharges(), "at least one revive always remains");
        c.set("difficulty.preset", "nonsense");
        assertEquals(Tuning.Preset.NORMAL, tuning(c).preset());
    }

    @Test
    void invalidValuesAreClampedInsteadOfBreakingTheFight() throws Exception {
        YamlConfiguration c = defaults();
        c.set("health.per-player", -5);
        c.set("damage.global-multiplier", 9999);
        c.set("fight.time-limit-minutes", 0);
        c.set("minions.max-alive", 500);
        c.set("enrage.stage-seconds", 1);
        Tuning t = tuning(c);
        assertEquals(50, t.healthPerPlayer(), 1e-9);
        assertEquals(10, t.globalDamage(), 1e-9);
        assertEquals(3 * 1200, t.timeLimitTicks());
        assertEquals(40, t.minionCap());
        assertEquals(200, t.enrageStageTicks());
    }

    @Test
    void enrageAndMinionKnobsApply() throws Exception {
        YamlConfiguration c = defaults();
        c.set("enrage.damage-per-stage", 0.1);
        c.set("minions.health-multiplier", 2.0);
        c.set("minions.damage-multiplier", 0.5);
        c.set("fight.disabled-attacks", java.util.List.of("MAW", "Beacons"));
        Tuning t = tuning(c);
        assertEquals(1.3, t.enrageFactor(3), 1e-9);
        assertEquals(2.0, t.minionHealth(), 1e-9);
        assertEquals(0.5, t.minionDamage(), 1e-9);
        assertEquals(java.util.Set.of("maw", "beacons"), t.disabledAttacks());
    }

    @Test
    void describeMentionsTheEffectiveNumbers() throws Exception {
        String text = tuning(defaults()).describe(5);
        assertTrue(text.contains("10000"));
        assertTrue(text.contains("NORMAL"));
    }
}
