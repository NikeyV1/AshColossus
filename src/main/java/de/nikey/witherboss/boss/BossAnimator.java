package de.nikey.witherboss.boss;

public final class BossAnimator {
    private static final int BLEND_IN = 5, BLEND_OUT = 7, IDLE_FADE = 12;
    private final BossRig rig;
    private final BossRig.Pose pose, previousIdle;
    private BossRig.Clip idle, action;
    private int idleStart, actionStart, idleChanged = -100_000, releasedAt = Integer.MIN_VALUE;

    public BossAnimator(BossRig rig) {
        this.rig = rig;
        pose = new BossRig.Pose(rig.bones().size());
        previousIdle = new BossRig.Pose(rig.bones().size());
        idle = rig.clip("idle");
    }

    public void idle(String name, int now) {
        BossRig.Clip next = rig.clip(name);
        if (next == idle) return;
        previousIdle.set(evaluateIdle(now));
        idle = next;
        idleStart = now;
        idleChanged = now;
    }

    public String idleName() {
        return idle.name();
    }

    public void play(String name, int now) {
        action = rig.clip(name);
        actionStart = now;
        releasedAt = Integer.MIN_VALUE;
    }

    public void release(int now) {
        if (action != null && (action.hold() || action.loop()) && releasedAt == Integer.MIN_VALUE) releasedAt = now;
    }

    public void stop() {
        action = null;
        releasedAt = Integer.MIN_VALUE;
    }

    public String action() {
        return action == null ? null : action.name();
    }

    public int elapsed(int now) {
        return action == null ? -1 : now - actionStart;
    }

    public boolean playing(String name, int now) {
        return action != null && action.name().equals(name) && (action.hold() || action.loop() || now - actionStart < action.length());
    }

    private BossRig.Pose evaluateIdle(int now) {
        BossRig.Pose base = new BossRig.Pose(rig.bones().size());
        int len = idle.length();
        rig.apply(idle, Math.floorMod(now - idleStart, len), base, 1);
        if (now - idleChanged >= 0 && now - idleChanged < IDLE_FADE) {
            float w = (now - idleChanged) / (float) IDLE_FADE;
            BossRig.Pose from = new BossRig.Pose(rig.bones().size());
            from.set(previousIdle);
            from.blend(base, w * w * (3 - 2 * w));
            return from;
        }
        return base;
    }

    public BossRig.Pose evaluate(int now) {
        pose.set(evaluateIdle(now));
        if (action != null) {
            int t = now - actionStart;
            boolean looping = action.loop();
            if (releasedAt != Integer.MIN_VALUE && now - releasedAt >= BLEND_OUT) {
                action = null;
                releasedAt = Integer.MIN_VALUE;
            } else if (!action.hold() && !looping && t >= action.length()) action = null;
            else {
                float w = Math.min(1, (t + 1) / (float) BLEND_IN);
                if (!action.hold() && !looping) w = Math.min(w, (action.length() - t) / (float) BLEND_OUT);
                if (releasedAt != Integer.MIN_VALUE) w = Math.min(w, 1 - (now - releasedAt) / (float) BLEND_OUT);
                w = Math.clamp(w, 0, 1);
                rig.apply(action, looping ? Math.floorMod(t, action.length()) : t, pose, w * w * (3 - 2 * w));
            }
        }
        return pose;
    }
}
