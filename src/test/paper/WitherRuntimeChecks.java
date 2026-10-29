package de.nikey.witherchecks;

import de.nikey.witherboss.WitherBossPlugin;
import de.nikey.witherboss.boss.*;

import org.bukkit.*;
import org.bukkit.damage.*;
import org.bukkit.entity.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.BooleanSupplier;

public final class WitherRuntimeChecks extends JavaPlugin {
    private final Map<UUID, GameMode> playerModes = new HashMap<>();
    private final Set<UUID> deadPlayers = new HashSet<>();
    private final Set<UUID> nonAdmins = new HashSet<>();
    private final Map<UUID, double[]> healths = new HashMap<>();
    private final Map<UUID, Vector> velocities = new HashMap<>();
    private final Map<UUID, Player> players = new LinkedHashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();
    private static final double FULL = 100;
    private WitherBossPlugin plugin;
    private WitherArena arena;
    private WitherEncounter fight;
    private int checks, peakEffects, peakEntities;
    private Skeleton kitDummy;
    private Chunk kitChunk;
    private boolean kitChunkForced;

    @Override
    public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> guarded(() -> {
            prepare();
            Bukkit.getScheduler().runTaskLater(this, () -> guarded(() -> {
                run();
            }), 2);
        }), 40);
    }

    private interface Step {
        void run() throws Exception;
    }

    private void guarded(Step step) {
        try {
            step.run();
        } catch (Throwable e) {
            getLogger().log(java.util.logging.Level.SEVERE, "WITHER_RUNTIME_CHECKS_FAIL", e);
            cleanup();
        }
    }

    private void check(boolean value, String description) {
        checks++;
        if (!value) {
            failures.add(description);
            getLogger().warning("CHECK_FAILED " + description);
        }
    }

    private static Object fallback(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) return null;
        if (type == boolean.class) return false;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == long.class) return 0L;
        return 0;
    }

    private Player player(Material held, int number) {
        UUID id = UUID.randomUUID();
        ItemStack[] contents = new ItemStack[41];
        Arrays.fill(contents, ItemStack.empty());
        contents[0] = new ItemStack(held);
        Location[] location = {arena.entry().add(number * 2, 0, 0)};
        double[] health = {FULL, 0};
        healths.put(id, health);
        velocities.put(id, new Vector());
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PlayerInventory.class}, (p, m, a) -> switch (m.getName()) {
            case "getSize" -> 41;
            case "getHeldItemSlot" -> 0;
            case "getItemInMainHand" -> contents[0];
            case "getItemInOffHand" -> contents[40];
            case "getContents", "getStorageContents" -> contents.clone();
            case "getArmorContents" -> Arrays.copyOfRange(contents, 36, 40);
            case "getItem" -> a[0] instanceof Integer slot ? contents[slot] : contents[((EquipmentSlot) a[0]).ordinal()];
            case "setItem" -> {
                contents[a[0] instanceof Integer slot ? slot : ((EquipmentSlot) a[0]).ordinal()] = (ItemStack) a[1];
                yield null;
            }
            case "setItemInMainHand" -> {
                contents[0] = (ItemStack) a[0];
                yield null;
            }
            case "setItemInOffHand" -> {
                contents[40] = (ItemStack) a[0];
                yield null;
            }
            case "firstEmpty" -> {
                int empty = -1;
                for (int i = 0; i < 36; i++)
                    if (contents[i] == null || contents[i].isEmpty()) {
                        empty = i;
                        break;
                    }
                yield empty;
            }
            case "getHolder" -> players.get(id);
            default -> fallback(m.getReturnType());
        });
        Player player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Player.class}, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "RaidCheck" + number;
            case "getWorld" -> location[0].getWorld();
            case "getLocation" -> location[0].clone();
            case "getEyeLocation" -> location[0].clone().add(0, 1.62, 0);
            case "getGameMode" -> playerModes.getOrDefault(id, GameMode.SURVIVAL);
            case "setGameMode" -> {
                playerModes.put(id, (GameMode) a[0]);
                yield null;
            }
            case "isOnline", "isValid", "hasLineOfSight", "canSee" -> true;
            case "hasPermission" -> !nonAdmins.contains(id);
            case "isDead" -> deadPlayers.contains(id);
            case "getInventory" -> inv;
            case "getPersistentDataContainer" -> new ItemStack(Material.PAPER).getItemMeta().getPersistentDataContainer();
            case "getItemOnCursor", "getActiveItem" -> ItemStack.empty();
            case "getActivePotionEffects", "getNearbyEntities" -> List.of();
            case "getHealth" -> health[0];
            case "setHealth" -> {
                health[0] = (double) a[0];
                yield null;
            }
            case "getAbsorptionAmount" -> health[1];
            case "getHeight" -> 1.8;
            case "getVelocity" -> velocities.get(id).clone();
            case "setVelocity" -> {
                velocities.put(id, ((Vector) a[0]).clone());
                yield null;
            }
            case "getBoundingBox" -> new org.bukkit.util.BoundingBox(location[0].getX() - .3, location[0].getY(), location[0].getZ() - .3, location[0].getX() + .3, location[0].getY() + 1.8, location[0].getZ() + .3);
            case "isBlocking" -> held == Material.SHIELD;
            case "teleport" -> {
                location[0] = ((Location) a[0]).clone();
                yield true;
            }
            case "damage" -> {
                double amount = (double) a[0];
                DamageSource source = a.length > 1 && a[1] instanceof DamageSource ds ? ds : DamageSource.builder(DamageType.GENERIC).build();
                Entity causing = source.getCausingEntity();
                EntityDamageEvent e = causing == null
                        ? new EntityDamageEvent((Player) p, EntityDamageEvent.DamageCause.CUSTOM, source, amount)
                        : new EntityDamageByEntityEvent(causing, (Player) p, EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, amount);
                Bukkit.getPluginManager().callEvent(e);
                if (!e.isCancelled()) health[0] = Math.max(1, health[0] - e.getFinalDamage());
                yield null;
            }
            case "equals" -> p == a[0];
            case "hashCode" -> id.hashCode();
            case "toString" -> "RaidCheck " + id;
            default -> fallback(m.getReturnType());
        });
        players.put(id, player);
        return player;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass())
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        throw new NoSuchFieldException(name);
    }

    private void set(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }

    private Object get(Object target, String name) throws Exception {
        return field(target.getClass(), name).get(target);
    }

    private Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass())
            try {
                Method m = c.getDeclaredMethod(name, types);
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (NoSuchMethodException ignored) {
            }
        throw new NoSuchMethodException(name);
    }

    @SuppressWarnings("unchecked")
    private Set<UUID> packLoaded() throws Exception {
        return (Set<UUID>) get(plugin.bosses(), "packLoaded");
    }

    private EntityDamageByEntityEvent hit(Entity attacker, Entity target, double amount) {
        var event = new EntityDamageByEntityEvent(attacker, target, EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.PLAYER_ATTACK).build(), amount);
        Bukkit.getPluginManager().callEvent(event);
        return event;
    }

    private AbstractArrow arrow(Player shooter, Location at) {
        AbstractArrow real = arena.world().spawn(at, Arrow.class);
        entities.add(real);
        real.setGravity(false);
        AbstractArrow proxy = (AbstractArrow) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Arrow.class}, (p, m, a) -> {
            if (m.getName().equals("getShooter")) return shooter;
            try {
                return m.invoke(real, a);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        });
        var pdc = proxy.getPersistentDataContainer();
        return proxy;
    }

    private void prepare() throws Exception {
        plugin = (WitherBossPlugin) Bukkit.getPluginManager().getPlugin("WitherBoss");
        check(plugin != null && plugin.isEnabled(), "Plugin enabled");
        String name = "runtime";
        if (plugin.bosses().store().arenas.containsKey(name)) {
            arena = plugin.bosses().store().arenas.get(name);
            if (arena.world() == null) WitherArenaBuilder.load(name);
        } else {
            arena = WitherArenaBuilder.build(name);
            plugin.bosses().store().arenas.put(name, arena);
        }
        plugin.bosses().store().managedArenas.add(name);
        plugin.bosses().store().save(true);
        check(arena.validate().isEmpty(), "Complete arena blueprint validation: " + arena.validate());
        WitherArena shifted = new WitherArena(name, arena.worldId(), -24.5, 78, 6.5);
        check(!shifted.validate().isEmpty(), "Incorrect stored arena origin reproduces validation error");
        plugin.bosses().store().arenas.put(name, shifted);
        plugin.bosses().store().managedArenas.remove(name);
        call(plugin.bosses(), "restoreArenas", new Class<?>[0]);
        check(plugin.bosses().store().arenas.get(name).equals(arena), "Preset origin automatically repaired");
        check(plugin.bosses().store().managedArenas.contains(name), "Managed world flag restored");
        check(WitherArenaBuilder.build(name).equals(arena), "Building existing preset is idempotent and preserves world");
        org.bukkit.block.Block obstacle = arena.center().add(10, 1, 10).getBlock();
        org.bukkit.block.BlockState original = obstacle.getState();
        obstacle.setType(Material.BASALT, false);
        check(!arena.validate().isEmpty(), "Actual obstacle still rejected");
        original.update(true, false);
        check(arena.validate().isEmpty(), "Canonical arena validates without disabling safety checks");
        arenaDesign();
        kitChunk = arena.center().add(45, 1, 0).getChunk();
        kitChunkForced = kitChunk.isForceLoaded();
        kitChunk.setForceLoaded(true);
        spawnKitDummy();
    }

    private void arenaDesign() {
        World world = arena.world();
        check(WitherArenaBuilder.current(world), "A freshly built arena carries the current design marker");
        check(world.getBlockAt(0, 80, 0).getType() == Material.LODESTONE, "Arena centre keeps its lodestone");
        check(world.getBlockAt(40, 79, 0).getType() == Material.LAVA && world.getBlockAt(0, 79, 40).getType() == Material.LAVA, "The lava moat surrounds the play area");
        check(world.getBlockAt(0, 80, -37).getBlockData() instanceof org.bukkit.block.data.type.Stairs st && st.getFacing() == org.bukkit.block.BlockFace.NORTH, "Gallery stairs climb away from the arena");
        boolean tallNorth = false;
        for (var box : world.getBlockAt(0, 80, -37).getCollisionShape().getBoundingBoxes()) tallNorth |= box.getMaxY() > .99 && box.getMaxZ() <= .5 + 1e-6;
        check(tallNorth, "The tall side of the gallery stairs points away from the arena (walkable climb)");
        check(arena.waitPoint().clone().add(0, -1, 0).getBlock().getType().isSolid() && arena.waitPoint().getBlock().isPassable() && arena.waitPoint().clone().add(0, 1, 0).getBlock().isPassable(), "The waiting point has a floor and two blocks of air");
        check(arena.entry().getBlock().isPassable() && arena.entry().clone().add(0, 1, 0).getBlock().isPassable(), "The entry point is free");
        boolean lavaInGallery = false;
        for (int x = -18; x <= 18; x++)
            for (int z = -48; z <= -36; z++)
                for (int y = 78; y <= 88; y++) lavaInGallery |= world.getBlockAt(x, y, z).getType() == Material.LAVA;
        check(!lavaInGallery, "No lava inside the waiting region");
        long start = System.nanoTime();
        world.getBlockAt(0, WitherArenaDesign.BASE, 0).setType(Material.POLISHED_BLACKSTONE, false);
        world.getBlockAt(10, 85, 10).setType(Material.BASALT, false);
        world.getBlockAt(48, 81, 0).setType(Material.OBSIDIAN, false);
        int[] seal = WitherArenaDesign.seal(0);
        world.getBlockAt(seal[0], 80, seal[1]).setType(Material.POLISHED_BASALT, false);
        check(!WitherArenaBuilder.current(world) && WitherArenaBuilder.preset(arena.name(), world) != null, "An old-design arena is still recognised as a preset");
        WitherArenaBuilder.renovate(world);
        long millis = (System.nanoTime() - start) / 1_000_000;
        getLogger().info("ARENA_RENOVATE_MILLIS " + millis);
        check(WitherArenaBuilder.current(world) && world.getBlockAt(10, 85, 10).getType() == Material.AIR, "Renovating removes old structures and sets the marker");
        check(arena.validate().isEmpty(), "A renovated arena validates: " + arena.validate());
        world.getBlockAt(10, 85, 10).setType(Material.BASALT, false);
        check(!arena.validate().isEmpty(), "A stray block above the play area is detected");
        WitherArenaBuilder.renovate(world);
        check(world.getBlockAt(10, 85, 10).getType() == Material.AIR && arena.validate().isEmpty(), "An explicit rebuild of a current arena clears stray blocks");
        int drift = drift(world);
        check(drift == 0, "The built arena matches the blueprint block for block: " + drift + " differences");
        Bukkit.getScheduler().runTaskLater(this, () -> getLogger().info("ARENA_DRIFT_AFTER_60S " + drift(world)), 1200);
    }

    private int drift(World world) {
        int different = 0;
        for (var entry : WitherArenaDesign.create().cells().entrySet()) {
            long key = entry.getKey();
            Material actual = world.getBlockAt(WitherArenaDesign.keyX(key), WitherArenaDesign.keyY(key), WitherArenaDesign.keyZ(key)).getType();
            Material wanted = entry.getValue().material();
            if (actual != wanted && !(wanted == Material.AIR && actual.isAir())) {
                if (different < 5) getLogger().warning("DRIFT " + WitherArenaDesign.keyX(key) + "/" + WitherArenaDesign.keyY(key) + "/" + WitherArenaDesign.keyZ(key) + " wanted " + wanted + " found " + actual);
                different++;
            }
        }
        return different;
    }

    private void spawnKitDummy() {
        kitDummy = arena.world().spawn(arena.center().add(45, 1, 0), Skeleton.class, e -> {
            e.setAI(false);
            e.setSilent(true);
            e.setInvisible(true);
            e.setLootTable(null);
            Objects.requireNonNull(e.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)).setBaseValue(1000);
            e.setHealth(1000);
        });
        entities.add(kitDummy);
        Material[] kit = {Material.NETHERITE_BOOTS, Material.NETHERITE_LEGGINGS, Material.NETHERITE_CHESTPLATE, Material.NETHERITE_HELMET};
        ItemStack[] armor = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            armor[i] = new ItemStack(kit[i]);
            armor[i].addEnchantment(org.bukkit.enchantments.Enchantment.PROTECTION, 4);
        }
        kitDummy.getEquipment().setArmorContents(armor);
    }

    private WitherEncounter start(Collection<Player> squad, boolean full) throws Exception {
        UUID id = UUID.randomUUID();
        List<UUID> ids = squad.stream().map(Player::getUniqueId).toList();
        WitherEncounter created = new WitherEncounter(plugin.bosses(), id, ids.getFirst(), arena, ids, true);
        if (full) call(created, "fullTest", new Class<?>[0]);
        set(plugin.bosses(), "encounter", created);
        packLoaded().addAll(ids);
        for (Player p : squad) created.reconnect(p);
        created.spawn();
        plugin.bosses().store().attempt(new WitherStore.Attempt(id, ids.getFirst(), ids, arena.name(), "RUNNING", true, new WitherStore.Materials(0, 0, 0, 0)));
        return created;
    }

    private long tagged() {
        return arena.world().getEntities().stream().filter(plugin.bosses()::tagged).count();
    }

    private void tick(int n) {
        for (int i = 0; i < n && fight.active(); i++) {
            fight.tickEncounter();
            peakEffects = Math.max(peakEffects, fight.effects());
        }
    }

    private boolean tickUntil(BooleanSupplier done, int limit) {
        for (int i = 0; i < limit; i++) {
            if (done.getAsBoolean()) return true;
            if (!fight.active()) return false;
            fight.tickEncounter();
            peakEffects = Math.max(peakEffects, fight.effects());
            if (i % 10 == 0) peakEntities = (int) Math.max(peakEntities, tagged());
        }
        return done.getAsBoolean();
    }

    private void hold() throws Exception {
        set(fight, "nextAttack", Integer.MAX_VALUE / 2);
    }

    private BossMove primary() throws Exception {
        return (BossMove) get(fight, "primary");
    }

    private void force(BossDirector.Attack attack) throws Exception {
        hold();
        set(fight, "exposedUntil", 0);
        Method m = WitherEncounter.class.getDeclaredMethod("forceAttack", BossDirector.Attack.class);
        m.setAccessible(true);
        m.invoke(fight, attack);
    }

    private boolean finishMove(int limit) {
        return tickUntil(() -> fight.moves().isEmpty(), limit);
    }

    private void healAll() {
        for (Player p : players.values()) {
            healths.get(p.getUniqueId())[0] = FULL;
            velocities.put(p.getUniqueId(), new Vector());
        }
    }

    private boolean hurt(Player p) {
        return healths.get(p.getUniqueId())[0] < FULL;
    }

    private void place(Player p, double x, double z, double up) {
        Location l = fight.floorAt(x, z).add(0, up, 0);
        l.setDirection(new Vector(fight.bossX() - x, 0, fight.bossZ() - z).lengthSquared() < 1e-6 ? new Vector(0, 0, 1) : new Vector(fight.bossX() - x, 0, fight.bossZ() - z));
        p.teleport(l);
    }

    private void polar(Player p, double angle, double radius, double up) {
        place(p, fight.bossX() + Math.sin(angle) * radius, fight.bossZ() + Math.cos(angle) * radius, up);
    }

    private void park(List<Player> squad) {
        for (int i = 0; i < squad.size(); i++) {
            double a = i * Math.PI * 2 / squad.size();
            place(squad.get(i), arena.x() + Math.sin(a) * 25, arena.z() + Math.cos(a) * 25, 0);
        }
    }

    private void away(Player p, double x, double z, double clearance, int seed) {
        for (int k = 0; k < 64; k++) {
            double a = seed * 1.1 + k * 0.4;
            double px = arena.x() + Math.sin(a) * 25, pz = arena.z() + Math.cos(a) * 25;
            if (Math.hypot(px - x, pz - z) > clearance) {
                place(p, px, pz, 0);
                return;
            }
        }
    }

    private void attackRun(BossDirector.Attack attack, int baseline, int limit) throws Exception {
        peakEffects = 0;
        peakEntities = 0;
        force(attack);
        boolean done = finishMove(limit);
        check(done, "Attack " + attack + " finishes on its own");
        check(peakEffects <= BossFx.EFFECT_BUDGET, "Attack " + attack + " stays inside display budget: " + peakEffects);
        check(peakEntities <= 200, "Attack " + attack + " keeps tagged entities bounded: " + peakEntities);
        tick(25);
        check(fight.effects() <= baseline + 1, "Attack " + attack + " cleans its displays: " + fight.effects() + " vs " + baseline);
        check(fight.hitboxes().count() == 2, "Attack " + attack + " releases every proxy hitbox");
    }

    private void run() throws Exception {
        Player sword = player(Material.DIAMOND_SWORD, 0),
                axe = player(Material.DIAMOND_AXE, 1),
                bow = player(Material.BOW, 2),
                shield = player(Material.SHIELD, 3),
                fifth = player(Material.DIAMOND_SWORD, 4),
                sixth = player(Material.DIAMOND_SWORD, 5);
        List<Player> squad = List.of(sword, axe, bow, shield, fifth, sixth);
        getLogger().info("WITHER_RUNTIME_CONTEXT activePlayerFixtures=" + squad.size() + " networkClients=" + Bukkit.getOnlinePlayers().size());
        plugin.getConfig().set("enrage.max-stages", 0);
        fight = start(squad, true);
        BossRig rig = plugin.bosses().rig();
        long modelled = rig.bones().stream().filter(b -> b.item() != null).count();
        check(fight.model().entities().size() == modelled, "Every modelled bone has one display: " + fight.model().entities().size() + "/" + modelled);
        check(fight.model().entities().stream().allMatch(d -> d.getItemStack().getItemMeta().getItemModel() != null
                        && d.getItemStack().getItemMeta().getItemModel().getNamespace().equals("wither_boss")),
                "Boss displays use the delivered raid item models");
        check(fight.model().entities().stream().allMatch(d -> d.getItemDisplayTransform() == ItemDisplay.ItemDisplayTransform.NONE),
                "Boss displays use the untransformed item context the rig compensates");
        check(fight.model().entities().stream().allMatch(plugin.bosses()::tagged), "Every boss display is tagged for orphan cleanup");
        check(fight.hitboxes().count() == 2, "Body hitboxes spawned");
        Slime body = fight.hitboxes().source();
        var armor = body.getAttribute(org.bukkit.attribute.Attribute.ARMOR);
        check(armor == null || armor.getValue() == 0, "Hitbox slimes carry no vanilla armor that would distort hits");
        check(arena.center().add(2, 5, 2).getBlock().getType() == Material.LIGHT, "Ambient arena light placed");
        check(arena.world().getTime() == 18_000 && Boolean.FALSE.equals(arena.world().getGameRuleValue(GameRules.ADVANCE_TIME)), "Arena night is frozen for the light design");
        check(squad.stream().allMatch(p -> arena.contains(p.getLocation())), "All six fixtures teleported into the arena");
        check(!fight.vulnerable(), "Intro immune");
        double hp = fight.state().health();
        check(hit(sword, body, 6).isCancelled() && fight.state().health() == hp, "Intro hit is rejected");
        check(!fight.damage(sword, 10, "sweep", true, 0, null) && !hurt(sword), "Players cannot be hurt during the intro");
        tick(WitherEncounter.INTRO + 1);
        check(fight.state().phase() == RaidState.Phase.GLUT && fight.vulnerable(), "Intro advances into phase I after 280 ticks");
        hold();
        park(squad);
        tick(5);
        int baseline = fight.effects();

        place(sword, fight.bossX(), fight.bossZ() - 5, 0);
        hp = fight.state().health();
        var first = hit(sword, fight.hitboxes().source(), 6);
        check(!first.isCancelled() && first.getDamage() == .001, "Accepted hits keep melee feedback without duplicate damage");
        check(Math.abs(hp - fight.state().health() - 3) < 1e-9, "Armored body takes 50 percent: " + (hp - fight.state().health()));
        check(hit(sword, axe, 6).isCancelled(), "Raid PvP cancelled");
        Player outsider = player(Material.DIAMOND_SWORD, 9);
        hp = fight.state().health();
        hit(outsider, body, 6);
        check(fight.state().health() == hp, "Outsider cannot damage boss");
        players.remove(outsider.getUniqueId());

        fight.expose(60);
        tick(1);
        Location heart = fight.model().socket("heart", "core");
        Location aim = heart.clone().add(fight.model().forward().multiply(3)).add(0, -1.62, 0);
        aim.setDirection(heart.toVector().subtract(aim.clone().add(0, 1.62, 0).toVector()));
        sword.teleport(aim);
        hp = fight.state().health();
        hit(sword, body, 6);
        check(Math.abs(hp - fight.state().health() - 9) < 1e-9, "Exposed heart takes 150 percent: " + (hp - fight.state().health()));
        aim.setDirection(aim.getDirection().multiply(-1));
        sword.teleport(aim);
        hp = fight.state().health();
        hit(sword, body, 6);
        check(Math.abs(hp - fight.state().health() - 3) < 1e-9, "Looking away from the heart hits armor");

        place(bow, fight.bossX(), fight.bossZ() - 9, 0);
        set(fight, "exposedUntil", 0);
        hp = fight.state().health();
        AbstractArrow plain = arrow(bow, fight.model().socket("chest", "center"));
        hit(plain, body, 6);
        double armored = hp - fight.state().health();
        double after = fight.state().health();
        hit(plain, body, 6);
        check(fight.state().health() == after, "One arrow cannot double book damage");
        fight.expose(60);
        tick(1);
        hp = fight.state().health();
        hit(arrow(bow, fight.model().socket("heart", "core")), body, 6);
        double weak = hp - fight.state().health();
        check(armored > 0 && Math.abs(weak / armored - 3) < 1e-6, "Arrow in the heart socket gets the heart bonus: " + armored + " -> " + weak);
        set(fight, "exposedUntil", 0);

        place(sword, fight.bossX(), fight.bossZ() - 5, 0);
        hp = fight.state().health();
        hit(sword, body, 6);
        check(Math.abs(hp - fight.state().health() - 3) < 1e-9, "Body hits are never reduced by hidden debuffs");
        healAll();
        fight.damage(sword, 10, "beam", true, 0, null);
        check(Math.abs(healths.get(sword.getUniqueId())[0] - (FULL - 10)) < 1e-9, "Boss damage is never raised by hidden debuffs");
        park(squad);

        healAll();
        place(sword, fight.bossX() + fight.model().forward().getX() * 3.5, fight.bossZ() + fight.model().forward().getZ() * 3.5, 0);
        place(axe, fight.bossX() - fight.model().forward().getX() * 5, fight.bossZ() - fight.model().forward().getZ() * 5, 0);
        attackRun(BossDirector.Attack.SWEEP, baseline, 120);
        check(hurt(sword) && !hurt(axe), "Sweep hits inside the telegraphed sector and spares players behind the boss");
        park(squad);

        healAll();
        for (int i = 0; i < squad.size(); i++) polar(squad.get(i), i * Math.PI / 3, 8, i % 2 == 0 ? 1.2 : 0);
        force(BossDirector.Attack.SLAM);
        Player slamTarget = (Player) get(primary(), "target");
        boolean slamDone = finishMove(260);
        check(slamDone, "Slam finishes");
        boolean jumpSafe = true, groundHit = true;
        for (int i = 0; i < squad.size(); i++) {
            Player p = squad.get(i);
            if (p.equals(slamTarget)) continue;
            if (i % 2 == 0) jumpSafe &= !hurt(p);
            else groundHit &= hurt(p);
        }
        check(slamTarget != null && hurt(slamTarget), "Slam impact hits its locked target");
        check(jumpSafe, "Airborne players clear the shockwave");
        check(groundHit, "Grounded players are struck by the shockwave");
        tick(25);
        check(fight.hitboxes().count() == 2 && fight.effects() <= baseline + 1, "Slam cleans up");
        park(squad);
        set(fight, "exposedUntil", 0);

        healAll();
        for (int i = 0; i < squad.size(); i++) polar(squad.get(i), i * Math.PI / 3, 12, 0);
        attackRun(BossDirector.Attack.BEAM, baseline, 220);
        getLogger().info("BEAM_HITS " + squad.stream().filter(this::hurt).count());
        park(squad);

        healAll();
        for (int i = 0; i < squad.size(); i++) polar(squad.get(i), i * Math.PI / 3, 14, 0);
        force(BossDirector.Attack.SKULLS);
        tickUntil(() -> fight.hitboxes().count() > 2, 60);
        int skulls = fight.hitboxes().count() - 2;
        check(skulls > 0, "Skulls spawn hittable proxies: " + skulls);
        List<Slime> proxies = fight.hitboxes().targets().stream().filter(e -> fight.hitboxes().proxy(e)).map(e -> (Slime) e).toList();
        check(fight.shieldPush(shield, proxies.getFirst()), "Shield push deflects a skull");
        for (Slime s : proxies.subList(1, proxies.size())) {
            hit(fifth, s, 6);
            hit(fifth, s, 6);
        }
        tick(2);
        check(fight.hitboxes().count() == 2, "Two hits destroy each remaining skull");
        check(squad.stream().noneMatch(this::hurt), "Destroyed skulls deal no damage");
        finishMove(260);
        tick(25);
        healAll();
        force(BossDirector.Attack.SKULLS);
        finishMove(260);
        check(squad.stream().anyMatch(this::hurt), "Unanswered skulls hit");
        tick(25);
        check(fight.hitboxes().count() == 2 && fight.effects() <= baseline + 1, "Skulls clean up");

        healAll();
        for (int i = 0; i < squad.size(); i++) polar(squad.get(i), i * Math.PI / 3, 9, 0);
        force(BossDirector.Attack.CHAIN);
        tickUntil(() -> fight.hitboxes().count() > 2, 60);
        tick(1);
        List<Slime> locks = fight.hitboxes().targets().stream().filter(e -> fight.hitboxes().proxy(e)).map(e -> (Slime) e).toList();
        check(locks.size() == 2, "Six fighters receive two soul chains: " + locks.size());
        check(locks.stream().allMatch(l -> l.getLocation().getY() - fight.floorY() <= 3.6), "Chain locks hang within melee reach");
        List<?> chains = (List<?>) get(primary(), "chains");
        Set<Player> chained = new HashSet<>();
        for (Object l : chains) chained.add((Player) get(l, "player"));
        List<Player> free = squad.stream().filter(p -> !chained.contains(p)).toList();
        Player cutter = free.getFirst();
        for (int i = 0; i < 3; i++) {
            var e = hit(cutter, locks.get(0), 6);
            check(!e.isCancelled() && e.getDamage() == .001, "Lock hit accepted with feedback");
        }
        fight.axe(axe, locks.get(1));
        hit(cutter, locks.get(1), 6);
        check(fight.hitboxes().count() == 2, "Three lock hits, or an axe stun plus one hit, cut each chain");
        check(tickUntil(fight::exposed, 20), "Cut chains stagger the boss into an exposure window");
        tickUntil(() -> fight.moves().isEmpty() && !fight.exposed(), 200);
        tick(25);
        check(fight.effects() <= baseline + 1, "Chains clean up");

        healAll();
        double before = fight.state().health();
        force(BossDirector.Attack.CHAIN);
        finishMove(240);
        check(fight.state().health() > before, "Uncut chain heals the boss");
        tick(25);
        park(squad);

        newPhaseOneMechanics(squad, baseline);
        hp = fight.state().health();
        fight.hitBody(sword, 1_000_000, null);
        check(fight.state().phase() == RaidState.Phase.HAMMER && Math.abs(fight.state().health() - fight.state().maximum() * 0.66) < 1e-6, "Burst clamps at the 66 percent phase floor");
        check(fight.transitioning() && !fight.vulnerable(), "Phase transition is immune");
        double locked = fight.state().health();
        hit(sword, body, 6);
        check(fight.state().health() == locked, "Transition hits do not book");
        tickUntil(() -> !fight.transitioning(), 400);
        check(fight.vulnerable(), "Phase II opens after the hammer draw");
        hold();
        tick(5);
        baseline = fight.effects();
        newPhaseTwoMechanics(squad, baseline);

        healAll();
        place(sword, fight.bossX(), fight.bossZ() + 2, 0);
        place(axe, fight.bossX() + 7, fight.bossZ(), 0);
        place(bow, fight.bossX() - 13, fight.bossZ(), 0);
        attackRun(BossDirector.Attack.SPIN, baseline, 160);
        check(!hurt(sword) && hurt(axe) && !hurt(bow), "Spin spares the golden centre and the far edge, hits the ring");
        park(squad);

        healAll();
        place(sword, fight.bossX() + 5, fight.bossZ(), 0);
        place(axe, fight.bossX() - 12, fight.bossZ(), 0);
        attackRun(BossDirector.Attack.NOVA, baseline, 160);
        check(hurt(sword) && !hurt(axe), "Nova hits inside radius 9 only");
        park(squad);

        healAll();
        for (int i = 0; i < squad.size(); i++) polar(squad.get(i), i * Math.PI / 3, 10, 0);
        attackRun(BossDirector.Attack.HAMMER, baseline, 220);
        getLogger().info("HAMMER_HITS " + squad.stream().filter(this::hurt).count());
        healAll();
        attackRun(BossDirector.Attack.RAIN, baseline, 300);
        getLogger().info("RAIN_HITS " + squad.stream().filter(this::hurt).count());
        park(squad);

        healAll();
        for (int i = 0; i < squad.size(); i++) polar(squad.get(i), i * Math.PI / 3, 8, 0);
        force(BossDirector.Attack.COMET);
        tick(1);
        double cx = (double) get(primary(), "cx"), cz = (double) get(primary(), "cz");
        check((int) get(primary(), "required") == 3, "Six fighters need three in the comet circle");
        for (int i = 0; i < 6; i++) {
            if (i < 3) place(squad.get(i), cx + (i - 1) * 0.8, cz, 0);
            else away(squad.get(i), cx, cz, 9, i);
        }
        finishMove(200);
        check(squad.subList(0, 3).stream().allMatch(this::hurt) && squad.subList(3, 6).stream().noneMatch(this::hurt), "Shared comet splits damage, no arena pulse");
        check(squad.subList(0, 3).stream().allMatch(p -> Math.abs(FULL - healths.get(p.getUniqueId())[0] - fight.damageValue("comet") / 3) < 1e-6), "Comet damage divided by soakers");
        tick(25);
        healAll();
        force(BossDirector.Attack.COMET);
        tick(1);
        cx = (double) get(primary(), "cx");
        cz = (double) get(primary(), "cz");
        place(sword, cx, cz, 0);
        for (int i = 1; i < 6; i++) away(squad.get(i), cx, cz, 9, i);
        finishMove(200);
        check(squad.stream().allMatch(this::hurt), "Failed soak pulses the raid: "
                + squad.stream().map(p -> healths.get(p.getUniqueId())[0] + "/" + arena.contains(p.getLocation())).toList());
        tick(25);

        healAll();
        for (int i = 0; i < squad.size(); i++) polar(squad.get(i), i * Math.PI / 3, 12, 0);
        force(BossDirector.Attack.BRAND);
        tickUntil(() -> {
            try {
                return !((List<?>) get(primary(), "brands")).isEmpty();
            } catch (Exception e) {
                return true;
            }
        }, 40);
        Set<Player> branded = new HashSet<>();
        for (Object m : (List<?>) get(primary(), "brands")) branded.add((Player) get(m, "player"));
        check(branded.size() == 3, "Brand marks three distinct players");
        finishMove(120);
        check(squad.stream().allMatch(p -> hurt(p) == branded.contains(p)), "Spread brands only hit their carriers");
        tick(25);
        healAll();
        Location stack = fight.floorAt(fight.bossX() + 10, fight.bossZ());
        for (int i = 0; i < squad.size(); i++) place(squad.get(i), stack.getX() + (i % 3) * 0.6, stack.getZ() + (i / 3) * 0.6, 0);
        force(BossDirector.Attack.BRAND);
        finishMove(120);
        check(squad.stream().allMatch(this::hurt), "Stacked brands burst into teammates");
        tick(25);
        check(fight.effects() <= baseline + 1 && fight.hitboxes().count() == 2, "Phase II attacks clean up");
        park(squad);

        ItemStack deathDrop = new ItemStack(Material.DIAMOND, 3);
        PlayerDeathEvent death = new PlayerDeathEvent(axe, DamageSource.builder(DamageType.MOB_ATTACK).build(), new ArrayList<>(List.of(deathDrop)), 10, null);
        death.setKeepInventory(true);
        deadPlayers.add(axe.getUniqueId());
        Bukkit.getPluginManager().callEvent(death);
        check(death.getKeepInventory() && death.getDrops().isEmpty() && death.getItemsToKeep().isEmpty(), "Raid death keeps the inventory safe");
        check(death.getKeepLevel() && death.getDroppedExp() == 0, "Raid death keeps XP");
        check(fight.state().member(axe.getUniqueId()).status == RaidState.Status.FALLEN, "Death tracked as fallen");
        tick(10);
        check(((Map<?, ?>) get(fight, "lanterns")).containsKey(axe.getUniqueId()), "Fallen player gets a revive lantern");
        int index = new ArrayList<>(fight.state().members().keySet()).indexOf(axe.getUniqueId());
        sword.teleport(arena.soul(index));
        fight.channel(sword, arena.soul(index));
        check(((Map<?, ?>) get(fight, "channels")).isEmpty(), "Revive waits until the fallen player respawned");
        deadPlayers.remove(axe.getUniqueId());
        axe.teleport(arena.waitPoint());
        fight.channel(sword, arena.soul(index));
        tick(20);
        sword.teleport(sword.getLocation().add(.4, 0, 0));
        tick(1);
        check(((Map<?, ?>) get(fight, "channels")).isEmpty() && fight.state().revives() == 5, "Moving cancels the revive without spending a charge");
        sword.teleport(arena.soul(index));
        fight.channel(sword, arena.soul(index));
        fight.damage(sword, 1, "skull", false, 0, null);
        check(((Map<?, ?>) get(fight, "channels")).isEmpty() && fight.state().revives() == 5, "Damage cancels the revive without spending a charge");
        fight.channel(sword, arena.soul(index));
        tick(plugin.tuning().reviveChannelTicks() - 1);
        check(fight.state().member(axe.getUniqueId()).status == RaidState.Status.FALLEN, "Revive needs the full five seconds");
        tick(1);
        check(fight.state().member(axe.getUniqueId()).status == RaidState.Status.ALIVE && fight.state().revives() == 4, "Completed revive spends one shared charge");
        check(arena.contains(axe.getLocation()), "Revived player returns into the arena");
        hp = fight.state().health();
        hit(axe, body, 6);
        check(fight.state().health() == hp, "Revived player has three seconds of protection without dealing damage");
        var resurrect = new EntityResurrectEvent(sword, EquipmentSlot.HAND);
        resurrect.setCancelled(false);
        Bukkit.getPluginManager().callEvent(resurrect);
        check(!resurrect.isCancelled(), "Totems of undying are not restricted in the raid");
        var block = new BlockBreakEvent(arena.center().getBlock(), sword);
        Bukkit.getPluginManager().callEvent(block);
        check(block.isCancelled(), "Arena blocks protected");
        plugin.bosses().logout(fifth);
        check(fight.state().member(fifth.getUniqueId()).status == RaidState.Status.DISCONNECTED, "Logout marks the fighter disconnected");
        plugin.bosses().reconnect(fifth);
        check(fight.state().member(fifth.getUniqueId()).status == RaidState.Status.FALLEN && arena.waiting(fifth.getLocation()), "Reconnect within 60 seconds waits for a revive");
        Player late = player(Material.DIAMOND_SWORD, 7);
        packLoaded().add(late.getUniqueId());
        late.teleport(arena.center().add(120, 1, 0));
        plugin.bosses().command(late, new String[] {"join"});
        check(fight.state().member(late.getUniqueId()) != null && arena.contains(late.getLocation()), "Late player joins the running attempt from outside");
        check(plugin.bosses().store().attempts.get(fight.id()).participants().contains(late.getUniqueId()), "Late participant persisted");
        boolean rejected = false;
        try {
            plugin.bosses().command(fifth, new String[] {"join"});
        } catch (IllegalArgumentException error) {
            rejected = true;
        }
        check(rejected && fight.state().revives() == 4, "Join cannot bypass a fall or reset the revive pool");
        check(plugin.bosses().complete(Bukkit.getConsoleSender(), new String[] {"test", arena.name(), ""}).size() == BossDirector.Attack.values().length, "Attack names complete for the test command");
        check(plugin.bosses().complete(Bukkit.getConsoleSender(), new String[] {"arena", "remove", ""}).contains(arena.name()), "Arena command third level completes");

        List<Player> fighters = List.of(sword, axe, bow, shield, sixth, late);
        hold();
        park(fighters);
        fight.hitBody(sword, 1_000_000, null);
        check(fight.state().phase() == RaidState.Phase.CORE && Math.abs(fight.state().health() - fight.state().maximum() * 0.33) < 1e-6, "Burst clamps at the 33 percent phase floor");
        tickUntil(() -> !fight.transitioning(), 400);
        check(fight.vulnerable() && fight.exposed(), "Phase III keeps the heart permanently exposed");
        hold();
        tick(5);
        baseline = fight.effects();
        healAll();
        for (int i = 0; i < fighters.size(); i++) polar(fighters.get(i), i * Math.PI / 3, 10, 0);
        attackRun(BossDirector.Attack.STORM, baseline, 260);
        getLogger().info("STORM_HITS " + fighters.stream().filter(this::hurt).count());
        park(fighters);

        BossGeometry.Pillar pillar = fight.pillars().getFirst();
        double px = (pillar.minX() + pillar.maxX()) / 2, pz = (pillar.minZ() + pillar.maxZ()) / 2;
        double ox = px - arena.x(), oz = pz - arena.z(), ol = Math.hypot(ox, oz);
        ox /= ol;
        oz /= ol;
        fight.model().place(px - ox * 8, pz - oz * 8);
        tick(2);
        healAll();
        place(sword, px + ox * 3, pz + oz * 3, 0);
        place(axe, fight.bossX() - oz * 8, fight.bossZ() + ox * 8, 0);
        attackRun(BossDirector.Attack.MAW, baseline, 160);
        check(!hurt(sword) && hurt(axe), "Pillar cover blocks the maw burst, the open floor does not");
        park(fighters);
        fight.model().place(arena.x(), arena.z() + 4);

        int coreStart = (int) get(fight, "coreStart");
        tickUntil(() -> fight.tick() - coreStart > 1005, 1200);
        check((double) get(fight, "safeRadius") == 30, "Melt boundary shrinks to radius 30");
        healAll();
        place(sword, arena.x() + 33, arena.z(), 0);
        place(axe, arena.x() + 5, arena.z(), 0);
        tick(21);
        check(hurt(sword) && !hurt(axe), "Molten edge hurts outside the safe radius only");
        park(fighters);
        for (Player p : fighters) place(p, arena.x() + Math.sin(fighters.indexOf(p)) * 10, arena.z() + Math.cos(fighters.indexOf(p)) * 10, 0);
        tickUntil(() -> fight.tick() - coreStart > 3005, 2000);
        check((double) get(fight, "safeRadius") == 18, "Melt boundary reaches 24 and then 18");
        int owned = (int) tagged();
        check(owned >= fight.model().entities().size() + 2, "Real Paper displays and hitboxes tracked: " + owned);
        fight.hitBody(sword, 1_000_000, null);
        check(fight.state().phase() == RaidState.Phase.DYING, "Lethal hit starts the death sequence");
        healAll();
        check(!fight.damage(axe, 50, "nova", true, 0, null) && !hurt(axe), "No damage during the death sequence");
        int claims = plugin.bosses().store().claims.size();
        WitherEncounter finished = fight;
        tickUntil(() -> !finished.active(), plugin.bosses().rig().clip("death").length() + 40);
        check(!plugin.bosses().active(), "Death sequence ends the test fight");
        check(plugin.bosses().store().claims.size() == claims && !plugin.bosses().store().defeated(), "Test victory creates no rewards and no server lock");
        cleanupState("victory");

        Zombie natural = (Zombie) arena.world().spawnEntity(arena.center().add(5, 1, 5), EntityType.ZOMBIE, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason.NATURAL);
        check(natural == null || !natural.isValid(), "Natural monster spawns are blocked in raid arena worlds");
        Zombie stray = arena.world().spawn(arena.center().add(6, 1, 6), Zombie.class, z -> z.setAI(false));
        entities.add(stray);
        check(stray.isValid(), "Deliberate spawns stay possible in arena worlds");
        fight = start(List.of(sword, axe), true);
        check(!stray.isValid(), "Starting a fight clears stray monsters from the arena");
        tick(WitherEncounter.INTRO + 2);
        hold();
        for (Player p : List.of(sword, axe)) fight.state().fallen(p.getUniqueId(), false, 0);
        tick(1);
        check(fight.active(), "Wipe plays the defeat sequence first");
        WitherEncounter wiped = fight;
        tickUntil(() -> !wiped.active(), 120);
        check(!plugin.bosses().active() && "DEFEAT".equals(plugin.bosses().store().attempts.get(wiped.id()).status()), "Wipe ends the attempt as a defeat");
        cleanupState("wipe");

        fight = start(List.of(sword), true);
        tick(WitherEncounter.INTRO + 2);
        hold();
        fight.hitBody(sword, 1_000_000, null);
        tickUntil(() -> !fight.transitioning(), 400);
        fight.hitBody(sword, 1_000_000, null);
        tickUntil(() -> {
            try {
                return (int) get(fight, "coreStart") >= 0;
            } catch (Exception e) {
                return true;
            }
        }, 400);
        hold();
        set(fight, "ticks", (int) get(fight, "coreStart") + plugin.tuning().meltdownAfterTicks() - 1);
        tick(2);
        check((int) get(fight, "meltdownStart") >= 0, "Meltdown starts after the phase III timer: " + diag());
        WitherEncounter melted = fight;
        check(melted.vulnerable(), "The boss stays vulnerable during the meltdown countdown");
        tickUntil(() -> !melted.active(), WitherEncounter.MELTDOWN_TICKS + 120);
        check(!plugin.bosses().active(), "Meltdown timeout ends in defeat");
        cleanupState("meltdown");

        fight = start(List.of(sword), false);
        plugin.bosses().stop(true, "Runtime cleanup");
        cleanupState("stop");

        Slime stale = arena.world().spawn(arena.waitPoint(), Slime.class, e -> {
            e.setAI(false);
            e.getPersistentDataContainer().set(plugin.bosses().entityKey(), org.bukkit.persistence.PersistentDataType.STRING, UUID.randomUUID().toString());
        });
        entities.add(stale);
        Slime foreign = arena.world().spawn(arena.waitPoint(), Slime.class, e -> e.setAI(false));
        entities.add(foreign);
        Bukkit.getPluginManager().callEvent(new org.bukkit.event.world.ChunkLoadEvent(stale.getChunk(), false));
        check(!stale.isValid() && foreign.isValid(), "Chunk reload removes only tagged orphan entities");
        foreign.remove();
        calibrate();
        lobbyAndLoot();
        getLogger().info((failures.isEmpty() ? "WITHER_RUNTIME_CHECKS_PASS checks=" : "WITHER_RUNTIME_CHECKS_FAIL failures=" + failures.size() + " checks=") + checks);
        failures.forEach(f -> getLogger().warning("FAILED: " + f));
        cleanup();
    }

    private void lobbyAndLoot() throws Exception {
        WitherService service = plugin.bosses();
        plugin.getConfig().set("event.min-participants", 2);
        plugin.getConfig().set("event.lobby-seconds", 5);
        plugin.getConfig().set("event.cooldown-minutes", 30);
        plugin.getConfig().set("event.refund-on-failed-start", true);
        Player leader = player(Material.DIAMOND_SWORD, 20), friend = player(Material.DIAMOND_SWORD, 21), third = player(Material.DIAMOND_SWORD, 22);
        for (Player p : List.of(leader, friend, third)) {
            packLoaded().add(p.getUniqueId());
            p.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation().add(p.getUniqueId().hashCode() % 3, 0, 0));
        }
        service.store().finished(0);
        java.util.function.Function<UUID, Player> lookup = players::get;
        java.util.function.Supplier<Collection<? extends Player>> everyone = () -> List.copyOf(players.values());
        set(service, "lookup", lookup);
        set(service, "everyone", everyone);
        String blocked = service.summonBlocked(leader.getWorld(), false);
        check(blocked == null || blocked.contains("players must be online"), "Summon conditions are evaluated without errors: " + blocked);

        service.summonByCommand(leader, true);
        check(service.lobbyOpen() && service.inLobby(leader.getUniqueId()), "Staff summon opens the preparation with the leader inside");
        check(service.summonBlocked(leader.getWorld(), true) != null, "A second summoning is refused while one is prepared");
        service.command(friend, new String[] {"join"});
        check(service.inLobby(friend.getUniqueId()), "Join admits a second fighter");
        check(!service.lobby().confirmed(friend.getUniqueId()) || service.lobby().confirmedCount() >= 1, "Preparation tracks agreements");
        service.command(friend, new String[] {"accept"});
        service.command(third, new String[] {"join"});
        service.command(third, new String[] {"leave"});
        check(!service.inLobby(third.getUniqueId()), "Leave removes a fighter from the preparation");
        check(service.lobby().confirmedCount() == 2, "Leader and friend are confirmed: " + service.lobby().confirmedCount());
        tickService(service, 5 * 20 + 3);
        check(service.active() && !service.encounter().preview(), "Enough confirmed fighters start the real fight");
        for (Player p : List.of(leader, friend)) {
            service.encounter().reconnect(p);
            p.teleport(service.encounter().arena().entry());
        }
        check(service.encounter().state().members().size() == 2, "Fight starts with the confirmed fighters");
        double expectedHp = plugin.tuning().healthPerPlayer() * Math.max(plugin.tuning().minScalingPlayers(), 2);
        check(Math.abs(service.encounter().state().maximum() - expectedHp) < 1e-6, "Boss HP follows the tuning: " + service.encounter().state().maximum());
        check(service.encounter().state().revives() == plugin.tuning().reviveCharges(), "Revive pool follows the tuning");
        fight = service.encounter();
        tick(WitherEncounter.INTRO + 2);
        hold();
        UUID attempt = fight.id();
        fight.hitBody(leader, 1_000_000, null);
        tickUntil(() -> !fight.transitioning(), 400);
        fight.hitBody(leader, 1_000_000, null);
        tickUntil(() -> !fight.transitioning(), 400);
        hold();
        fight.state().member(friend.getUniqueId()).damage = 500;
        fight.state().member(leader.getUniqueId()).damage = 900;
        fight.hitBody(leader, 1_000_000, null);
        check(fight.state().phase() == RaidState.Phase.DYING, "Lethal hit ends the real fight in victory");
        int before = service.store().claims.size();
        WitherEncounter finished = fight;
        tickUntil(() -> !finished.active(), plugin.bosses().rig().clip("death").length() + 60);
        check(!service.active(), "Victory closes the fight");
        check("VICTORY".equals(service.store().attempts.get(attempt).status()), "Victory is stored");
        check(service.store().victories() == 1 && service.store().lastFinished() > 0, "Victory count and cooldown timestamp recorded");
        check(service.store().claims.size() > before, "Loot became persistent claims: " + (service.store().claims.size() - before));
        check(service.cooldownSeconds() > 1700, "Cooldown blocks the next summoning: " + service.cooldownSeconds());
        String cooling = service.summonBlocked(leader.getWorld(), false);
        check(cooling != null, "Summoning is refused during the cooldown");
        check(service.summonBlocked(leader.getWorld(), true) == null, "Staff can bypass the cooldown");
        List<String> received = service.rewards().deliver(leader);
        check(!received.isEmpty(), "Delivery reports what the winner received: " + received);
        long deliveredStacks = service.store().claims.values().stream().filter(c -> c.owner.equals(leader.getUniqueId()) && c.state == WitherStore.Delivery.DELIVERED).count();
        check(deliveredStacks > 0, "Loot was delivered to the winner's inventory: " + deliveredStacks);
        boolean titled = service.store().titles.contains(leader.getUniqueId()) && service.store().titles.contains(friend.getUniqueId());
        check(titled, "Both eligible fighters earned the title");
        service.store().finished(0);
        cleanupState("lobby-and-loot");
    }

    private void tickService(WitherService service, int ticks) {
        for (int i = 0; i < ticks; i++) service.tick();
    }

    private double lateral(double x, double z) {
        return Math.hypot(x - fight.bossX(), z - fight.bossZ());
    }

    private void newPhaseOneMechanics(List<Player> squad, int baseline) throws Exception {
        var pillar = fight.pillars().getFirst();
        double homeX = fight.bossX(), homeZ = fight.bossZ(), pillarZ = (pillar.minZ() + pillar.maxZ()) / 2;
        fight.model().place(pillar.maxX() + 9, pillarZ);
        for (int i = 0; i < 6; i++) fight.glide(pillar.maxX() + 3, pillarZ);
        check(fight.bossX() - pillar.maxX() >= 3.7, "The boss keeps clear of the pillars: " + (fight.bossX() - pillar.maxX()));
        fight.model().place(homeX, homeZ);
        Player sword = squad.get(0), axe = squad.get(1), bow = squad.get(2), shield = squad.get(3), fifth = squad.get(4);
        double config = plugin.tuning().healthPerPlayer();
        check(Math.abs(fight.state().maximum() - config * 6) < 1e-6, "Six fighters give six times the health per player: " + fight.state().maximum());
        check(fight.groupSize() == 6 && fight.perGroup(3, 4) == 2 && fight.perGroup(2, 4) == 3, "Group scaling helpers follow the living fighters");

        int stageBase = fight.tick();
        double slam = plugin.tuning().attackDamage("slam") * plugin.tuning().globalDamage() * plugin.tuning().phaseDamage(RaidState.Phase.GLUT);
        check(fight.stage() == 0 && Math.abs(fight.damageValue("slam") - slam) < 1e-9, "Pinned test config keeps stage 0 at configured damage");
        plugin.getConfig().set("enrage.max-stages", 8);
        int per = plugin.tuning().enrageStageTicks();
        set(fight, "ticks", WitherEncounter.INTRO + per * 3 + 5);
        check(fight.stage() == 3 && Math.abs(fight.damageValue("slam") - slam * plugin.tuning().enrageFactor(3)) < 1e-9, "Enrage stage 3 adds its configured damage: " + fight.damageValue("slam"));
        set(fight, "ticks", WitherEncounter.INTRO + per * 40);
        check(fight.stage() == 8 && Math.abs(fight.damageValue("slam") - slam * plugin.tuning().enrageFactor(8)) < 1e-9, "Enrage stages are capped at the configured maximum");
        set(fight, "ticks", stageBase);
        plugin.getConfig().set("enrage.max-stages", 0);

        for (Player p : squad) place(p, fight.bossX() + 4 + squad.indexOf(p) * 0.4, fight.bossZ() + 2, 0);
        healAll();
        for (Player p : List.of(sword, axe, bow)) fight.hitBody(p, 120, null);
        place(shield, fight.bossX() + 30, fight.bossZ(), 0);
        place(fifth, fight.bossX() - 30, fight.bossZ() + 4, 0);
        tick(1);
        check(fight.moves().stream().anyMatch(m -> m.type == BossDirector.Attack.RETRIBUTION), "Three attackers bursting together trigger Vergeltung");
        tick(45);
        check(hurt(sword) && hurt(axe) && hurt(bow) && !hurt(shield) && !hurt(fifth), "Vergeltung hits only the players inside its telegraphed ring");
        for (Player p : List.of(sword, axe, bow)) fight.hitBody(p, 120, null);
        tick(3);
        tick(5);
        check(fight.moves().stream().noneMatch(m -> m.type == BossDirector.Attack.RETRIBUTION), "Vergeltung has a cooldown");
        healAll();
        park(squad);
        tick(20);
        check(fight.effects() <= baseline + 1, "Vergeltung cleans up");

        healAll();
        force(BossDirector.Attack.SPIKES);
        @SuppressWarnings("unchecked")
        List<Player> lineTargets = (List<Player>) get(primary(), "targets");
        check(lineTargets.size() == 2, "Six fighters are chased by two spike lines: " + lineTargets.size());
        park(squad);
        Player chased = lineTargets.getFirst();
        place(chased, fight.bossX() + 12, fight.bossZ() - 4, 0);
        Player safe = squad.stream().filter(p -> !lineTargets.contains(p)).findFirst().orElseThrow();
        place(safe, fight.bossX() - 28, fight.bossZ(), 0);
        peakEffects = 0;
        boolean done = finishMove(200);
        check(done && hurt(chased), "Spike line hits its locked target standing on the path");
        check(peakEffects <= BossFx.EFFECT_BUDGET, "Spikes respect the display budget: " + peakEffects);
        tick(25);
        check(fight.effects() <= baseline + 1 && fight.hitboxes().count() == 2, "Spikes clean up");

        healAll();
        for (int i = 0; i < squad.size(); i++) polar(squad.get(i), i * Math.PI / 3, 12, 0);
        force(BossDirector.Attack.DEBRIS);
        tick(1);
        @SuppressWarnings("unchecked")
        List<Object> aimed = (List<Object>) get(primary(), "rocks");
        int covered = 0;
        for (Player p : squad) {
            boolean near = false;
            for (Object r : aimed) if (Math.hypot((double) get(r, "x") - p.getLocation().getX(), (double) get(r, "z") - p.getLocation().getZ()) <= 6) near = true;
            if (near) covered++;
        }
        check(covered >= squad.size() - 1, "Falling rocks are aimed at the players: " + covered + "/" + squad.size());
        finishMove(260);
        tick(25);
        healAll();
        park(squad);
        force(BossDirector.Attack.DEBRIS);
        tick(1);
        @SuppressWarnings("unchecked")
        List<Object> rocks = (List<Object>) get(primary(), "rocks");
        double rx = (double) get(rocks.getFirst(), "x"), rz = (double) get(rocks.getFirst(), "z");
        check(rocks.size() >= 8, "Six fighters face a barrage of falling rocks: " + rocks.size());
        place(sword, rx, rz, 0);
        Player far = squad.get(5);
        double[] free = {fight.arena().x() + 33, fight.arena().z()};
        for (Object r : rocks) if (Math.hypot((double) get(r, "x") - free[0], (double) get(r, "z") - free[1]) < 5) free = new double[] {fight.arena().x() - 33, fight.arena().z()};
        place(far, free[0], free[1], 0);
        boolean debris = finishMove(260);
        check(debris && hurt(sword) && !hurt(far), "Debris rocks hit players inside their circles only");
        tick(25);
        check(fight.effects() <= baseline + 1, "Debris and rubble displays are removed");

        healAll();
        park(squad);
        force(BossDirector.Attack.MINIONS);
        check(finishMove(200), "Minion summon finishes");
        int spawned = fight.minions().count();
        check(spawned >= 4 && spawned <= 10, "Six fighters face a minion wave: " + spawned);
        List<org.bukkit.entity.WitherSkeleton> list = fight.minions().list();
        check(list.stream().allMatch(m -> plugin.bosses().tagged(m) && plugin.bosses().target(m) && m.isGlowing()), "Minions are tagged, glowing and counted as raid targets");
        check(list.stream().filter(m -> fight.minions().brute(m)).count() == 1, "A brute joins a six-fighter wave");
        org.bukkit.entity.WitherSkeleton grunt = list.stream().filter(m -> !fight.minions().brute(m)).findFirst().orElseThrow();
        healAll();
        place(sword, grunt.getLocation().getX() + 1, grunt.getLocation().getZ(), 0);
        var strike = new EntityDamageByEntityEvent(grunt, sword, EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.MOB_ATTACK).build(), 7);
        Bukkit.getPluginManager().callEvent(strike);
        double expected = fight.damageValue("minion") * plugin.tuning().minionDamage();
        check(strike.isCancelled() && Math.abs(FULL - healths.get(sword.getUniqueId())[0] - expected) < 1e-6, "Minion melee is converted into scaled raid damage: " + (FULL - healths.get(sword.getUniqueId())[0]) + " vs " + expected);
        Bukkit.getPluginManager().callEvent(new EntityDamageByEntityEvent(grunt, sword, EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.MOB_ATTACK).build(), 7));
        check(Math.abs(FULL - healths.get(sword.getUniqueId())[0] - expected) < 1e-6, "Minion strikes respect their own cooldown");
        double life = grunt.getHealth();
        var playerHit = hit(sword, grunt, 6);
        check(!playerHit.isCancelled(), "Raid fighters can hurt minions");
        var envHit = new EntityDamageEvent(grunt, EntityDamageEvent.DamageCause.CUSTOM, DamageSource.builder(DamageType.GENERIC).build(), 5);
        Bukkit.getPluginManager().callEvent(envHit);
        check(envHit.isCancelled(), "Only raid fighters can damage minions");
        check(fight.shieldPush(shield, grunt) == false, "Shield push ignores minions");
        grunt.setHealth(0);
        tick(2);
        check(fight.minions().count() == spawned - 1 && fight.minions().killed() == 1, "Dead minions are counted and removed");
        for (int i = 0; i < 40; i++) tick(10);
        check(fight.minions().count() <= plugin.tuning().minionCap(), "Minion cap holds");
        fight.minions().clear(false);
        check(fight.minions().count() == 0 && arena.world().getEntities().stream().noneMatch(e -> e instanceof org.bukkit.entity.WitherSkeleton && plugin.bosses().tagged(e)), "Clearing minions removes the entities");
        healAll();
        park(squad);
        tick(30);
        check(fight.effects() <= baseline + 1, "Minion summon cleans up");
    }

    private void newPhaseTwoMechanics(List<Player> squad, int baseline) throws Exception {
        Player sword = squad.get(0), axe = squad.get(1), bow = squad.get(2), shield = squad.get(3);
        for (BossDirector.Attack attack : List.of(BossDirector.Attack.GRID)) {
            healAll();
            park(squad);
            force(attack);
            tick(1);
            @SuppressWarnings("unchecked")
            List<List<Object>> waves = (List<List<Object>>) get(primary(), "waves");
            check(waves.size() == 2 && waves.get(0).size() >= 3 && waves.get(1).size() >= 3, "Glutgitter shows two alternating lane groups");
            Object even = waves.get(0).get(waves.get(0).size() / 2), odd = waves.get(1).get(waves.get(1).size() / 2);
            double ex = (double) call(even, "cx", new Class<?>[0]), ez = (double) call(even, "cz", new Class<?>[0]);
            double ox = (double) call(odd, "cx", new Class<?>[0]), oz = (double) call(odd, "cz", new Class<?>[0]);
            place(sword, ex, ez, 0);
            place(axe, ox, oz, 0);
            place(bow, ex, ez, 1.8);
            boolean first = tickUntil(() -> hurt(sword), 120);
            check(first && !hurt(axe) && !hurt(bow), "First grid wave hits its own lanes, spares the other group and jumpers");
            boolean second = tickUntil(() -> hurt(axe), 120);
            check(second, "Second grid wave hits the formerly safe lanes");
            check(finishMove(200), "Glutgitter finishes");
            tick(30);
            check(fight.effects() <= baseline + 1, "Glutgitter walls and lanes are removed: " + fight.effects());
        }

        healAll();
        park(squad);
        force(BossDirector.Attack.MIASMA);
        BossMove miasma = primary();
        tickUntil(() -> {
            try {
                return !((List<?>) get(miasma, "zones")).isEmpty();
            } catch (Exception e) {
                return true;
            }
        }, 60);
        @SuppressWarnings("unchecked")
        List<Object> zones = (List<Object>) get(miasma, "zones");
        double zx = (double) get(zones.getFirst(), "x"), zz = (double) get(zones.getFirst(), "z");
        place(sword, zx, zz, 0);
        place(axe, fight.arena().x() + 33, fight.arena().z(), 0);
        for (Object z : zones) if (Math.hypot((double) get(z, "x") - fight.arena().x() - 33, (double) get(z, "z") - fight.arena().z()) < 7) place(axe, fight.arena().x() - 33, fight.arena().z(), 0);
        check(zones.size() >= 3, "Six fighters face at least three nebula zones: " + zones.size());
        int impact = (int) get(miasma, "impactAt"), miasmaStart = (int) get(miasma, "start");
        tickUntil(() -> fight.tick() - miasmaStart >= impact - 2, 200);
        check(!hurt(sword) && !hurt(axe), "Nebula zones are harmless while telegraphed");
        tickUntil(() -> fight.tick() - miasmaStart >= impact + 3, 20);
        check(fight.moves().stream().anyMatch(m -> m.type == BossDirector.Attack.MIASMA) && primary() == null, "Nebula releases the boss for the next attack");
        healAll();
        double before = healths.get(sword.getUniqueId())[0];
        tick(45);
        check(healths.get(sword.getUniqueId())[0] < before - 3 * 0.9 * fight.damageValue("miasma") * 0.5 && !hurt(axe), "Standing in nebula hurts repeatedly, leaving it is safe");
        check(finishMove(400), "Nebula expires");
        tick(30);
        check(fight.effects() <= baseline + 1, "Nebula cleans up");

        healAll();
        park(squad);
        force(BossDirector.Attack.BLADE);
        tick(1);
        place(sword, fight.bossX() + 11, fight.bossZ(), 0);
        place(axe, fight.bossX() - 11, fight.bossZ() + 2, 0);
        place(shield, fight.arena().x() + 31, fight.arena().z() + 14, 0);
        int bladeStart = fight.tick();
        while (fight.moves().size() > 0 && fight.tick() - bladeStart < 260) {
            place(axe, fight.bossX() - 11, fight.bossZ() + 2, 1.6);
            fight.tickEncounter();
            peakEffects = Math.max(peakEffects, fight.effects());
        }
        check(hurt(sword), "Kettenrad hits grounded players on the circle");
        check(!hurt(axe) && !hurt(shield), "Kettenrad is jumpable and spares players outside its reach");
        check(fight.model().animator().action() == null || !fight.model().animator().action().equals("lash"), "Kettenrad releases the boss pose");
        tick(30);
        check(fight.effects() <= baseline + 1, "Kettenrad cleans up");

        healAll();
        park(squad);
        force(BossDirector.Attack.BEACONS);
        tick(2);
        @SuppressWarnings("unchecked")
        List<Object> beacons = new ArrayList<>((List<Object>) get(primary(), "beacons"));
        check(beacons.size() == 4 && Math.abs(fight.shield() - 0.35) < 1e-9, "Six fighters face four beacons and a 65 percent shield");
        place(sword, fight.bossX() + 6, fight.bossZ(), 0);
        double hpBefore = fight.state().health();
        fight.hitBody(sword, 100, null);
        check(Math.abs(hpBefore - fight.state().health() - 100 * 0.5 * 0.35) < 1e-6, "Beacon shield reduces boss damage: " + (hpBefore - fight.state().health()));
        double lastShield = fight.shield();
        for (Object b : beacons) {
            org.bukkit.entity.Slime proxy = (org.bukkit.entity.Slime) get(b, "proxy");
            for (int i = 0; i < 12 && !(boolean) get(b, "down"); i++) {
                hit(bow, proxy, 60);
            }
            check((boolean) get(b, "down") && fight.shield() > lastShield - 1e-9, "Beacon falls to focused fire and weakens the shield");
            lastShield = fight.shield();
        }
        check(Math.abs(fight.shield() - 1.0) < 1e-9, "Shield is gone with the last beacon");
        check(tickUntil(fight::exposed, 25), "Destroying all beacons exposes the boss");
        check(finishMove(100), "Beacon phase ends after the last beacon");
        check(fight.hitboxes().count() == 2, "Beacon hitboxes are released");
        tick(80);
        check(fight.effects() <= baseline + 1, "Beacon displays are removed");

        healAll();
        park(squad);
        force(BossDirector.Attack.BEACONS);
        tick(2);
        place(sword, fight.bossX() + 4, fight.bossZ(), 0);
        for (Player p : List.of(axe, bow, shield)) place(p, fight.bossX() - 9, fight.bossZ() + 3, 0);
        double healthBefore = fight.state().health();
        for (int i = 0; i < 96; i++) tick(10);
        check(squad.subList(0, 4).stream().filter(this::hurt).count() >= 3, "Overloaded beacons hurt the unprotected raid");
        check(Math.abs(fight.shield() - 1.0) < 1e-9 && fight.hitboxes().count() == 2 && fight.moves().isEmpty(), "Overload ends the beacon phase cleanly");
        check(fight.state().health() >= healthBefore, "Overloaded beacons feed the boss");
        healAll();
        park(squad);
        tick(80);
        check(fight.effects() <= baseline + 1, "Overload cleans up");
    }

    private String diag() {
        try {
            return "phase=" + fight.state().phase() + " tick=" + fight.tick() + " active=" + fight.active() + " targetable=" + fight.targetable().size()
                    + " next=" + get(fight, "nextAttack") + " primary=" + primary() + " moves=" + fight.moves().size() + " transitionUntil=" + get(fight, "transitionUntil")
                    + " coreStart=" + get(fight, "coreStart") + " health=" + fight.state().health() + " members=" + fight.state().members().values().stream().map(m -> m.status).toList()
                    + " history=" + fight.director().history();
        } catch (Exception e) {
            return e.toString();
        }
    }

    private void cleanupState(String label) {
        check(arena.world().getEntities().stream().noneMatch(plugin.bosses()::tagged), label + ": every own entity removed");
        check(arena.world().getPluginChunkTickets().getOrDefault(plugin, List.of()).isEmpty(), label + ": chunk tickets released");
        check(arena.center().add(2, 5, 2).getBlock().getType() != Material.LIGHT, label + ": temporary light blocks removed");
        check(fight.music().listeners().isEmpty(), label + ": music listeners released");
        check(fight.effects() == 0, label + ": effect displays released");
    }

    private void calibrate() {
        if (!kitDummy.isValid()) spawnKitDummy();
        Skeleton dummy = kitDummy;
        var armor = dummy.getAttribute(org.bukkit.attribute.Attribute.ARMOR);
        var toughness = dummy.getAttribute(org.bukkit.attribute.Attribute.ARMOR_TOUGHNESS);
        armor.setBaseValue(20 - armor.getValue() + armor.getBaseValue());
        toughness.setBaseValue(12 - toughness.getValue() + toughness.getBaseValue());
        check(armor.getValue() == 20 && toughness.getValue() == 12, "Reference armor 20 / toughness 12 on a Paper damage dummy");
        StringBuilder table = new StringBuilder();
        for (String key : de.nikey.witherboss.config.Tuning.ATTACK_DAMAGE) {
            for (RaidState.Phase phase : List.of(RaidState.Phase.GLUT, RaidState.Phase.HAMMER, RaidState.Phase.CORE)) {
                double raw = plugin.tuning().damage(key, phase, plugin.tuning().enrageMaxStages());
                dummy.setHealth(1000);
                dummy.setAbsorptionAmount(0);
                dummy.setNoDamageTicks(0);
                dummy.damage(raw, DamageSource.builder(DamageType.MOB_ATTACK).build());
                double lost = 1000 - dummy.getHealth();
                table.append(' ').append(key).append(phase.name().charAt(0)).append('=').append(Math.round(raw * 10) / 10.0).append("->").append(Math.round(lost * 10) / 10.0);
                double limit = phase == RaidState.Phase.CORE ? 14 : phase == RaidState.Phase.HAMMER ? 16 : 20;
                check(lost >= 0.5 && lost < limit, "Full netherite P4 reference keeps " + key + " in " + phase + " survivable (under " + limit + "): " + lost);
            }
        }
        getLogger().info("RAID_KIT_DAMAGE" + table);
        dummy.remove();
    }

    private void cleanup() {
        try {
            if (plugin != null && plugin.bosses() != null) plugin.bosses().stop(true, "Runtime final cleanup");
        } catch (Throwable ignored) {
        }
        entities.forEach(Entity::remove);
        if (kitChunk != null) kitChunk.setForceLoaded(kitChunkForced);
    }
}
