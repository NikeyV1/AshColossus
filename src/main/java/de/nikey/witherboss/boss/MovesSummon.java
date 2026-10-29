package de.nikey.witherboss.boss;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

final class MovesSummon {
    private MovesSummon() {}

    static List<double[]> spots(WitherEncounter f, int count, double spacing, double nearFraction) {
        List<double[]> out = new ArrayList<>();
        List<Player> players = f.targetable();
        int near = (int) Math.round(count * nearFraction);
        List<Player> order = new ArrayList<>(players);
        Collections.shuffle(order, f.random());
        for (int i = 0; i < near && !order.isEmpty(); i++) {
            Player p = order.get(i % order.size());
            Location l = p.getLocation();
            double ox, oz;
            if (i < order.size()) {
                Vector v = p.getVelocity();
                ox = v.getX() * 8 + (f.random().nextDouble() - 0.5) * 2;
                oz = v.getZ() * 8 + (f.random().nextDouble() - 0.5) * 2;
            } else {
                double a = f.random().nextDouble() * Math.PI * 2;
                ox = Math.sin(a) * spacing * 0.95;
                oz = Math.cos(a) * spacing * 0.95;
            }
            add(f, out, l.getX() + ox, l.getZ() + oz, spacing);
        }
        for (int tries = 0; tries < 120 && out.size() < count; tries++) {
            double a = f.random().nextDouble() * Math.PI * 2, r = 6 + f.random().nextDouble() * 26;
            add(f, out, f.arena().x() + Math.sin(a) * r, f.arena().z() + Math.cos(a) * r, spacing);
        }
        return out;
    }

    private static void add(WitherEncounter f, List<double[]> out, double x, double z, double spacing) {
        double[] c = f.clampToArena(x, z, 31);
        c = f.awayFromPillars(c[0], c[1], 2.0);
        if (Math.hypot(c[0] - f.bossX(), c[1] - f.bossZ()) < 5) return;
        for (double[] o : out) if (Math.hypot(o[0] - c[0], o[1] - c[1]) < spacing) return;
        out.add(c);
    }

    static final class Minions extends BossMove {
        private final List<double[]> spawns = new ArrayList<>();
        private int summonAt;
        private int brutes;

        Minions(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.MINIONS, escort);
        }

        protected void onBegin() {
            if (animate()) f.play("summon");
            summonAt = f.marker("summon", "summon", 36);
            impactAt = summonAt;
            length = animate() ? f.rig().clip("summon").length() : summonAt + 30;
            int players = f.groupSize();
            int count = Math.min(10, 2 + (players + 1) / 2 + f.stage() / 3);
            count = (int) Math.round(count * f.tuning().minionCount());
            count = Math.min(count, f.tuning().minionCap() - f.minions().count());
            brutes = (players >= 5 ? 1 : 0) + (f.stage() >= 4 ? 1 : 0) + (f.state().phase() == RaidState.Phase.CORE ? 1 : 0);
            brutes = Math.min(brutes, count / 3);
            List<double[]> spots = new ArrayList<>();
            for (int i = 0; i < 3 && spots.size() < count; i++) {
                Location s = f.arena().seal(i);
                double dx = f.arena().x() - s.getX(), dz = f.arena().z() - s.getZ(), d = Math.hypot(dx, dz);
                spots.add(new double[] {s.getX() + dx / d * 3.2, s.getZ() + dz / d * 3.2});
            }
            spots.addAll(spots(f, Math.max(0, count - spots.size()), 4.5, 0.4));
            spawns.addAll(spots.subList(0, Math.min(count, spots.size())));
            for (double[] s : spawns) mark(f.fx().circle(s[0], s[1], 1.6, BossFx.DANGER, summonAt - 2));
            f.fx().sound(f.model().socket("head", "mouth"), "boss.roar_short", 3f, 0.7f);
            for (Player p : f.targetable()) f.hint(p, "Ash Sentinels · take out the sentinels!");
        }

