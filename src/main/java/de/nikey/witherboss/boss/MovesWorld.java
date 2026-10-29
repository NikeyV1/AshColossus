package de.nikey.witherboss.boss;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

final class MovesWorld {
    private MovesWorld() {}

    static final class Debris extends BossMove {
        static final double RADIUS = 2.6;
        static final int FLIGHT = 46;

        private final class Rock {
            final double x, z;
            final Location from;
            final BlockDisplay display;
            final float size, spin;

            Rock(double x, double z, Location from, Material type, float size) {
                this.x = x;
                this.z = z;
                this.from = from;
                this.size = size;
                this.spin = (f.random().nextFloat() - 0.5f) * 5f;
                display = f.fx().block(type, type == Material.MAGMA_BLOCK ? 15 : 9);
            }
        }

        private final List<Rock> rocks = new ArrayList<>();
        private int castAt;

        Debris(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.DEBRIS, escort);
        }

        protected void onBegin() {
            if (animate()) f.play("command");
            castAt = animate() ? f.marker("command", "cast", 24) : 12;
            impactAt = castAt + FLIGHT;
            length = impactAt + 110;
            int count = Math.clamp(4 + f.groupSize() + f.stage() / 2, 4, 14);
            List<double[]> spots = MovesSummon.spots(f, count, RADIUS * 1.8, 0.9);
            int i = 0;
            for (double[] s : spots) {
                mark(f.fx().circle(s[0], s[1], RADIUS, BossFx.DANGER, impactAt));
                Location from = f.arena().seal(i % 3).add(0, 9.5, 0);
                Material type = f.random().nextInt(10) < 3 ? Material.MAGMA_BLOCK : f.random().nextBoolean() ? Material.BLACKSTONE : Material.POLISHED_BLACKSTONE_BRICKS;
                rocks.add(new Rock(s[0], s[1], from, type, 1.7f + f.random().nextFloat() * 0.8f));
                i++;
            }
            f.fx().sound(f.model().socket("head", "mouth"), "boss.roar_short", 3f, 0.8f);
            for (Player p : f.targetable()) f.hint(p, "Forge Collapse · avoid the circles!");
        }

        private Location position(Rock r, double s) {
            Location to = f.floorAt(r.x, r.z).add(0, r.size / 2, 0);
            double arc = 9 + Math.hypot(to.getX() - r.from.getX(), to.getZ() - r.from.getZ()) * 0.25;
            return new Location(to.getWorld(), r.from.getX() + (to.getX() - r.from.getX()) * s, r.from.getY() + (to.getY() - r.from.getY()) * s + arc * 4 * s * (1 - s), r.from.getZ() + (to.getZ() - r.from.getZ()) * s);
        }

        protected void onTick(int t) {
            if (t == castAt) {
                f.fx().sound(f.arena().center().add(0, 12, 0), "atk.debris_fall", 5f, 1f);
                for (int i = 0; i < 3; i++) {
                    Location top = f.arena().seal(i).add(0, 9.5, 0);
                    f.fx().particle(Particle.BLOCK, top, 40, 1.2, 0.8, 1.2, 0.1, Material.BLACKSTONE.createBlockData());
                }
            }
            if (t >= castAt && t < impactAt && t % 2 == 0) {
                double s = (t - castAt + 2) / (double) FLIGHT;
                for (Rock r : rocks) {
                    if (r.display == null) continue;
                    Location at = position(r, Math.min(1, s));
                    Quaternionf q = new Quaternionf().rotateXYZ((float) (r.spin * s * 3), (float) (r.spin * s * 2), (float) (r.spin * s));
                    BossFx.apply(r.display, f.fx().blockMatrix(at, q, new Vector3f(r.size)), 2);
                    if (t % 4 == 0) f.fx().particle(Particle.SMOKE, at, 2, 0.3, 0.01);
                }
            }
            if (t == impactAt) impact();
            if (t == impactAt + 12) release();
            if (t == impactAt + 100)
                for (Rock r : rocks) f.fx().remove(r.display);
        }

