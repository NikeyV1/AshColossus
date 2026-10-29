package de.nikey.witherboss.boss;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkeleton;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.*;

public final class BossMinions {

    private final WitherEncounter f;
    private final List<WitherSkeleton> alive = new ArrayList<>();
    private final Set<UUID> brutes = new HashSet<>();
    private final Map<UUID, Integer> lastStrike = new HashMap<>();
    private int killed;

    BossMinions(WitherEncounter f) {
        this.f = f;
    }

    public int count() {
        alive.removeIf(m -> !m.isValid() || m.isDead());
        return alive.size();
    }

    public int killed() {
        return killed;
    }

    public boolean owns(Entity e) {
        return e instanceof WitherSkeleton w && alive.contains(w);
    }

    public boolean brute(Entity e) {
        return brutes.contains(e.getUniqueId());
    }

    public List<WitherSkeleton> list() {
        return List.copyOf(alive);
    }

    WitherSkeleton spawn(Location at, boolean brute) {
        if (count() >= f.tuning().minionCap()) return null;
        int players = Math.max(1, f.state().members().size());
        double health = Math.clamp(f.tuning().minionBaseHealth(brute) * (1 + 0.12 * Math.max(0, players - 4)) * (1 + 0.05 * f.stage()) * f.tuning().minionHealth(), 1, 2048);
        WitherSkeleton m = at.getWorld().spawn(at, WitherSkeleton.class, e -> {
            f.tag(e);
            e.setRemoveWhenFarAway(false);
            e.setCanPickupItems(false);
            e.setLootTable(null);
            e.setSilent(false);
            e.setPersistent(false);
            e.setGlowing(true);
            var max = e.getAttribute(Attribute.MAX_HEALTH);
            if (max != null) max.setBaseValue(health);
            e.setHealth(health);
            var speed = e.getAttribute(Attribute.MOVEMENT_SPEED);
            if (speed != null) speed.setBaseValue(brute ? 0.25 : 0.31);
            var follow = e.getAttribute(Attribute.FOLLOW_RANGE);
            if (follow != null) follow.setBaseValue(70);
            var kb = e.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(brute ? 0.8 : 0.2);
            var scale = e.getAttribute(Attribute.SCALE);
            if (scale != null) scale.setBaseValue(brute ? 1.45 : 1.0);
            EntityEquipment eq = e.getEquipment();
            if (eq != null) {
                eq.setItemInMainHand(new ItemStack(brute ? Material.NETHERITE_AXE : Material.IRON_SWORD));
                eq.setHelmet(new ItemStack(brute ? Material.NETHERITE_HELMET : Material.CHAINMAIL_HELMET));
                eq.setItemInMainHandDropChance(0);
                eq.setHelmetDropChance(0);
            }
            e.customName(Component.text(brute ? "Ashbreaker" : "Ash Sentinel", brute ? NamedTextColor.GOLD : NamedTextColor.RED));
            e.setCustomNameVisible(brute);
            e.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 30, 4, false, false));
        });
        alive.add(m);
        if (brute) brutes.add(m.getUniqueId());
        f.fx().particle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 1, 0), 40, 0.5, 0.06);
        f.fx().particle(Particle.ASH, at.clone().add(0, 1, 0), 30, 0.6, 0.02);
        f.fx().light(at.clone().add(0, 1, 0), 11, 20);
        return m;
    }

    void tick(int now) {
        if (alive.isEmpty()) return;
        Iterator<WitherSkeleton> it = alive.iterator();
        while (it.hasNext()) {
            WitherSkeleton m = it.next();
            if (!m.isValid() || m.isDead()) {
                it.remove();
                brutes.remove(m.getUniqueId());
                continue;
            }
            if (m.getLocation().getY() < f.floorY() - 4 || !f.arena().leashed(m.getLocation())) {
                Location back = f.floorAt(f.arena().x() + (f.random().nextDouble() - 0.5) * 16, f.arena().z() + (f.random().nextDouble() - 0.5) * 16).add(0, 1, 0);
                m.teleport(back);
            }
            if (now % 10 == 0) retarget(m);
            if (now % 4 == 0) f.fx().particle(Particle.SOUL, m.getLocation().add(0, 1.1, 0), 1, 0.25, 0.01);
        }
    }

    private void retarget(WitherSkeleton m) {
        if (!(m instanceof Mob mob)) return;
        LivingEntity current = mob.getTarget();
        if (current instanceof Player p && f.targetable().contains(p) && f.random().nextInt(5) != 0) return;
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : f.targetable()) {
            double d = p.getLocation().distanceSquared(m.getLocation()) + countTargeting(p) * 36.0;
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        try {
            mob.setTarget(best);
        } catch (RuntimeException ignored) {
        }
    }

    private int countTargeting(Player p) {
        int n = 0;
        for (WitherSkeleton m : alive) if (m instanceof Mob mob && p.equals(mob.getTarget())) n++;
        return n;
    }

    boolean strike(LivingEntity attacker, Player victim, int now) {
        if (!owns(attacker) || now - lastStrike.getOrDefault(attacker.getUniqueId(), -100) < 14) return false;
        lastStrike.put(attacker.getUniqueId(), now);
        double raw = f.damageValue("minion") * f.tuning().minionDamage() * (brute(attacker) ? 1.6 : 1.0);
        var knock = f.away(attacker.getLocation(), victim, brute(attacker) ? 0.9 : 0.45, 0.28);
        f.damage(victim, raw, "minion", true, brute(attacker) ? 40 : 0, knock);
        return true;
    }

    void died(Entity e, Player killer) {
        killed++;
        alive.removeIf(m -> m.getUniqueId().equals(e.getUniqueId()));
        brutes.remove(e.getUniqueId());
        Location at = e.getLocation().add(0, 1, 0);
        f.fx().particle(Particle.ASH, at, 24, 0.5, 0.02);
        f.fx().particle(Particle.SOUL, at, 10, 0.4, 0.03);
        if (killer != null && f.state().member(killer.getUniqueId()) != null) f.credit(killer);
    }

    void stun(Entity e) {
        if (e instanceof LivingEntity le && owns(e)) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 3, false, true));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 50, 1, false, false));
        }
    }

    public void clear(boolean effects) {
        for (WitherSkeleton m : List.copyOf(alive)) {
            if (effects && m.isValid()) {
                f.fx().particle(Particle.ASH, m.getLocation().add(0, 1, 0), 20, 0.4, 0.02);
                f.fx().particle(Particle.SOUL, m.getLocation().add(0, 1, 0), 6, 0.3, 0.03);
            }
            if (m.isValid()) m.remove();
        }
        alive.clear();
        brutes.clear();
        lastStrike.clear();
    }
}
