package de.nikey.witherboss.boss;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

import java.util.*;

final class MovesCore {
    private MovesCore() {}

    static final class Storm extends BossMove {
        static final double START = 2.5, MAX = 36, HALF = 1.4, TURN = Math.toRadians(0.9);
        private int fireAt, fireEnd;
        private final List<BossFx.Mark> lanes = new ArrayList<>();
        private final List<ItemDisplay> beams = new ArrayList<>();
        private ItemDisplay arrow;
        private final Map<UUID, Integer> nextHit = new HashMap<>();

        Storm(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.STORM, escort);
        }

        protected void onBegin() {
            f.play("storm");
            fireAt = f.marker("storm", "fire", 40);
            fireEnd = f.marker("storm", "fire_end", 160);
            impactAt = fireAt;
            length = f.rig().clip("storm").length();
            for (int k = 0; k < 3; k++) {
                double yaw = yaw(k);
                double[] s = start(yaw);
                lanes.add(mark(f.fx().lane(s[0], s[1], yaw, reach(s, yaw), HALF * 2, BossFx.DANGER, fireAt)));
            }
            arrow = f.fx().flatDecal("rotation_arrow", BossFx.DANGER, f.bossX(), f.bossZ(), 0.045f, f.model().facing(), 14, 14);
            f.fx().sound(f.model().socket("heart", "core"), "atk.nova_charge", 3f, 0.7f);
            for (Player p : f.targetable()) f.hint(p, "Consuming Storm · run with the rotation between the beams!");
        }

        private double yaw(int k) {
            return f.model().facing() + k * Math.PI * 2 / 3;
        }

        private double[] start(double yaw) {
            return new double[] {f.bossX() + Math.sin(yaw) * START, f.bossZ() + Math.cos(yaw) * START};
        }

        private double reach(double[] s, double yaw) {
            return Math.min(f.edge(s[0], s[1], yaw, 35.5), BossGeometry.clip(s[0], s[1], yaw, MAX, f.pillars()));
        }

        protected void onTick(int t) {
            if (t == fireAt) {
                f.fx().sound(f.bossGround().add(0, 5, 0), "atk.storm", 4f, 1f);
                f.fx().remove(arrow);
                arrow = null;
                for (int k = 0; k < 3; k++) {
                    beams.add(f.fx().effect("fx/beam", BossFx.WHITE, 15));
                    beams.add(f.fx().effect("fx/beam_glow", BossFx.SOUL, 15));
                    beams.add(f.fx().effect("fx/beam", BossFx.WHITE, 15));
                }
                lanes.forEach(m -> m.color(BossFx.DANGER_HOT));
            }
            if (t > fireAt && t <= fireEnd) {
                f.model().facing(f.model().facing() + TURN);
                Location heart = f.model().socket("heart", "core");
                for (int k = 0; k < 3; k++) {
                    double yaw = yaw(k);
                    double[] s = start(yaw);
                    double len = reach(s, yaw);
                    Location ground = f.floorAt(s[0], s[1]).add(0, 0.5, 0);
                    Location end = f.floorAt(s[0] + Math.sin(yaw) * len, s[1] + Math.cos(yaw) * len).add(0, 0.6, 0);
                    float pulse = 0.85f + 0.15f * (float) Math.sin((t + k * 3) * 1.1);
                    if (t % 2 == 0) {
                        BossFx.apply(beams.get(k * 3), f.fx().beamMatrix("fx/beam", heart, ground, 0.45f * pulse), 2);
                        BossFx.apply(beams.get(k * 3 + 1), f.fx().beamMatrix("fx/beam_glow", ground, end, 1.1f * pulse), 2);
                        BossFx.apply(beams.get(k * 3 + 2), f.fx().beamMatrix("fx/beam", ground, end, 0.5f * pulse), 2);
                        f.fx().moveLane(lanes.get(k), s[0], s[1], yaw, len, HALF * 2, 2);
                        f.fx().particle(Particle.SOUL_FIRE_FLAME, end, 6, 0.3, 0.3, 0.3, 0.03, null);
                    }
                    if (t % 6 == 0) f.fx().light(end.clone().add(0, 0.6, 0), 13, 8);
                    var lane = new BossGeometry.Lane(s[0], s[1], yaw, len, HALF);
                    for (Player p : f.targetable())
                        if (f.tick() >= nextHit.getOrDefault(p.getUniqueId(), 0) && lane.contains(p.getLocation().getX(), p.getLocation().getZ())) {
                            nextHit.put(p.getUniqueId(), f.tick() + 10);
                            f.damage(p, f.damageValue("storm"), "storm", true, 20, null);
                        }
                }
            }
            if (t == fireEnd + 1) {
                beams.forEach(f.fx()::remove);
                beams.clear();
                lanes.forEach(BossFx.Mark::remove);
            }
        }

