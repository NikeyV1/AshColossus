package de.nikey.witherboss.boss;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MusicDirectorTest {
    private final List<String> log = new ArrayList<>();
    private final MusicDirector music = new MusicDirector(new MusicDirector.Output() {
        public void play(UUID listener, String track) {
            log.add("play " + listener.getLeastSignificantBits() + " " + track);
        }

        public void stop(UUID listener, String track) {
            log.add("stop " + listener.getLeastSignificantBits() + " " + track);
        }

        public void silenceVanilla(UUID listener) {}
    });
    private final UUID a = new UUID(0, 1), b = new UUID(0, 2);

    private long count(String entry) {
        return log.stream().filter(entry::equals).count();
    }

    @Test
    void introLeadsIntoLoopThatRetriggersOnTime() {
        music.join(a, 0);
        music.oneShot("intro", 0, "phase1", 280);
        for (int t = 0; t <= 280 + 640 * 2; t++) music.tick(t);
        assertEquals(1, count("play 1 intro"));
        assertEquals(3, count("play 1 phase1"));
    }

    @Test
    void transitionStopsOldLoopAndStartsNextOnce() {
        music.join(a, 0);
        music.loop("phase1", 0);
        log.clear();
        music.oneShot("transition", 100, "phase2", 110);
        assertTrue(log.contains("stop 1 phase1"));
        for (int t = 100; t <= 220; t++) music.tick(t);
        assertEquals(1, count("play 1 phase2"));
        assertEquals(0, count("play 1 phase1"));
    }

    @Test
    void rejoinNeverDoublesAndLeaveStopsEverything() {
        music.join(a, 0);
        music.loop("phase3", 0);
        music.join(a, 50);
        music.join(a, 60);
        for (int t = 61; t < 600; t++) music.tick(t);
        long plays = count("play 1 phase3");
        assertEquals(3, plays);
        assertTrue(log.contains("stop 1 phase3"));
        log.clear();
        music.leave(a);
        for (String track : MusicDirector.ALL) assertTrue(log.contains("stop 1 " + track));
        log.clear();
        music.tick(700);
        assertTrue(log.isEmpty());
    }

    @Test
    void lateJoinerHearsCurrentLoopAndVictoryOutlivesClose() {
        music.join(a, 0);
        music.loop("phase2", 0);
        music.join(b, 300);
        assertEquals(1, count("play 2 phase2"));
        music.oneShot("victory", 400, null, 0);
        log.clear();
        music.finish();
        assertFalse(log.contains("stop 1 victory"));
        assertTrue(log.contains("stop 1 phase2"));
        assertFalse(music.listening(a));
    }
}
