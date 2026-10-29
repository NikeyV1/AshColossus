package de.nikey.witherboss.boss;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;

import net.kyori.adventure.text.Component;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

public final class BossFx implements AutoCloseable {
    public static final Color DANGER = Color.fromRGB(0xFF2A3A);
    public static final Color DANGER_HOT = Color.fromRGB(0xFF8A3D);
    public static final Color GOLD = Color.fromRGB(0xFFC83D);
    public static final Color SOUL = Color.fromRGB(0x3FE6D2);
    public static final Color WHITE = Color.fromRGB(0xFFFFFF);
    public static final int EFFECT_BUDGET = 120, TEXT_BUDGET = 12;

    private static final Map<String, Double> CIRCLES =
            Map.of("s", 2.4, "m", 4.5, "l", 9.0, "xl", 13.0, "xxl", 20.0);

    private record Pending(int at, Runnable action) {}

    private final BossRig rig;
    private final Location anchor;
    private final Consumer<Entity> tag;
    private final List<Display> displays = new ArrayList<>();
    private final List<TextDisplay> texts = new ArrayList<>();
    private final Map<TextDisplay, Integer> textExpiry = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private final Map<Block, Integer> lights = new HashMap<>();
    private int tick;

    public BossFx(BossRig rig, Location anchor, Consumer<Entity> tag) {
        this.rig = rig;
        this.anchor = anchor.clone();
        this.anchor.setYaw(0);
        this.anchor.setPitch(0);
        this.tag = tag;
    }

    public final class Mark {
        private final List<ItemDisplay> parts = new ArrayList<>();
        private boolean removed;

        public void remove() {
            if (removed) return;
            removed = true;
            for (ItemDisplay d : parts) BossFx.this.remove(d);
            parts.clear();
        }

        public boolean removed() {
            return removed;
        }

        public List<ItemDisplay> parts() {
            return parts;
        }

        public void color(Color c) {
            for (ItemDisplay d : parts) tint(d, c);
        }
    }

    public Location anchor() {
        return anchor.clone();
    }

    public int active() {
        displays.removeIf(d -> !d.isValid());
        return displays.size();
    }

    public void tick(int now) {
        tick = now;
        if (!pending.isEmpty()) {
            List<Pending> due = new ArrayList<>();
            pending.removeIf(p -> {
                if (p.at() <= now) {
                    due.add(p);
                    return true;
                }
                return false;
            });
            for (Pending p : due) p.action().run();
        }
        textExpiry.entrySet().removeIf(e -> {
            if (e.getValue() > now) return false;
            e.getKey().remove();
            texts.remove(e.getKey());
            return true;
        });
        if (now % 5 == 0)
            lights.entrySet().removeIf(e -> {
                if (e.getValue() > now) return false;
                unlight(e.getKey());
                return true;
            });
    }

    public void later(int delay, Runnable action) {
        pending.add(new Pending(tick + Math.max(1, delay), action));
    }

    public ItemDisplay item(NamespacedKey model, Color tint, int light) {
        if (active() >= EFFECT_BUDGET) return null;
        ItemDisplay display =
                anchor.getWorld()
                        .spawn(
                                anchor,
                                ItemDisplay.class,
                                d -> {
                                    tag.accept(d);
                                    ItemStack stack = WitherRewards.model(model);
                                    if (tint != null)
                                        stack.setData(
                                                DataComponentTypes.CUSTOM_MODEL_DATA,
                                                CustomModelData.customModelData().addColor(tint).build());
                                    d.setItemStack(stack);
                                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                                    d.setBrightness(new Display.Brightness(light, light));
                                    d.setViewRange(2.5f);
                                    d.setDisplayWidth(96);
                                    d.setDisplayHeight(64);
                                    d.setShadowRadius(0);
                                    d.setTransformationMatrix(new Matrix4f().scale(0.0001f));
                                });
        displays.add(display);
        return display;
    }

