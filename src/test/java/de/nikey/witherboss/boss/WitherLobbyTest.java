package de.nikey.witherboss.boss;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class WitherLobbyTest {
    private WitherLobby lobby(int ticks, int minimum) {
        return new WitherLobby(UUID.randomUUID(), UUID.randomUUID(), "forge", new WitherStore.Materials(3, 4, 0), ticks, minimum);
    }

    private void join(WitherLobby l, int n) {
        for (int i = 0; i < n; i++) l.add(UUID.randomUUID(), new org.bukkit.Location(null, 0, 0, 0));
    }

    @Test
    void countsDownAndStartsWithEnoughParticipants() {
        WitherLobby l = lobby(5, 3);
        join(l, 3);
        for (int i = 0; i < 4; i++) assertEquals(WitherLobby.Result.WAITING, l.tick());
        assertEquals(WitherLobby.Result.START, l.tick());
    }

    @Test
    void tooFewWithoutAnyoneLeavingIsRefundable() {
        WitherLobby l = lobby(3, 3);
        join(l, 2);
        l.tick();
        l.tick();
        assertEquals(WitherLobby.Result.TOO_FEW, l.tick());
        assertFalse(l.someoneLeft());
    }

    @Test
    void theLeaderLeavingMakesTheAbortFinal() {
        WitherLobby l = lobby(3, 3);
        l.add(l.leader(), new org.bukkit.Location(null, 0, 0, 0));
        join(l, 2);
        assertNotNull(l.remove(l.leader()));
        l.tick();
        l.tick();
        assertEquals(WitherLobby.Result.CANCELLED, l.tick());
        assertTrue(l.someoneLeft());
    }

    @Test
    void otherPlayersLeavingNeverForfeitTheLeadersMaterial() {
        WitherLobby l = lobby(3, 3);
        l.add(l.leader(), new org.bukkit.Location(null, 0, 0, 0));
        join(l, 2);
        l.remove(l.members().get(1));
        l.remove(l.members().get(1));
        l.tick();
        l.tick();
        assertEquals(WitherLobby.Result.TOO_FEW, l.tick());
        assertFalse(l.someoneLeft());
    }

    @Test
    void emptyLobbyEndsAtOnce() {
        WitherLobby l = lobby(100, 3);
        l.add(l.leader(), new org.bukkit.Location(null, 0, 0, 0));
        l.remove(l.leader());
        assertEquals(WitherLobby.Result.CANCELLED, l.tick());
    }

    @Test
    void membersAreUniqueAndLateJoinersCount() {
        WitherLobby l = lobby(2, 3);
        UUID a = UUID.randomUUID();
        assertTrue(l.add(a, new org.bukkit.Location(null, 0, 0, 0)));
        assertFalse(l.add(a, new org.bukkit.Location(null, 1, 0, 0)));
        join(l, 1);
        l.tick();
        join(l, 1);
        assertEquals(WitherLobby.Result.START, l.tick());
        assertEquals(3, l.members().size());
    }

    @Test
    void systemDropsDoNotCountAsCancel() {
        WitherLobby l = lobby(2, 3);
        join(l, 2);
        l.drop(l.members().getFirst());
        l.tick();
        assertEquals(WitherLobby.Result.TOO_FEW, l.tick());
    }

    private org.bukkit.Location spot() {
        return new org.bukkit.Location(null, 0, 0, 0);
    }

    @Test
    void undecidedPlayersNeverCountTowardsTheMinimum() {
        WitherLobby l = lobby(2, 3);
        l.add(l.leader(), spot());
        l.add(UUID.randomUUID(), spot(), false);
        l.add(UUID.randomUUID(), spot(), false);
        assertEquals(3, l.size());
        assertEquals(1, l.confirmedCount());
        l.tick();
        assertEquals(WitherLobby.Result.TOO_FEW, l.tick());
    }

    @Test
    void confirmingBeforeTheDeadlineStartsTheFight() {
        WitherLobby l = lobby(3, 3);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        l.add(l.leader(), spot());
        l.add(a, spot(), false);
        l.add(b, spot(), false);
        assertEquals(List.of(a, b), l.undecided());
        assertTrue(l.confirm(a));
        assertFalse(l.confirm(a));
        assertTrue(l.confirm(b));
        assertTrue(l.undecided().isEmpty());
        l.tick();
        l.tick();
        assertEquals(WitherLobby.Result.START, l.tick());
        assertEquals(3, l.confirmedMembers().size());
    }

    @Test
    void silenceFromEveryoneEndsInTheRefundableAbort() {
        WitherLobby l = lobby(2, 3);
        for (int i = 0; i < 5; i++) l.add(UUID.randomUUID(), spot(), false);
        assertEquals(0, l.confirmedCount());
        l.tick();
        assertEquals(WitherLobby.Result.TOO_FEW, l.tick());
        assertFalse(l.someoneLeft());
    }

    @Test
    void leavingRevokesConfirmationAndOutsidersCannotConfirm() {
        WitherLobby l = lobby(5, 1);
        UUID a = UUID.randomUUID();
        l.add(a, spot());
        assertTrue(l.confirmed(a));
        l.remove(a);
        assertFalse(l.confirmed(a));
        assertEquals(0, l.confirmedCount());
        assertFalse(l.confirm(UUID.randomUUID()));
    }
}
