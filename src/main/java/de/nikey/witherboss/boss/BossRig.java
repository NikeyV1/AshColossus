package de.nikey.witherboss.boss;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.bukkit.NamespacedKey;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class BossRig {
    public enum Ease {
        LINEAR,
        SMOOTH,
        IN,
        OUT,
        SNAP;

        float apply(float f) {
            return switch (this) {
                case LINEAR -> f;
                case IN -> f * f;
                case OUT -> 1 - (1 - f) * (1 - f);
                case SNAP -> f >= 1 ? 1 : 0;
                case SMOOTH -> f * f * (3 - 2 * f);
            };
        }
    }

    public record Bone(
            int index,
            String name,
            int parent,
            Vector3f pivot,
            Vector3f center,
            float scale,
            NamespacedKey item,
            Map<String, Vector3f> sockets) {}

    public record Key(float t, float[] value, Ease ease) {}

    public static final class Channel {
        Key[] rot, pos, scale;
    }

    public record Clip(
            String name,
            int length,
            boolean loop,
            boolean hold,
            Map<String, Integer> markers,
            Channel[] channels) {
        public int marker(String name, int fallback) {
            return markers.getOrDefault(name, fallback);
        }
    }

    public record EffectMeta(Vector3f center, float scale, Vector3f min, Vector3f max) {}

    public record DecalMeta(boolean anchored, boolean billboard, boolean tinted) {}

    private final List<Bone> bones = new ArrayList<>();
    private final Map<String, Bone> byName = new HashMap<>();
    private final Map<String, Clip> clips = new HashMap<>();
    private final Map<String, EffectMeta> effects = new HashMap<>();
    private final Map<String, DecalMeta> decals = new HashMap<>();
    private final Set<String> sounds = new HashSet<>();

    public static final List<String> BOSS_CLIPS = List.of("idle", "idle_hammer", "idle_core", "intro", "death");

    public static BossRig load(InputStream rig, InputStream animations) throws Exception {
        return load(rig, animations, BOSS_CLIPS);
    }

    public static BossRig load(InputStream rig, InputStream animations, List<String> required) throws Exception {
        try (var a = new InputStreamReader(rig, StandardCharsets.UTF_8);
                var b = new InputStreamReader(animations, StandardCharsets.UTF_8)) {
            return new BossRig(JsonParser.parseReader(a).getAsJsonObject(), JsonParser.parseReader(b).getAsJsonObject(), required);
        }
    }

    BossRig(JsonObject rig, JsonObject animations) {
        this(rig, animations, BOSS_CLIPS);
    }

    BossRig(JsonObject rig, JsonObject animations, List<String> required) {
        for (JsonElement e : rig.getAsJsonArray("bones")) {
            JsonObject o = e.getAsJsonObject();
            String name = o.get("name").getAsString();
            String parentName = o.get("parent").getAsString();
            int parent = parentName.equals("root") ? -1 : index(parentName);
            if (!parentName.equals("root") && parent < 0)
                throw new IllegalArgumentException("Rig parents missing: " + name);
            Map<String, Vector3f> sockets = new HashMap<>();
            for (var s : o.getAsJsonObject("sockets").entrySet())
                sockets.put(s.getKey(), vec(s.getValue().getAsJsonArray()));
            String itemName = o.get("item").getAsString();
            String[] item = itemName.isEmpty() ? null : itemName.split(":", 2);
            Bone bone =
                    new Bone(
                            bones.size(),
                            name,
                            parent,
                            vec(o.getAsJsonArray("pivot")),
                            vec(o.getAsJsonArray("center")),
                            o.get("scale").getAsFloat(),
                            item == null ? null : new NamespacedKey(item[0], item[1]),
                            Map.copyOf(sockets));
            bones.add(bone);
            byName.put(name, bone);
        }
        if (bones.isEmpty() || bones.size() > 48) throw new IllegalArgumentException("Invalid boss rig.");
        for (var e : rig.getAsJsonObject("effects").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            effects.put(
                    e.getKey(),
                    new EffectMeta(
                            vec(o.getAsJsonArray("center")),
                            o.get("scale").getAsFloat(),
                            vec(o.getAsJsonArray("min")),
                            vec(o.getAsJsonArray("max"))));
        }
        for (var e : rig.getAsJsonObject("decals").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            decals.put(
                    e.getKey(),
                    new DecalMeta(
                            o.get("anchored").getAsBoolean(),
                            o.get("billboard").getAsBoolean(),
                            o.get("tinted").getAsBoolean()));
        }
        for (JsonElement s : rig.getAsJsonArray("sounds")) sounds.add(s.getAsString());
        for (var e : animations.getAsJsonObject("clips").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            Channel[] channels = new Channel[bones.size()];
            for (var b : o.getAsJsonObject("bones").entrySet()) {
                int i = index(b.getKey());
                if (i < 0) continue;
                JsonObject ch = b.getValue().getAsJsonObject();
                Channel c = new Channel();
                c.rot = keys(ch.getAsJsonArray("rot"), 3);
                c.pos = keys(ch.getAsJsonArray("pos"), 3);
                c.scale = keys(ch.getAsJsonArray("scale"), 1);
                channels[i] = c;
            }
            Map<String, Integer> markers = new HashMap<>();
            for (var m : o.getAsJsonObject("markers").entrySet())
                markers.put(m.getKey(), m.getValue().getAsInt());
            clips.put(
                    e.getKey(),
                    new Clip(
                            e.getKey(),
                            Math.max(1, o.get("length").getAsInt()),
                            o.get("loop").getAsBoolean(),
                            o.get("hold").getAsBoolean(),
                            Map.copyOf(markers),
                            channels));
        }
        for (String clip : required)
            if (!clips.containsKey(clip)) throw new IllegalArgumentException("Animation missing: " + clip);
    }

    private static Key[] keys(JsonArray array, int width) {
        if (array == null || array.isEmpty()) return null;
        Key[] out = new Key[array.size()];
        for (int i = 0; i < out.length; i++) {
            JsonArray k = array.get(i).getAsJsonArray();
            float[] v = new float[width];
            for (int j = 0; j < width; j++) v[j] = k.get(1 + j).getAsFloat();
            out[i] = new Key(k.get(0).getAsFloat(), v, Ease.valueOf(k.get(1 + width).getAsString().toUpperCase(Locale.ROOT)));
        }
        return out;
    }

    private static Vector3f vec(JsonArray a) {
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }

    public int index(String name) {
        Bone b = byName.get(name);
        return b == null ? -1 : b.index();
    }

    public List<Bone> bones() {
        return Collections.unmodifiableList(bones);
    }

    public Bone bone(String name) {
        Bone b = byName.get(name);
        if (b == null) throw new IllegalArgumentException("Unknown bone: " + name);
        return b;
    }

    public Clip clip(String name) {
        Clip c = clips.get(name);
        if (c == null) throw new IllegalArgumentException("Unknown animation: " + name);
        return c;
    }

    public boolean hasClip(String name) {
        return clips.containsKey(name);
    }

    public Set<String> clipNames() {
        return Collections.unmodifiableSet(clips.keySet());
    }

    public EffectMeta effect(String name) {
        EffectMeta e = effects.get(name);
        if (e == null) throw new IllegalArgumentException("Unknown effect model: " + name);
        return e;
    }

    public DecalMeta decal(String name) {
        DecalMeta d = decals.get(name);
        if (d == null) throw new IllegalArgumentException("Unknown ground decal: " + name);
        return d;
    }

    public boolean hasSound(String event) {
        return sounds.contains(event);
    }

    static float[] sample(Key[] keys, float t, float[] out) {
        if (keys == null) return null;
        if (t <= keys[0].t()) return copy(keys[0].value(), out);
        for (int i = 0; i + 1 < keys.length; i++) {
            Key a = keys[i], b = keys[i + 1];
            if (t <= b.t()) {
                float f = b.t() == a.t() ? 1 : (t - a.t()) / (b.t() - a.t());
                f = b.ease().apply(Math.clamp(f, 0, 1));
                for (int j = 0; j < out.length; j++) out[j] = a.value()[j] + (b.value()[j] - a.value()[j]) * f;
                return out;
            }
        }
        return copy(keys[keys.length - 1].value(), out);
    }

    private static float[] copy(float[] v, float[] out) {
        System.arraycopy(v, 0, out, 0, out.length);
        return out;
    }

    public static final class Pose {
        public final float[][] rot, pos;
        public final float[] scale;

        public Pose(int bones) {
            rot = new float[bones][3];
            pos = new float[bones][3];
            scale = new float[bones];
            Arrays.fill(scale, 1);
        }

        public void set(Pose other) {
            for (int i = 0; i < scale.length; i++) {
                System.arraycopy(other.rot[i], 0, rot[i], 0, 3);
                System.arraycopy(other.pos[i], 0, pos[i], 0, 3);
                scale[i] = other.scale[i];
            }
        }

        public void blend(Pose other, float w) {
            for (int i = 0; i < scale.length; i++) {
                for (int j = 0; j < 3; j++) {
                    rot[i][j] += (other.rot[i][j] - rot[i][j]) * w;
                    pos[i][j] += (other.pos[i][j] - pos[i][j]) * w;
                }
                scale[i] += (other.scale[i] - scale[i]) * w;
            }
        }
    }

    public void apply(Clip clip, float t, Pose pose, float weight) {
        float[] tmp3 = new float[3], tmp1 = new float[1];
        for (int i = 0; i < clip.channels().length; i++) {
            Channel c = clip.channels()[i];
            if (c == null) continue;
            if (sample(c.rot, t, tmp3) != null)
                for (int j = 0; j < 3; j++) pose.rot[i][j] += (tmp3[j] - pose.rot[i][j]) * weight;
            if (sample(c.pos, t, tmp3) != null)
                for (int j = 0; j < 3; j++) pose.pos[i][j] += (tmp3[j] - pose.pos[i][j]) * weight;
            if (sample(c.scale, t, tmp1) != null) pose.scale[i] += (tmp1[0] - pose.scale[i]) * weight;
        }
    }

    public Matrix4f[] world(Pose pose, Matrix4f root) {
        Matrix4f[] out = new Matrix4f[bones.size()];
        for (Bone b : bones) {
            Matrix4f parent = b.parent() < 0 ? root : out[b.parent()];
            float[] r = pose.rot[b.index()], p = pose.pos[b.index()];
            out[b.index()] =
                    new Matrix4f(parent)
                            .translate(b.pivot())
                            .translate(p[0] / 8f, p[1] / 8f, p[2] / 8f)
                            .rotateZYX((float) Math.toRadians(r[2]), (float) Math.toRadians(r[1]), (float) Math.toRadians(r[0]))
                            .scale(Math.max(pose.scale[b.index()], 0.0001f));
        }
        return out;
    }

    public static Matrix4f display(Matrix4f world, Vector3f center, float scale) {
        return new Matrix4f(world).translate(center).scale(scale).rotateY((float) Math.PI);
    }
}
