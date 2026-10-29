package de.nikey.witherboss.boss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;

class WitherSummoningTest {
    private static final WitherSummoning.Window BERLIN_EVENING =
            new WitherSummoning.Window(true, LocalTime.of(15, 0), LocalTime.of(22, 0), ZoneId.of("Europe/Berlin"));

    private ZonedDateTime time(int hour, int minute) {
        return ZonedDateTime.of(2026, 10, 8, hour, minute, 0, 0, ZoneId.of("Europe/Berlin"));
    }

    private String blocked(boolean busy, boolean testing, int online, int minimum, ZonedDateTime now, WitherSummoning.Window window, long cooldown) {
        return WitherSummoning.blocked(busy, testing, online, minimum, now, window, cooldown);
    }

    @Test
    void requiresAtLeastTheConfiguredPlayersOnline() {
        assertNotNull(blocked(false, false, 1, 2, time(16, 0), WitherSummoning.Window.OFF, 0));
        assertNull(blocked(false, false, 2, 2, time(16, 0), WitherSummoning.Window.OFF, 0));
        assertNull(blocked(false, false, 20, 2, time(16, 0), WitherSummoning.Window.OFF, 0));
        assertTrue(blocked(false, false, 4, 5, time(16, 0), WitherSummoning.Window.OFF, 0).contains("5"));
    }

    @Test
    void timeWindowIsStartInclusiveAndEndExclusiveInItsOwnZone() {
        assertNotNull(blocked(false, false, 6, 5, time(14, 59), BERLIN_EVENING, 0));
        assertNull(blocked(false, false, 6, 5, time(15, 0), BERLIN_EVENING, 0));
        assertNull(blocked(false, false, 6, 5, time(21, 59), BERLIN_EVENING, 0));
        assertNotNull(blocked(false, false, 6, 5, time(22, 0), BERLIN_EVENING, 0));
        assertNull(blocked(false, false, 6, 5, time(15, 0).withZoneSameInstant(ZoneOffset.UTC), BERLIN_EVENING, 0));
    }

    @Test
    void windowsMayCrossMidnightAndEqualBoundsMeanAllDay() {
        WitherSummoning.Window night = new WitherSummoning.Window(true, LocalTime.of(22, 0), LocalTime.of(4, 0), ZoneOffset.UTC);
        ZonedDateTime base = ZonedDateTime.of(2026, 10, 8, 0, 0, 0, 0, ZoneOffset.UTC);
        assertTrue(night.open(base.withHour(23)));
        assertTrue(night.open(base.withHour(3)));
        assertFalse(night.open(base.withHour(4)));
        assertFalse(night.open(base.withHour(12)));
        assertTrue(new WitherSummoning.Window(true, LocalTime.NOON, LocalTime.NOON, ZoneOffset.UTC).open(base));
        assertTrue(WitherSummoning.Window.OFF.open(base.withHour(5)));
    }

    @Test
    void cooldownBlocksWithTheRemainingMinutes() {
        String message = blocked(false, false, 6, 2, time(16, 0), WitherSummoning.Window.OFF, 600);
        assertNotNull(message);
        assertTrue(message.contains("11 min"));
        assertNull(blocked(false, false, 6, 2, time(16, 0), WitherSummoning.Window.OFF, 0));
    }

    @Test
    void busyBlocksEverythingAndTestsSkipTheHurdles() {
        assertNotNull(blocked(true, false, 6, 5, time(16, 0), WitherSummoning.Window.OFF, 0));
        assertNotNull(blocked(true, true, 1, 5, time(2, 0), BERLIN_EVENING, 0));
        assertNull(blocked(false, true, 1, 5, time(2, 0), BERLIN_EVENING, 999));
    }

    @Test
    void timeParsingToleratesShortAndBrokenValues() {
        assertEquals(LocalTime.of(9, 5), WitherSummoning.parseTime("9:05", LocalTime.MIN));
        assertEquals(LocalTime.of(18, 30), WitherSummoning.parseTime("18:30", LocalTime.MIN));
        assertEquals(LocalTime.NOON, WitherSummoning.parseTime("lunch", LocalTime.NOON));
    }
}