        private void impact() {
            marks.forEach(BossFx.Mark::remove);
            Set<UUID> hit = new HashSet<>();
            int sounds = 0;
            for (Rock r : rocks) {
                Location at = f.floorAt(r.x, r.z);
                for (Player p : f.targetable()) {
                    if (hit.contains(p.getUniqueId())) continue;
                    if (Math.hypot(p.getLocation().getX() - r.x, p.getLocation().getZ() - r.z) <= RADIUS && p.getLocation().getY() - f.floorY() < 3.5) {
                        hit.add(p.getUniqueId());
                        f.damage(p, f.damageValue("debris"), "debris", true, 0, f.away(at, p, 0.6, 0.6));
                    }
                }
                f.fx().flash(r.x, r.z, RADIUS, BossFx.DANGER_HOT);
                f.fx().particle(Particle.BLOCK, at.clone().add(0, 0.3, 0), 30, 1.2, 0.2, 1.2, 0.2, Material.BLACKSTONE.createBlockData());
                f.fx().particle(Particle.EXPLOSION, at.clone().add(0, 0.6, 0), 1, 0.4, 0);
                if (sounds++ < 4) f.fx().sound(at, "atk.debris_hit", 2.5f, 1f);
                if (r.display != null) {
                    Location rest = f.floorAt(r.x, r.z).add(0, r.size * 0.3, 0);
                    BossFx.apply(r.display, f.fx().blockMatrix(rest, new Quaternionf().rotateY(r.spin), new Vector3f(r.size * 0.85f, r.size * 0.6f, r.size * 0.85f)), 3);
                }
            }
            f.shake(f.arena().center(), 14);
        }