    public BlockDisplay block(BlockData data, int light) {
        if (active() >= EFFECT_BUDGET) return null;
        BlockDisplay display =
                anchor.getWorld()
                        .spawn(
                                anchor,
                                BlockDisplay.class,
                                d -> {
                                    tag.accept(d);
                                    d.setBlock(data);
                                    d.setBrightness(new Display.Brightness(light, light));
                                    d.setViewRange(2.5f);
                                    d.setDisplayWidth(96);
                                    d.setDisplayHeight(64);
                                    d.setShadowRadius(0);
                                    d.setTransformationMatrix(new Matrix4f().scale(0.0001f));
                                });
        displays.add(display);
        return display;
    }

    public Matrix4f blockMatrix(Location center, Quaternionf rotation, Vector3f size) {
        return new Matrix4f()
                .translate((float) (center.getX() - anchor.getX()), (float) (center.getY() - anchor.getY()), (float) (center.getZ() - anchor.getZ()))
                .rotate(rotation)
                .scale(size)
                .translate(-0.5f, -0.5f, -0.5f);
    }

    public BlockDisplay block(Material type, int light) {
        return block(type.createBlockData(), light);
    }

    public void tint(ItemDisplay d, Color c) {
        if (d == null || !d.isValid()) return;
        ItemStack stack = d.getItemStack();
        stack.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addColor(c).build());
        d.setItemStack(stack);
    }

    public void remove(Display d) {
        if (d != null && d.isValid()) d.remove();
        displays.remove(d);
    }

    public static void apply(Display d, Matrix4f m, int interpolation) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(Math.max(0, interpolation));
        d.setTransformationMatrix(m);
    }

    public Matrix4f flatMatrix(double x, double z, float y, double yaw, double sx, double sz) {
        return flat(x, z, y, yaw, sx, sz);
    }

    private Matrix4f flat(double x, double z, float y, double yaw, double sx, double sz) {
        return new Matrix4f()
                .translate((float) (x - anchor.getX()), (float) (anchor.getY() - anchor.getBlockY()) + y, (float) (z - anchor.getZ()))
                .rotateY((float) yaw)
                .scale((float) Math.max(sx, 0.001), 1, (float) Math.max(sz, 0.001))
                .rotateY((float) Math.PI);
    }

    private NamespacedKey decal(String name) {
        rig.decal(name);
        return new NamespacedKey("wither_boss", "decal/" + name);
    }

    public ItemDisplay flatDecal(String name, Color color, double x, double z, float y, double yaw, double sx, double sz) {
        ItemDisplay d = item(decal(name), rig.decal(name).tinted() ? color : null, 15);
        apply(d, flat(x, z, y, yaw, sx, sz), 0);
        return d;
    }

    private void grow(ItemDisplay d, double x, double z, float y, double yaw, double fromX, double fromZ, double toX, double toZ, int ticks) {
        if (d == null) return;
        apply(d, flat(x, z, y, yaw, fromX, fromZ), 0);
        later(1, () -> apply(d, flat(x, z, y, yaw, toX, toZ), Math.max(1, ticks - 1)));
    }

    public static String circleKey(double radius) {
        String best = "m";
        double score = Double.MAX_VALUE;
        for (var e : CIRCLES.entrySet()) {
            double s = Math.abs(Math.log(radius / e.getValue()));
            if (s < score) {
                score = s;
                best = e.getKey();
            }
        }
        return best;
    }

    public Mark circle(double x, double z, double r, Color color, int growTicks) {
        Mark m = new Mark();
        ItemDisplay line = flatDecal("circle_line_" + circleKey(r), color, x, z, 0.035f, 0, r * 2, r * 2);
        ItemDisplay fill = flatDecal("circle_fill", color, x, z, 0.025f, 0, 0.01, 0.01);
        if (growTicks > 0) grow(fill, x, z, 0.025f, 0, 0.05, 0.05, r * 2, r * 2, growTicks);
        else apply(fill, flat(x, z, 0.025f, 0, r * 2, r * 2), 0);
        add(m, line, fill);
        return m;
    }

    public Mark donut(String key, double x, double z, double outer, Color color) {
        Mark m = new Mark();
        add(m, flatDecal("donut_" + key + "_line", color, x, z, 0.035f, 0, outer * 2, outer * 2),
                flatDecal("donut_" + key + "_fill", color, x, z, 0.025f, 0, outer * 2, outer * 2));
        return m;
    }

    public Mark sector(double x, double z, double yaw, double r, Color color, int growTicks) {
        Mark m = new Mark();
        ItemDisplay line = flatDecal("cone_line", color, x, z, 0.035f, yaw, r * 2, r);
        ItemDisplay fill = flatDecal("cone_fill", color, x, z, 0.025f, yaw, 0.01, 0.01);
        if (growTicks > 0) grow(fill, x, z, 0.025f, yaw, 0.1, 0.05, r * 2, r, growTicks);
        else apply(fill, flat(x, z, 0.025f, yaw, r * 2, r), 0);
        add(m, line, fill);
        return m;
    }

    public Mark lane(double x, double z, double yaw, double length, double width, Color color, int growTicks) {
        Mark m = new Mark();
        ItemDisplay line = flatDecal("lane_line", color, x, z, 0.035f, yaw, width, length);
        ItemDisplay fill = flatDecal("lane_fill", color, x, z, 0.025f, yaw, width, 0.01);
        if (growTicks > 0) grow(fill, x, z, 0.025f, yaw, width, 0.05, width, length, growTicks);
        else apply(fill, flat(x, z, 0.025f, yaw, width, length), 0);
        add(m, line, fill);
        return m;
    }

    public void moveLane(Mark m, double x, double z, double yaw, double length, double width, int interpolation) {
        if (m == null || m.removed() || m.parts.size() < 2) return;
        apply(m.parts.get(0), flat(x, z, 0.035f, yaw, width, length), interpolation);
        apply(m.parts.get(1), flat(x, z, 0.025f, yaw, width, length), interpolation);
    }

    public void moveCircle(Mark m, double x, double z, double r, int interpolation) {
        if (m == null || m.removed() || m.parts.size() < 2) return;
        apply(m.parts.get(0), flat(x, z, 0.035f, 0, r * 2, r * 2), interpolation);
        apply(m.parts.get(1), flat(x, z, 0.025f, 0, r * 2, r * 2), interpolation);
    }

    public void flash(double x, double z, double r, Color color) {
        ItemDisplay d = flatDecal("flash_disk", color, x, z, 0.045f, 0, r * 2, r * 2);
        if (d == null) return;
        later(2, () -> apply(d, flat(x, z, 0.045f, 0, r * 2.15, r * 2.15), 5));
        later(7, () -> remove(d));
    }

    public void flashLane(double x, double z, double yaw, double length, double width, Color color) {
        ItemDisplay d = flatDecal("lane_fill", color, x, z, 0.045f, yaw, width * 1.1, length);
        if (d == null) return;
        later(6, () -> remove(d));
    }

    public void flashSector(double x, double z, double yaw, double r, Color color) {
        ItemDisplay d = flatDecal("cone_fill", color, x, z, 0.045f, yaw, r * 2, r);
        ItemDisplay d2 = flatDecal("cone_fill", color, x, z, 0.05f, yaw, r * 2, r);
        later(6, () -> {
            remove(d);
            remove(d2);
        });
    }

    private void add(Mark m, ItemDisplay... parts) {
        for (ItemDisplay d : parts) if (d != null) m.parts.add(d);
    }

    public ItemDisplay icon(String name, Color tint, Location at, float scale) {
        ItemDisplay d = item(decal(name), tint, 15);
        if (d == null) return null;
        d.setBillboard(Display.Billboard.CENTER);
        d.setTeleportDuration(2);
        d.teleport(at);
        apply(d, new Matrix4f().scale(scale).rotateY((float) Math.PI), 0);
        return d;
    }

    public static void follow(Display d, Location at) {
        if (d != null && d.isValid()) d.teleport(at);
    }

    public ItemDisplay effect(String name, Color tint, int light) {
        rig.effect(name);
        return item(new NamespacedKey("wither_boss", name), tint, light);
    }

    public Matrix4f effectMatrix(String name, Location at, Quaternionf rotation, Vector3f scale) {
        BossRig.EffectMeta meta = rig.effect(name);
        return new Matrix4f()
                .translate((float) (at.getX() - anchor.getX()), (float) (at.getY() - anchor.getY()), (float) (at.getZ() - anchor.getZ()))
                .rotate(rotation)
                .scale(scale)
                .translate(meta.center())
                .scale(meta.scale())
                .rotateY((float) Math.PI);
    }

    public static Quaternionf aim(double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-6) return new Quaternionf();
        return new Quaternionf().rotateY((float) Math.atan2(dx, dz)).rotateX((float) -Math.asin(Math.clamp(dy / len, -1, 1)));
    }

    public Matrix4f beamMatrix(String name, Location from, Location to, float width) {
        double dx = to.getX() - from.getX(), dy = to.getY() - from.getY(), dz = to.getZ() - from.getZ();
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        BossRig.EffectMeta meta = rig.effect(name);
        float unit = meta.max().z - meta.min().z;
        return effectMatrix(name, from, aim(dx, dy, dz), new Vector3f(width, width, (float) Math.max(0.01, len / unit)));
    }

    public TextDisplay text(Location at, Component text, int life, float scale, boolean rise) {
        if (texts.size() >= TEXT_BUDGET) {
            TextDisplay oldest = texts.removeFirst();
            textExpiry.remove(oldest);
            oldest.remove();
        }
        TextDisplay t =
                at.getWorld()
                        .spawn(
                                at,
                                TextDisplay.class,
                                d -> {
                                    tag.accept(d);
                                    d.text(text);
                                    d.setBillboard(Display.Billboard.CENTER);
                                    d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                                    d.setShadowed(true);
                                    d.setBrightness(new Display.Brightness(15, 15));
                                    d.setViewRange(1.5f);
                                    d.setTransformation(
                                            new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
                                });
        texts.add(t);
        textExpiry.put(t, tick + life);
        if (rise)
            later(1, () -> {
                if (!t.isValid()) return;
                t.setInterpolationDelay(0);
                t.setInterpolationDuration(life - 2);
                t.setTransformation(new Transformation(new Vector3f(0, 1.1f, 0), new Quaternionf(), new Vector3f(scale * 0.8f, scale * 0.8f, scale * 0.8f), new Quaternionf()));
            });
        return t;
    }

    public TextDisplay label(Location at, Component text, float scale) {
        return at.getWorld()
                .spawn(
                        at,
                        TextDisplay.class,
                        d -> {
                            tag.accept(d);
                            d.text(text);
                            d.setBillboard(Display.Billboard.CENTER);
                            d.setBackgroundColor(Color.fromARGB(110, 0, 0, 0));
                            d.setBrightness(new Display.Brightness(15, 15));
                            d.setViewRange(0.7f);
                            d.setLineWidth(220);
                            d.setTransformation(
                                    new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
                        });
    }

    public void light(Location at, int level, int duration) {
        Block b = at.getBlock();
        if (!b.getType().isAir() && b.getType() != Material.LIGHT) return;
        if (b.getType() == Material.LIGHT && !lights.containsKey(b)) return;
        if (b.getType() != Material.LIGHT || ((Levelled) b.getBlockData()).getLevel() < level) {
            Levelled data = (Levelled) Material.LIGHT.createBlockData();
            data.setLevel(Math.clamp(level, 0, 15));
            b.setBlockData(data, false);
        }
        lights.merge(b, tick + duration, Math::max);
    }

    private static void unlight(Block b) {
        if (b.getType() == Material.LIGHT) b.setType(Material.AIR, false);
    }

    public int lights() {
        return lights.size();
    }

    public void particle(Particle p, Location at, int count, double spread, double speed) {
        at.getWorld().spawnParticle(p, at, count, spread, spread, spread, speed, null, true);
    }

    public <T> void particle(Particle p, Location at, int count, double sx, double sy, double sz, double speed, T data) {
        at.getWorld().spawnParticle(p, at, count, sx, sy, sz, speed, data, true);
    }

    public void sound(Location at, String event, float volume, float pitch) {
        at.getWorld().playSound(at, "wither_boss:" + event, SoundCategory.HOSTILE, volume, pitch);
    }

    public void clearMarks() {
        for (Display d : List.copyOf(displays)) remove(d);
        pending.clear();
    }

    @Override
    public void close() {
        clearMarks();
        texts.forEach(Entity::remove);
        texts.clear();
        textExpiry.clear();
        lights.keySet().forEach(BossFx::unlight);
        lights.clear();
    }
}
