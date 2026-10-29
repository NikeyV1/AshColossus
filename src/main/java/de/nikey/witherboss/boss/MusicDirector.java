package de.nikey.witherboss.boss;

import java.util.*;

public final class MusicDirector {
    public interface Output {
        void play(UUID listener, String track);

        void stop(UUID listener, String track);

        void silenceVanilla(UUID listener);
    }

    public static final Map<String, Integer> LOOPS = Map.of("phase1", 640, "phase2", 600, "phase3", 640);
    public static final Map<String, Integer> ONE_SHOTS =
            Map.of("intro", 280, "transition", 100, "victory", 270, "defeat", 190);
    public static final List<String> ALL = List.of("intro", "phase1", "phase2", "phase3", "transition", "victory", "defeat");

    private static final class Listener {
        final Set<String> playing = new HashSet<>();
        int nextLoop = Integer.MAX_VALUE;
    }

    private final Output out;
    private final Map<UUID, Listener> listeners = new LinkedHashMap<>();
    private String loop;
    private String oneShot;
    private int oneShotEnd, loopStart = Integer.MAX_VALUE;

    public MusicDirector(Output out) {
        this.out = out;
    }

    public void join(UUID id, int now) {
        Listener l = listeners.remove(id);
        for (String track : ALL) out.stop(id, track);
        l = new Listener();
        listeners.put(id, l);
        out.silenceVanilla(id);
        if (oneShot != null && now < oneShotEnd && !oneShot.equals("transition")) start(id, l, oneShot);
        if (loop != null && now >= loopStart) {
            start(id, l, loop);
            l.nextLoop = now + LOOPS.get(loop);
        }
    }

    public void leave(UUID id) {
        if (listeners.remove(id) == null) return;
        for (String track : ALL) out.stop(id, track);
    }

    public boolean listening(UUID id) {
        return listeners.containsKey(id);
    }

    public Set<UUID> listeners() {
        return Collections.unmodifiableSet(listeners.keySet());
    }

    private void start(UUID id, Listener l, String track) {
        out.play(id, track);
        l.playing.add(track);
    }

    private void stopAll(UUID id, Listener l) {
        for (String t : l.playing) out.stop(id, t);
        l.playing.clear();
        l.nextLoop = Integer.MAX_VALUE;
    }

    public void oneShot(String track, int now, String thenLoop, int loopDelay) {
        if (!ONE_SHOTS.containsKey(track)) throw new IllegalArgumentException(track);
        for (var e : listeners.entrySet()) {
            stopAll(e.getKey(), e.getValue());
            start(e.getKey(), e.getValue(), track);
        }
        oneShot = track;
        oneShotEnd = now + ONE_SHOTS.get(track);
        loop = thenLoop;
        loopStart = thenLoop == null ? Integer.MAX_VALUE : now + loopDelay;
    }

    public void loop(String track, int now) {
        if (!LOOPS.containsKey(track)) throw new IllegalArgumentException(track);
        loop = track;
        loopStart = now;
        for (var e : listeners.entrySet()) {
            stopLoops(e.getKey(), e.getValue());
            start(e.getKey(), e.getValue(), track);
            e.getValue().nextLoop = now + LOOPS.get(track);
        }
    }

    private void stopLoops(UUID id, Listener l) {
        for (String t : LOOPS.keySet())
            if (l.playing.remove(t)) out.stop(id, t);
    }

    public void tick(int now) {
        if (oneShot != null && now >= oneShotEnd + 60) {
            for (var e : listeners.values()) e.playing.remove(oneShot);
            oneShot = null;
        }
        if (loop == null) return;
        if (now >= loopStart)
            for (var e : listeners.entrySet()) {
                Listener l = e.getValue();
                if (l.nextLoop != Integer.MAX_VALUE) continue;
                stopLoops(e.getKey(), l);
                start(e.getKey(), l, loop);
                l.nextLoop = now + LOOPS.get(loop);
                out.silenceVanilla(e.getKey());
            }
        for (var e : listeners.entrySet()) {
            Listener l = e.getValue();
            if (now >= l.nextLoop) {
                start(e.getKey(), l, loop);
                l.nextLoop = now + LOOPS.get(loop);
                out.silenceVanilla(e.getKey());
            }
        }
    }

    public String loop() {
        return loop;
    }

    public void finish() {
        for (var e : listeners.entrySet())
            for (String track : ALL)
                if (!track.equals(oneShot) || !(track.equals("victory") || track.equals("defeat"))) out.stop(e.getKey(), track);
        listeners.clear();
        loop = null;
        oneShot = null;
        loopStart = Integer.MAX_VALUE;
    }

    public void stopEverything() {
        for (var e : listeners.entrySet()) {
            for (String track : ALL) out.stop(e.getKey(), track);
        }
        listeners.clear();
        loop = null;
        oneShot = null;
        loopStart = Integer.MAX_VALUE;
    }
}
