package de.nikey.witherboss.boss;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

final class MovesHammer {
    private MovesHammer() {}

    static final class Hammer extends BossMove {
        static final double WIDTH = 5, MAX = 26, START = 4.6, FISSURE = 1.6;
        private Player target;
        private int exposedStart, exposedEnd;
        private double yaw, sx, sz, len;
        private ItemDisplay crack;
        private final Set<UUID> burned = new HashSet<>();
        private final Map<ItemDisplay, Integer> flames = new HashMap<>();

        Hammer(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.HAMMER, escort);
        }

        protected void onBegin() {
            target = f.choose();
            f.play("hammer_smash");
            impactAt = f.marker("hammer_smash", "impact", 30);
            exposedStart = f.marker("hammer_smash", "exposed_start", impactAt + 4);
            exposedEnd = f.marker("hammer_smash", "exposed_end", impactAt + 50);
            length = f.rig().clip("hammer_smash").length();
            if (target != null) f.markPlayer(target, "Hammerfall is aiming at you!");
        }

        protected void onTick(int t) {
            if (t <= 10 && target != null) f.turnTowards(target.getLocation().getX(), target.getLocation().getZ(), 12);
            if (t == 10) {
                yaw = f.model().facing();
                sx = f.bossX() + Math.sin(yaw) * START;
                sz = f.bossZ() + Math.cos(yaw) * START;
                len = Math.min(MAX, Math.min(f.edge(sx, sz, yaw, 35.5), BossGeometry.clip(sx, sz, yaw, MAX, f.pillars())));
                mark(f.fx().lane(sx, sz, yaw, len, WIDTH, BossFx.DANGER, impactAt - 10));
            }
            if (t == impactAt - 4) f.fx().sound(f.bossGround().add(0, 6, 0), "atk.whoosh", 3f, 0.5f);
            if (t == impactAt) {
                marks.forEach(BossFx.Mark::remove);
                var lane = new BossGeometry.Lane(sx, sz, yaw, len, WIDTH / 2);
                for (Player p : f.targetable())
                    if (lane.contains(p.getLocation().getX(), p.getLocation().getZ())) {
                        Vector side = new Vector(Math.cos(yaw), 0, -Math.sin(yaw));
                        Vector rel = p.getLocation().toVector().subtract(new Vector(sx, p.getLocation().getY(), sz));
                        if (rel.dot(side) < 0) side.multiply(-1);
                        f.damage(p, f.damageValue("hammer"), "hammer", true, 0, side.multiply(0.9).setY(0.4));
                    }
                f.fx().flashLane(sx, sz, yaw, len, WIDTH, BossFx.DANGER_HOT);
                Location head = f.floorAt(f.bossX() + Math.sin(yaw) * HAMMER_REACH, f.bossZ() + Math.cos(yaw) * HAMMER_REACH);
                f.fx().sound(head, "atk.hammer_smash", 4f, 1f);
                f.fx().sound(head, "atk.fissure", 3f, 1f);
                f.fx().particle(Particle.EXPLOSION, head.clone().add(0, 0.5, 0), 2, 0.8, 0);
                f.fx().particle(Particle.BLOCK, head.clone().add(0, 0.2, 0), 50, 1.5, 0.1, 1.5, 0.2, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
                f.fx().light(head.clone().add(0, 1, 0), 15, 14);
                f.shake(head, 14);
                crack = f.fx().flatDecal("crack", null, sx, sz, 0.03f, yaw, FISSURE * 2, 0.05);
                if (crack != null) f.fx().later(1, () -> BossFx.apply(crack, f.fx().flatMatrix(sx, sz, 0.03f, yaw, FISSURE * 2, len), 15));
            }
            if (t > impactAt && t <= impactAt + 40) {
                double front = Math.min(len, len * (t - impactAt) / 16.0);
                if (t <= impactAt + 16 && t % 2 == 0) {
                    Location l = f.floorAt(sx + Math.sin(yaw) * front, sz + Math.cos(yaw) * front);
                    ItemDisplay flame = f.fx().effect("fx/flame", BossFx.SOUL, 15);
                    if (flame != null) {
                        float rot = f.random().nextFloat() * 6.28f;
                        BossFx.apply(flame, f.fx().effectMatrix("fx/flame", l, new Quaternionf().rotateY(rot), new Vector3f(1.6f, 0.2f, 1.6f)), 0);
                        f.fx().later(1, () -> BossFx.apply(flame, f.fx().effectMatrix("fx/flame", l, new Quaternionf().rotateY(rot), new Vector3f(1.8f, 2.6f, 1.8f)), 5));
                        f.fx().later(8, () -> BossFx.apply(flame, f.fx().effectMatrix("fx/flame", l, new Quaternionf().rotateY(rot), new Vector3f(1.2f, 0.05f, 1.2f)), 6));
                        flames.put(flame, f.tick() + 15);
                    }
                    f.fx().particle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 0.5, 0), 14, 0.5, 0.8, 0.5, 0.05, null);
                    f.fx().light(l.clone().add(0, 1, 0), 13, 10);
                }
                if (t % 3 == 0)
                    for (double s = 0; s < front; s += 2.5)
                        f.fx().particle(Particle.SOUL_FIRE_FLAME, f.floorAt(sx + Math.sin(yaw) * s, sz + Math.cos(yaw) * s).add(0, 0.2, 0), 1, 0.4, 0.1, 0.4, 0.02, null);
                var fire = new BossGeometry.Lane(sx, sz, yaw, front, FISSURE);
                for (Player p : f.targetable())
                    if (!burned.contains(p.getUniqueId()) && fire.contains(p.getLocation().getX(), p.getLocation().getZ())) {
                        burned.add(p.getUniqueId());
                        f.damage(p, f.damageValue("fissure"), "fissure", true, 40, new Vector(0, 0.5, 0));
                    }
            }
            flames.entrySet().removeIf(e -> {
                if (e.getValue() > f.tick()) return false;
                f.fx().remove(e.getKey());
                return true;
            });
            if (t == impactAt + 44) {
                f.fx().remove(crack);
                crack = null;
            }
            if (t == exposedStart) f.expose(exposedEnd - exposedStart);
        }

        protected void onCleanup() {
            f.fx().remove(crack);
            flames.keySet().forEach(f.fx()::remove);
            flames.clear();
        }
    }

    static final class Spin extends BossMove {
        static final double INNER = 3.5, OUTER = 11;
        private int spinAt, spinEnd;
        private BossFx.Mark donut;

        Spin(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.SPIN, escort);
        }

        protected void onBegin() {
            f.play("spin");
            spinAt = f.marker("spin", "spin", 32);
            spinEnd = f.marker("spin", "spin_end", 52);
            impactAt = spinAt + 8;
            length = f.rig().clip("spin").length();
            donut = mark(f.fx().donut("spin", f.bossX(), f.bossZ(), OUTER, BossFx.DANGER));
            mark(f.fx().circle(f.bossX(), f.bossZ(), INNER - 0.3, BossFx.GOLD, 0));
            f.fx().sound(f.bossGround().add(0, 4, 0), "boss.roar_short", 2.5f, 0.7f);
            for (Player p : f.targetable()) f.hint(p, "Ember Whirl · get close to the boss!");
        }

        protected void onTick(int t) {
            if (t == spinAt - 12 && donut != null) donut.color(BossFx.DANGER_HOT);
            if (t == spinAt) f.fx().sound(f.bossGround().add(0, 3, 0), "atk.spin", 4f, 1f);
            if (t >= spinAt && t <= spinEnd) {
                double p = (t - spinAt) / (double) (spinEnd - spinAt);
                f.model().extraYaw(Math.PI * 4 * (p * p * (3 - 2 * p)));
                if (t % 2 == 0)
                    for (int i = 0; i < 10; i++) {
                        double a = f.model().visualFacing() + i * Math.PI / 5;
                        f.fx().particle(Particle.SWEEP_ATTACK, f.floorAt(f.bossX() + Math.sin(a) * 7, f.bossZ() + Math.cos(a) * 7).add(0, 1.4, 0), 1, 0.2, 0);
                    }
            }
            if (t == spinEnd + 1) f.model().extraYaw(0);
            if (t == impactAt) {
                var ring = new BossGeometry.Ring(f.bossX(), f.bossZ(), INNER, OUTER);
                for (Player p : f.targetable())
                    if (ring.contains(p.getLocation().getX(), p.getLocation().getZ()))
                        f.damage(p, f.damageValue("spin"), "spin", true, 0, f.away(f.bossGround(), p, 1.1, 0.4));
                    else if (Math.hypot(p.getLocation().getX() - f.bossX(), p.getLocation().getZ() - f.bossZ()) < INNER)
                        f.success(p, "In the eye of the whirl");
                marks.forEach(BossFx.Mark::remove);
            }
        }

        protected void onCleanup() {
            f.model().extraYaw(0);
        }
    }

    static final class Nova extends BossMove {
        static final double RADIUS = 9;
        private double cx, cz;

        Nova(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.NOVA, escort);
        }

        protected void onBegin() {
            f.play("nova");
            impactAt = f.marker("nova", "burst", 36);
            length = f.rig().clip("nova").length();
            cx = f.bossX();
            cz = f.bossZ();
            mark(f.fx().circle(cx, cz, RADIUS, BossFx.DANGER, impactAt));
            f.fx().sound(f.model().socket("heart", "core"), "atk.nova_charge", 4f, 1f);
            for (Player p : f.targetable()) f.hint(p, "Core Nova · keep your distance!");
        }

        protected void onTick(int t) {
            Location heart = f.model().socket("heart", "core");
            if (t < impactAt) {
                f.model().light(15, 15, true);
                if (t % 3 == 0) f.fx().light(heart, 9 + t / 6, 6);
                if (t % 2 == 0) f.fx().particle(Particle.SOUL, heart, 4, 1.4, -0.06);
            }
            if (t == impactAt) {
                marks.forEach(BossFx.Mark::remove);
                var c = new BossGeometry.Circle(cx, cz, RADIUS);
                for (Player p : f.targetable())
                    if (c.contains(p.getLocation().getX(), p.getLocation().getZ()))
                        f.damage(p, f.damageValue("nova"), "nova", true, 0, f.away(f.floorAt(cx, cz), p, 1.2, 0.45));
                f.fx().flash(cx, cz, RADIUS, BossFx.DANGER_HOT);
                f.fx().sound(heart, "atk.nova_burst", 4f, 1f);
                f.fx().particle(Particle.END_ROD, heart, 60, 0.3, 0.5);
                f.fx().particle(Particle.SOUL_FIRE_FLAME, f.floorAt(cx, cz).add(0, 0.4, 0), 80, RADIUS * 0.5, 0.1, RADIUS * 0.5, 0.08, null);
                f.fx().light(heart, 15, 10);
                f.shake(heart, 10);
            }
        }
    }

    static final class Rain extends BossMove {
        static final double RADIUS = 2.2;
        static final int FALL = 26;

        private final class Ember {
            final double x, z;
            final int land;
            final BossFx.Mark mark;
            final ItemDisplay rock;

            Ember(double x, double z, int land) {
                this.x = x;
                this.z = z;
                this.land = land;
                mark = f.fx().circle(x, z, RADIUS, BossFx.DANGER, FALL);
                rock = f.fx().effect("fx/comet", null, 15);
                if (rock != null) {
                    Location top = f.floorAt(x, z).add(0, 15, 0), bottom = f.floorAt(x, z).add(0, 0.3, 0);
                    BossFx.apply(rock, f.fx().effectMatrix("fx/comet", top, new Quaternionf(), new Vector3f(0.45f)), 0);
                    f.fx().later(1, () -> BossFx.apply(rock, f.fx().effectMatrix("fx/comet", bottom, new Quaternionf().rotateXYZ(2, 1, 0.5f), new Vector3f(0.45f)), FALL - 1));
                }
            }
        }

        private final List<Ember> embers = new ArrayList<>();
        private int strike;
        private final int[] waves = new int[3];

        Rain(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.RAIN, escort);
        }

        protected void onBegin() {
            String clip = f.state().phase() == RaidState.Phase.CORE ? "rain_fist" : "rain";
            f.play(clip);
            strike = f.marker(clip, "impact", 20);
            waves[0] = strike;
            waves[1] = strike + 14;
            waves[2] = strike + 28;
            impactAt = strike + FALL;
            length = waves[2] + FALL + 6;
        }

        protected void onTick(int t) {
            if (t == strike) {
                Location at = f.floorAt(f.bossX() + f.model().forward().getX() * HAMMER_REACH, f.bossZ() + f.model().forward().getZ() * HAMMER_REACH);
                f.fx().sound(at, "atk.hammer_smash", 3.5f, 1.15f);
                f.fx().particle(Particle.LAVA, at.clone().add(0, 0.5, 0), 20, 1, 0.3);
                f.fx().particle(Particle.FLAME, at.clone().add(0, 1, 0), 40, 0.6, 0.5, 0.6, 0.2, null);
                f.shake(at, 8);
            }
            for (int w : waves) if (t == w) wave();
            Iterator<Ember> it = embers.iterator();
            while (it.hasNext()) {
                Ember e = it.next();
                if (f.tick() < e.land) continue;
                var c = new BossGeometry.Circle(e.x, e.z, RADIUS);
                for (Player p : f.targetable())
                    if (c.contains(p.getLocation().getX(), p.getLocation().getZ()))
                        f.damage(p, f.damageValue("ember"), "ember", true, 40, new Vector(0, 0.35, 0));
                Location at = f.floorAt(e.x, e.z);
                f.fx().flash(e.x, e.z, RADIUS, BossFx.DANGER_HOT);
                f.fx().sound(at, "atk.ember_impact", 2f, 0.9f + f.random().nextFloat() * 0.25f);
                f.fx().particle(Particle.LAVA, at.clone().add(0, 0.3, 0), 6, 0.6, 0.1);
                f.fx().particle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 0.3, 0), 10, 0.8, 0.05, 0.8, 0.04, null);
                e.mark.remove();
                f.fx().remove(e.rock);
                it.remove();
            }
        }

        private void wave() {
            int n = Math.min(14, f.groupSize() + 3 + f.stage() / 2);
            List<double[]> spots = new ArrayList<>();
            for (Player p : f.targetable()) {
                if (spots.size() >= n) break;
                double a = f.random().nextDouble() * Math.PI * 2, r = f.random().nextDouble() * 2.5;
                tryAdd(spots, p.getLocation().getX() + Math.sin(a) * r, p.getLocation().getZ() + Math.cos(a) * r);
            }
            for (int tries = 0; tries < 60 && spots.size() < n; tries++) {
                double a = f.random().nextDouble() * Math.PI * 2, r = Math.sqrt(f.random().nextDouble()) * 31;
                tryAdd(spots, f.arena().x() + Math.sin(a) * r, f.arena().z() + Math.cos(a) * r);
            }
            for (double[] s : spots) embers.add(new Ember(s[0], s[1], f.tick() + FALL));
            f.fx().sound(f.bossGround().add(0, 10, 0), "atk.ember_fall", 3f, 1f);
        }

        private void tryAdd(List<double[]> spots, double x, double z) {
            double[] c = f.clampToArena(x, z, 32);
            if (Math.hypot(c[0] - f.bossX(), c[1] - f.bossZ()) < 4) return;
            for (double[] s : spots) if (Math.hypot(s[0] - c[0], s[1] - c[1]) < 4.5) return;
            spots.add(c);
        }

        protected void onCleanup() {
            for (Ember e : embers) {
                e.mark.remove();
                f.fx().remove(e.rock);
            }
            embers.clear();
        }
    }

    static final class Comet extends BossMove {
        static final double RADIUS = 3.5;
        private double cx, cz;
        private int required, summon;
        private ItemDisplay rock, arrows;
        private TextDisplay label;

        Comet(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.COMET, escort);
        }

        protected void onBegin() {
            f.play("comet");
            summon = f.marker("comet", "summon", 30);
            impactAt = f.marker("comet", "throw", 72) + 4;
            length = f.rig().clip("comet").length();
            List<Player> alive = f.targetable();
            double sx = 0, sz = 0;
            for (Player p : alive) {
                sx += p.getLocation().getX();
                sz += p.getLocation().getZ();
            }
            double[] c = alive.isEmpty() ? new double[] {f.arena().x(), f.arena().z() + 10} : f.clampToArena(sx / alive.size(), sz / alive.size(), 26);
            c = f.awayFromPillars(c[0], c[1], RADIUS + 1);
            cx = c[0];
            cz = c[1];
            required = f.solo() ? 1 : Math.min(4, (int) Math.ceil(alive.size() / 2.0));
            mark(f.fx().circle(cx, cz, RADIUS, BossFx.GOLD, impactAt));
            arrows = f.fx().flatDecal("arrows_in", BossFx.GOLD, cx, cz, 0.04f, 0, RADIUS * 2, RADIUS * 2);
            label = f.fx().label(f.floorAt(cx, cz).add(0, 2.4, 0), text(0), 1.6f);
            f.fx().sound(f.bossGround().add(0, 8, 0), "atk.comet_call", 4f, 1f);
            for (Player p : alive) f.hint(p, "Consuming Comet · gather in the golden circle (" + required + "+)!");
        }

        private net.kyori.adventure.text.Component text(int inside) {
            return BossHud.bold(inside + "/" + required, inside >= required ? BossHud.GOLD : BossHud.DANGER).append(BossHud.plain(" gather", BossHud.GOLD));
        }

        private int inside() {
            int n = 0;
            for (Player p : f.targetable()) if (Math.hypot(p.getLocation().getX() - cx, p.getLocation().getZ() - cz) <= RADIUS) n++;
            return n;
        }

        protected void onTick(int t) {
            if (t % 5 == 0 && label != null && label.isValid()) label.text(text(inside()));
            if (arrows != null && t % 2 == 0)
                BossFx.apply(arrows, f.fx().flatMatrix(cx, cz, 0.04f, t * 0.05, RADIUS * 2 * (1 - 0.12 * ((t % 20) / 20.0)), RADIUS * 2 * (1 - 0.12 * ((t % 20) / 20.0))), 2);
            if (t == summon) {
                rock = f.fx().effect("fx/comet", null, 15);
                Location top = f.floorAt(cx, cz).add(0, 34, 0), bottom = f.floorAt(cx, cz).add(0, 1.2, 0);
                BossFx.apply(rock, f.fx().effectMatrix("fx/comet", top, new Quaternionf(), new Vector3f(2.2f)), 0);
                int fall = impactAt - summon;
                f.fx().later(1, () -> BossFx.apply(rock, f.fx().effectMatrix("fx/comet", bottom, new Quaternionf().rotateXYZ(3, 2, 1), new Vector3f(2.2f)), fall - 1));
                f.fx().sound(top, "atk.comet_fall", 4f, 1f);
            }
            if (t > summon && t < impactAt) {
                double p = (t - summon) / (double) (impactAt - summon);
                Location trail = f.floorAt(cx, cz).add(0, 34 - 32.8 * p, 0);
                f.fx().particle(Particle.LARGE_SMOKE, trail.clone().add(0, 1.5, 0), 3, 0.5, 0.02);
                f.fx().particle(Particle.SOUL_FIRE_FLAME, trail, 4, 0.6, 0.02);
                if (t % 4 == 0) f.fx().light(f.floorAt(cx, cz).add(0, 1, 0), 6 + (int) (9 * p), 6);
            }
            if (t == impactAt) {
                List<Player> in = new ArrayList<>();
                for (Player p : f.targetable()) if (Math.hypot(p.getLocation().getX() - cx, p.getLocation().getZ() - cz) <= RADIUS) in.add(p);
                double raw = f.damageValue("comet");
                if (in.size() >= required) {
                    for (Player p : in) {
                        f.damage(p, raw / in.size(), "comet", true, 0, new Vector(0, 0.5, 0));
                        f.credit(p);
                        f.success(p, "Comet shared");
                    }
                } else {
                    for (Player p : in) f.damage(p, raw, "comet", true, 0, new Vector(0, 0.7, 0));
                    for (Player p : f.targetable())
                        if (!in.contains(p)) f.damage(p, f.damageValue("pulse"), "pulse", true, 0, null);
                    f.broadcast(net.kyori.adventure.text.Component.text("Too few in the circle · arena pulse!", BossHud.DANGER));
                }
                Location at = f.floorAt(cx, cz);
                f.fx().flash(cx, cz, RADIUS + 1, BossFx.GOLD);
                f.fx().sound(at, "atk.comet_impact", 4f, 1f);
                f.fx().particle(Particle.EXPLOSION_EMITTER, at.clone().add(0, 0.5, 0), 1, 0, 0);
                f.fx().particle(Particle.BLOCK, at.clone().add(0, 0.3, 0), 80, 2.5, 0.2, 2.5, 0.3, Material.BLACKSTONE.createBlockData());
                f.fx().light(at.clone().add(0, 1, 0), 15, 12);
                f.shake(at, 16);
                marks.forEach(BossFx.Mark::remove);
                f.fx().remove(rock);
                f.fx().remove(arrows);
                if (label != null) label.remove();
                rock = arrows = null;
                label = null;
            }
        }

        protected void onCleanup() {
            f.fx().remove(rock);
            f.fx().remove(arrows);
            if (label != null) label.remove();
        }
    }

    static final class Brand extends BossMove {
        static final double RADIUS = 3.0;

        private final class Mark {
            final Player player;
            final ItemDisplay line, fill, icon;

            Mark(Player player) {
                this.player = player;
                Location l = player.getLocation();
                line = f.fx().flatDecal("circle_line_" + BossFx.circleKey(RADIUS), BossFx.DANGER, l.getX(), l.getZ(), 0.035f, 0, RADIUS * 2, RADIUS * 2);
                fill = f.fx().flatDecal("circle_fill", BossFx.DANGER, l.getX(), l.getZ(), 0.025f, 0, 0.05, 0.05);
                icon = f.fx().icon("icon_brand", BossFx.DANGER, l.clone().add(0, 2.7, 0), 0.9f);
            }
        }

        private final List<Mark> brands = new ArrayList<>();
        private int markAt;

        Brand(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.BRAND, escort);
        }

        protected void onBegin() {
            f.play("brand");
            markAt = f.marker("brand", "mark", 14);
            impactAt = markAt + 60;
            length = impactAt + 8;
        }

        protected void onTick(int t) {
            if (t == markAt) {
                int count = Math.clamp((long) Math.ceil(f.groupSize() / 2.0), 1, 5);
                for (Player p : f.chooseDistinct(count)) {
                    brands.add(new Mark(p));
                    f.markPlayer(p, "Brand Mark! Move away from the others");
                    f.fx().sound(p.getLocation(), "atk.brand_mark", 2f, 1f);
                }
            }
            if (t > markAt && t < impactAt && t % 2 == 0) {
                double prog = (t - markAt) / (double) (impactAt - markAt);
                for (Mark m : brands) {
                    Location l = m.player.getLocation();
                    BossFx.apply(m.line, f.fx().flatMatrix(l.getX(), l.getZ(), 0.035f, 0, RADIUS * 2, RADIUS * 2), 2);
                    BossFx.apply(m.fill, f.fx().flatMatrix(l.getX(), l.getZ(), 0.025f, 0, RADIUS * 2 * prog, RADIUS * 2 * prog), 2);
                    BossFx.follow(m.icon, l.clone().add(0, 2.7, 0));
                    if (t % 6 == 0) f.fx().particle(Particle.FLAME, l.clone().add(0, 0.2, 0), 4, RADIUS * 0.6, 0.05, RADIUS * 0.6, 0.01, null);
                }
            }
            if (t == impactAt) {
                Map<UUID, Boolean> struck = new HashMap<>();
                for (Mark m : brands) {
                    Location c = m.player.getLocation();
                    if (f.targetable().contains(m.player) && !struck.containsKey(m.player.getUniqueId())) {
                        struck.put(m.player.getUniqueId(), true);
                        f.damage(m.player, f.damageValue("brand"), "brand", true, 0, null);
                    }
                    for (Player p : f.targetable()) {
                        if (p.equals(m.player) || struck.containsKey(p.getUniqueId())) continue;
                        if (Math.hypot(p.getLocation().getX() - c.getX(), p.getLocation().getZ() - c.getZ()) <= RADIUS) {
                            struck.put(p.getUniqueId(), true);
                            f.damage(p, f.damageValue("brand"), "brand", true, 0, null);
                        }
                    }
                    f.fx().flash(c.getX(), c.getZ(), RADIUS, BossFx.DANGER_HOT);
                    f.fx().sound(c, "atk.brand_burst", 2.5f, 1f);
                    f.fx().particle(Particle.FLAME, c.clone().add(0, 0.5, 0), 40, RADIUS * 0.5, 0.3, RADIUS * 0.5, 0.05, null);
                }
                clear();
            }
        }

        private void clear() {
            for (Mark m : brands) {
                f.fx().remove(m.line);
                f.fx().remove(m.fill);
                f.fx().remove(m.icon);
            }
            brands.clear();
        }

        protected void onCleanup() {
            clear();
        }
    }
}
