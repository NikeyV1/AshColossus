package de.nikey.witherboss.boss;

import de.nikey.witherboss.ui.Msg;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;

import de.nikey.witherboss.config.Tuning;

import org.bukkit.*;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public final class WitherEncounter implements AutoCloseable {
    public static final int INTRO = 280;
    public static final int KNOCK_GRACE = 30;
    public static final int MELTDOWN_TICKS = 200, TEST_PLAYERS = 6;
    private static final int[] MELT_AT = {1000, 2000, 3000};
    private static final double[] MELT_RADIUS = {30, 24, 18};
    private static final String[] MELT_KEY = {"melt30", "melt24", "melt18"};

    private record Channel(UUID target, int started, Location origin) {}

    private record Burn(UUID owner, int until) {}

    private record Hit(int tick, UUID player, double dealt) {}

    private final WitherService service;
    private final UUID id, leader;
    private final WitherArena arena;
    private final RaidState state;
    private final boolean preview;
    private boolean fullTest;
    private BossDirector.Attack previewAttack;
    private final Random random = new Random();
    private final BossDirector director = new BossDirector(random);
    private final Map<UUID, GameMode> testModes = new HashMap<>();
    private final Map<UUID, Location> returnOrigins = new HashMap<>();
    private final List<Entity> owned = new ArrayList<>();
    private final Map<UUID, Player> online = new LinkedHashMap<>();
    private final List<Chunk> tickets = new ArrayList<>();
    private final Map<UUID, Channel> channels = new HashMap<>();
    private final Map<UUID, Burn> burns = new HashMap<>();
    private final Map<UUID, Integer> lastTargeted = new HashMap<>();
    private final Map<UUID, Integer> lastKnockHit = new HashMap<>();
    private final Map<UUID, ItemDisplay> lanterns = new HashMap<>();
    private final Map<UUID, TextDisplay> lanternLabels = new HashMap<>();
    private final List<BossMove> moves = new ArrayList<>();
    private final BossMinions minions = new BossMinions(this);
    private final ArrayDeque<Hit> recentHits = new ArrayDeque<>();
    private double shield = 1;
    private int nextReaction, stageShown;
    private BossModel model;
    private BossFx fx;
    private BossHud hud;
    private WitherHitboxes hitboxes;
    private MusicDirector music;
    private BossMove primary;
    private BossDirector.Attack pendingEscort;
    private int pendingEscortAt;
    private int ticks, nextAttack = INTRO + 40, transitionUntil, transitionStart, exposedUntil, exposeExtended;
    private int coreStart = -1, meltdownStart = -1, deathStart = -1, endingUntil = -1, groundDepth, damageDepth;
    private int previewEnd = -1, meltStep;
    private String endingReason;
    private RaidState.Phase transitionTo;
    private Location lookTarget;
    private Player focus;
    private double safeRadius = 36, headYaw, headPitch;
    private BossFx.Mark meltMark;
    private boolean closed, ambienceSaved, savedStorm;
    private Boolean savedAdvanceTime, savedAdvanceWeather;
    private long savedTime;

    public WitherEncounter(WitherService service, UUID id, UUID leader, WitherArena arena, Collection<UUID> participants, boolean preview) {
        this.service = service;
        this.id = id;
        this.leader = leader;
        this.arena = arena;
        this.preview = preview;
        Tuning tuning = service.tuning();
        double factor = preview ? tuning.testHealthFactor() : 1.0;
        state = new RaidState(
                participants,
                tuning.healthPerPlayer() * factor,
                preview ? TEST_PLAYERS : tuning.minScalingPlayers(),
                new RaidState.Rules(tuning.reviveCharges(), tuning.bodyMultiplier(), tuning.coreBodyMultiplier(), tuning.heartMultiplier()));
        director.disable(tuning.disabledAttacks());
        for (UUID participant : participants) {
            Player p = Bukkit.getPlayer(participant);
            if (p != null) online.put(participant, p);
        }
    }

    public void spawn() {
        try {
            Location c = arena.center();
            for (int x = (c.getBlockX() - 48) >> 4; x <= (c.getBlockX() + 48) >> 4; x++)
                for (int z = (c.getBlockZ() - 48) >> 4; z <= (c.getBlockZ() + 48) >> 4; z++) {
                    Chunk chunk = arena.world().getChunkAt(x, z);
                    chunk.addPluginChunkTicket(service.plugin());
                    tickets.add(chunk);
                }
            World w = arena.world();
            for (Entity stray : w.getEntities())
                if (stray instanceof Monster && !service.tagged(stray) && arena.near(stray.getLocation())) stray.remove();
            savedAdvanceTime = w.getGameRuleValue(GameRules.ADVANCE_TIME);
            savedAdvanceWeather = w.getGameRuleValue(GameRules.ADVANCE_WEATHER);
            savedTime = w.getTime();
            savedStorm = w.hasStorm();
            ambienceSaved = true;
            w.setGameRule(GameRules.ADVANCE_TIME, false);
            w.setGameRule(GameRules.ADVANCE_WEATHER, false);
            w.setTime(18_000);
            w.setStorm(false);
            fx = new BossFx(service.rig(), c, this::tag);
            model = new BossModel(service.rig(), c, this::tag);
            model.place(c.getX(), c.getZ() + 4);
            model.facing(Math.PI);
            hitboxes = new WitherHitboxes(c.clone().add(0, 4, 4), this::tag);
            hud = new BossHud();
            music = new MusicDirector(new MusicDirector.Output() {
                public void play(UUID listener, String track) {
                    Player p = Bukkit.getPlayer(listener);
                    if (p != null) p.playSound(p, "wither_boss:music." + track, SoundCategory.RECORDS, 0.85f, 1f);
                }

                public void stop(UUID listener, String track) {
                    Player p = Bukkit.getPlayer(listener);
                    if (p != null) p.stopSound("wither_boss:music." + track, SoundCategory.RECORDS);
                }

                public void silenceVanilla(UUID listener) {
                    Player p = Bukkit.getPlayer(listener);
                    if (p != null) p.stopSound(SoundCategory.MUSIC);
                }
            });
            ambientLights();
            for (Player player : players()) {
                returnOrigins.putIfAbsent(player.getUniqueId(), player.getLocation().clone());
                player.setGliding(false);
                if (!player.teleport(arena.entry()))
                    throw new IllegalArgumentException(player.getName() + ": another plugin prevented the teleport to the arena.");
            }
            if (mechanicPreview()) {
                model.animator().play("roar", 0);
                nextAttack = 50;
            } else {
                model.animator().play("intro", 0);
                music.oneShot("intro", 0, "phase1", INTRO);
            }
            listeners();
            model.render(0, true);
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private void ambientLights() {
        for (int x = -33; x <= 33; x += 7)
            for (int z = -33; z <= 33; z += 7)
                if (x * x + z * z <= 34 * 34) fx.light(arena.center().add(x, 5, z), 11, Integer.MAX_VALUE / 2);
    }

    public void tag(Entity entity) {
        entity.getPersistentDataContainer().set(service.entityKey(), PersistentDataType.STRING, id.toString());
        entity.setPersistent(false);
        owned.add(entity);
    }

    public boolean owns(Entity entity) {
        return id.toString().equals(entity.getPersistentDataContainer().get(service.entityKey(), PersistentDataType.STRING));
    }

    public List<Player> players() {
        return online.values().stream().filter(Player::isOnline).toList();
    }

    public void reconnect(Player p) {
        if (state.member(p.getUniqueId()) != null) online.put(p.getUniqueId(), p);
    }

    public List<Player> alive() {
        return players().stream()
                .filter(p -> state.member(p.getUniqueId()).status == RaidState.Status.ALIVE && !p.isDead() && arena.contains(p.getLocation()))
                .toList();
    }

    public List<Player> targetable() {
        return alive().stream().filter(p -> state.living(p.getUniqueId(), ticks)).toList();
    }

    public WitherService service() {
        return service;
    }

    public UUID id() {
        return id;
    }

    public UUID leader() {
        return leader;
    }

    public WitherArena arena() {
        return arena;
    }

    public RaidState state() {
        return state;
    }

    public int tick() {
        return ticks;
    }

    public Random random() {
        return random;
    }

    public BossRig rig() {
        return service.rig();
    }

    public BossModel model() {
        return model;
    }

    public BossFx fx() {
        return fx;
    }

    public BossHud hud() {
        return hud;
    }

    public WitherHitboxes hitboxes() {
        return hitboxes;
    }

    public MusicDirector music() {
        return music;
    }

    public boolean preview() {
        return preview;
    }

    public boolean mechanicPreview() {
        return preview && !fullTest;
    }

    void fullTest() {
        fullTest = true;
    }

    void previewAttack(BossDirector.Attack attack) {
        previewAttack = attack;
    }

    public boolean solo() {
        return state.members().size() == 1 || targetable().size() <= 1;
    }

    void origin(UUID player, Location origin) {
        returnOrigins.putIfAbsent(player, origin.clone());
    }

    void rememberTest(Player p, GameMode mode, Location origin) {
        testModes.put(p.getUniqueId(), mode);
        returnOrigins.put(p.getUniqueId(), origin.clone());
    }

    void restoreTestMode(Player p) {
        GameMode mode = testModes.remove(p.getUniqueId());
        if (mode != null) p.setGameMode(mode);
    }

    void restoreParticipant(Player p) {
        Location origin = returnOrigins.remove(p.getUniqueId());
        if (origin != null) service.returnAfterFight(p, origin);
        restoreTestMode(p);
    }

    public void enlist(Player p) {
        if (state.member(p.getUniqueId()) != null
                || state.phase() == RaidState.Phase.DYING
                || state.phase() == RaidState.Phase.ENDED)
            throw new IllegalArgumentException("You are already part of this fight; fallen fighters need a revive.");
        Location origin = p.getLocation().clone();
        p.setGliding(false);
        p.leaveVehicle();
        p.eject();
        if (!p.teleport(arena.entry()))
            throw new IllegalArgumentException(p.getName() + ": another plugin prevented the teleport to the arena.");
        state.enlist(p.getUniqueId());
        online.put(p.getUniqueId(), p);
        returnOrigins.putIfAbsent(p.getUniqueId(), origin);
        if (state.phase() != RaidState.Phase.INTRO) state.member(p.getUniqueId()).protectionUntil = ticks + 60;
        rules(p);
        music.join(p.getUniqueId(), ticks);
    }

    private void rules(Player p) {
        p.sendMessage(BossHud.bold("The Ash Colossus", BossHud.DANGER).append(BossHud.plain(" · three rules", BossHud.ASH)));
        p.sendMessage(BossHud.plain(" ✦ ", BossHud.GOLD).append(BossHud.plain("The armor absorbs a lot. After heavy attacks he collapses exhausted: that is the moment to hit the golden heart.", Msg.LIGHT)));
        p.sendMessage(BossHud.plain(" ✦ ", BossHud.GOLD).append(BossHud.plain("Red areas fill up until impact. Gold means: step in here or hit here.", Msg.LIGHT)));
        p.sendMessage(BossHud.plain(" ✦ ", BossHud.GOLD).append(BossHud.plain("Fallen fighters come back with the shared revive charges.", Msg.LIGHT)));
    }

    public double bossX() {
        return model.x();
    }

    public double bossZ() {
        return model.z();
    }

    public Location bossGround() {
        return model.ground();
    }

    public double floorY() {
        return arena.y();
    }

    public Location floorAt(double x, double z) {
        return new Location(arena.world(), x, arena.y(), z);
    }

    public List<BossGeometry.Pillar> pillars() {
        List<BossGeometry.Pillar> out = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Location s = arena.seal(i);
            out.add(new BossGeometry.Pillar(s.getBlockX() - 1, s.getBlockZ() - 1, s.getBlockX() + 2, s.getBlockZ() + 2));
        }
        return out;
    }

    public double edge(double x, double z, double yaw, double radius) {
        double dx = Math.sin(yaw), dz = Math.cos(yaw);
        double ox = x - arena.x(), oz = z - arena.z();
        double b = ox * dx + oz * dz, c = ox * ox + oz * oz - radius * radius;
        double disc = b * b - c;
        if (disc < 0) return 0;
        return Math.max(0, -b + Math.sqrt(disc));
    }

    public double[] clampToArena(double x, double z, double radius) {
        double dx = x - arena.x(), dz = z - arena.z(), d = Math.hypot(dx, dz);
        if (d <= radius) return new double[] {x, z};
        return new double[] {arena.x() + dx / d * radius, arena.z() + dz / d * radius};
    }

    public double[] awayFromPillars(double x, double z, double margin) {
        for (BossGeometry.Pillar p : pillars()) {
            double cx = (p.minX() + p.maxX()) / 2, cz = (p.minZ() + p.maxZ()) / 2;
            double dx = x - cx, dz = z - cz, d = Math.hypot(dx, dz);
            double need = 1.5 + margin;
            if (d < need) {
                if (d < 0.01) {
                    dx = 1;
                    d = 1;
                }
                x = cx + dx / d * need;
                z = cz + dz / d * need;
            }
        }
        return new double[] {x, z};
    }

    public void turnTowards(double x, double z, double maxDegrees) {
        double want = BossModel.facingTowards(x - model.x(), z - model.z());
        model.facing(BossGeometry.turn(model.facing(), want, Math.toRadians(maxDegrees)));
    }

    private static final double BODY_CLEARANCE = 3.8;

    public void glide(double x, double z) {
        double[] c = clampToArena(x, z, 24);
        c = BossGeometry.slide(model.x(), model.z(), c[0], c[1], pillars(), BODY_CLEARANCE);
        model.place(c[0], c[1]);
    }

    public void lookAt(Location target) {
        lookTarget = target == null ? null : target.clone();
    }

    public Player nearest(double radius) {
        Player best = null;
        double bd = radius * radius;
        for (Player p : targetable()) {
            double d = Math.pow(p.getLocation().getX() - bossX(), 2) + Math.pow(p.getLocation().getZ() - bossZ(), 2);
            if (d <= bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    public int near(double radius) {
        int n = 0;
        for (Player p : targetable())
            if (Math.hypot(p.getLocation().getX() - bossX(), p.getLocation().getZ() - bossZ()) <= radius) n++;
        return n;
    }

    public Player choose() {
        List<Player> list = chooseDistinct(1);
        return list.isEmpty() ? null : list.getFirst();
    }

    public List<Player> chooseDistinct(int count) {
        List<Player> pool = new ArrayList<>(targetable());
        pool.sort(Comparator.comparingInt(p -> lastTargeted.getOrDefault(p.getUniqueId(), -10_000) + random.nextInt(40)));
        List<Player> out = new ArrayList<>(pool.subList(0, Math.min(count, pool.size())));
        for (Player p : out) lastTargeted.put(p.getUniqueId(), ticks);
        return out;
    }

    public void play(String clip) {
        model.animator().play(clip, ticks);
    }

    public int marker(String clip, String name, int fallback) {
        return rig().clip(clip).marker(name, fallback);
    }

    public boolean grounded(Player p) {
        return p.getLocation().getY() - floorY() < 0.55 && !p.isFlying();
    }

    public Vector away(Location from, Player p, double strength, double up) {
        Vector v = p.getLocation().toVector().subtract(from.toVector()).setY(0);
        if (v.lengthSquared() < 1e-4) v = model.forward();
        return v.normalize().multiply(strength).setY(up);
    }

    public int stage() {
        if (mechanicPreview() || state.phase() == RaidState.Phase.INTRO) return 0;
        int per = service.tuning().enrageStageTicks();
        return Math.min(service.tuning().enrageMaxStages(), Math.max(0, ticks - INTRO) / per);
    }

    public Tuning tuning() {
        return service.tuning();
    }

    public double damageValue(String name) {
        return service.tuning().damage(name, state.phase(), stage());
    }

    public int timeLimit() {
        return service.tuning().timeLimitTicks();
    }

    public int groupSize() {
        return Math.max(1, targetable().size());
    }

    public int perGroup(int players, int max) {
        return Math.min(max, Math.max(1, (int) Math.ceil(groupSize() / (double) players)));
    }

    public BossMinions minions() {
        return minions;
    }

    public boolean minion(Entity e) {
        return e != null && minions.owns(e);
    }

    public boolean minionStrike(LivingEntity attacker, Player victim) {
        return active() && endingUntil < 0 && state.living(victim.getUniqueId(), ticks) && minions.strike(attacker, victim, ticks);
    }

    public void minionDied(Entity e, Player killer) {
        minions.died(e, killer);
    }

    public void shield(double factor) {
        shield = Math.clamp(factor, 0.05, 1.0);
    }

    public double shield() {
        return shield;
    }

    public boolean ground() {
        return groundDepth > 0;
    }

    public boolean dealing() {
        return damageDepth > 0;
    }

    public boolean damage(Player player, double raw, String type, boolean ground, int witherTicks, Vector knockback) {
        if (!active() || endingUntil >= 0 || state.phase() == RaidState.Phase.DYING || !state.living(player.getUniqueId(), ticks)) return false;
        boolean burst = knockback != null && !type.equals("minion");
        if (burst && ticks - lastKnockHit.getOrDefault(player.getUniqueId(), -KNOCK_GRACE) < KNOCK_GRACE) return false;
        double before = player.getHealth() + player.getAbsorptionAmount();
        if (ground) groundDepth++;
        damageDepth++;
        try {
            var source = DamageSource.builder(ground ? DamageType.MOB_ATTACK : DamageType.MOB_PROJECTILE)
                    .withCausingEntity(hitboxes.source())
                    .withDirectEntity(hitboxes.source())
                    .withDamageLocation(bossGround())
                    .build();
            player.setNoDamageTicks(0);
            if (ground && player.isBlocking()) player.clearActiveItem();
            player.damage(raw, source);
        } finally {
            damageDepth--;
            if (ground) groundDepth--;
        }
        boolean struck = player.getHealth() + player.getAbsorptionAmount() < before;
        if (struck && !player.isDead()) {
            if (witherTicks > 0) player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.WITHER, witherTicks, 0));
            if (knockback != null) player.setVelocity(knockback);
            if (burst) lastKnockHit.put(player.getUniqueId(), ticks);
            ui(player, "boss.hurt", 0.6f, 0.7f);
        }
        return struck;
    }

    public void markPlayer(Player p, String text) {
        ui(p, "ui.warning", 1f, 1f);
        hud.action(p, BossHud.mark(text), ticks, 50);
    }

    public void hint(Player p, String text) {
        if (hud.actionFree(p, ticks)) hud.action(p, BossHud.plain("➜ ", BossHud.GOLD).append(BossHud.bold(text, Msg.LIGHT)), ticks, 30);
    }

    public void success(Player p, String text) {
        ui(p, "ui.success", 0.8f, 1f);
        hud.action(p, BossHud.plain("✔ ", BossHud.GOLD).append(BossHud.bold(text, BossHud.GOLD)), ticks, 30);
    }

    public void credit(Player p) {
        RaidState.Member m = state.member(p.getUniqueId());
        if (m != null) m.actions++;
    }

    public void ui(Player p, String event, float volume, float pitch) {
        p.playSound(p, "wither_boss:" + event, SoundCategory.MASTER, volume, pitch);
    }

    public void broadcast(Component text) {
        for (Player p : players()) p.sendMessage(Msg.title("Ash Colossus › ").append(text));
    }

    public void shake(Location at, int strength) {
        fx.particle(Particle.BLOCK_CRUMBLE, at.clone().add(0, 0.2, 0), strength * 4, strength * 0.25, 0.1, strength * 0.25, 0.1, Material.POLISHED_BLACKSTONE_BRICKS.createBlockData());
        fx.particle(Particle.ASH, at.clone().add(0, 1, 0), strength * 3, strength * 0.3, 0.6, strength * 0.3, 0.02, null);
    }

    public void expose(int duration) {
        exposedUntil = Math.max(exposedUntil, ticks + duration);
        exposeExtended = 0;
        fx.sound(model.socket("heart", "core"), "boss.stagger", 3f, 1f);
        for (Player p : targetable()) {
            hud.action(p, BossHud.plain("✦ ", BossHud.GOLD).append(BossHud.bold("Exhausted · hit the golden heart!", BossHud.GOLD)), ticks, 40);
            if (hud.firstTime(p, "exposed"))
                p.sendMessage(BossHud.plain("The colossus is exhausted: the golden heart is exposed and takes far more damage now.", BossHud.ASH));
        }
    }

    public boolean exposed() {
        return state.phase() == RaidState.Phase.CORE || ticks < exposedUntil;
    }

    public void stagger() {
        if (primary != null && !primary.finished() && primary.type != BossDirector.Attack.CHAIN) return;
        play("stagger");
        int from = marker("stagger", "exposed_start", 11), to = marker("stagger", "exposed_end", 52);
        fx.later(from, () -> {
            if (active() && endingUntil < 0 && state.phase() != RaidState.Phase.DYING) expose(to - from);
        });
        nextAttack = Math.max(nextAttack, ticks + rig().clip("stagger").length() + 10);
    }

    public double heal(double fraction) {
        return state.heal(fraction);
    }

    public boolean active() {
        return !closed && state.phase() != RaidState.Phase.ENDED;
    }

    boolean closing() {
        return closed;
    }

    public boolean transitioning() {
        return ticks < transitionUntil || state.phase() == RaidState.Phase.INTRO;
    }

    public boolean vulnerable() {
        return !transitioning() && endingUntil < 0 && state.phase() != RaidState.Phase.DYING && state.phase() != RaidState.Phase.ENDED;
    }

    public RaidState.Part part(Player attacker, Entity damager) {
        if (!exposed()) return RaidState.Part.BODY;
        Location heart = model.socket("heart", "core");
        if (damager instanceof Projectile pr) return pr.getLocation().distanceSquared(heart) <= 1.7 * 1.7 ? RaidState.Part.HEART : RaidState.Part.BODY;
        Location eye = attacker.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector rel = heart.toVector().subtract(eye.toVector());
        double t = Math.clamp(rel.dot(dir), 0, 6);
        double dist = rel.clone().subtract(dir.multiply(t)).length();
        return dist <= 1.3 ? RaidState.Part.HEART : RaidState.Part.BODY;
    }

    public boolean hitBody(Player player, double damage, Entity damager) {
        if (!Double.isFinite(damage) || damage <= 0 || !arena.contains(player.getLocation()) || !vulnerable() || !state.living(player.getUniqueId(), ticks))
            return false;
        UUID projectile = damager instanceof Projectile ? damager.getUniqueId() : null;
        RaidState.Part part = part(player, damager);
        RaidState.Phase before = state.phase();
        double dealt = state.hit(player.getUniqueId(), part, damage * shield, exposed(), ticks, projectile);
        if (dealt <= 0) return false;
        recentHits.addLast(new Hit(ticks, player.getUniqueId(), dealt));
        boolean heart = part == RaidState.Part.HEART;
        Location at = heart ? model.socket("heart", "core") : damager instanceof Projectile ? damager.getLocation() : model.socket("chest", "center");
        fx.text(at.clone().add(random.nextDouble() - 0.5, 0.4, random.nextDouble() - 0.5),
                heart ? BossHud.bold("✦ " + Math.round(dealt), BossHud.GOLD) : BossHud.plain(String.valueOf(Math.round(dealt)), BossHud.ASH), 16, heart ? 1.6f : 1.1f, true);
        if (heart) {
            fx.sound(at, "boss.weakpoint", 2f, 0.9f + random.nextFloat() * 0.2f);
            model.flash("heart", ticks + 3);
        } else {
            fx.sound(at, exposed() ? "boss.hurt" : "boss.armor", 1.6f, 0.85f + random.nextFloat() * 0.3f);
            model.flash("chest", ticks + 2);
            if (!exposed() && hud.firstTime(player, "armor"))
                hud.action(player, BossHud.plain("The armor blocks your blows. Wait for the golden heart!", BossHud.ASH), ticks, 40);
        }
        if (state.phase() != before) phaseChanged(before);
        return true;
    }

    public void axe(Player player, Entity target) {
        if (minions.owns(target)) {
            minions.stun(target);
            return;
        }
        if (hitboxes.proxy(target)) {
            for (BossMove m : moves) {
                if (m instanceof MovesGlut.Chain c) c.stun(target, player);
                if (m instanceof MovesGlut.Skulls s) s.deflect(target, player);
            }
        } else if (hitboxes.body(target) && ticks < exposedUntil && exposeExtended == 0) {
            exposeExtended = 1;
            exposedUntil += 20;
            success(player, "Stun · exhaustion +1 s");
        }
    }

    public boolean shieldPush(Player player, Entity entity) {
        if (!hitboxes.proxy(entity)) return false;
        for (BossMove m : moves) if (m instanceof MovesGlut.Skulls s && s.deflect(entity, player)) return true;
        return false;
    }

    public void ignite(Player player, Entity target, int duration) {
        if (minions.owns(target)) target.setFireTicks(Math.max(target.getFireTicks(), duration));
        else if (hitboxes.body(target)) burns.put(player.getUniqueId(), new Burn(player.getUniqueId(), ticks + duration));
        else if (hitboxes.proxy(target)) hitboxes.hitProxy(target, player, 4, null);
    }

    private void burns() {
        burns.entrySet().removeIf(e -> e.getValue().until() <= ticks);
        if (ticks % 20 != 0) return;
        for (Burn b : List.copyOf(burns.values())) {
            Player owner = online.get(b.owner());
            if (owner != null && owner.isOnline()) {
                hitBodyPassive(owner, 1);
                fx.particle(Particle.FLAME, model.socket("chest", "center"), 8, 0.6, 0.02);
            }
        }
    }

    private void hitBodyPassive(Player owner, double amount) {
        if (!vulnerable() || !state.living(owner.getUniqueId(), ticks)) return;
        RaidState.Phase before = state.phase();
        if (state.hit(owner.getUniqueId(), RaidState.Part.BODY, amount, false, ticks, null) > 0 && state.phase() != before) phaseChanged(before);
    }

    private void cancelMoves() {
        for (BossMove m : moves) m.cleanup();
        moves.clear();
        primary = null;
        pendingEscort = null;
        model.extraYaw(0);
        lookAt(null);
    }

    private void phaseChanged(RaidState.Phase before) {
        cancelMoves();
        minions.clear(true);
        shield = 1;
        channels.clear();
        burns.clear();
        exposedUntil = 0;
        director.reset();
        transitionStart = ticks;
        transitionTo = state.phase();
        switch (state.phase()) {
            case HAMMER -> {
                transitionUntil = ticks + rig().clip("draw_hammer").length();
                play("draw_hammer");
                music.oneShot("transition", ticks, "phase2", transitionUntil - ticks);
                hud.title(players(), BossHud.bold("II · The Consuming Hammer", BossHud.DANGER), BossHud.plain("The colossus tears his weapon from the forge", BossHud.ASH), 50);
                fx.sound(bossGround(), "atk.hammer_draw", 4f, 1f);
                push(9, 0.9);
            }
            case CORE -> {
                transitionUntil = ticks + rig().clip("shatter").length();
                play("shatter");
                music.oneShot("transition", ticks, "phase3", transitionUntil - ticks);
                hud.title(players(), BossHud.bold("III · Core Meltdown", BossHud.GOLD), BossHud.plain("The heart lies bare · the rim melts away", BossHud.ASH), 50);
                push(9, 0.9);
            }
            case DYING -> {
                deathStart = ticks;
                transitionUntil = Integer.MAX_VALUE;
                model.animator().play("death", ticks);
                music.oneShot("victory", ticks, null, 0);
                fx.sound(model.socket("chest", "center"), "boss.death", 5f, 1f);
                if (meltMark != null) meltMark.remove();
                hud.title(players(), BossHud.bold("Victory!", BossHud.GOLD), BossHud.plain("The Ash Colossus crumbles to ash", BossHud.ASH), 60);
            }
            default -> {}
        }
        for (Player p : players()) ui(p, "ui.phase", 1f, 1f);
        nextAttack = transitionUntil + 30;
    }

    private void push(double radius, double strength) {
        for (Player p : targetable()) {
            double d = Math.hypot(p.getLocation().getX() - bossX(), p.getLocation().getZ() - bossZ());
            if (d < radius) p.setVelocity(away(bossGround(), p, strength, 0.4));
        }
    }

    private void transition(int t) {
        if (transitionTo == RaidState.Phase.HAMMER) {
            if (t == 32) {
                Location at = floorAt(bossX() + model.forward().getX() * 3, bossZ() + model.forward().getZ() * 3);
                fx.particle(Particle.SOUL_FIRE_FLAME, at.clone().add(0, 0.5, 0), 80, 1.5, 1.5, 1.5, 0.1, null);
                fx.particle(Particle.LAVA, at.clone().add(0, 0.5, 0), 20, 1, 0.3);
                fx.light(at.clone().add(0, 1, 0), 15, 40);
                shake(at, 14);
            }
            if (t == marker("draw_hammer", "roar", 84)) fx.sound(model.socket("head", "eyes"), "boss.roar2", 5f, 1f);
        } else if (transitionTo == RaidState.Phase.CORE) {
            if (t == marker("shatter", "smash", 38)) fx.sound(bossGround(), "atk.hammer_smash", 4f, 0.8f);
            if (t == marker("shatter", "shatter", 44)) {
                Location at = floorAt(bossX() + model.forward().getX() * HAMMER_HEAD, bossZ() + model.forward().getZ() * HAMMER_HEAD);
                fx.sound(at, "atk.shatter", 5f, 1f);
                for (int i = 0; i < 8; i++) {
                    ItemDisplay shard = fx.effect("fx/shard", null, 15);
                    if (shard == null) continue;
                    double a = random.nextDouble() * Math.PI * 2, r = 5 + random.nextDouble() * 9;
                    Location from = at.clone().add(0, 1, 0), to = floorAt(at.getX() + Math.sin(a) * r, at.getZ() + Math.cos(a) * r).add(0, 0.25, 0);
                    BossFx.apply(shard, fx.effectMatrix("fx/shard", from, new Quaternionf(), new Vector3f(1.5f)), 0);
                    fx.later(1, () -> BossFx.apply(shard, fx.effectMatrix("fx/shard", to, new Quaternionf().rotateXYZ((float) a * 3, (float) a, 1), new Vector3f(1.5f)), 14));
                    fx.later(60, () -> fx.remove(shard));
                }
                fx.particle(Particle.EXPLOSION_EMITTER, at.clone().add(0, 1, 0), 1, 0, 0);
                fx.light(at.clone().add(0, 1, 0), 15, 20);
            }
            if (t == marker("shatter", "burst", 80)) {
                fx.sound(model.socket("head", "eyes"), "boss.roar1", 5f, 0.9f);
                fx.particle(Particle.END_ROD, model.socket("heart", "core"), 80, 0.5, 0.4);
            }
        }
        if (ticks == transitionUntil - 1) {
            model.animator().idle(state.phase() == RaidState.Phase.HAMMER ? "idle_hammer" : "idle_core", ticks);
            if (state.phase() == RaidState.Phase.CORE) coreStart = ticks;
        }
    }

    private void cameraAt(Location target) {
        for (Player p : players()) p.lookAt(target, io.papermc.paper.entity.LookAnchor.EYES);
    }

    private void introDirector(int t) {
        World w = arena.world();
        Location ground = bossGround();
        if (t == 6) hud.title(players(), BossHud.plain("The forge is silent", BossHud.ASH), Component.empty(), 50);
        for (int i = 0; i < 3; i++) {
            if (t == 16 + 18 * i) {
                Location seal = arena.seal(i).add(0, 10, 0);
                w.strikeLightningEffect(seal);
                fx.particle(Particle.SOUL_FIRE_FLAME, seal, 40, 0.8, 0.1);
                shake(arena.seal(i), 6);
            }
        }
        if (t == 74) hud.title(players(), BossHud.plain("Something stirs in the embers", BossHud.DANGER), Component.empty(), 40);
        if (t == 58) cameraAt(ground.clone().add(0, 0.5, 0));
        if (t >= 96 && t <= 206 && t % 4 == 0) {
            double progress = (t - 96) / 110.0;
            cameraAt(ground.clone().add(0, 1 + progress * progress * 13, 0));
        }
        if (t >= 60 && t < 230 && t % 40 == 0) shake(ground, 8 + t / 40);
        if (t >= 70 && t <= 210 && t % 2 == 0)
            for (int k = 0; k < 3; k++) {
                double angle = t * 0.22 + k * Math.PI * 2 / 3, r = 3.4 - (t - 70) * 0.012, height = (t - 70) * 0.085;
                fx.particle(Particle.SOUL_FIRE_FLAME, ground.clone().add(Math.cos(angle) * r, height, Math.sin(angle) * r), 2, 0.05, 0.01);
            }
        if (t >= 60 && t <= 200 && t % 10 == 0) {
            double ring = (t - 60) * 0.12;
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI * 2 / 24;
                fx.particle(Particle.ASH, floorAt(ground.getX() + Math.sin(a) * ring, ground.getZ() + Math.cos(a) * ring).add(0, 0.3, 0), 2, 0.2, 0.02);
            }
        }
        if (t == marker("intro", "ignite", 160)) {
            fx.particle(Particle.END_ROD, model.socket("heart", "core"), 90, 3, 0.25);
            w.strikeLightningEffect(ground);
        }
        if (t == marker("intro", "roar", 232) - 6) {
            for (int i = 0; i < 3; i++) w.strikeLightningEffect(arena.seal(i).add(0, 10, 0));
            cameraAt(model.socket("head", "eyes"));
        }
    }

    private void intro(int t) {
        introDirector(t);
        if (t == 20) {
            fx.sound(arena.center(), "boss.assemble", 4f, 0.9f);
            for (int i = 0; i < 3; i++) {
                ItemDisplay skull = fx.effect("fx/skull", null, 15);
                Location from = arena.seal(i).add(0, 9.5, 0), to = arena.center().add(0, 3, 4);
                double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
                BossFx.apply(skull, fx.effectMatrix("fx/skull", from, BossFx.aim(dx, 0, dz), new Vector3f(2f)), 0);
                fx.later(2, () -> BossFx.apply(skull, fx.effectMatrix("fx/skull", to, BossFx.aim(dx, -4, dz), new Vector3f(1f)), 38));
                fx.later(42, () -> fx.remove(skull));
                fx.particle(Particle.SOUL_FIRE_FLAME, from, 30, 0.4, 0.05);
            }
        }
        if (t > 20 && t < 60 && t % 2 == 0)
            for (int i = 0; i < 3; i++) {
                Location from = arena.seal(i).add(0, 9.5, 0), to = arena.center().add(0, 3, 4);
                double p = (t - 22) / 38.0;
                fx.particle(Particle.SOUL_FIRE_FLAME, from.clone().add(to.toVector().subtract(from.toVector()).multiply(p)), 3, 0.15, 0.01);
            }
        if (t == 60) {
            fx.sound(bossGround(), "boss.emerge", 5f, 1f);
            fx.particle(Particle.SOUL_FIRE_FLAME, bossGround().add(0, 1, 0), 120, 1.2, 3, 1.2, 0.08, null);
            fx.particle(Particle.LAVA, bossGround().add(0, 0.5, 0), 30, 1.5, 0.3, 1.5, 0, null);
            shake(bossGround(), 16);
        }
        if (t >= 60 && t <= 200 && t % 5 == 0) {
            fx.light(bossGround().add(0, 1, 0), 12, 8);
            fx.particle(Particle.ASH, bossGround().add(0, 2, 0), 20, 2, 2, 2, 0.02, null);
        }
        if (t == 100 || t == 140 || t == 180) fx.sound(bossGround().add(0, 4, 0), "boss.assemble", 3f, 1.1f);
        if (t == marker("intro", "ignite", 160)) {
            fx.sound(model.socket("heart", "core"), "boss.heartbeat", 4f, 1f);
            fx.light(model.socket("heart", "core"), 15, 30);
        }
        if (t == marker("intro", "roar", 232)) {
            fx.sound(model.socket("head", "eyes"), "boss.roar1", 6f, 1f);
            push(14, 1.0);
            hud.title(players(), BossHud.bold("THE ASH COLOSSUS", BossHud.DANGER), BossHud.plain("Colossus of the Broken Forge", BossHud.ASH), 60);
            for (Player p : players()) rules(p);
        }
        if (t >= INTRO) {
            state.begin();
            model.animator().idle("idle", ticks);
            nextAttack = ticks + 40;
        }
    }

    private void dying(int t) {
        int implode = marker("death", "implode", 104), dust = marker("death", "dust", 140);
        if (t == implode) {
            Location heart = model.socket("heart", "core");
            fx.particle(Particle.END_ROD, heart, 120, 0.2, 0.6);
            fx.particle(Particle.SOUL, heart, 80, 1.5, 0.1);
            fx.light(heart, 15, 20);
            fx.sound(heart, "atk.nova_burst", 5f, 1.3f);
        }
        if (t >= dust && t % 4 == 0) fx.particle(Particle.ASH, bossGround().add(0, 1.5, 0), 40, 2.5, 1.5, 2.5, 0.02, null);
        if (t == dust) model.flashAll(ticks + 6);
        if (t > dust && t % 2 == 0 && model.vanishNext()) {
            String bone = model.lastVanished();
            if (bone != null) {
                Location at = model.bonePosition(bone);
                fx.particle(Particle.ASH, at, 25, 0.5, 0.02);
                fx.particle(Particle.SOUL, at, 3, 0.3, 0.02);
                if (random.nextInt(3) == 0) fx.sound(at, "boss.hurt", 1.2f, 0.6f);
            }
        }
        if (t >= rig().clip("death").length() + 10) service.win(this);
    }

    private void ending(int t) {
        if (ticks < endingUntil) return;
        if (service.tuning().loseInventoryOnDefeat()) {
            for (Player player : players()) service.clearRaidInventory(player);
            clearDrops();
        }
        service.stop(false, endingReason);
    }

    private void clearDrops() {
        World w = arena.world();
        if (w == null) return;
        for (Item item : w.getNearbyEntitiesByType(Item.class, arena.center(), 40))
            if (arena.contains(item.getLocation())) item.remove();
    }

    public void defeat(String reason) {
        if (endingUntil >= 0 || closed) return;
        cancelMoves();
        minions.clear(true);
        if (meltMark != null) meltMark.remove();
        endingUntil = ticks + 80;
        endingReason = reason;
        play("taunt");
        music.oneShot("defeat", ticks, null, 0);
        fx.sound(model.socket("head", "eyes"), "boss.roar2", 5f, 0.8f);
        hud.title(players(), BossHud.bold("Defeat", BossHud.DANGER), BossHud.plain(reason, BossHud.ASH), 60);
    }

    public void tickEncounter() {
        if (!active()) return;
        ticks++;
        fx.tick(ticks);
        music.tick(ticks);
        if (ticks % 100 == 0) owned.removeIf(e -> !e.isValid());
        model.light(12, exposed() ? 15 : 13, ticks < exposedUntil && state.phase() != RaidState.Phase.DYING);
        if (endingUntil >= 0) ending(ticks);
        else if (state.phase() == RaidState.Phase.DYING) dying(ticks - deathStart);
        else if (state.phase() == RaidState.Phase.INTRO && !mechanicPreview()) intro(ticks);
        else if (state.phase() == RaidState.Phase.INTRO) {
            if (ticks >= 40) {
                state.begin();
                if (previewAttack != null && previewAttack.phase() != RaidState.Phase.GLUT) state.preview(previewAttack.phase());
                model.animator().idle(state.phase() == RaidState.Phase.HAMMER ? "idle_hammer" : state.phase() == RaidState.Phase.CORE ? "idle_core" : "idle", ticks);
                if (state.phase() == RaidState.Phase.CORE) coreStart = ticks;
            }
        } else if (ticks < transitionUntil) transition(ticks - transitionStart);
        else combat();
        if (!active()) return;
        steerHead();
        model.render(ticks, ticks % 2 == 0);
        updateHitboxes();
        if (ticks % 5 == 0) hudUpdate();
        if (ticks % 20 == 0) listeners();
    }

    private void combat() {
        if (state.wiped()) {
            defeat("The group has fallen.");
            return;
        }
        if (ticks >= timeLimit()) {
            defeat("Time limit of " + timeLimit() / 1200 + " minutes reached.");
            return;
        }
        if (!preview && ticks % 100 == 0 && Bukkit.getTPS()[0] < service.tuning().minimumTps()) {
            service.stop(true, "The fight was stopped safely because the server TPS dropped too low.");
            return;
        }
        channels();
        burns();
        minions.tick(ticks);
        shields();
        escalation();
        reaction();
        if (state.phase() == RaidState.Phase.CORE && !mechanicPreview() && service.tuning().meltdownEnabled()) {
            meltdownBoundary();
            if (!active() || endingUntil >= 0) return;
            if (meltdownStart >= 0) return;
        }
        for (BossMove m : List.copyOf(moves)) {
            m.tick();
            if (!active()) return;
        }
        moves.removeIf(BossMove::finished);
        if (primary != null && (primary.finished() || primary.released())) {
            primary = null;
            nextAttack = Math.max(nextAttack, ticks + (int) Math.round(BossDirector.gap(state.phase(), ticks, random, stage(), groupSize(), timeLimit()) * service.tuning().pauseMultiplier()));
            if (mechanicPreview()) previewEnd = ticks + 60;
        }
        if (pendingEscort != null && ticks >= pendingEscortAt) {
            BossMove e = BossMove.create(this, pendingEscort, true);
            moves.add(e);
            e.begin();
            pendingEscort = null;
        }
        if (primary == null) idleBehaviour();
        if (mechanicPreview()) {
            if (previewEnd >= 0 && ticks >= previewEnd && moves.isEmpty()) {
                service.stop(false, "Mechanic preview finished.");
                return;
            }
            if (previewEnd < 0 && primary == null && ticks >= nextAttack && !targetable().isEmpty())
                startPrimary(new BossDirector.Choice(previewAttack == null ? BossDirector.Attack.SLAM : previewAttack, null, 0));
            return;
        }
        if (primary == null && ticks >= nextAttack && ticks >= exposedUntil && !targetable().isEmpty()) {
            BossDirector.Choice c = director.choose(new BossDirector.Context(state.phase(), ticks, near(10), targetable().size(), preview && solo(), coreStart < 0 ? transitionUntil : coreStart, stage(), minions.count()));
            startPrimary(c);
        }
    }

    private void shields() {
        if (ticks % 4 != 0) return;
        for (Player p : alive())
            if (p.isBlocking() && state.living(p.getUniqueId(), ticks))
                for (Entity entity : p.getNearbyEntities(2.6, 2, 2.6)) shieldPush(p, entity);
    }

    private void escalation() {
        int stage = stage();
        if (stage <= stageShown) return;
        stageShown = stage;
        fx.sound(model.socket("head", "mouth"), "boss.roar_short", 3f, 0.8f);
        fx.particle(Particle.FLAME, model.socket("chest", "center"), 40, 1.2, 0.05);
        for (Player p : players())
            hud.action(p, BossHud.plain("☠ The colossus grows angrier", BossHud.DANGER), ticks, 50);
    }

    private void reaction() {
        while (!recentHits.isEmpty() && ticks - recentHits.peekFirst().tick() > 50) recentHits.removeFirst();
        if (primary != null && primary.type == BossDirector.Attack.BEACONS) {
            recentHits.clear();
            return;
        }
        if (!service.tuning().retributionEnabled() || ticks < nextReaction || mechanicPreview() || transitioning() || endingUntil >= 0 || meltdownStart >= 0 || recentHits.isEmpty()) return;
        double sum = 0;
        Set<UUID> who = new HashSet<>();
        for (Hit h : recentHits) {
            sum += h.dealt();
            who.add(h.player());
        }
        double threshold = state.maximum() * service.tuning().retributionBurstPercent() / 100.0;
        int needed = Math.min(service.tuning().retributionAttackers(), Math.max(2, groupSize()));
        if (who.size() < needed || sum < threshold) return;
        nextReaction = ticks + Math.max(200, 340 - stage() * 14);
        recentHits.clear();
        BossMove move = BossMove.create(this, BossDirector.Attack.RETRIBUTION, primary != null && !primary.finished());
        moves.add(move);
        move.begin();
    }

    private void startPrimary(BossDirector.Choice c) {
        if (c.primary() == BossDirector.Attack.BEACONS) {
            pendingEscort = null;
            for (BossMove move : List.copyOf(moves)) {
                if (move.type != BossDirector.Attack.RETRIBUTION) continue;
                move.cleanup();
                moves.remove(move);
            }
        }
        primary = BossMove.create(this, c.primary(), false);
        moves.add(primary);
        primary.begin();
        for (Player p : targetable())
            if (hud.firstTime(p, "attack:" + c.primary().key()))
                p.sendMessage(BossHud.plain("New · ", BossHud.GOLD).append(BossHud.bold(c.primary().label, Msg.LIGHT)).append(BossHud.plain(" – " + c.primary().hint, BossHud.ASH)));
        if (c.escort() != null && BossDirector.compatible(c.primary(), c.escort())) {
            pendingEscort = c.escort();
            pendingEscortAt = ticks + c.escortDelay();
        }
    }

    private void idleBehaviour() {
        if (ticks % 60 == 0 || focus == null || !targetable().contains(focus)) focus = nearestOrAny();
        if (focus == null) return;
        Location f = focus.getLocation();
        turnTowards(f.getX(), f.getZ(), 4);
        lookAt(focus.getEyeLocation());
        List<Player> list = targetable();
        double sx = 0, sz = 0;
        for (Player p : list) {
            sx += p.getLocation().getX();
            sz += p.getLocation().getZ();
        }
        sx /= list.size();
        sz /= list.size();
        double d = Math.hypot(sx - bossX(), sz - bossZ());
        if (d > 11 && ticks >= exposedUntil) glide(bossX() + (sx - bossX()) / d * 0.09, bossZ() + (sz - bossZ()) / d * 0.09);
        if (ticks % 80 == 0) fx.sound(model.socket("head", "mouth"), "boss.breath", 2f, 0.9f + random.nextFloat() * 0.15f);
        if (ticks % 30 == 0) fx.sound(model.socket("heart", "core"), "boss.heartbeat", state.phase() == RaidState.Phase.CORE ? 2.5f : 1.4f, state.phase() == RaidState.Phase.CORE ? 1.25f : 1f);
    }

    private Player nearestOrAny() {
        Player p = nearest(14);
        if (p != null) return p;
        List<Player> list = targetable();
        return list.isEmpty() ? null : list.get(random.nextInt(list.size()));
    }

    private void steerHead() {
        float wantYaw = 0, wantPitch = 0;
        if (lookTarget != null && state.phase() != RaidState.Phase.DYING) {
            Location eyes = model.socket("head", "eyes");
            double dx = lookTarget.getX() - eyes.getX(), dz = lookTarget.getZ() - eyes.getZ(), dy = lookTarget.getY() - eyes.getY();
            double rel = BossGeometry.wrap(BossModel.facingTowards(dx, dz) - model.visualFacing());
            wantYaw = (float) Math.toDegrees(Math.clamp(rel, -0.9, 0.9));
            wantPitch = (float) Math.clamp(Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz))), -25, 30);
        }
        headYaw += (wantYaw - headYaw) * 0.18;
        headPitch += (wantPitch - headPitch) * 0.18;
        model.additive("head", (float) headPitch * 0.6f, (float) headYaw * 0.65f, 0);
        model.additive("neck", (float) headPitch * 0.3f, (float) headYaw * 0.35f, 0);
    }

    private void updateHitboxes() {
        Location low = model.socket("pelvis", "low").add(0, -0.6, 0), high = model.socket("chest", "center");
        if (low.getY() < floorY() + 1.2) low.setY(floorY() + 1.2);
        if (high.getY() < floorY() + 1.6) high.setY(floorY() + 1.6);
        hitboxes.update(low, 2.6, high, 3.4);
    }

    private void meltdownBoundary() {
        if (coreStart < 0) return;
        int t = ticks - coreStart;
        if (meltdownStart < 0 && t >= service.tuning().meltdownAfterTicks()) {
            meltdownStart = ticks;
            cancelMoves();
            model.animator().play("meltdown", ticks);
            fx.sound(model.socket("heart", "core"), "atk.meltdown", 5f, 1f);
            broadcast(Component.text("Total meltdown! Ten seconds to bring the colossus down!", BossHud.DANGER));
            hud.title(players(), BossHud.bold("Total Meltdown", BossHud.DANGER), BossHud.plain("10 seconds", BossHud.ASH), 30);
        }
        if (meltdownStart >= 0) {
            if (ticks % 4 == 0) fx.particle(Particle.END_ROD, model.socket("heart", "core"), 6, 0.6, 0.15);
            if (ticks - meltdownStart >= MELTDOWN_TICKS) defeat("The total meltdown has swallowed the forge.");
            return;
        }
        if (meltStep < MELT_AT.length) {
            int at = MELT_AT[meltStep];
            if (t == at - 100) {
                if (meltMark != null) meltMark.remove();
                meltMark = fx.donut(MELT_KEY[meltStep], arena.x(), arena.z(), 37, BossFx.DANGER);
                fx.sound(arena.center(), "atk.boundary", 5f, 1f);
                broadcast(Component.text("In 5 seconds the rim melts down to radius " + (int) MELT_RADIUS[meltStep] + "!", BossHud.DANGER));
                for (Player p : targetable()) markPlayer(p, "The melting rim advances · move to the center!");
            }
            if (t == at) {
                safeRadius = MELT_RADIUS[meltStep];
                if (meltMark != null) meltMark.color(BossFx.DANGER_HOT);
                meltStep++;
            }
        }
        if (ticks % 10 == 0 && safeRadius < 36)
            for (int i = 0; i < 12; i++) {
                double a = random.nextDouble() * Math.PI * 2, r = safeRadius + random.nextDouble() * (36 - safeRadius);
                fx.particle(Particle.LAVA, floorAt(arena.x() + Math.sin(a) * r, arena.z() + Math.cos(a) * r).add(0, 0.2, 0), 1, 0, 0);
            }
        if (ticks % 20 == 0 && safeRadius < 36)
            for (Player p : targetable())
                if (arena.horizontal(p.getLocation()) > safeRadius * safeRadius) damage(p, damageValue("boundary"), "boundary", true, 0, null);
    }

    public boolean channel(Player player, Location clicked) {
        if (!state.living(player.getUniqueId(), ticks) || !arena.contains(player.getLocation())) return false;
        int n = 0;
        for (UUID target : state.members().keySet()) {
            if (clicked.distanceSquared(arena.soul(n++)) < 5 && state.member(target).status == RaidState.Status.FALLEN) {
                if (state.revives() == 0) {
                    Msg.send(player, "All revive charges are used up.");
                    return true;
                }
                Player fallen = online.get(target);
                if (fallen == null || !fallen.isOnline() || fallen.isDead()) {
                    Msg.send(player, "The fallen player has to respawn first.");
                    return true;
                }
                if (!service.hasPack(fallen)) {
                    Msg.send(player, "The fallen player has to load the resource pack first (/witherboss pack).");
                    return true;
                }
                channels.put(player.getUniqueId(), new Channel(target, ticks, player.getLocation()));
                fx.sound(player.getLocation(), "ui.revive", 0.8f, 0.6f);
                return true;
            }
        }
        return false;
    }

    public void hurt(Player p) {
        if (channels.remove(p.getUniqueId()) != null) hud.action(p, BossHud.plain("Recall interrupted", BossHud.DANGER), ticks, 30);
    }

    private void channels() {
        Iterator<Map.Entry<UUID, Channel>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            Player p = online.get(entry.getKey());
            Channel c = entry.getValue();
            if (p == null || !state.living(entry.getKey(), ticks) || !p.getWorld().equals(c.origin().getWorld()) || p.getLocation().distanceSquared(c.origin()) > 0.09) {
                if (p != null) hud.action(p, BossHud.plain("Recall interrupted · stand still!", BossHud.DANGER), ticks, 30);
                it.remove();
                continue;
            }
            int spent = ticks - c.started();
            int bars = Math.min(10, spent * 10 / service.tuning().reviveChannelTicks());
            hud.action(p, BossHud.plain("Recalling soul ", BossHud.GOLD).append(BossHud.plain("▮".repeat(bars), BossHud.GOLD)).append(BossHud.plain("▯".repeat(10 - bars), BossHud.ASH)), ticks, 5);
            if (spent % 4 == 0) {
                double a = spent * 0.4;
                fx.particle(Particle.SOUL, p.getLocation().add(Math.cos(a) * 0.8, spent / (double) service.tuning().reviveChannelTicks() * 2, Math.sin(a) * 0.8), 2, 0.02, 0.01);
            }
            if (spent < service.tuning().reviveChannelTicks()) continue;
            Player target = online.get(c.target());
            if (target != null && target.isOnline() && !target.isDead() && service.hasPack(target) && state.revive(c.target(), ticks)) {
                target.teleport(arena.entry());
                credit(p);
                ui(target, "ui.revive", 1f, 1f);
                success(p, "Soul recalled · " + state.revives() + " charges left");
                hud.action(target, BossHud.bold("Back in the fight · 3 seconds of protection", BossHud.GOLD), ticks, 60);
                fx.particle(Particle.SOUL_FIRE_FLAME, arena.entry().add(0, 1, 0), 40, 0.6, 0.05);
            }
            it.remove();
        }
        if (ticks % 10 == 0) lanterns();
    }

    private void lanterns() {
        int n = 0;
        for (var e : state.members().entrySet()) {
            Location spot = arena.soul(n++);
            boolean fallen = e.getValue().status == RaidState.Status.FALLEN;
            ItemDisplay lantern = lanterns.get(e.getKey());
            if (fallen && lantern == null && !lanternLabels.containsKey(e.getKey())) {
                lantern = fx.effect("fx/lantern", null, 15);
                if (lantern != null) {
                    BossFx.apply(lantern, fx.effectMatrix("fx/lantern", spot.clone().add(0, 0.05, 0), new Quaternionf(), new Vector3f(1.6f)), 0);
                    lanterns.put(e.getKey(), lantern);
                }
                Player p = online.get(e.getKey());
                lanternLabels.put(e.getKey(), fx.label(spot.clone().add(0, 2.2, 0),
                        BossHud.plain("↺ ", BossHud.GOLD).append(BossHud.bold(p == null ? "Fallen" : p.getName(), Msg.LIGHT))
                                .append(BossHud.plain(" · right-click, stand still for 5 s", BossHud.ASH)), 1.0f));
            } else if (!fallen && (lantern != null || lanternLabels.containsKey(e.getKey()))) {
                if (lantern != null) fx.remove(lantern);
                lanterns.remove(e.getKey());
                TextDisplay label = lanternLabels.remove(e.getKey());
                if (label != null) label.remove();
            }
            if (fallen && ticks % 20 == 0) fx.particle(Particle.SOUL, spot.clone().add(0, 1, 0), 3, 0.2, 0.01);
        }
    }

    private void hudUpdate() {
        hud.health(state, exposed() && state.phase() != RaidState.Phase.CORE ? ticks < exposedUntil : state.phase() == RaidState.Phase.CORE, !vulnerable());
        if (state.phase() == RaidState.Phase.INTRO)
            hud.mechanic(mechanicPreview() ? "Mechanic preview" : "Awakening", null, 1 - ticks / (float) INTRO, BossBar.Color.WHITE);
        else if (endingUntil >= 0) hud.mechanic("Defeat", endingReason, 0, BossBar.Color.RED);
        else if (state.phase() == RaidState.Phase.DYING) hud.mechanic("Victory", "The colossus crumbles", 1, BossBar.Color.YELLOW);
        else if (ticks < transitionUntil) hud.mechanic("Phase change", "Keep your distance", 1 - (ticks - transitionStart) / (float) Math.max(1, transitionUntil - transitionStart), BossBar.Color.WHITE);
        else if (meltdownStart >= 0) hud.mechanic("Total Meltdown", "everything on the heart now!", 1 - (ticks - meltdownStart) / (float) MELTDOWN_TICKS, BossBar.Color.RED);
        else if (ticks < exposedUntil) hud.mechanic("Exhausted", "hit the golden heart!", (exposedUntil - ticks) / 60f, BossBar.Color.YELLOW);
        else if (primary != null && primary.warningPhase()) hud.mechanic(primary.type.label, primary.type.hint, primary.warning(), BossBar.Color.RED);
        else if (primary != null) hud.mechanic(primary.type.label, primary.type.hint, 0, BossBar.Color.RED);
        else if (coreStart >= 0) {
            int left = Math.max(0, service.tuning().meltdownAfterTicks() - (ticks - coreStart)) / 20;
            hud.mechanic("Meltdown in " + Msg.time(left), "safe radius " + (int) safeRadius, left / (service.tuning().meltdownAfterTicks() / 20f), BossBar.Color.PURPLE);
        } else
            hud.mechanic("Revive charges " + state.revives(), "Limit " + Msg.time((timeLimit() - ticks) / 20), 0, BossBar.Color.WHITE);
    }

    private void listeners() {
        List<Player> near = new ArrayList<>();
        for (Player p : arena.world().getPlayers()) if (arena.near(p.getLocation())) near.add(p);
        hud.viewers(near);
        Set<UUID> ids = new HashSet<>();
        for (Player p : near) {
            ids.add(p.getUniqueId());
            if (!music.listening(p.getUniqueId())) music.join(p.getUniqueId(), ticks);
        }
        for (UUID listener : Set.copyOf(music.listeners())) if (!ids.contains(listener)) music.leave(listener);
    }

    public int effects() {
        return fx == null ? 0 : fx.active();
    }

    public List<BossMove> moves() {
        return List.copyOf(moves);
    }

    public BossDirector director() {
        return director;
    }

    void forceAttack(BossDirector.Attack attack) {
        cancelMoves();
        startPrimary(new BossDirector.Choice(attack, null, 0));
    }

    private void restoreAmbience() {
        if (!ambienceSaved) return;
        ambienceSaved = false;
        World w = arena.world();
        if (w == null) return;
        if (savedAdvanceTime != null) w.setGameRule(GameRules.ADVANCE_TIME, savedAdvanceTime);
        if (savedAdvanceWeather != null) w.setGameRule(GameRules.ADVANCE_WEATHER, savedAdvanceWeather);
        w.setTime(savedTime);
        w.setStorm(savedStorm);
    }

    static final double HAMMER_HEAD = BossMove.HAMMER_REACH;

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        state.end();
        channels.clear();
        burns.clear();
        for (BossMove m : moves) {
            try {
                m.cleanup();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            restoreAmbience();
            moves.clear();
            minions.clear(false);
            if (music != null) music.finish();
            if (hitboxes != null) hitboxes.close();
            if (fx != null) fx.close();
            if (model != null) model.close();
            if (hud != null) hud.close();
        } finally {
            try {
                for (Entity entity : owned) if (entity.isValid()) entity.remove();
                owned.clear();
            } finally {
                try {
                    for (Chunk c : tickets) c.removePluginChunkTicket(service.plugin());
                    tickets.clear();
                } finally {
                    for (Player p : online.values()) {
                        if (!returnOrigins.containsKey(p.getUniqueId()) && !p.isDead() && arena.contains(p.getLocation())) p.teleport(arena.waitPoint());
                        restoreParticipant(p);
                    }
                }
            }
        }
    }
}