        protected void onCleanup() {
            for (Rock r : rocks) f.fx().remove(r.display);
            rocks.clear();
        }
    }

    static final class Grid extends BossMove {
        static final double WIDTH = 3.6, PITCH = 8.6, RADIUS = 34;
        static final int WARN = 34;

        private record Lane(double sx, double sz, double yaw, double len) {
            double cx() {
                return sx + Math.sin(yaw) * len / 2;
            }

            double cz() {
                return sz + Math.cos(yaw) * len / 2;
            }
        }

        private final List<List<Lane>> waves = new ArrayList<>();
        private final List<List<BossFx.Mark>> shown = new ArrayList<>();
        private int castAt;

        Grid(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.GRID, escort);
        }

        private List<Lane> lanes(double yaw, int parity, double shift) {
            List<Lane> out = new ArrayList<>();
            double dx = Math.sin(yaw), dz = Math.cos(yaw), px = Math.cos(yaw), pz = -Math.sin(yaw);
            for (int k = -4; k <= 4; k++) {
                if (Math.floorMod(k, 2) != parity) continue;
                double off = k * PITCH + shift;
                if (Math.abs(off) >= RADIUS - 2) continue;
                double half = Math.sqrt(RADIUS * RADIUS - off * off);
                out.add(new Lane(f.arena().x() + px * off - dx * half, f.arena().z() + pz * off - dz * half, yaw, half * 2));
            }
            return out;
        }

        protected void onBegin() {
            if (animate()) f.play("command");
            castAt = animate() ? f.marker("command", "cast", 24) : 10;
            boolean core = f.state().phase() == RaidState.Phase.CORE;
            double yaw = f.random().nextInt(core ? 4 : 2) * (core ? Math.PI / 4 : Math.PI / 2) + (f.random().nextBoolean() ? Math.PI : 0);
            double shift = (f.random().nextDouble() - 0.5) * PITCH * 0.5;
            waves.add(lanes(yaw, 0, shift));
            waves.add(lanes(yaw, 1, shift));
            if (core || f.stage() >= 4) waves.add(lanes(yaw + Math.PI / 2, 0, 0));
            for (int i = 0; i < waves.size(); i++) shown.add(new ArrayList<>());
            impactAt = castAt + WARN;
            length = castAt + WARN * waves.size() + 50;
            f.fx().sound(f.model().socket("heart", "core"), "atk.nova_charge", 3f, 0.7f);
            for (Player p : f.targetable()) f.hint(p, "Ember Grid · stand between the lanes!");
        }

        protected void onTick(int t) {
            for (int w = 0; w < waves.size(); w++) {
                if (t == castAt + WARN * w) show(w);
                if (t == castAt + WARN * (w + 1)) fire(w);
            }
            if (t == castAt + WARN * waves.size()) release();
        }

        private void show(int w) {
            for (Lane l : waves.get(w)) {
                BossFx.Mark m = f.fx().lane(l.sx(), l.sz(), l.yaw(), l.len(), WIDTH, BossFx.DANGER, WARN);
                if (m != null) {
                    shown.get(w).add(m);
                    marks.add(m);
                }
            }
            f.fx().sound(f.arena().center(), "ui.lock", 2f, 0.7f);
        }

        private void fire(int w) {
            shown.get(w).forEach(BossFx.Mark::remove);
            Set<UUID> hit = new HashSet<>();
            for (Lane l : waves.get(w)) {
                var shape = new BossGeometry.Lane(l.sx(), l.sz(), l.yaw(), l.len(), WIDTH / 2);
                for (Player p : f.targetable()) {
                    if (hit.contains(p.getUniqueId()) || !shape.contains(p.getLocation().getX(), p.getLocation().getZ())) continue;
                    if (p.getLocation().getY() - f.floorY() < 1.0) {
                        hit.add(p.getUniqueId());
                        Vector side = new Vector(Math.cos(l.yaw()), 0, -Math.sin(l.yaw()));
                        Vector rel = p.getLocation().toVector().subtract(new Vector(l.cx(), p.getLocation().getY(), l.cz()));
                        if (rel.dot(side) < 0) side.multiply(-1);
                        f.damage(p, f.damageValue("grid"), "grid", true, 40, side.multiply(0.9).setY(0.45));
                    } else f.success(p, "Jumped over the embers");
                }
                BlockDisplay wall = f.fx().block(Material.MAGMA_BLOCK, 15);
                Location floor = f.floorAt(l.cx(), l.cz());
                Quaternionf q = new Quaternionf().rotateY((float) l.yaw());
                if (wall != null) {
                    BossFx.apply(wall, f.fx().blockMatrix(floor.clone().add(0, 0.15, 0), q, new Vector3f((float) WIDTH, 0.3f, (float) l.len())), 0);
                    f.fx().later(1, () -> BossFx.apply(wall, f.fx().blockMatrix(floor.clone().add(0, 0.85, 0), q, new Vector3f((float) WIDTH, 1.7f, (float) l.len())), 3));
                    f.fx().later(18, () -> BossFx.apply(wall, f.fx().blockMatrix(floor.clone().add(0, 0.1, 0), q, new Vector3f((float) WIDTH, 0.2f, (float) l.len())), 5));
                    f.fx().later(25, () -> f.fx().remove(wall));
                }
                f.fx().flashLane(l.sx(), l.sz(), l.yaw(), l.len(), WIDTH, BossFx.DANGER_HOT);
                for (double s = 2; s < l.len(); s += 5)
                    f.fx().particle(Particle.FLAME, f.floorAt(l.sx() + Math.sin(l.yaw()) * s, l.sz() + Math.cos(l.yaw()) * s).add(0, 0.6, 0), 6, 0.9, 0.5, 0.9, 0.04, null);
                if (hit.isEmpty() || waves.get(w).indexOf(l) == 0) f.fx().sound(floor, "atk.grid_fire", 3f, 1f);
            }
            f.shake(f.arena().center(), 8);
        }
    }

    static final class Spikes extends BossMove {
        static final double STEP = 2.4, RADIUS = 1.5;
        static final int COUNT = 11, TELE = 16, INTERVAL = 3;

        private final class Spike {
            final double x, z;
            final int erupt;
            final BossFx.Mark mark;
            BlockDisplay block;
            boolean fired;

            Spike(double x, double z, int erupt, BossFx.Mark mark) {
                this.x = x;
                this.z = z;
                this.erupt = erupt;
                this.mark = mark;
            }
        }

        private final List<Spike> spikes = new ArrayList<>();
        private final List<Player> targets = new ArrayList<>();
        private final double[] yaws = new double[3];
        private int startAt, lines;

        Spikes(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.SPIKES, escort);
        }

        protected void onBegin() {
            if (animate()) f.play("rake");
            startAt = animate() ? f.marker("rake", "start", 22) : 14;
            impactAt = startAt + TELE;
            length = startAt + COUNT * INTERVAL + TELE + 40;
            lines = Math.min(3, f.perGroup(3, 3) + (f.state().phase() == RaidState.Phase.CORE ? 1 : 0));
            targets.addAll(f.chooseDistinct(lines));
            lines = Math.max(1, targets.size());
            for (Player p : targets) f.markPlayer(p, "Bone Spikes are hunting you!");
            f.fx().sound(f.bossGround().add(0, 2, 0), "boss.roar_short", 2.5f, 0.9f);
        }

        protected void onTick(int t) {
            if (t < startAt && !targets.isEmpty()) f.turnTowards(targets.getFirst().getLocation().getX(), targets.getFirst().getLocation().getZ(), 14);
            if (t == startAt) {
                for (int k = 0; k < lines; k++) {
                    Player p = k < targets.size() ? targets.get(k) : null;
                    yaws[k] = p == null ? f.model().facing() : BossModel.facingTowards(p.getLocation().getX() - f.bossX(), p.getLocation().getZ() - f.bossZ());
                }
                f.fx().sound(f.bossGround().add(0, 1, 0), "atk.whoosh", 3f, 0.6f);
            }
            if (t >= startAt && (t - startAt) % INTERVAL == 0 && (t - startAt) / INTERVAL < COUNT) {
                int i = (t - startAt) / INTERVAL;
                double d = 3.2 + i * STEP;
                for (int k = 0; k < lines; k++) {
                    double[] c = f.clampToArena(f.bossX() + Math.sin(yaws[k]) * d, f.bossZ() + Math.cos(yaws[k]) * d, 33);
                    spikes.add(new Spike(c[0], c[1], t + TELE, f.fx().circle(c[0], c[1], RADIUS, BossFx.DANGER, TELE)));
                }
            }
            for (Spike s : spikes) {
                if (!s.fired && t >= s.erupt) fire(s);
            }
            spikes.removeIf(s -> {
                if (s.fired && t >= s.erupt + 16) {
                    f.fx().remove(s.block);
                    return true;
                }
                return false;
            });
            if (t == startAt + COUNT * INTERVAL + TELE) release();
        }

        private void fire(Spike s) {
            s.fired = true;
            if (s.mark != null) s.mark.remove();
            Location at = f.floorAt(s.x, s.z);
            for (Player p : f.targetable())
                if (Math.hypot(p.getLocation().getX() - s.x, p.getLocation().getZ() - s.z) <= RADIUS && p.getLocation().getY() - f.floorY() < 2.6)
                    f.damage(p, f.damageValue("spike"), "spike", true, 0, new Vector(0, 0.8, 0));
            org.bukkit.block.data.type.PointedDripstone data = (org.bukkit.block.data.type.PointedDripstone) Material.POINTED_DRIPSTONE.createBlockData();
            data.setVerticalDirection(org.bukkit.block.BlockFace.UP);
            data.setThickness(org.bukkit.block.data.type.PointedDripstone.Thickness.BASE);
            s.block = f.fx().block(data, 7);
            if (s.block != null) {
                Quaternionf q = new Quaternionf().rotateY((float) (f.random().nextDouble() * Math.PI));
                BossFx.apply(s.block, f.fx().blockMatrix(at.clone().add(0, 0.1, 0), q, new Vector3f(1.9f, 0.2f, 1.9f)), 0);
                f.fx().later(1, () -> BossFx.apply(s.block, f.fx().blockMatrix(at.clone().add(0, 1.7, 0), q, new Vector3f(2.3f, 3.6f, 2.3f)), 2));
                f.fx().later(12, () -> BossFx.apply(s.block, f.fx().blockMatrix(at.clone().add(0, 0.1, 0), q, new Vector3f(1.9f, 0.2f, 1.9f)), 4));
            }
            f.fx().particle(Particle.BLOCK, at.clone().add(0, 0.3, 0), 12, 0.6, 0.1, 0.6, 0.15, Material.BONE_BLOCK.createBlockData());
            if (spikes.indexOf(s) % 2 == 0) f.fx().sound(at, "atk.spike", 2.2f, 0.9f + f.random().nextFloat() * 0.3f);
        }

        protected void onCleanup() {
            for (Spike s : spikes) {
                if (s.mark != null) s.mark.remove();
                f.fx().remove(s.block);
            }
            spikes.clear();
        }
    }

    static final class Blade extends BossMove {
        static final double LENGTH = 31, WIDTH = 2.6, OMEGA = Math.toRadians(2.4);

        private final BlockDisplay[] chains = new BlockDisplay[2];
        private final BossFx.Mark[] lanes = new BossFx.Mark[2];
        private final Map<UUID, Integer> lastHit = new HashMap<>();
        private int fireAt, fireEnd, direction;
        private double yaw0;

        Blade(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.BLADE, escort);
        }

        protected void onBegin() {
            f.play("lash");
            fireAt = f.marker("lash", "fire", 28);
            fireEnd = f.marker("lash", "fire_end", 140);
            impactAt = fireAt;
            length = f.rig().clip("lash").length();
            direction = f.random().nextBoolean() ? 1 : -1;
            Player target = f.choose();
            yaw0 = target == null ? f.model().facing() : BossModel.facingTowards(target.getLocation().getX() - f.bossX(), target.getLocation().getZ() - f.bossZ());
            for (int k = 0; k < 2; k++) {
                double yaw = yaw0 + k * Math.PI;
                lanes[k] = mark(f.fx().lane(f.bossX(), f.bossZ(), yaw, LENGTH, WIDTH, BossFx.DANGER, fireAt));
            }
            f.fx().sound(f.model().socket("heart", "core"), "atk.chain_cast", 3f, 0.6f);
            for (Player p : f.targetable()) f.hint(p, "Chain Wheel · jump over the chain!");
        }

        protected void onTick(int t) {
            if (t < fireAt) f.turnTowards(f.bossX() + Math.sin(yaw0) * 5, f.bossZ() + Math.cos(yaw0) * 5, 14);
            if (t == fireAt) {
                for (int k = 0; k < 2; k++) {
                    chains[k] = f.fx().block(chain(), 11);
                    if (lanes[k] != null) lanes[k].color(BossFx.DANGER_HOT);
                }
                f.fx().sound(f.bossGround().add(0, 3, 0), "atk.blade_whirl", 4f, 1f);
            }
            if (t >= fireAt && t <= fireEnd) {
                double swept = direction * OMEGA * (t - fireAt);
                f.model().extraYaw(swept);
                if ((t - fireAt) % 36 == 0 && t > fireAt) f.fx().sound(f.bossGround().add(0, 2, 0), "atk.blade_whirl", 3f, 0.9f);
                for (int k = 0; k < 2; k++) {
                    double yaw = yaw0 + swept + k * Math.PI;
                    double len = Math.min(LENGTH, BossGeometry.clip(f.bossX(), f.bossZ(), yaw, LENGTH, f.pillars()));
                    if (t % 2 == 0) {
                        f.fx().moveLane(lanes[k], f.bossX(), f.bossZ(), yaw, len, WIDTH, 2);
                        if (chains[k] != null) {
                            Location mid = f.floorAt(f.bossX() + Math.sin(yaw) * len / 2, f.bossZ() + Math.cos(yaw) * len / 2).add(0, 0.7, 0);
                            BossFx.apply(chains[k], f.fx().blockMatrix(mid, new Quaternionf().rotateY((float) yaw), new Vector3f(1.3f, 1.3f, (float) Math.max(0.1, len))), 2);
                        }
                        f.fx().particle(Particle.CRIT, f.floorAt(f.bossX() + Math.sin(yaw) * len, f.bossZ() + Math.cos(yaw) * len).add(0, 0.8, 0), 4, 0.4, 0.1);
                    }
                    var shape = new BossGeometry.Lane(f.bossX(), f.bossZ(), yaw, len, WIDTH / 2);
                    for (Player p : f.targetable()) {
                        if (!shape.contains(p.getLocation().getX(), p.getLocation().getZ())) continue;
                        if (t - lastHit.getOrDefault(p.getUniqueId(), -100) < 40) continue;
                        lastHit.put(p.getUniqueId(), t);
                        if (p.getLocation().getY() - f.floorY() < 0.8) {
                            Vector push = new Vector(Math.cos(yaw), 0, -Math.sin(yaw)).multiply(direction * 0.9).setY(0.35);
                            f.damage(p, f.damageValue("blade"), "blade", true, 0, push);
                        } else f.success(p, "Jumped over the chain");
                    }
                }
            }
            if (t == fireEnd + 1) {
                f.model().extraYaw(0);
                for (int k = 0; k < 2; k++) {
                    f.fx().remove(chains[k]);
                    if (lanes[k] != null) lanes[k].remove();
                }
            }
        }

        private static org.bukkit.block.data.BlockData chain() {
            org.bukkit.block.data.BlockData data = Material.IRON_CHAIN.createBlockData();
            if (data instanceof Orientable o) o.setAxis(org.bukkit.Axis.Z);
            return data;
        }

        protected void onCleanup() {
            f.model().extraYaw(0);
            for (BlockDisplay d : chains) f.fx().remove(d);
        }
    }

    static final class Miasma extends BossMove {
        static final double RADIUS = 4.2, GROWTH = 0.9;
        static final int WARN = 30, LIFE = 260;

        private final class Zone {
            final double x, z;
            final BossFx.Mark mark;

            Zone(double x, double z, BossFx.Mark mark) {
                this.x = x;
                this.z = z;
                this.mark = mark;
            }
        }

        private final List<Zone> zones = new ArrayList<>();
        private int castAt;

        Miasma(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.MIASMA, escort);
        }

        protected void onBegin() {
            if (animate()) f.play("command");
            castAt = animate() ? f.marker("command", "cast", 24) : 8;
            impactAt = castAt + WARN;
            length = impactAt + LIFE + 10;
            f.fx().sound(f.model().socket("head", "mouth"), "boss.roar_short", 3f, 0.6f);
            for (Player p : f.targetable()) f.hint(p, "Consuming Miasma · leave the circles!");
        }

        private double radius(int t) {
            return RADIUS + GROWTH * Math.clamp((t - impactAt) / (double) LIFE, 0, 1);
        }

        protected void onTick(int t) {
            if (t == castAt) {
                int count = Math.clamp(2 + f.groupSize() / 3 + f.stage() / 3, 2, 6);
                for (double[] s : MovesSummon.spots(f, count, RADIUS * 2 + 1.5, 0.85)) {
                    BossFx.Mark m = mark(f.fx().circle(s[0], s[1], RADIUS, BossFx.DANGER, WARN));
                    zones.add(new Zone(s[0], s[1], m));
                    f.fx().sound(f.floorAt(s[0], s[1]), "ui.lock", 1.5f, 0.6f);
                }
            }
            if (t == impactAt) {
                for (Zone z : zones) if (z.mark != null) z.mark.color(BossFx.DANGER_HOT);
                f.fx().sound(f.arena().center(), "atk.miasma", 4f, 1f);
                release();
            }
            if (t > impactAt && t <= impactAt + LIFE) {
                double r = radius(t);
                for (Zone z : zones) {
                    Location at = f.floorAt(z.x, z.z);
                    if (t % 10 == 0) f.fx().moveCircle(z.mark, z.x, z.z, r, 10);
                    if (t % 2 == 0) {
                        for (int k = 0; k < 3; k++) {
                            double a = f.random().nextDouble() * Math.PI * 2, d = Math.sqrt(f.random().nextDouble()) * r;
                            Location p = at.clone().add(Math.sin(a) * d, 0.2 + f.random().nextDouble() * 1.2, Math.cos(a) * d);
                            f.fx().particle(Particle.DUST, p, 1, 0.15, 0.1, 0.15, 0, new Particle.DustOptions(org.bukkit.Color.fromRGB(0x3A1248), 2.4f));
                            if (k == 0) f.fx().particle(Particle.SOUL, p, 1, 0.1, 0.02);
                        }
                    }
                    if (t % 10 != 0) continue;
                    for (Player p : f.targetable()) {
                        if (Math.hypot(p.getLocation().getX() - z.x, p.getLocation().getZ() - z.z) > r) continue;
                        f.damage(p, f.damageValue("miasma"), "miasma", true, 0, null);
                    }
                }
            }
            if (t == impactAt + LIFE) {
                marks.forEach(BossFx.Mark::remove);
                for (Zone z : zones) f.fx().particle(Particle.SOUL, f.floorAt(z.x, z.z).add(0, 0.5, 0), 20, 1.5, 0.05);
            }
        }
    }
}