        protected void onTick(int t) {
            if (t == summonAt - 14) f.fx().sound(f.bossGround().add(0, 3, 0), "atk.minion_spawn", 4f, 1f);
            if (t == summonAt) {
                marks.forEach(BossFx.Mark::remove);
                int i = 0;
                for (double[] s : spawns) {
                    Location at = f.floorAt(s[0], s[1]).add(0, 0.2, 0);
                    f.minions().spawn(at, i++ < brutes);
                    f.fx().flash(s[0], s[1], 1.8, BossFx.DANGER_HOT);
                }
                f.shake(f.bossGround(), 6);
            }
            if (t > summonAt + 20) finish();
        }
    }

    static final class Beacons extends BossMove {
        static final int DURATION = 900, PULSE = 90;
        static final double WAVE_SPEED = 0.45, WAVE_MAX = 11, WAVE_BAND = 1.0;

        private final class Beacon {
            final double x, z;
            final BlockDisplay block;
            final ItemDisplay beam, link;
            final Slime proxy;
            final TextDisplay label;
            double hp;
            boolean down;
            int wave = -1;
            ItemDisplay ring;
            final Set<UUID> hit = new HashSet<>();

            Beacon(double x, double z, double hp) {
                this.x = x;
                this.z = z;
                this.hp = hp;
                Location base = f.floorAt(x, z);
                block = f.fx().block(Material.BEACON, 15);
                if (block != null) BossFx.apply(block, f.fx().blockMatrix(base.clone().add(0, 1.0, 0), new Quaternionf(), new Vector3f(2f)), 0);
                beam = f.fx().effect("fx/beam", BossFx.SOUL, 15);
                link = f.fx().effect("fx/beam_glow", BossFx.DANGER, 15);
                proxy = f.hitboxes().proxy(base.clone().add(0, 1.2, 0), 2.4, (attacker, damage, damager) -> hurt(this, attacker, damage));
                label = f.fx().label(base.clone().add(0, 3.6, 0), Component.empty(), 1.2f);
            }
        }

        private final List<Beacon> beacons = new ArrayList<>();
        private double total;
        private boolean blasted, warned;

        Beacons(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.BEACONS, escort);
        }

        protected void onBegin() {
            f.play("channel");
            impactAt = DURATION;
            length = DURATION + 60;
            int players = f.groupSize();
            int count = Math.clamp(2 + players / 3 + f.stage() / 4, 2, 5);
            double hp = (220 + 30 * players) * (1 + 0.05 * f.stage()) * 0.75 * f.tuning().beaconHealth();
            double base = f.random().nextDouble() * Math.PI * 2;
            for (int i = 0; i < count; i++) {
                double a = base + i * Math.PI * 2 / count, r = 19 + f.random().nextDouble() * 4;
                double[] c = f.awayFromPillars(f.arena().x() + Math.sin(a) * r, f.arena().z() + Math.cos(a) * r, 2.5);
                c = f.clampToArena(c[0], c[1], 30);
                beacons.add(new Beacon(c[0], c[1], hp));
            }
            total = beacons.size();
            f.shield(0.35);
            f.fx().sound(f.model().socket("heart", "core"), "atk.beacon_charge", 5f, 1f);
            f.broadcast(Component.text("Consuming Beacons! The boss is shielded until every beacon is destroyed.", BossHud.DANGER));
            for (Player p : f.targetable()) f.hint(p, "Consuming Beacons · destroy them together!");
        }

        private boolean hurt(Beacon b, Player attacker, double damage) {
            if (b.down) return false;
            b.hp -= Math.max(3, damage);
            f.credit(attacker);
            Location at = b.proxy.getLocation();
            f.fx().sound(at, "atk.skull_break", 1.5f, 1.2f);
            f.fx().particle(Particle.ELECTRIC_SPARK, at.clone().add(0, 0.8, 0), 8, 0.4, 0.1);
            if (b.hp <= 0) destroy(b, attacker);
            return true;
        }

        private int alive() {
            int n = 0;
            for (Beacon b : beacons) if (!b.down) n++;
            return n;
        }

        private void destroy(Beacon b, Player by) {
            b.down = true;
            Location at = f.floorAt(b.x, b.z).add(0, 1, 0);
            f.fx().sound(at, "atk.nova_burst", 3f, 1.4f);
            f.fx().particle(Particle.EXPLOSION, at, 2, 0.5, 0);
            f.fx().particle(Particle.END_ROD, at, 30, 0.5, 0.2);
            f.fx().particle(Particle.SOUL_FIRE_FLAME, at, 40, 0.5, 0.12);
            f.fx().light(at, 15, 10);
            release(b);
            f.shield(0.35 + 0.65 * (1 - alive() / total));
            if (by != null) f.success(by, "Beacon destroyed · " + alive() + " left");
            if (alive() == 0) {
                f.broadcast(Component.text("All beacons destroyed · feedback!", BossHud.GOLD));
                finish();
                f.stagger();
            }
        }

        private void release(Beacon b) {
            f.fx().remove(b.block);
            f.fx().remove(b.beam);
            f.fx().remove(b.link);
            f.fx().remove(b.ring);
            f.hitboxes().release(b.proxy);
            if (b.label != null) b.label.remove();
        }

        protected void onTick(int t) {
            Location heart = f.model().socket("heart", "core");
            int left = DURATION - t;
            for (Beacon b : beacons) {
                if (b.down) continue;
                Location top = f.floorAt(b.x, b.z).add(0, 2.1, 0);
                if (t % 4 == 0) {
                    BossFx.apply(b.beam, f.fx().beamMatrix("fx/beam", top, top.clone().add(0, 36, 0), 0.45f + 0.1f * (float) Math.sin(t * 0.3)), 4);
                    BossFx.apply(b.link, f.fx().beamMatrix("fx/beam_glow", top, heart, 0.3f), 4);
                }
                if (t % 10 == 0 && b.label != null && b.label.isValid())
                    b.label.text(BossHud.bold(Math.max(0, (int) Math.ceil(b.hp)) + " HP", BossHud.GOLD));
                if (t % 3 == 0) f.fx().particle(Particle.SOUL_FIRE_FLAME, top.clone().add(0, 0.6, 0), 2, 0.3, 0.02);
                if (t % 6 == 0) f.fx().light(top, 13, 8);
                if (t > 20 && t % PULSE == 0) {
                    b.wave = t;
                    b.hit.clear();
                    b.ring = f.fx().flatDecal("circle_line_" + BossFx.circleKey(2), BossFx.DANGER_HOT, b.x, b.z, 0.045f, 0, 2, 2);
                    f.fx().sound(top, "atk.beacon_pulse", 3f, 1f);
                }
                if (b.wave >= 0) wave(b, t);
            }
            if (!warned && left <= 100) {
                warned = true;
                f.broadcast(Component.text("The beacons overload in 5 seconds!", BossHud.DANGER));
                for (Player p : f.targetable()) f.markPlayer(p, "Beacons overloaded · pillars protect you!");
            }
            if (t >= DURATION && !blasted) blast();
            if (blasted && t > DURATION + 20) finish();
        }

        private void wave(Beacon b, int t) {
            double r = 1.5 + (t - b.wave) * WAVE_SPEED;
            if (r > WAVE_MAX) {
                f.fx().remove(b.ring);
                b.ring = null;
                b.wave = -1;
                return;
            }
            if (t % 2 == 0 && b.ring != null) BossFx.apply(b.ring, f.fx().flatMatrix(b.x, b.z, 0.045f, 0, r * 2, r * 2), 2);
            for (Player p : f.targetable()) {
                if (b.hit.contains(p.getUniqueId())) continue;
                double d = Math.hypot(p.getLocation().getX() - b.x, p.getLocation().getZ() - b.z);
                if (Math.abs(d - r) > WAVE_BAND) continue;
                b.hit.add(p.getUniqueId());
                if (p.getLocation().getY() - f.floorY() < 0.55) {
                    f.damage(p, f.damageValue("beacon"), "beacon", true, 0, f.away(f.floorAt(b.x, b.z), p, 0.6, 0.3));
                } else f.success(p, "Jumped over the wave");
            }
        }

        private void blast() {
            blasted = true;
            int remaining = alive();
            if (remaining == 0) return;
            double factor = remaining / total;
            f.fx().sound(f.bossGround().add(0, 4, 0), "atk.beacon_blast", 6f, 1f);
            for (Beacon b : beacons) {
                if (b.down) continue;
                Location at = f.floorAt(b.x, b.z).add(0, 1, 0);
                f.fx().particle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0);
                f.fx().flash(b.x, b.z, 5, BossFx.DANGER_HOT);
                release(b);
                b.down = true;
            }
            for (Player p : f.targetable()) {
                if (BossGeometry.occluded(f.bossX(), f.bossZ(), p.getLocation().getX(), p.getLocation().getZ(), f.pillars())) {
                    f.success(p, "The pillar protected you");
                    continue;
                }
                f.damage(p, f.damageValue("beaconblast") * (0.5 + 0.5 * factor), "beaconblast", true, 60, new Vector(0, 0.5, 0));
            }
            double healed = f.heal(0.03 * factor);
            if (healed > 0) f.broadcast(Component.text("The beacons feed the Ash Colossus (+" + (int) healed + " HP)", BossHud.DANGER));
            f.shield(1.0);
            f.shake(f.bossGround(), 16);
        }

        protected void onCleanup() {
            for (Beacon b : beacons) release(b);
            beacons.clear();
            f.shield(1.0);
            f.model().animator().release(f.tick());
        }
    }

    static final class Retribution extends BossMove {
        static final double RADIUS = 8.5;
        private double cx, cz;

        Retribution(WitherEncounter f, boolean escort) {
            super(f, BossDirector.Attack.RETRIBUTION, escort);
        }

        protected void onBegin() {
            cx = f.bossX();
            cz = f.bossZ();
            impactAt = 24;
            length = animate() ? f.rig().clip("roar").length() : 40;
            if (animate()) f.play("roar");
            mark(f.fx().circle(cx, cz, RADIUS, BossFx.DANGER, impactAt));
            f.fx().sound(f.model().socket("head", "mouth"), "boss.roar_short", 3f, 0.6f);
            f.fx().sound(f.model().socket("heart", "core"), "atk.nova_charge", 3f, 1.2f);
            for (Player p : f.targetable())
                if (Math.hypot(p.getLocation().getX() - cx, p.getLocation().getZ() - cz) <= RADIUS) f.markPlayer(p, "Retribution! Get out of the ring");
        }

        protected void onTick(int t) {
            if (t == impactAt) {
                marks.forEach(BossFx.Mark::remove);
                var circle = new BossGeometry.Circle(cx, cz, RADIUS);
                for (Player p : f.targetable())
                    if (circle.contains(p.getLocation().getX(), p.getLocation().getZ()))
                        f.damage(p, f.damageValue("retribution"), "retribution", true, 0, f.away(f.floorAt(cx, cz), p, 1.4, 0.55));
                f.fx().flash(cx, cz, RADIUS, BossFx.DANGER_HOT);
                f.fx().sound(f.floorAt(cx, cz), "atk.retribution", 4f, 1f);
                f.fx().particle(Particle.EXPLOSION, f.floorAt(cx, cz).add(0, 1, 0), 6, RADIUS * 0.4, 0);
                f.fx().particle(Particle.SOUL_FIRE_FLAME, f.floorAt(cx, cz).add(0, 0.4, 0), 70, RADIUS * 0.45, 0.1, RADIUS * 0.45, 0.08, null);
                f.fx().light(f.floorAt(cx, cz).add(0, 1, 0), 15, 10);
                f.shake(f.floorAt(cx, cz), 12);
            }
        }
    }
}
