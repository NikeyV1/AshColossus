package de.nikey.witherboss.boss;

import org.bukkit.entity.Player;

import java.util.*;

public abstract class BossMove {
    public static final double SLAM_REACH = 5.6, HAMMER_REACH = 7.4;

    protected final WitherEncounter f;
    public final BossDirector.Attack type;
    protected final boolean escort;
    protected final List<BossFx.Mark> marks = new ArrayList<>();
    protected int start, impactAt = 1, length = 60;
    private boolean finished, released;

    protected BossMove(WitherEncounter f, BossDirector.Attack type, boolean escort) {
        this.f = f;
        this.type = type;
        this.escort = escort;
    }

    public static BossMove create(WitherEncounter f, BossDirector.Attack type, boolean escort) {
        return switch (type) {
            case SWEEP -> new MovesGlut.Sweep(f, escort);
            case SLAM -> new MovesGlut.Slam(f, escort);
            case BEAM -> new MovesGlut.Beam(f, escort);
            case SKULLS -> new MovesGlut.Skulls(f, escort);
            case CHAIN -> new MovesGlut.Chain(f, escort);
            case HAMMER -> new MovesHammer.Hammer(f, escort);
            case SPIN -> new MovesHammer.Spin(f, escort);
            case NOVA -> new MovesHammer.Nova(f, escort);
            case RAIN -> new MovesHammer.Rain(f, escort);
            case COMET -> new MovesHammer.Comet(f, escort);
            case BRAND -> new MovesHammer.Brand(f, escort);
            case STORM -> new MovesCore.Storm(f, escort);
            case MAW -> new MovesCore.Maw(f, escort);
            case SPIKES -> new MovesWorld.Spikes(f, escort);
            case DEBRIS -> new MovesWorld.Debris(f, escort);
            case BLADE -> new MovesWorld.Blade(f, escort);
            case GRID -> new MovesWorld.Grid(f, escort);
            case MIASMA -> new MovesWorld.Miasma(f, escort);
            case MINIONS -> new MovesSummon.Minions(f, escort);
            case BEACONS -> new MovesSummon.Beacons(f, escort);
            case RETRIBUTION -> new MovesSummon.Retribution(f, escort);
        };
    }

    public final void begin() {
        start = f.tick();
        onBegin();
    }

    public final void tick() {
        if (finished) return;
        int t = f.tick() - start;
        onTick(t);
        if (t >= length) finish();
    }

    protected abstract void onBegin();

    protected abstract void onTick(int t);

    protected void onCleanup() {}

    protected final void finish() {
        if (finished) return;
        finished = true;
        cleanup();
    }

    public final boolean finished() {
        return finished;
    }

    protected final void release() {
        released = true;
    }

    public final boolean released() {
        return released;
    }

    public final void cleanup() {
        for (BossFx.Mark m : marks) m.remove();
        marks.clear();
        onCleanup();
    }

    protected BossFx.Mark mark(BossFx.Mark m) {
        if (m != null) marks.add(m);
        return m;
    }

    public float warning() {
        int t = f.tick() - start;
        if (t >= impactAt) return 0;
        return 1 - t / (float) Math.max(1, impactAt);
    }

    public boolean warningPhase() {
        return f.tick() - start < impactAt;
    }

    public boolean escort() {
        return escort;
    }

    protected boolean animate() {
        return !escort;
    }

    protected Player primaryTarget() {
        return f.choose();
    }
}
