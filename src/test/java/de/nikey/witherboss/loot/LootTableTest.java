package de.nikey.witherboss.loot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

class LootTableTest {
    @TempDir Path dir;
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();

    private LootTable table() {
        return new LootTable(dir.resolve("loot.yml"));
    }

    private List<LootTable.Participant> party() {
        return List.of(new LootTable.Participant(a, 100), new LootTable.Participant(b, 900), new LootTable.Participant(c, 50));
    }

    @Test
    void eachPlayerRollsSeparately() {
        LootTable t = table();
        t.add(LootEntry.command(0, "give %player% diamond 3", 100, LootEntry.Scope.EACH_PLAYER));
        var rewards = t.roll(party(), new Random(1));
        assertEquals(3, rewards.size());
        assertEquals(Set.of(a, b, c), new HashSet<>(rewards.stream().map(LootTable.Reward::player).toList()));
    }

    @Test
    void randomPlayerGivesOneWinnerAndTopDamageGivesTheBestFighter() {
        LootTable t = table();
        t.add(LootEntry.xp(0, 10, 10, 100, LootEntry.Scope.RANDOM_PLAYER));
        t.add(LootEntry.xp(0, 20, 20, 100, LootEntry.Scope.TOP_DAMAGE));
        Set<UUID> randomWinners = new HashSet<>();
        for (int seed = 0; seed < 60; seed++) {
            var rewards = t.roll(party(), new Random(seed));
            assertEquals(2, rewards.size());
            var random = rewards.stream().filter(r -> r.xp() == 10).toList();
            var top = rewards.stream().filter(r -> r.xp() == 20).toList();
            assertEquals(1, random.size());
            assertEquals(b, top.getFirst().player());
            randomWinners.add(random.getFirst().player());
        }
        assertEquals(3, randomWinners.size(), "every participant can win the random entry");
    }

    @Test
    void chanceIsRespectedStatistically() {
        LootTable t = table();
        t.add(LootEntry.command(0, "say lucky", 25, LootEntry.Scope.EACH_PLAYER));
        t.add(LootEntry.command(0, "say never", 0, LootEntry.Scope.EACH_PLAYER));
        Random random = new Random(42);
        int hits = 0;
        for (int i = 0; i < 4000; i++)
            for (var r : t.roll(List.of(new LootTable.Participant(a, 1)), random)) {
                assertEquals("say lucky", r.command());
                hits++;
            }
        assertEquals(1000, hits, 120);
    }

    @Test
    void amountsStayWithinMinAndMax() {
        LootTable t = table();
        t.add(LootEntry.xp(0, 300, 500, 100, LootEntry.Scope.EACH_PLAYER));
        Set<Integer> seen = new HashSet<>();
        Random random = new Random(7);
        for (int i = 0; i < 500; i++) {
            int xp = t.roll(List.of(new LootTable.Participant(a, 1)), random).getFirst().xp();
            assertTrue(xp >= 300 && xp <= 500);
            seen.add(xp);
        }
        assertTrue(seen.size() > 50);
    }

    @Test
    void disabledEntriesAndDisabledTablesDropNothing() {
        LootTable t = table();
        LootEntry e = t.add(LootEntry.command(0, "say hi", 100, LootEntry.Scope.EACH_PLAYER));
        e.enabled = false;
        assertTrue(t.roll(party(), new Random(1)).isEmpty());
        e.enabled = true;
        assertEquals(3, t.roll(party(), new Random(1)).size());
        t.enabled(false);
        assertTrue(t.roll(party(), new Random(1)).isEmpty());
        assertTrue(table().roll(List.of(), new Random(1)).isEmpty());
    }

    @Test
    void entriesPersistWithStableIdsAcrossSaveAndLoad() throws Exception {
        LootTable t = table();
        t.add(LootEntry.command(0, "give %player% emerald 5", 42.5, LootEntry.Scope.TOP_DAMAGE));
        LootEntry removed = t.add(LootEntry.xp(0, 1, 2, 10, LootEntry.Scope.EACH_PLAYER));
        t.add(LootEntry.trophy(0));
        t.remove(removed.id);
        t.get(1).enabled = false;
        t.save();
        LootTable loaded = LootTable.load(dir.resolve("loot.yml"));
        assertEquals(2, loaded.entries().size());
        LootEntry cmd = loaded.get(1);
        assertEquals("give %player% emerald 5", cmd.command);
        assertEquals(42.5, cmd.chance, 1e-9);
        assertEquals(LootEntry.Scope.TOP_DAMAGE, cmd.scope);
        assertFalse(cmd.enabled);
        assertEquals(LootEntry.Type.TROPHY, loaded.get(3).type);
        LootEntry fresh = loaded.add(LootEntry.trophy(0));
        assertEquals(4, fresh.id, "ids are never reused");
    }

    @Test
    void brokenYamlIsKeptAsBackupAndReportedInsteadOfBeingOverwritten() throws Exception {
        Path file = dir.resolve("loot.yml");
        Files.writeString(file, "entries: [unclosed");
        assertThrows(java.io.IOException.class, () -> LootTable.load(file));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("loot.yml.broken-")));
        }
        assertEquals("entries: [unclosed", Files.readString(file));
    }

    @Test
    void scopeParsingAcceptsLooseSpelling() {
        assertEquals(LootEntry.Scope.TOP_DAMAGE, LootEntry.Scope.parse("topdamage"));
        assertEquals(LootEntry.Scope.RANDOM_PLAYER, LootEntry.Scope.parse("random_player"));
        assertEquals(LootEntry.Scope.EACH_PLAYER, LootEntry.Scope.parse("???"));
        assertEquals("25%", LootEntry.command(1, "x", 25, LootEntry.Scope.EACH_PLAYER).chanceText());
        assertEquals("0.5%", LootEntry.command(1, "x", 0.5, LootEntry.Scope.EACH_PLAYER).chanceText());
    }
}