        protected void onCleanup() {
            beams.forEach(f.fx()::remove);
            beams.clear();
            f.fx().remove(arrow);
        }
    }

    static final class Maw extends BossMove {
        static final double RADIUS = 13;
        private double cx, cz;

        Maw(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.MAW, escort);
        }

        protected void onBegin() {
            f.play("inhale");
            impactAt = f.marker("inhale", "burst", 52);
            length = f.rig().clip("inhale").length();
            cx = f.bossX();
            cz = f.bossZ();
            mark(f.fx().circle(cx, cz, RADIUS, BossFx.DANGER, impactAt));
            f.fx().sound(f.model().socket("heart", "core"), "atk.inhale", 4f, 1f);
            for (Player p : f.targetable()) f.hint(p, "Consuming Maw · run against the pull or hide behind a pillar!");
        }

        protected void onTick(int t) {
            Location heart = f.model().socket("heart", "core");
            if (t < impactAt) {
                for (Player p : f.targetable()) {
                    if (BossGeometry.occluded(cx, cz, p.getLocation().getX(), p.getLocation().getZ(), f.pillars())) continue;
                    Vector pull = new Vector(cx - p.getLocation().getX(), 0, cz - p.getLocation().getZ());
                    double d = pull.length();
                    if (d < 6.5 || d > 30) continue;
                    Vector v = p.getVelocity();
                    Vector h = v.clone().setY(0).add(pull.normalize().multiply(0.07));
                    if (h.length() > 0.34) h.normalize().multiply(0.34);
                    p.setVelocity(h.setY(v.getY()));
                }
                if (t % 2 == 0)
                    for (int i = 0; i < 8; i++) {
                        double a = f.random().nextDouble() * Math.PI * 2, r = 6 + f.random().nextDouble() * 14;
                        Location l = f.floorAt(cx + Math.sin(a) * r, cz + Math.cos(a) * r).add(0, 0.6 + f.random().nextDouble() * 3, 0);
                        Vector dir = heart.toVector().subtract(l.toVector()).normalize();
                        f.fx().particle(Particle.SOUL, l, 0, dir.getX(), dir.getY(), dir.getZ(), 0.6, null);
                    }
                if (t % 3 == 0) f.fx().light(heart, 8 + t / 7, 6);
                f.model().light(15, 15, true);
            }
            if (t == impactAt) {
                marks.forEach(BossFx.Mark::remove);
                for (Player p : f.targetable()) {
                    double d = Math.hypot(p.getLocation().getX() - cx, p.getLocation().getZ() - cz);
                    if (d > RADIUS) continue;
                    if (BossGeometry.occluded(cx, cz, p.getLocation().getX(), p.getLocation().getZ(), f.pillars())) {
                        f.success(p, "The pillar protected you");
                        continue;
                    }
                    f.damage(p, f.damageValue("maw"), "maw", true, 0, f.away(f.floorAt(cx, cz), p, 1.3, 0.5));
                }
                f.fx().flash(cx, cz, RADIUS, BossFx.DANGER_HOT);
                f.fx().sound(heart, "atk.nova_burst", 4f, 0.8f);
                f.fx().particle(Particle.END_ROD, heart, 80, 0.4, 0.6);
                f.fx().particle(Particle.SOUL_FIRE_FLAME, f.floorAt(cx, cz).add(0, 0.4, 0), 120, RADIUS * 0.5, 0.1, RADIUS * 0.5, 0.1, null);
                f.fx().light(heart, 15, 10);
                f.shake(heart, 14);
            }
        }
    }
}
