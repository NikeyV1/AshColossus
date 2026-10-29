package de.nikey.witherboss.boss;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BossRigTest {
    static BossRig rig;

    @BeforeAll
    static void load() throws Exception {
        rig = BossRig.load(
                Objects.requireNonNull(BossRigTest.class.getResourceAsStream("/boss-rig.json")),
                Objects.requireNonNull(BossRigTest.class.getResourceAsStream("/boss-animations.json")));
    }

    private static Matrix4f root(double facing) {
        return new Matrix4f().translate(0, BossModel.HOVER, 0).rotateY((float) facing);
    }

    private static Vector3f socket(Matrix4f[] world, String bone, String socket) {
        BossRig.Bone b = rig.bone(bone);
        return world[b.index()].transformPosition(new Vector3f(b.sockets().getOrDefault(socket, new Vector3f())));
    }

    private static Matrix4f[] pose(String idle, String clip, int t) {
        BossAnimator a = new BossAnimator(rig);
        a.idle(idle, -1000);
        if (clip != null) a.play(clip, 0);
        a.evaluate(Math.max(0, t - 1));
        return rig.world(a.evaluate(t), root(0));
    }

    @Test
    void everyClipStaysFiniteAndAttachedInEveryIdle() {
        for (String idle : List.of("idle", "idle_hammer", "idle_core"))
            for (String name : rig.clipNames()) {
                BossRig.Clip clip = rig.clip(name);
                BossAnimator a = new BossAnimator(rig);
                a.idle(idle, -1000);
                a.play(name, 0);
                for (int t = 0; t <= clip.length(); t += 2) {
                    BossRig.Pose p = a.evaluate(t);
                    Matrix4f[] w = rig.world(p, root(0.7));
                    for (BossRig.Bone b : rig.bones()) {
                        Vector3f o = w[b.index()].transformPosition(new Vector3f());
                        assertTrue(Float.isFinite(o.x) && Float.isFinite(o.y) && Float.isFinite(o.z), name + " " + b.name());
                        assertTrue(o.length() < 20, name + "@" + t + " " + b.name() + " " + o);
                        if (b.parent() >= 0) {
                            Vector3f expected = w[b.parent()].transformPosition(new Vector3f(b.pivot()).add(
                                    p.pos[b.index()][0] / 8f, p.pos[b.index()][1] / 8f, p.pos[b.index()][2] / 8f));
                            assertTrue(expected.distance(o) < 1e-3, "Joint detached: " + name + " " + b.name());
                        }
                    }
                }
            }
    }

    @Test
    void displayMatrixCancelsTheClientItemDisplayFlip() {
        Matrix4f world = new Matrix4f().translate(1, 2, 3).rotateXYZ(0.3f, 1.1f, -0.4f);
        Vector3f center = new Vector3f(0.2f, -0.1f, 0.4f);
        Matrix4f shown = BossRig.display(world, center, 2f).rotateY((float) Math.PI);
        Matrix4f intended = new Matrix4f(world).translate(center).scale(2f);
        assertTrue(shown.equals(intended, 1e-5f));
    }

    @Test
    void faceAndHeartPointTowardsTheFacingDirection() {
        for (double facing : new double[] {0, 1.2, Math.PI, -2.4}) {
            BossAnimator a = new BossAnimator(rig);
            Matrix4f[] w = rig.world(a.evaluate(0), root(facing));
            Vector3f eyes = socket(w, "head", "eyes"), head = w[rig.bone("head").index()].transformPosition(new Vector3f());
            Vector3f dir = new Vector3f((float) Math.sin(facing), 0, (float) Math.cos(facing));
            assertTrue(new Vector3f(eyes).sub(head).dot(dir) > 0.5, "Eyes do not face forward at " + facing);
            Vector3f leftHand = w[rig.bone("hand_l").index()].transformPosition(new Vector3f());
            Vector3f left = new Vector3f(dir).cross(new Vector3f(0, -1, 0));
            assertTrue(leftHand.dot(left) > 1, "left hand is not on the left");
        }
        assertEquals(Math.PI / 2, BossModel.facingTowards(1, 0), 1e-9);
        assertEquals(0, BossModel.facingTowards(0, 1), 1e-9);
    }

    @Test
    void fistsAndHammerLandOnTheTelegraphedSpot() {
        Matrix4f[] slam = pose("idle", "slam", rig.clip("slam").marker("impact", 32));
        for (String claw : List.of("claw_l", "claw_r")) {
            Vector3f tips = socket(slam, claw, "tips");
            assertEquals(0, tips.y, 0.6, claw + " does not touch the ground");
            assertEquals(BossMove.SLAM_REACH, tips.z, 0.7);
            assertTrue(Math.abs(tips.x) < MovesGlut.Slam.RADIUS);
        }
        Matrix4f[] hammer = pose("idle_hammer", "hammer_smash", rig.clip("hammer_smash").marker("impact", 30));
        Vector3f head = socket(hammer, "hammer", "head");
        assertEquals(BossMove.HAMMER_REACH, head.z, 0.7);
        assertTrue(head.y < 2.4 && head.y > 0.3, "Hammer head is not on the ground: " + head);
        assertTrue(Math.abs(head.x) < MovesHammer.Hammer.WIDTH / 2);
    }

    @Test
    void hammerStaysInTheFistAboveTheFloorAndClearOfTheBody() {
        for (String clipName : List.of("draw_hammer", "hammer_smash", "spin", "rain", "shatter", "lash")) {
            BossRig.Clip clip = rig.clip(clipName);
            for (int t = 0; t <= clip.length(); t += 2) {
                BossAnimator a = new BossAnimator(rig);
                a.idle(clipName.equals("draw_hammer") || clipName.equals("shatter") || clipName.equals("lash") ? "idle_hammer" : "idle_hammer", -1000);
                a.play(clipName, 0);
                a.evaluate(Math.max(0, t - 1));
                BossRig.Pose p = a.evaluate(t);
                if (p.scale[rig.bone("hammer").index()] < 0.9f) continue;
                Matrix4f[] w = rig.world(p, root(0));
                Vector3f head = socket(w, "hammer", "head"), tip = socket(w, "hammer", "tip"), heart = socket(w, "heart", "core");
                Vector3f hand = socket(w, "hand_r", "grip");
                Vector3f grip = w[rig.bone("hammer").index()].transformPosition(new Vector3f());
                assertTrue(grip.distance(hand) < 0.3, clipName + "@" + t + " grip leaves the fist: " + grip.distance(hand));
                assertTrue(head.y > -0.2, clipName + "@" + t + " hammer head in the floor: " + head);
                assertTrue(tip.y > -1.4, clipName + "@" + t + " hammer tip in the floor: " + tip);
                assertTrue(head.distance(heart) > 1.6, clipName + "@" + t + " hammer head is inside the body: " + head.distance(heart));
            }
        }
        Matrix4f[] carry = pose("idle_hammer", null, 0);
        Vector3f head = socket(carry, "hammer", "head");
        assertTrue(head.y > 6 && head.x < -2, "Carried hammer is not high on the right side: " + head);
    }

    @Test
    void heartIsMeleeReachableDuringEveryExposureWindow() {
        for (var e : Map.of("slam", "idle", "hammer_smash", "idle_hammer", "stagger", "idle").entrySet()) {
            BossRig.Clip clip = rig.clip(e.getKey());
            int from = clip.marker("exposed_start", -1), to = clip.marker("exposed_end", -1);
            assertTrue(from > 0 && to > from, e.getKey());
            for (int t = from; t < to; t += 4) {
                Vector3f heart = socket(pose(e.getValue(), e.getKey(), t), "heart", "core");
                assertTrue(heart.y > 1.8 && heart.y < 4.2, e.getKey() + "@" + t + " heart at " + heart.y);
            }
        }
    }

    @Test
    void attackMarkersFitTheirClips() {
        for (String name : rig.clipNames()) {
            BossRig.Clip clip = rig.clip(name);
            for (var m : clip.markers().entrySet())
                assertTrue(m.getValue() >= 0 && m.getValue() <= clip.length(), name + ":" + m.getKey());
        }
        for (BossDirector.Attack a : BossDirector.Attack.values()) assertNotNull(a.label);
    }

    @Test
    void hiddenHammerOnlyInFirstPhase() {
        assertTrue(rigScale("idle", "hammer") < 0.01);
        assertEquals(1, rigScale("idle_hammer", "hammer"), 1e-3);
        assertTrue(rigScale("idle_core", "hammer") < 0.01);
    }

    private static float rigScale(String idle, String bone) {
        BossAnimator a = new BossAnimator(rig);
        a.idle(idle, -1000);
        return a.evaluate(0).scale[rig.bone(bone).index()];
    }
}
