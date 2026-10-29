package de.nikey.witherboss.boss;

import net.kyori.adventure.text.Component;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

import java.util.*;

final class MovesGlut {
    private MovesGlut() {}

    static final class Sweep extends BossMove {
        static final double RADIUS = 8.5, HALF = Math.toRadians(55);
        private Player target;
        private boolean left;
        private double yaw;
        private BossFx.Mark mark;

        Sweep(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.SWEEP, escort);
        }

        protected void onBegin() {
            target = f.nearest(10);
            if (target == null) target = f.choose();
            left = f.random().nextBoolean();
            String clip = left ? "sweep_l" : "sweep_r";
            f.play(clip);
            impactAt = f.marker(clip, "hit", 24);
            length = f.rig().clip(clip).length();
        }

        protected void onTick(int t) {
            if (t <= 4 && target != null) f.turnTowards(target.getLocation().getX(), target.getLocation().getZ(), 14);
            if (t == 4) {
                yaw = f.model().facing();
                mark = mark(f.fx().sector(f.bossX(), f.bossZ(), yaw, RADIUS, BossFx.DANGER, impactAt - 4));
            }
            if (t == impactAt - 3) f.fx().sound(f.bossGround().add(0, 3, 0), "atk.whoosh", 2.5f, 0.8f + f.random().nextFloat() * 0.2f);
            if (t == impactAt) {
                var shape = new BossGeometry.Sector(f.bossX(), f.bossZ(), yaw, RADIUS, HALF);
                for (Player p : f.targetable())
                    if (shape.contains(p.getLocation().getX(), p.getLocation().getZ()))
                        f.damage(p, f.damageValue("sweep"), "sweep", true, 0, f.away(f.bossGround(), p, 0.95, 0.35));
                if (mark != null) mark.remove();
                f.fx().flashSector(f.bossX(), f.bossZ(), yaw, RADIUS, BossFx.DANGER_HOT);
                for (int i = -5; i <= 5; i++) {
                    double a = yaw + HALF * i / 5.0;
                    Location l = f.floorAt(f.bossX() + Math.sin(a) * RADIUS * 0.75, f.bossZ() + Math.cos(a) * RADIUS * 0.75).add(0, 0.6, 0);
                    f.fx().particle(Particle.SWEEP_ATTACK, l, 1, 0.1, 0);
                    f.fx().particle(Particle.ASH, l, 6, 0.4, 0.02);
                }
            }
        }
    }

    static final class Slam extends BossMove {
        static final double RADIUS = 4.5, WAVE_SPEED = 0.55, WAVE_MAX = 20, WAVE_BAND = 0.75;
        private static final int SEGMENTS = 28;

        private final class Wave {
            final double cx, cz;
            final int born;
            final Set<UUID> hit = new HashSet<>();
            final List<ItemDisplay> parts = new ArrayList<>();

            Wave(double cx, double cz, int born) {
                this.cx = cx;
                this.cz = cz;
                this.born = born;
            }

            double radius(int now) {
                return RADIUS + (now - born) * WAVE_SPEED;
            }
        }

        private Player target;
        private double cx, cz;
        private int lockAt, exposedStart, exposedEnd;
        private ItemDisplay tracker;
        private BossFx.Mark mark;
        private final List<Wave> waves = new ArrayList<>();
        private final String clip;

        Slam(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.SLAM, escort);
            clip = "slam";
        }

        protected void onBegin() {
            target = f.choose();
            f.play(clip);
            impactAt = f.marker(clip, "impact", 32);
            exposedStart = f.marker(clip, "exposed_start", impactAt + 4);
            exposedEnd = f.marker(clip, "exposed_end", impactAt + 60);
            length = f.rig().clip(clip).length() + 30;
            lockAt = impactAt / 2;
            Location l = target == null ? f.bossGround() : target.getLocation();
            double[] c = f.clampToArena(l.getX(), l.getZ(), 30);
            cx = c[0];
            cz = c[1];
            tracker = f.fx().flatDecal("circle_line_" + BossFx.circleKey(RADIUS), BossFx.DANGER, cx, cz, 0.035f, 0, RADIUS * 2, RADIUS * 2);
            if (target != null) f.markPlayer(target, "Grave Fist is aiming at you!");
        }

        protected void onTick(int t) {
            if (t < lockAt && target != null && f.targetable().contains(target) && t % 2 == 0) {
                double[] c = f.clampToArena(target.getLocation().getX(), target.getLocation().getZ(), 30);
                cx = c[0];
                cz = c[1];
                BossFx.apply(tracker, f.fx().flatMatrix(cx, cz, 0.035f, 0, RADIUS * 2, RADIUS * 2), 2);
            }
            if (t == lockAt) {
                f.fx().remove(tracker);
                mark = mark(f.fx().circle(cx, cz, RADIUS, BossFx.DANGER, impactAt - lockAt));
                f.fx().sound(f.floorAt(cx, cz), "ui.lock", 1.5f, 0.8f);
            }
            if (t < impactAt - 2) {
                double dx = cx - f.bossX(), dz = cz - f.bossZ(), d = Math.hypot(dx, dz);
                f.turnTowards(cx, cz, 15);
                if (d > 0.01) {
                    double goal = Math.max(0, d - SLAM_REACH);
                    double step = Math.min(0.55, goal);
                    if (goal > 0.05) f.glide(f.bossX() + dx / d * step, f.bossZ() + dz / d * step);
                    else if (d < SLAM_REACH - 0.2) f.glide(f.bossX() - dx / d * 0.3, f.bossZ() - dz / d * 0.3);
                }
            }
            if (t == impactAt - 3) f.fx().sound(f.bossGround().add(0, 4, 0), "atk.whoosh", 3f, 0.6f);
            if (t == impactAt) impact();
            if (t == impactAt + 12 && f.state().phase() == RaidState.Phase.CORE) startWave();
            if (t == exposedStart) f.expose(exposedEnd - exposedStart);
            tickWaves();
            if (t > impactAt && waves.isEmpty() && t >= f.rig().clip(clip).length()) finish();
        }

        private void impact() {
            if (mark != null) mark.remove();
            var shape = new BossGeometry.Circle(cx, cz, RADIUS);
            Set<UUID> struck = new HashSet<>();
            for (Player p : f.targetable())
                if (shape.contains(p.getLocation().getX(), p.getLocation().getZ())) {
                    struck.add(p.getUniqueId());
                    f.damage(p, f.damageValue("slam"), "slam", true, 0, f.away(f.floorAt(cx, cz), p, 0.6, 0.55));
                }
            Location at = f.floorAt(cx, cz);
            f.fx().flash(cx, cz, RADIUS, BossFx.DANGER_HOT);
            f.fx().sound(at, "atk.slam", 4f, 0.9f);
            f.fx().sound(at, "atk.shockwave", 3f, 1f);
            f.fx().particle(Particle.EXPLOSION, at.clone().add(0, 0.5, 0), 3, 1.2, 0);
            f.fx().particle(Particle.BLOCK, at.clone().add(0, 0.2, 0), 60, 2.2, 0.1, 2.2, 0.2, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
            f.fx().particle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 0.3, 0), 30, 2, 0.1, 2, 0.05, null);
            f.fx().light(at.clone().add(0, 1, 0), 15, 12);
            f.shake(at, 12);
            startWave(struck);
        }

        private void startWave() {
            startWave(Set.of());
        }

        private void startWave(Set<UUID> already) {
            Wave w = new Wave(cx, cz, f.tick());
            w.hit.addAll(already);
            int travel = (int) Math.ceil((WAVE_MAX - RADIUS) / WAVE_SPEED);
            for (int i = 0; i < SEGMENTS; i++) {
                double a = i * Math.PI * 2 / SEGMENTS;
                double r0 = RADIUS, r1 = WAVE_MAX;
                ItemDisplay d = f.fx().flatDecal("wave_segment", BossFx.DANGER, cx + Math.sin(a) * r0, cz + Math.cos(a) * r0, 0.05f, a, 2 * Math.PI * r0 / SEGMENTS * 1.08, 1.5);
                if (d == null) continue;
                w.parts.add(d);
                double fx1 = cx + Math.sin(a) * r1, fz1 = cz + Math.cos(a) * r1;
                f.fx().later(1, () -> BossFx.apply(d, f.fx().flatMatrix(fx1, fz1, 0.05f, a, 2 * Math.PI * r1 / SEGMENTS * 1.08, 1.5), travel - 1));
            }
            waves.add(w);
        }

        private void tickWaves() {
            Iterator<Wave> it = waves.iterator();
            while (it.hasNext()) {
                Wave w = it.next();
                double r = w.radius(f.tick());
                if (r > WAVE_MAX) {
                    w.parts.forEach(f.fx()::remove);
                    it.remove();
                    continue;
                }
                for (Player p : f.targetable()) {
                    if (w.hit.contains(p.getUniqueId())) continue;
                    double d = Math.hypot(p.getLocation().getX() - w.cx, p.getLocation().getZ() - w.cz);
                    if (Math.abs(d - r) <= WAVE_BAND && f.grounded(p)) {
                        w.hit.add(p.getUniqueId());
                        f.damage(p, f.damageValue("wave"), "wave", true, 0, f.away(f.floorAt(w.cx, w.cz), p, 0.7, 0.3));
                    } else if (Math.abs(d - r) <= WAVE_BAND) {
                        w.hit.add(p.getUniqueId());
                        f.success(p, "Jumped over the wave");
                    }
                }
                if (f.tick() % 2 == 0)
                    for (int i = 0; i < 18; i++) {
                        double a = i * Math.PI * 2 / 18 + f.tick() * 0.02;
                        Location l = f.floorAt(w.cx + Math.sin(a) * r, w.cz + Math.cos(a) * r).add(0, 0.25, 0);
                        f.fx().particle(Particle.SOUL_FIRE_FLAME, l, 1, 0.05, 0.08, 0.05, 0.01, null);
                        if (i % 3 == 0) f.fx().particle(Particle.ASH, l, 3, 0.3, 0.1, 0.3, 0, null);
                    }
            }
        }

        protected void onCleanup() {
            f.fx().remove(tracker);
            for (Wave w : waves) w.parts.forEach(f.fx()::remove);
            waves.clear();
        }
    }

    static final class Beam extends BossMove {
        static final double WIDTH = 3.0, MAX = 40;
        private Player target;
        private int lockAt, fireEnd;
        private double yaw, beamLength, sweep;
        private ItemDisplay aim, core, glow, crack, tracker;
        private BossFx.Mark lane;
        private final Set<UUID> hit = new HashSet<>();
        private double sx, sz;

        Beam(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.BEAM, escort);
        }

        protected void onBegin() {
            target = f.choose();
            f.play("beam");
            lockAt = f.marker("beam", "lock", 30);
            impactAt = f.marker("beam", "fire", 42);
            fireEnd = f.marker("beam", "fire_end", 62);
            length = f.rig().clip("beam").length();
            yaw = f.model().facing();
            if (target != null) f.markPlayer(target, "Soul Beam is tracking you!");
            f.fx().sound(f.bossGround().add(0, 8, 0), "atk.beam_charge", 3f, 1f);
            aim = f.fx().effect("fx/beam", BossFx.DANGER, 15);
            tracker = f.fx().flatDecal("lane_line", BossFx.DANGER, f.bossX(), f.bossZ(), 0.035f, yaw, WIDTH, 1);
        }

        private double reach(double yaw) {
            return Math.min(f.edge(f.bossX(), f.bossZ(), yaw, 35.5), BossGeometry.clip(f.bossX(), f.bossZ(), yaw, MAX, f.pillars()));
        }

        protected void onTick(int t) {
            Location eyes = f.model().socket("head", "eyes");
            if (t < lockAt) {
                if (target != null && f.targetable().contains(target)) {
                    f.turnTowards(target.getLocation().getX(), target.getLocation().getZ(), 5);
                    f.lookAt(target.getLocation().add(0, 1, 0));
                }
                yaw = f.model().facing();
                beamLength = reach(yaw);
                if (t % 2 == 0) {
                    BossFx.apply(tracker, f.fx().flatMatrix(f.bossX(), f.bossZ(), 0.035f, yaw, WIDTH, beamLength), 2);
                    Location to = target != null && f.targetable().contains(target)
                            ? target.getLocation().add(0, 1.1, 0)
                            : f.floorAt(f.bossX() + Math.sin(yaw) * beamLength, f.bossZ() + Math.cos(yaw) * beamLength);
                    BossFx.apply(aim, f.fx().beamMatrix("fx/beam", eyes, to, 0.08f + 0.04f * (t / (float) lockAt)), 2);
                }
                if (t % 6 == 0) f.fx().light(eyes, 10 + t / 6, 8);
            }
            if (t == lockAt) {
                f.fx().remove(aim);
                f.fx().remove(tracker);
                sx = f.bossX();
                sz = f.bossZ();
                lane = mark(f.fx().lane(sx, sz, yaw, beamLength, WIDTH, BossFx.DANGER, impactAt - lockAt));
                f.fx().sound(eyes, "atk.beam_lock", 3f, 1f);
                if (target != null) f.ui(target, "ui.lock", 1f, 1f);
            }
            if (t == impactAt) {
                f.fx().sound(eyes, "atk.beam_fire", 4f, 1f);
                core = f.fx().effect("fx/beam", BossFx.WHITE, 15);
                glow = f.fx().effect("fx/beam_glow", BossFx.SOUL, 15);
                crack = f.fx().flatDecal("crack", null, sx, sz, 0.03f, yaw, 1.8, 0.05);
                if (crack != null) {
                    double len = beamLength;
                    f.fx().later(1, () -> BossFx.apply(crack, f.fx().flatMatrix(sx, sz, 0.03f, yaw, 1.8, len), 11));
                }
                if (lane != null) lane.color(BossFx.DANGER_HOT);
            }
            if (t >= impactAt && t <= fireEnd) {
                sweep = Math.min(beamLength, 1.5 + (beamLength - 1.5) * Math.min(1, (t - impactAt) / 12.0));
                Location end = f.floorAt(sx + Math.sin(yaw) * sweep, sz + Math.cos(yaw) * sweep).add(0, 0.25, 0);
                float pulse = 0.85f + 0.15f * (float) Math.sin(t * 1.3);
                BossFx.apply(core, f.fx().beamMatrix("fx/beam", eyes, end, 0.55f * pulse), 1);
                BossFx.apply(glow, f.fx().beamMatrix("fx/beam_glow", eyes, end, 1.25f * pulse), 1);
                f.fx().light(end.clone().add(0, 0.8, 0), 15, 6);
                f.fx().particle(Particle.SOUL_FIRE_FLAME, end, 8, 0.4, 0.2, 0.4, 0.04, null);
                f.fx().particle(Particle.FLAME, end, 3, 0.3, 0.1, 0.3, 0.02, null);
                if (t % 2 == 0)
                    for (double s = 1.5; s < sweep; s += 2.5)
                        f.fx().particle(Particle.SOUL_FIRE_FLAME, f.floorAt(sx + Math.sin(yaw) * s, sz + Math.cos(yaw) * s).add(0, 0.2, 0), 1, 0.4, 0.05, 0.4, 0.01, null);
                var trail = new BossGeometry.Lane(sx, sz, yaw, sweep, WIDTH / 2);
                for (Player p : f.targetable())
                    if (!hit.contains(p.getUniqueId()) && trail.contains(p.getLocation().getX(), p.getLocation().getZ())) {
                        hit.add(p.getUniqueId());
                        f.damage(p, f.damageValue("beam"), "beam", true, 40, null);
                    }
            }
            if (t == fireEnd) {
                f.fx().remove(core);
                f.fx().remove(glow);
                if (lane != null) lane.remove();
            }
            if (t > fireEnd) f.lookAt(null);
        }

        protected void onCleanup() {
            f.fx().remove(aim);
            f.fx().remove(core);
            f.fx().remove(glow);
            f.fx().remove(crack);
            f.fx().remove(tracker);
            f.lookAt(null);
        }
    }

    static final class Skulls extends BossMove {
        static final double SPEED = 0.45, TURN = Math.toRadians(7), BLAST = 2.5;

        private final class Skull {
            Location pos;
            Vector vel;
            final Player target;
            final ItemDisplay display;
            Slime proxy;
            final int born;
            double hp = 10;
            boolean dead;

            Skull(Location pos, Vector vel, Player target) {
                this.pos = pos;
                this.vel = vel;
                this.target = target;
                born = f.tick();
                display = f.fx().effect("fx/skull", null, 15);
                proxy = f.hitboxes().proxy(pos, 1.1, (attacker, damage, damager) -> hurt(this, attacker, damage));
            }
        }

        private final List<Player> marked = new ArrayList<>();
        private final Map<Player, ItemDisplay> icons = new HashMap<>();
        private final List<Skull> skulls = new ArrayList<>();
        private int launchAt;

        Skulls(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.SKULLS, escort);
        }

        protected void onBegin() {
            int count = f.perGroup(3, 4);
            marked.addAll(f.chooseDistinct(count));
            for (Player p : marked) {
                icons.put(p, f.fx().icon("icon_target", BossFx.DANGER, p.getLocation().add(0, 2.7, 0), 0.9f));
                f.markPlayer(p, "Consuming Skulls are hunting you!");
            }
            if (animate()) f.play("spit");
            launchAt = f.marker("spit", "launch", 26);
            impactAt = launchAt;
            length = launchAt + 200;
            f.fx().sound(f.bossGround().add(0, 8, 0), "boss.roar_short", 2.5f, 1.3f);
        }

        protected void onTick(int t) {
            if (t % 2 == 0)
                for (var e : icons.entrySet()) BossFx.follow(e.getValue(), e.getKey().getLocation().add(0, 2.7, 0));
            if (t == launchAt) {
                int i = 0;
                for (Player p : marked) {
                    if (!f.targetable().contains(p)) continue;
                    for (int k = 0; k < (marked.size() == 1 ? 2 : 1); k++) {
                        String bone = (i++ % 2 == 0) ? "skull_l" : "skull_r";
                        Location mouth = f.model().socket(bone, "mouth");
                        Vector dir = p.getEyeLocation().toVector().subtract(mouth.toVector()).normalize().multiply(0.3).add(new Vector(0, 0.12, 0));
                        dir.rotateAroundY((k == 0 ? -1 : 1) * 0.35);
                        if (skulls.size() < 7) skulls.add(new Skull(mouth, dir, p));
                        f.fx().sound(mouth, "atk.skull_launch", 2.5f, 0.9f + f.random().nextFloat() * 0.2f);
                        f.fx().particle(Particle.SOUL_FIRE_FLAME, mouth, 12, 0.3, 0.05);
                    }
                }
            }
            if (t > launchAt) {
                for (Skull s : skulls) step(s);
                skulls.removeIf(s -> s.dead);
                if (skulls.isEmpty()) finish();
            }
        }

        private void step(Skull s) {
            int age = f.tick() - s.born;
            double speed = Math.min(SPEED, 0.3 + age * 0.008);
            if (s.target.isValid() && f.targetable().contains(s.target)) {
                Vector want = s.target.getLocation().add(0, 1.0, 0).toVector().subtract(s.pos.toVector());
                if (want.lengthSquared() > 1e-4) {
                    Vector cur = s.vel.clone().normalize();
                    Vector desired = want.normalize();
                    double angle = cur.angle(desired);
                    if (angle > TURN) {
                        Vector axis = cur.getCrossProduct(desired);
                        if (axis.lengthSquared() < 1e-8) axis = new Vector(0, 1, 0);
                        cur.rotateAroundAxis(axis.normalize(), TURN);
                    } else cur = desired;
                    s.vel = cur;
                }
            }
            s.vel = s.vel.clone().normalize().multiply(speed);
            Location next = s.pos.clone().add(s.vel);
            var block = s.pos.getWorld().rayTraceBlocks(s.pos, s.vel.clone().normalize(), speed, FluidCollisionMode.NEVER, true);
            Player struck = null;
            for (Player p : f.targetable()) {
                var trace = p.getBoundingBox().clone().expand(0.35).rayTrace(s.pos.toVector(), s.vel.clone().normalize(), speed);
                if (trace != null) {
                    struck = p;
                    break;
                }
            }
            if (struck != null || block != null || age > 170 || !f.arena().near(next) || next.getY() < f.floorY() + 0.2) {
                explode(s, struck != null ? next : s.pos);
                return;
            }
            s.pos = next;
            BossFx.apply(s.display, f.fx().effectMatrix("fx/skull", s.pos, BossFx.aim(s.vel.getX(), s.vel.getY(), s.vel.getZ()), new Vector3f(1.35f)), 1);
            f.hitboxes().move(s.proxy, s.pos, 1.1);
            f.fx().particle(Particle.SOUL_FIRE_FLAME, s.pos, 2, 0.12, 0.01);
            if (age % 2 == 0) f.fx().particle(Particle.LARGE_SMOKE, s.pos, 1, 0.05, 0.0);
            if (age % 16 == 0) f.fx().sound(s.pos, "atk.skull_fly", 1.6f, 1f);
        }

        private void explode(Skull s, Location at) {
            s.dead = true;
            for (Player p : f.targetable())
                if (p.getLocation().add(0, 1, 0).distanceSquared(at) <= BLAST * BLAST) {
                    f.damage(p, f.damageValue("skull"), "skull", false, 40, null);
                }
            f.fx().sound(at, "atk.skull_explode", 3f, 1f);
            f.fx().particle(Particle.EXPLOSION, at, 1, 0.1, 0);
            f.fx().particle(Particle.SOUL_FIRE_FLAME, at, 30, 0.8, 0.12);
            f.fx().light(at, 13, 6);
            remove(s);
        }

        private boolean hurt(Skull s, Player attacker, double damage) {
            if (s.dead) return false;
            s.hp -= Math.max(4, damage);
            f.fx().sound(s.pos, "boss.hurt", 1.2f, 1.6f);
            if (s.hp > 0) return true;
            s.dead = true;
            f.fx().sound(s.pos, "atk.skull_break", 2f, 1f);
            f.fx().particle(Particle.BLOCK, s.pos, 30, 0.3, 0.3, 0.3, 0.1, Material.BONE_BLOCK.createBlockData());
            f.fx().particle(Particle.SOUL, s.pos, 10, 0.3, 0.05);
            f.credit(attacker);
            f.success(attacker, "Consuming Skulls smashed");
            remove(s);
            return true;
        }

        boolean deflect(Entity proxy, Player by) {
            for (Skull s : skulls)
                if (!s.dead && s.proxy != null && s.proxy.getUniqueId().equals(proxy.getUniqueId())) {
                    s.hp = 0;
                    return hurt(s, by, 100);
                }
            return false;
        }

        private void remove(Skull s) {
            f.fx().remove(s.display);
            f.hitboxes().release(s.proxy);
            s.proxy = null;
        }

        protected void onCleanup() {
            for (Skull s : skulls) remove(s);
            skulls.clear();
            icons.values().forEach(f.fx()::remove);
            icons.clear();
        }
    }

    static final class Chain extends BossMove {
        static final int LINKS = 8, DURATION = 140;

        private final class Link {
            final Player player;
            final List<ItemDisplay> links = new ArrayList<>();
            ItemDisplay lock, icon;
            Slime proxy;
            TextDisplay counter;
            int hits, tension;
            boolean done;

            Link(Player player) {
                this.player = player;
            }
        }

        private final List<Link> chains = new ArrayList<>();
        private int castAt;
        private boolean staggered;

        Chain(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.CHAIN, escort);
        }

        protected void onBegin() {
            int count = f.perGroup(3, 3);
            for (Player p : f.chooseDistinct(count)) {
                Link l = new Link(p);
                chains.add(l);
                l.icon = f.fx().icon("icon_chain", BossFx.GOLD, p.getLocation().add(0, 2.7, 0), 0.9f);
                f.markPlayer(p, f.solo() ? "Soul Chain! Keep more than 18 blocks of distance" : "Soul Chain! Teammates: break the golden lock");
            }
            if (animate()) f.play("chain");
            castAt = f.marker("chain", "cast", 20);
            impactAt = castAt;
            length = castAt + DURATION + 4;
            f.fx().sound(f.model().socket("heart", "core"), "atk.chain_cast", 3f, 1f);
        }

        protected void onTick(int t) {
            Location heart = f.model().socket("heart", "core");
            for (Link l : chains) {
                if (l.done) continue;
                if (t % 2 == 0) BossFx.follow(l.icon, l.player.getLocation().add(0, 2.7, 0));
                if (t < castAt) continue;
                if (!l.player.isValid() || !f.targetable().contains(l.player)) {
                    release(l);
                    continue;
                }
                Location to = chainEnd(heart, l.player);
                if (t == castAt) spawn(l, heart, to);
                if (t % 2 == 0) render(l, heart, to);
                Vector pull = f.bossGround().toVector().subtract(l.player.getLocation().toVector()).setY(0);
                double dist = pull.length();
                if (dist > 6) {
                    Vector v = l.player.getVelocity();
                    Vector add = pull.normalize().multiply(0.045);
                    Vector horiz = v.clone().setY(0).add(add);
                    if (horiz.length() > 0.35) horiz.normalize().multiply(0.35);
                    l.player.setVelocity(horiz.setY(v.getY()));
                }
                if (dist > 18) l.tension++;
                if (l.tension >= 30) {
                    snap(l, null);
                    continue;
                }
                if (t == castAt + 50 || t == castAt + 100) {
                    for (int i = 1; i < 8; i++) f.fx().particle(Particle.SOUL, lerp(to, heart, i / 8.0), 1, 0.05, 0.01);
                }
                if (t % 4 == 0) f.fx().particle(Particle.SOUL, lerp(heart, to, f.random().nextDouble()), 1, 0.1, 0.01);
                if (t == castAt + DURATION) fail(l, heart);
            }
            if (t > castAt && chains.stream().allMatch(l -> l.done)) finish();
        }

        private static Location chainEnd(Location heart, Player p) {
            Location feet = p.getLocation().add(0, 0.7, 0);
            Vector back = heart.toVector().subtract(feet.toVector());
            double len = back.length();
            return len < 1.5 ? feet : feet.add(back.multiply(0.9 / len));
        }

        private static Location lockPoint(Location from, Location to) {
            double drop = from.getY() - to.getY();
            double share = drop > 1 ? Math.clamp((from.getY() - (to.getY() + 1.4)) / drop, 0.45, 0.93) : 0.5;
            return lerp(from, to, share);
        }

        private static Location lerp(Location a, Location b, double f) {
            return a.clone().add(b.toVector().subtract(a.toVector()).multiply(f));
        }

        private void spawn(Link l, Location from, Location to) {
            for (int i = 0; i < LINKS; i++) {
                ItemDisplay d = f.fx().effect("fx/chain", null, 13);
                if (d == null) continue;
                l.links.add(d);
                if (i >= LINKS - 3) l.player.hideEntity(f.service().plugin(), d);
            }
            Location mid = lockPoint(from, to);
            l.lock = f.fx().effect("fx/lock", null, 15);
            if (l.lock != null) l.lock.setGlowing(true);
            if (l.lock != null) l.lock.setGlowColorOverride(BossFx.GOLD);
            l.proxy = f.hitboxes().proxy(mid, 1.8, (attacker, damage, damager) -> hit(l, attacker));
            l.counter = f.fx().label(mid.clone().add(0, 1.2, 0), BossHud.plain("Lock 0/3", BossHud.GOLD), 1.1f);
            render(l, from, to);
        }

        private void render(Link l, Location from, Location to) {
            Vector d = to.toVector().subtract(from.toVector());
            double len = d.length();
            Location mid = lockPoint(from, to);
            for (int i = 0; i < l.links.size(); i++) {
                Location a = lerp(from, to, i / (double) LINKS);
                float seg = (float) (len / LINKS / 2.0);
                BossFx.apply(l.links.get(i), f.fx().effectMatrix("fx/chain", a, BossFx.aim(d.getX(), d.getY(), d.getZ()), new Vector3f(0.85f, 0.85f, Math.max(0.05f, seg))), 2);
            }
            float pulse = 1.2f + 0.12f * (float) Math.sin(f.tick() * 0.4);
            BossFx.apply(l.lock, f.fx().effectMatrix("fx/lock", mid, BossFx.aim(d.getX(), 0, d.getZ()), new Vector3f(pulse)), 2);
            f.hitboxes().move(l.proxy, mid, 1.8);
            if (l.counter != null) BossFx.follow(l.counter, mid.clone().add(0, 1.2, 0));
        }

        private boolean hit(Link l, Player attacker) {
            if (l.done || attacker != null && attacker.equals(l.player) && !f.solo()) return false;
            l.hits += 1;
            Location mid = l.proxy.getLocation();
            f.fx().sound(mid, "atk.chain_hit", 2f, 1f + l.hits * 0.1f);
            f.fx().particle(Particle.ELECTRIC_SPARK, mid.clone().add(0, 0.6, 0), 10, 0.3, 0.1);
            if (l.counter != null) l.counter.text(BossHud.plain("Lock " + Math.min(3, l.hits) + "/3", BossHud.GOLD));
            if (l.hits >= 3) snap(l, attacker);
            return true;
        }

        void stun(Entity proxy, Player by) {
            for (Link l : chains)
                if (!l.done && l.proxy != null && l.proxy.getUniqueId().equals(proxy.getUniqueId())) {
                    hit(l, by);
                    hit(l, by);
                }
        }

        private void snap(Link l, Player by) {
            Location at = l.proxy != null ? l.proxy.getLocation() : l.player.getLocation();
            f.fx().sound(at, "atk.chain_snap", 3f, 1f);
            f.fx().particle(Particle.END_ROD, at.clone().add(0, 0.5, 0), 25, 0.6, 0.08);
            f.success(l.player, "Soul Chain broken");
            if (by != null && !by.equals(l.player)) {
                f.credit(by);
                f.success(by, "Chain severed · the boss staggers!");
            }
            release(l);
            if (!staggered) {
                staggered = true;
                f.stagger();
            }
        }

        private void fail(Link l, Location heart) {
            if (l.done) return;
            f.fx().sound(heart, "atk.chain_fail", 3f, 1f);
            f.damage(l.player, f.damageValue("chain"), "chain", true, 60, null);
            double healed = f.heal(0.015);
            if (healed > 0) f.broadcast(Component.text("The Soul Chain feeds the Ash Colossus (+" + (int) healed + " HP)", BossHud.DANGER));
            for (int i = 0; i < 12; i++) f.fx().particle(Particle.SOUL, lerp(l.player.getLocation().add(0, 1, 0), heart, i / 12.0), 2, 0.1, 0.02);
            release(l);
        }

        private void release(Link l) {
            l.done = true;
            l.links.forEach(f.fx()::remove);
            l.links.clear();
            f.fx().remove(l.lock);
            f.fx().remove(l.icon);
            f.hitboxes().release(l.proxy);
            l.proxy = null;
            if (l.counter != null) l.counter.remove();
            l.counter = null;
        }

        protected void onCleanup() {
            chains.forEach(this::release);
        }
    }
}
