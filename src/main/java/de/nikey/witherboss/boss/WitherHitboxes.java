package de.nikey.witherboss.boss;

import net.kyori.adventure.text.Component;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Consumer;

public final class WitherHitboxes implements AutoCloseable {
    public interface ProxyHandler {
        boolean hit(Player attacker, double damage, Entity damager);
    }

    private record Proxy(Slime slime, ProxyHandler handler) {}

    private static final double BASE = 1.04;
    private final Consumer<Entity> tag;
    private final Slime low, high;
    private final Map<UUID, Proxy> proxies = new HashMap<>();

    public WitherHitboxes(Location at, Consumer<Entity> tag) {
        this.tag = tag;
        low = spawn(at, 2.6);
        Slime h = null;
        try {
            h = spawn(at, 3.6);
            h.customName(Component.text("The Ash Colossus"));
            h.setCustomNameVisible(false);
        } catch (RuntimeException e) {
            low.remove();
            throw e;
        }
        high = h;
    }

    private Slime spawn(Location at, double size) {
        return at.getWorld()
                .spawn(
                        bottom(at, size),
                        Slime.class,
                        e -> {
                            tag.accept(e);
                            e.setSize(2);
                            e.setMaximumNoDamageTicks(0);
                            e.setAI(false);
                            e.setGravity(false);
                            e.setInvisible(true);
                            e.setSilent(true);
                            e.setCollidable(false);
                            e.setRemoveWhenFarAway(false);
                            e.setLootTable(null);
                            Objects.requireNonNull(e.getAttribute(Attribute.MAX_HEALTH)).setBaseValue(1024);
                            e.setHealth(1024);
                            var kb = e.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
                            if (kb != null) kb.setBaseValue(1);
                            scale(e, size);
                        });
    }

    private static void scale(Slime e, double size) {
        var attr = e.getAttribute(Attribute.SCALE);
        if (attr != null) attr.setBaseValue(Math.clamp(size / BASE, 0.0625, 16));
    }

    private static Location bottom(Location center, double size) {
        return center.clone().add(0, -size / 2, 0);
    }

    private static void keep(Slime s, Location center, double size) {
        if (!s.isValid()) throw new IllegalStateException("Boss hitbox lost.");
        s.teleport(bottom(center, size));
        s.setNoDamageTicks(0);
        s.setFireTicks(0);
        if (s.getHealth() < 1024) s.setHealth(1024);
        s.setVelocity(new Vector());
    }

    public void update(Location lowCenter, double lowSize, Location highCenter, double highSize) {
        scale(low, lowSize);
        scale(high, highSize);
        keep(low, lowCenter, lowSize);
        keep(high, highCenter, highSize);
        proxies.values().removeIf(p -> !p.slime().isValid());
    }

    public Slime proxy(Location center, double size, ProxyHandler handler) {
        Slime s = spawn(center, size);
        proxies.put(s.getUniqueId(), new Proxy(s, handler));
        return s;
    }

    public void move(Slime s, Location center, double size) {
        if (s != null && s.isValid()) keep(s, center, size);
    }

    public void release(Slime s) {
        if (s == null) return;
        proxies.remove(s.getUniqueId());
        s.remove();
    }

    public boolean body(Entity e) {
        return e.getUniqueId().equals(low.getUniqueId()) || e.getUniqueId().equals(high.getUniqueId());
    }

    public boolean proxy(Entity e) {
        return proxies.containsKey(e.getUniqueId());
    }

    public boolean target(Entity e) {
        return body(e) || proxy(e);
    }

    public boolean hitProxy(Entity e, Player attacker, double damage, Entity damager) {
        Proxy p = proxies.get(e.getUniqueId());
        return p != null && p.handler().hit(attacker, damage, damager);
    }

    public List<LivingEntity> targets() {
        List<LivingEntity> out = new ArrayList<>();
        out.add(high);
        out.add(low);
        for (Proxy p : proxies.values()) if (p.slime().isValid()) out.add(p.slime());
        return out;
    }

    public Slime source() {
        return high;
    }

    public int count() {
        return 2 + proxies.size();
    }

    @Override
    public void close() {
        low.remove();
        high.remove();
        proxies.values().forEach(p -> p.slime().remove());
        proxies.clear();
    }
}
