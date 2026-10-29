package de.nikey.witherboss.boss;

import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.util.Vector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Consumer;

public final class BossModel implements AutoCloseable {
    public static final float HOVER = 1.0f;
    private final BossRig rig;
    private final BossAnimator animator;
    private final Location anchor;
    private final ItemDisplay[] displays;
    private final Matrix4f[] last;
    private final int[] flashUntil;
    private final float[][] additive;
    private final boolean[] vanished;
    private Matrix4f[] world;
    private double x, z, facing, extraYaw;
    private float lift;
    private int baseLight = 11, heartLight = 15;
    private boolean heartGlow;

    public BossModel(BossRig rig, Location anchor, Consumer<ItemDisplay> tag) {
        this.rig = rig;
        this.anchor = anchor.clone();
        this.anchor.setYaw(0);
        this.anchor.setPitch(0);
        animator = new BossAnimator(rig);
        int n = rig.bones().size();
        displays = new ItemDisplay[n];
        last = new Matrix4f[n];
        flashUntil = new int[n];
        additive = new float[n][3];
        vanished = new boolean[n];
        x = anchor.getX();
        z = anchor.getZ();
        try {
            for (BossRig.Bone b : rig.bones()) {
                if (b.item() == null) continue;
                displays[b.index()] =
                        anchor.getWorld()
                                .spawn(
                                        this.anchor,
                                        ItemDisplay.class,
                                        d -> {
                                            tag.accept(d);
                                            d.setItemStack(WitherRewards.model(b.item()));
                                            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                                            d.setInterpolationDuration(2);
                                            d.setViewRange(2.5f);
                                            d.setDisplayWidth(96);
                                            d.setDisplayHeight(64);
                                            d.setShadowRadius(0);
                                            d.setBrightness(new Display.Brightness(baseLight, baseLight));
                                            d.setTransformationMatrix(new Matrix4f().scale(0.0001f));
                                        });
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    public BossAnimator animator() {
        return animator;
    }

    public BossRig rig() {
        return rig;
    }

    public void place(double x, double z) {
        this.x = x;
        this.z = z;
    }

    public double x() {
        return x;
    }

    public double z() {
        return z;
    }

    public void facing(double radians) {
        facing = radians;
    }

    public double facing() {
        return facing;
    }

    public double visualFacing() {
        return facing + extraYaw;
    }

    public void extraYaw(double radians) {
        extraYaw = radians;
    }

    public void lift(float blocks) {
        lift = blocks;
    }

    public Vector forward() {
        return new Vector(Math.sin(facing), 0, Math.cos(facing));
    }

    public static double facingTowards(double dx, double dz) {
        return Math.atan2(dx, dz);
    }

    public void additive(String bone, float rx, float ry, float rz) {
        int i = rig.index(bone);
        if (i >= 0) {
            additive[i][0] = rx;
            additive[i][1] = ry;
            additive[i][2] = rz;
        }
    }

    public void clearAdditive() {
        for (float[] a : additive) Arrays.fill(a, 0);
    }

    public boolean vanishNext() {
        for (int i = vanished.length - 1; i >= 0; i--)
            if (!vanished[i] && rig.bones().get(i).item() != null) {
                vanished[i] = true;
                return true;
            }
        return false;
    }

    public String lastVanished() {
        for (int i = 0; i < vanished.length; i++)
            if (vanished[i]) return rig.bones().get(i).name();
        return null;
    }

    public void flash(String bone, int until) {
        int i = rig.index(bone);
        if (i >= 0) flashUntil[i] = until;
    }

    public void flashAll(int until) {
        Arrays.fill(flashUntil, until);
    }

    public void light(int base, int heart, boolean glow) {
        baseLight = Math.clamp(base, 0, 15);
        heartLight = Math.clamp(heart, 0, 15);
        heartGlow = glow;
    }

    public Matrix4f root() {
        return new Matrix4f()
                .translate((float) (x - anchor.getX()), HOVER + lift, (float) (z - anchor.getZ()))
                .rotateY((float) (facing + extraYaw));
    }

    public Matrix4f[] compute(int tick) {
        BossRig.Pose pose = animator.evaluate(tick);
        for (int i = 0; i < additive.length; i++)
            for (int j = 0; j < 3; j++) pose.rot[i][j] += additive[i][j];
        world = rig.world(pose, root());
        return world;
    }

    public void render(int tick, boolean send) {
        compute(tick);
        if (!send) return;
        int heart = rig.index("heart");
        for (BossRig.Bone b : rig.bones()) {
            ItemDisplay d = displays[b.index()];
            if (b.item() == null) continue;
            if (d == null || !d.isValid()) throw new IllegalStateException("Boss model lost.");
            Matrix4f m = vanished[b.index()]
                    ? new Matrix4f(world[b.index()]).translate(b.center()).scale(0.0001f)
                    : BossRig.display(world[b.index()], b.center(), b.scale());
            if (last[b.index()] == null || !last[b.index()].equals(m, 1e-4f)) {
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(vanished[b.index()] ? 8 : 2);
                d.setTransformationMatrix(m);
                last[b.index()] = m;
            }
            int light = tick < flashUntil[b.index()] ? 15 : b.index() == heart ? heartLight : baseLight;
            Display.Brightness want = new Display.Brightness(light, light);
            if (!want.equals(d.getBrightness())) d.setBrightness(want);
            boolean glow = b.index() == heart && heartGlow;
            if (d.isGlowing() != glow) {
                d.setGlowing(glow);
                if (glow) d.setGlowColorOverride(Color.fromRGB(0xFFD24A));
            }
        }
    }

    public Location socket(String bone, String socket) {
        if (world == null) throw new IllegalStateException("The boss model has not been computed yet.");
        BossRig.Bone b = rig.bone(bone);
        Vector3f local = b.sockets().getOrDefault(socket, new Vector3f());
        Vector3f p = world[b.index()].transformPosition(new Vector3f(local));
        return anchor.clone().add(p.x, p.y, p.z);
    }

    public Location bonePosition(String bone) {
        BossRig.Bone b = rig.bone(bone);
        Vector3f p = world[b.index()].transformPosition(new Vector3f());
        return anchor.clone().add(p.x, p.y, p.z);
    }

    public Location ground() {
        return new Location(anchor.getWorld(), x, anchor.getY(), z);
    }

    public int displays() {
        return displays.length;
    }

    public List<ItemDisplay> entities() {
        return Arrays.stream(displays).filter(Objects::nonNull).toList();
    }

    public void hide() {
        for (ItemDisplay d : displays)
            if (d != null && d.isValid()) d.setTransformationMatrix(new Matrix4f().scale(0.0001f));
        Arrays.fill(last, null);
    }

    @Override
    public void close() {
        for (ItemDisplay d : displays) if (d != null && d.isValid()) d.remove();
        Arrays.fill(displays, null);
    }
}
