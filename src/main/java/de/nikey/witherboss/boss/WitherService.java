package de.nikey.witherboss.boss;

import de.nikey.witherboss.WitherBossPlugin;
import de.nikey.witherboss.config.Tuning;
import de.nikey.witherboss.loot.LootTable;
import de.nikey.witherboss.ui.Msg;

import net.kyori.adventure.resource.*;
import net.kyori.adventure.text.Component;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.*;
import org.bukkit.entity.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.net.URI;
import java.time.*;
import java.util.*;

public final class WitherService implements AutoCloseable {
    public enum Trigger {
        SIGIL,
        WITHER_BUILD,
        COMMAND
    }

    public static final String DEFAULT_PACK_URL = "https://raw.githubusercontent.com/NikeyV1/AshColossus/master/resource-pack/WitherBoss-Pack-1.0.0.zip";

    private final WitherBossPlugin plugin;
    private final WitherStore store;
    private final WitherRewards rewards;
    private final NamespacedKey entityKey;
    private final BossRig rig;
    private final Set<UUID> packLoaded = new HashSet<>(), packRequested = new HashSet<>(), agreeOnPack = new HashSet<>();
    private String packHash = "";

    private record PendingTest(String arena, BossDirector.Attack attack) {}

    private final Map<UUID, PendingTest> pendingTests = new HashMap<>();
    private final Map<UUID, Location> pendingReturns = new HashMap<>();
    private final Map<UUID, Long> trophyCooldown = new HashMap<>();
    private WitherEncounter encounter;
    private WitherLobby lobby;
    private java.util.function.Function<UUID, Player> lookup = Bukkit::getPlayer;
    private java.util.function.Supplier<Collection<? extends Player>> everyone = Bukkit::getOnlinePlayers;
    private java.util.function.Supplier<Instant> clock = Instant::now;
    private PackHost packHost;

    public WitherService(WitherBossPlugin plugin) throws Exception {
        this.plugin = plugin;
        entityKey = new NamespacedKey(plugin, "boss-entity");
        store = new WitherStore(plugin.getDataFolder().toPath());
        rewards = new WitherRewards(plugin, store);
        try (var rigStream = Objects.requireNonNull(plugin.getResource("boss-rig.json"));
                var animStream = Objects.requireNonNull(plugin.getResource("boss-animations.json"))) {
            rig = BossRig.load(rigStream, animStream);
        }
        restoreArenas();
        store.recover(tuning().refundOnFailedStart());
        for (World world : Bukkit.getWorlds())
            for (Entity e : world.getEntities()) if (tagged(e)) e.remove();
        installPack();
        Bukkit.getScheduler().runTask(plugin, this::prepareArena);
        announce();
    }

    private void installPack() {
        try {
            packHash = PackInstaller.install(plugin, config().getString("resource-pack.sha1", "auto"));
            startHost();
        } catch (Exception e) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Resource pack could not be installed: " + e.getMessage());
        }
    }

    private void startHost() throws Exception {
        if (!config().getBoolean("resource-pack.enabled", true) || !config().getBoolean("resource-pack.self-host.enabled", false)) return;
        packHost = PackHost.start(
                config().getString("resource-pack.self-host.bind-address", "0.0.0.0"),
                config().getInt("resource-pack.self-host.port", 25663),
                packHash,
                plugin.getDataFolder().toPath().resolve(PackInstaller.FILE));
        plugin.getLogger().info("Hosting the resource pack on port " + packHost.port() + ". Open that TCP port in your firewall and set resource-pack.self-host.public-address.");
        if (config().getString("resource-pack.self-host.public-address", "").isBlank())
            plugin.getLogger().warning("resource-pack.self-host.public-address is empty, players cannot download the pack yet.");
    }

    private void announce() {
        var log = plugin.getLogger();
        if (!enabled()) {
            log.info("The Ash Colossus is disabled (general.enabled: false). Withers behave like vanilla.");
            return;
        }
        if (oneTimeLocked()) {
            log.info("The Ash Colossus was already defeated and event.one-time-only is on. Withers behave like vanilla.");
            return;
        }
        log.info("Ash Colossus ready. Trigger: " + trigger() + ". " + tuning().describe(5));
        if (packUrl().isBlank() && config().getBoolean("resource-pack.enabled", true))
            log.warning("No resource pack URL is available. Players would see the boss without models. See resource-pack in config.yml.");
    }

    private void prepareArena() {
        if (!enabled()) return;
        try {
            readyArena(null, false);
        } catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "The boss arena could not be prepared: " + error.getMessage());
        }
    }

    public void reloadPack() {
        if (packHost != null) packHost.close();
        packHost = null;
        installPack();
    }

    private void restoreArenas() {
        boolean changed = false;
        for (WitherArena stored : List.copyOf(store.arenas.values())) {
            World world = stored.world();
            if (world == null && WitherArenaBuilder.existing(stored.name())) {
                World loaded = WitherArenaBuilder.load(stored.name());
                if (loaded.getUID().equals(stored.worldId()) || WitherArenaBuilder.preset(stored.name(), loaded) != null) world = loaded;
            }
            WitherArena preset = WitherArenaBuilder.preset(stored.name(), world);
            if (preset != null && (!preset.equals(stored) || !store.managedArenas.contains(stored.name()))) {
                store.arenas.put(stored.name(), preset);
                store.managedArenas.add(stored.name());
                changed = true;
                plugin.getLogger().info("Preset arena " + stored.name() + " was corrected to 0.5/80/0.5.");
            }
        }
        if (changed) store.save(true);
    }

    private WitherArena readyArena(String requested, boolean testing) {
        restoreArenas();
        String name = requested == null ? config().getString("general.arena", "forge") : requested;
        WitherArena result = store.arenas.get(name);
        if (result == null) {
            result = WitherArenaBuilder.build(name);
            store.arenas.put(name, result);
            store.managedArenas.add(name);
            store.save(true);
        }
        if (store.managedArenas.contains(result.name()) && result.world() != null && !active() && lobby == null && !WitherArenaBuilder.current(result.world())) {
            plugin.getLogger().info("Rebuilding arena " + result.name() + " to the current design.");
            WitherArenaBuilder.renovate(result.world());
        }
        List<String> errors = result.validate();
        if (!errors.isEmpty() && requested == null && (testing || store.managedArenas.contains(name))) {
            result = WitherArenaBuilder.build("solo_test");
            store.arenas.put(result.name(), result);
            store.managedArenas.add(result.name());
            store.save(true);
            errors = result.validate();
        }
        if (!errors.isEmpty()) throw new IllegalArgumentException("Arena " + result.name() + ": " + errors.getFirst());
        return result;
    }

    public boolean enabled() {
        return config().getBoolean("general.enabled", true);
    }

    public Trigger trigger() {
        try {
            return Trigger.valueOf(config().getString("event.trigger", "SIGIL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Trigger.SIGIL;
        }
    }

    public boolean oneTimeLocked() {
        return tuning().oneTimeOnly() && store.defeated();
    }

    public boolean blocksVanillaWither() {
        return enabled() && trigger() == Trigger.WITHER_BUILD && !oneTimeLocked() && !config().getBoolean("event.allow-vanilla-when-unavailable", false);
    }

    public boolean lobbyOpen() {
        return lobby != null;
    }

    public boolean inLobby(UUID player) {
        return lobby != null && lobby.has(player);
    }

    public WitherLobby lobby() {
        return lobby;
    }

    public WitherSummoning.Window window() {
        ZoneId zone;
        try {
            zone = ZoneId.of(config().getString("event.time-window.timezone", "UTC"));
        } catch (RuntimeException e) {
            zone = ZoneOffset.UTC;
        }
        return new WitherSummoning.Window(
                config().getBoolean("event.time-window.enabled", false),
                WitherSummoning.parseTime(config().getString("event.time-window.from", "00:00"), LocalTime.MIN),
                WitherSummoning.parseTime(config().getString("event.time-window.to", "23:59"), LocalTime.MAX),
                zone);
    }

    public long cooldownSeconds() {
        long until = store.lastFinished() + tuning().cooldownMinutes() * 60_000L;
        return Math.max(0, (until - clock.get().toEpochMilli()) / 1000);
    }

    public String summonBlocked(World world, boolean bypassConditions) {
        if (oneTimeLocked()) return "The Ash Colossus has already been defeated on this server.";
        List<String> worlds = config().getStringList("event.allowed-worlds");
        if (!worlds.isEmpty() && world != null && worlds.stream().noneMatch(w -> w.equalsIgnoreCase(world.getName())))
            return "The Ash Colossus cannot be summoned in this world.";
        if (bypassConditions) return active() || lobby != null ? "A boss event is already in progress." : null;
        return WitherSummoning.blocked(
                active() || lobby != null,
                config().getBoolean("test.enabled", false),
                everyone.get().size(),
                tuning().minOnline(),
                clock.get().atZone(window().zone()),
                window(),
                cooldownSeconds());
    }

    public EquipmentSlot sigilSlot(Player player, EquipmentSlot used) {
        EquipmentSlot other = used == EquipmentSlot.OFF_HAND ? EquipmentSlot.HAND : EquipmentSlot.OFF_HAND;
        return plugin.sigil().is(player.getInventory().getItem(other)) ? other : null;
    }

    public void summon(Player p, WitherSummoning.Structure structure, org.bukkit.block.data.BlockData replaced, EquipmentSlot hand, EquipmentSlot sigilHand) {
        String blocked = summonBlocked(p.getWorld(), false);
        if (blocked != null) throw new IllegalArgumentException(blocked);
        checkEligible(p);
        WitherArena arena = readyArena(null, false);
        if (!structure.unchanged(replaced)) throw new IllegalArgumentException("The summoning structure changed, the blocks stay where they are.");
        ItemStack skull = p.getInventory().getItem(hand);
        if (skull.getType() != Material.WITHER_SKELETON_SKULL || skull.getAmount() < 1)
            throw new IllegalArgumentException("The last Wither skeleton skull is missing in the hand you used.");
        if (sigilHand != null && !plugin.sigil().is(p.getInventory().getItem(sigilHand)))
            throw new IllegalArgumentException("The Colossus Sigil is gone.");
        WitherStore.Materials materials = new WitherStore.Materials(3, structure.sand(), structure.soil(), sigilHand != null ? 1 : 0);
        Location center = structure.placed().getLocation().add(0.5, 0.5, 0.5);
        openLobby(p, arena, materials, center, () -> {
            p.getInventory().setItem(hand, skull.asQuantity(skull.getAmount() - 1));
            if (sigilHand != null) {
                ItemStack sigil = p.getInventory().getItem(sigilHand);
                p.getInventory().setItem(sigilHand, sigil.asQuantity(sigil.getAmount() - 1));
            }
            structure.consume();
            p.saveData();
        });
    }

    public void summonByCommand(Player p, boolean bypassConditions) {
        if (!enabled()) throw new IllegalArgumentException("The Ash Colossus is disabled in config.yml.");
        String blocked = summonBlocked(p.getWorld(), bypassConditions);
        if (blocked != null) throw new IllegalArgumentException(blocked);
        checkEligible(p);
        WitherArena arena = readyArena(null, false);
        openLobby(p, arena, new WitherStore.Materials(0, 0, 0, 0), p.getLocation(), () -> {});
    }

    private void openLobby(Player p, WitherArena arena, WitherStore.Materials materials, Location center, Runnable consume) {
        UUID id = UUID.randomUUID();
        int seconds = tuning().lobbySeconds();
        int minimum = tuning().minParticipants();
        WitherLobby opened = new WitherLobby(id, p.getUniqueId(), arena.name(), materials, seconds * 20, minimum);
        store.attempt(new WitherStore.Attempt(id, p.getUniqueId(), List.of(p.getUniqueId()), arena.name(), "PREPARED", false, materials));
        lobby = opened;
        try {
            admit(p, arena, true);
            consume.run();
            store.status(id, "RUNNING");
        } catch (Throwable error) {
            WitherLobby failed = lobby;
            lobby = null;
            if (failed != null) {
                Location origin = failed.drop(p.getUniqueId());
                if (origin != null) returnAfterFight(p, origin);
            }
            store.forfeit(id);
            throw error;
        }
        double radius = tuning().joinRadius();
        for (Player other : List.copyOf(everyone.get())) {
            if (other.equals(p) || !center.getWorld().equals(other.getWorld()) || other.getLocation().distanceSquared(center) > radius * radius) continue;
            try {
                admit(other, arena, config().getBoolean("event.auto-confirm-nearby", true));
            } catch (IllegalArgumentException refused) {
                Msg.send(other, "A boss fight is starting nearby, but you were not pulled in: " + refused.getMessage());
            } catch (RuntimeException error) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "A player could not be pulled into the arena.", error);
            }
        }
        if (config().getBoolean("event.announce-summon", true)) {
            Component call = summonCall(p.getName(), seconds, minimum);
            for (Player online : List.copyOf(everyone.get()))
                if (!lobby.has(online.getUniqueId())) {
                    online.sendMessage(call);
                    online.playSound(online, Sound.ENTITY_WITHER_SPAWN, SoundCategory.MASTER, .35f, .7f);
                }
        }
        plugin.discord().bossSummoned(p.getName(), seconds, minimum, lobby.size());
    }

    private static Component summonCall(String summoner, int seconds, int minimum) {
        return Msg.title("✦ " + summoner + " has awakened the Ash Colossus! ")
                .append(Msg.text("The fight is decided in " + seconds + " seconds (at least " + minimum + " fighters). "))
                .append(button("[Join]", "/witherboss join", "At your own risk"))
                .append(Msg.text(" at your own risk."));
    }

    private void rehearse(Player p, String secondsArgument) {
        if (active() || lobby != null) throw new IllegalArgumentException("A fight or preparation is already running.");
        int seconds = secondsArgument == null ? tuning().lobbySeconds() : Integer.parseInt(secondsArgument);
        if (seconds < 5 || seconds > 300) throw new IllegalArgumentException("Seconds: 5 to 300.");
        WitherArena arena = readyArena(null, false);
        UUID id = UUID.randomUUID();
        WitherStore.Materials none = new WitherStore.Materials(0, 0, 0, 0);
        WitherLobby opened = new WitherLobby(id, p.getUniqueId(), arena.name(), none, seconds * 20, 1).rehearsal(true);
        store.attempt(new WitherStore.Attempt(id, p.getUniqueId(), List.of(p.getUniqueId()), arena.name(), "RUNNING", true, none));
        lobby = opened;
        try {
            admit(p, arena, false);
        } catch (Throwable error) {
            lobby = null;
            store.forfeit(id);
            throw error;
        }
        Msg.send(p, "Rehearsal: only you, no announcement, no materials, no rewards, no cooldown. This is how the server message looks:");
        p.sendMessage(summonCall(p.getName(), seconds, tuning().minParticipants()));
        Msg.send(p, "Accepting starts the fight alone (rehearsal minimum 1). /witherboss stop cancels it.");
    }

    private void admit(Player o, WitherArena arena, boolean agreed) {
        if (lobby == null) throw new IllegalArgumentException("There is no boss fight right now.");
        if (lobby.has(o.getUniqueId())) throw new IllegalArgumentException("You are already in the arena.");
        checkEligible(o);
        Location origin = o.getLocation().clone();
        o.setGliding(false);
        o.leaveVehicle();
        o.eject();
        if (!o.teleport(arena.waitPoint()))
            throw new IllegalArgumentException(o.getName() + ": another plugin prevented the teleport to the arena.");
        boolean packReady = hasPack(o);
        lobby.add(o.getUniqueId(), origin, agreed && packReady);
        if (agreed && !packReady) agreeOnPack.add(o.getUniqueId());
        if (!packReady) {
            offerPack(o);
            Msg.send(o, "Accept the server resource pack, otherwise you cannot fight.");
        }
        store.participant(lobby.id(), o.getUniqueId());
        Component leave = button("[Leave]", "/witherboss leave", "Back to where you came from");
        int seconds = Math.max(1, lobby.remaining() / 20);
        if (agreed)
            o.sendMessage(Msg.title("✦ You are fighting. ").append(Msg.text("The fight is decided in " + seconds + " seconds, at your own risk. ")).append(leave));
        else
            o.sendMessage(
                    Msg.title("✦ You were pulled into the arena. ")
                            .append(Msg.text("A boss fight is about to begin. Decide within " + seconds + " seconds whether you fight (at your own risk). Without agreeing you are sent back. "))
                            .append(button("[Fight]", "/witherboss accept", "Take part in the fight"))
                            .append(Msg.text(" "))
                            .append(leave));
        if (lobby.leader().equals(o.getUniqueId()) && !lobby.rehearsal() && !tuning().refundOnFailedStart())
            Msg.send(o, "The summoning materials are used up and are not returned, even if the fight does not take place.");
    }

    private static Component button(String label, String command, String hover) {
        return Msg.title(label)
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand(command))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Msg.text(hover)));
    }

    private void accept(Player p) {
        if (lobby == null || !lobby.has(p.getUniqueId())) throw new IllegalArgumentException("You are not in a boss fight preparation.");
        if (!hasPack(p)) {
            agreeOnPack.add(p.getUniqueId());
            if (!packRequested.contains(p.getUniqueId())) offerPack(p);
            Msg.send(p, "Agreement noted: it counts as soon as the server resource pack is loaded.");
            return;
        }
        if (!lobby.confirm(p.getUniqueId())) {
            Msg.send(p, "You have already agreed.");
            return;
        }
        Msg.send(p, "Agreed · " + lobby.confirmedCount() + " of at least " + lobby.minimum() + " fighters confirmed.");
    }

    private void lobbyLeave(Player p) {
        Location origin = lobby.remove(p.getUniqueId());
        if (origin == null) return;
        returnAfterFight(p, origin);
        Msg.send(p, lobby != null && p.getUniqueId().equals(lobby.leader()) ? "You left the boss fight." : "You left the boss fight.");
    }

    private void lobbyTick() {
        WitherLobby l = lobby;
        WitherArena arena = store.arenas.get(l.arena());
        for (UUID id : l.members()) {
            Player o = lookup.apply(id);
            if (o == null || !o.isOnline()) {
                Location origin = l.remove(id);
                if (origin != null) pendingReturns.put(id, origin);
            } else if (o.isDead() || arena == null || !o.getWorld().getUID().equals(arena.worldId()) || !arena.contains(o.getLocation()) && !arena.waiting(o.getLocation())) {
                Location origin = l.remove(id);
                if (origin != null && !o.isDead()) {
                    returnAfterFight(o, origin);
                    Msg.send(o, "You left the arena and cancelled the boss fight.");
                }
            }
        }
        if (l.remaining() == 1) revalidate(l);
        int ticks = l.remaining();
        if (ticks % 20 == 0 && ticks > 0)
            for (UUID id : l.members()) {
                Player o = lookup.apply(id);
                if (o == null) continue;
                boolean agreed = l.confirmed(id);
                o.sendActionBar(
                        agreed
                                ? Msg.title("✦ Boss fight in " + ticks / 20 + " s ").append(Msg.text("· " + l.confirmedCount() + "/" + l.minimum() + " confirmed · /witherboss leave to cancel"))
                                : Msg.title("✦ Agree within " + ticks / 20 + " s ").append(Msg.text("· /witherboss accept, otherwise you go back")));
                if (!agreed && (ticks == l.total() / 2 || ticks == 200) && ticks < l.total())
                    o.sendMessage(
                            Msg.title("✦ " + ticks / 20 + " seconds left: ")
                                    .append(button("[Fight]", "/witherboss accept", "Take part in the fight"))
                                    .append(Msg.text(" or "))
                                    .append(button("[Leave]", "/witherboss leave", "Back to where you came from")));
                if (ticks <= 60) o.playSound(o.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, SoundCategory.MASTER, 0.8f, 0.6f + (60 - ticks) / 120f);
            }
        switch (l.tick()) {
            case WAITING -> {}
            case START -> startFromLobby();
            case TOO_FEW -> abortLobby("The boss fight does not take place: fewer than " + l.minimum() + " confirmed fighters are in the arena.");
            case CANCELLED -> abortLobby("The boss fight was cancelled.");
        }
    }

    private void revalidate(WitherLobby l) {
        for (UUID id : l.members()) {
            Player o = lookup.apply(id);
            if (o == null) continue;
            try {
                checkPlayer(o);
            } catch (IllegalArgumentException refused) {
                Location origin = l.drop(id);
                if (origin != null) returnAfterFight(o, origin);
                Msg.send(o, "You were removed from the boss fight: " + refused.getMessage());
            }
        }
    }

    private void startFromLobby() {
        WitherLobby l = lobby;
        lobby = null;
        agreeOnPack.clear();
        WitherArena arena = store.arenas.get(l.arena());
        for (UUID undecided : l.undecided()) {
            Location origin = l.drop(undecided);
            Player o = lookup.apply(undecided);
            if (origin == null) continue;
            if (o != null && o.isOnline()) {
                returnAfterFight(o, origin);
                Msg.send(o, "You did not agree and were sent back.");
            } else pendingReturns.put(undecided, origin);
        }
        List<UUID> ids = l.members();
        WitherEncounter created = new WitherEncounter(this, l.id(), l.leader(), arena, ids, l.rehearsal());
        if (l.rehearsal()) created.fullTest();
        l.origins().forEach(created::origin);
        encounter = created;
        try {
            created.spawn();
            for (Player p : arena.world().getPlayers())
                if (!ids.contains(p.getUniqueId()) && arena.contains(p.getLocation())) p.teleport(arena.waitPoint());
            store.attempt(new WitherStore.Attempt(l.id(), l.leader(), ids, arena.name(), "RUNNING", l.rehearsal(), l.materials()));
        } catch (Throwable error) {
            encounter = null;
            created.close();
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "The boss fight could not start.", error);
            boolean refunded = settleFailedStart(l.id(), l.leader());
            for (UUID id : ids) {
                Player o = lookup.apply(id);
                if (o != null)
                    Msg.send(o, l.rehearsal() ? "The rehearsal could not start." : "The boss fight could not start." + (refunded ? " Your summoning materials were returned." : ""));
            }
        }
    }

    private boolean settleFailedStart(UUID attempt, UUID leader) {
        WitherStore.Attempt a = store.attempts.get(attempt);
        if (a != null && !a.preview() && tuning().refundOnFailedStart() && store.refund(attempt)) {
            Player owner = lookup.apply(leader);
            if (owner != null) rewards.deliver(owner);
            return true;
        }
        store.forfeit(attempt);
        return false;
    }

    private void abortLobby(String reason) {
        WitherLobby l = lobby;
        if (l == null) return;
        lobby = null;
        agreeOnPack.clear();
        boolean refunded = settleFailedStart(l.id(), l.leader());
        String text = refunded ? reason + " Your summoning materials were returned." : reason;
        for (var entry : l.origins().entrySet()) {
            Player o = lookup.apply(entry.getKey());
            if (o != null && o.isOnline()) {
                returnAfterFight(o, entry.getValue());
                Msg.send(o, text);
            } else pendingReturns.put(entry.getKey(), entry.getValue());
        }
    }

    private void startTest(Player p, String arenaName, BossDirector.Attack attack) {
        if (!config().getBoolean("test.enabled", false) && !p.hasPermission("witherboss.admin"))
            throw new IllegalArgumentException("Test fights are disabled.");
        if (active() || lobby != null) throw new IllegalArgumentException("A fight is already running · /witherboss join or /witherboss stop.");
        if (p.isDead()) throw new IllegalArgumentException("Respawn first, then try again.");
        WitherArena arena = readyArena(arenaName, true);
        if (!hasPack(p)) {
            if (packUrl().isBlank()) throw new IllegalArgumentException("No resource pack is configured (resource-pack in config.yml).");
            pendingTests.put(p.getUniqueId(), new PendingTest(arena.name(), attack));
            if (!packRequested.contains(p.getUniqueId())) offerPack(p);
            Msg.send(p, "Test prepared · accept the server resource pack and it starts automatically.");
            return;
        }
        GameMode mode = p.getGameMode();
        Location origin = p.getLocation();
        p.setGameMode(GameMode.SURVIVAL);
        try {
            launch(p, arena, List.of(p.getUniqueId()), attack);
            encounter.rememberTest(p, mode, origin);
            Msg.send(p, "Boss test started · free, no rewards · /witherboss stop ends it.");
        } catch (RuntimeException | Error error) {
            p.setGameMode(mode);
            p.teleport(origin);
            throw error;
        }
    }

    public WitherBossPlugin plugin() {
        return plugin;
    }

    public WitherStore store() {
        return store;
    }

    public WitherRewards rewards() {
        return rewards;
    }

    public FileConfiguration config() {
        return plugin.getConfig();
    }

    public Tuning tuning() {
        return plugin.tuning();
    }

    public BossRig rig() {
        return rig;
    }

    public String packUrl() {
        if (!config().getBoolean("resource-pack.enabled", true)) return "";
        if (config().getBoolean("resource-pack.self-host.enabled", false)) {
            String host = config().getString("resource-pack.self-host.public-address", "");
            if (!host.isBlank()) return "http://" + host + ":" + config().getInt("resource-pack.self-host.port", 25663) + PackHost.PATH;
        }
        String url = config().getString("resource-pack.url", "default");
        return url.equalsIgnoreCase("default") ? DEFAULT_PACK_URL : url;
    }

    public String packHash() {
        return packHash;
    }

    public NamespacedKey entityKey() {
        return entityKey;
    }

    public WitherEncounter encounter() {
        return encounter;
    }

    public boolean active() {
        return encounter != null && encounter.active();
    }

    public boolean tagged(Entity e) {
        return e.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING);
    }

    public boolean member(UUID player) {
        return active() && encounter.state().member(player) != null;
    }

    public boolean alive(Player player) {
        return member(player.getUniqueId()) && encounter.state().member(player.getUniqueId()).status == RaidState.Status.ALIVE;
    }

    public boolean canAttack(Player player) {
        return !member(player.getUniqueId()) || encounter.state().living(player.getUniqueId(), encounter.tick());
    }

    public boolean target(Entity e) {
        return active() && (encounter.minion(e) || encounter.hitboxes() != null && encounter.hitboxes().target(e));
    }

    public boolean vulnerable(Entity e) {
        return target(e) && (encounter.minion(e) || encounter.hitboxes().proxy(e) || encounter.vulnerable());
    }

    public boolean ground(Player p) {
        return member(p.getUniqueId()) && encounter.ground();
    }

    public boolean suppressSpawns(Location l) {
        if (l == null || l.getWorld() == null) return false;
        for (WitherArena a : store.arenas.values()) {
            if (a.near(l)) return true;
            if (store.managedArenas.contains(a.name()) && l.getWorld().getUID().equals(a.worldId())) return true;
        }
        return false;
    }

    public boolean lobbyContains(Location l) {
        WitherArena prepared = lobby == null ? null : store.arenas.get(lobby.arena());
        return prepared != null && (prepared.contains(l) || prepared.waiting(l));
    }

    public boolean arenaWorld(World world) {
        if (world == null) return false;
        for (WitherArena a : store.arenas.values()) if (world.getUID().equals(a.worldId())) return true;
        return false;
    }

    public boolean protectedLocation(Location l) {
        if (active() && (encounter.arena().contains(l) || encounter.arena().waiting(l))) return true;
        WitherArena prepared = lobby == null ? null : store.arenas.get(lobby.arena());
        return prepared != null && (prepared.contains(l) || prepared.waiting(l));
    }

    public boolean localPvp(Player a, Player b) {
        return active() && (member(a.getUniqueId()) || member(b.getUniqueId()) || protectedLocation(a.getLocation()) || protectedLocation(b.getLocation()));
    }

    public UUID packId() {
        return UUID.nameUUIDFromBytes(("wither_boss:" + packHash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public boolean hasPack(Player player) {
        return packUrl().isBlank() || packLoaded.contains(player.getUniqueId());
    }

    public void offerPack(Player p) {
        String url = packUrl(), hash = packHash;
        if (url.isBlank() || !hash.matches("[a-fA-F0-9]{40}")) return;
        ResourcePackInfo pack = ResourcePackInfo.resourcePackInfo().id(packId()).uri(URI.create(url)).hash(hash).build();
        packLoaded.remove(p.getUniqueId());
        packRequested.add(p.getUniqueId());
        p.sendResourcePacks(
                ResourcePackRequest.resourcePackRequest()
                        .packs(pack)
                        .replace(false)
                        .required(false)
                        .prompt(Msg.text("Ash Colossus by NikeyV1 (Buffed SMP): boss models and sounds for the fight."))
                        .build());
    }

    public void packStatus(Player p, UUID id, org.bukkit.event.player.PlayerResourcePackStatusEvent.Status status) {
        if (!id.equals(packId()) || !packRequested.contains(p.getUniqueId())) return;
        if (status == org.bukkit.event.player.PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            packLoaded.add(p.getUniqueId());
            if (lobby != null && lobby.has(p.getUniqueId()) && agreeOnPack.remove(p.getUniqueId())) {
                lobby.confirm(p.getUniqueId());
                Msg.send(p, "Resource pack loaded, your agreement counts now.");
            }
            PendingTest pending = pendingTests.remove(p.getUniqueId());
            if (pending != null)
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        if (p.isOnline()) startTest(p, pending.arena(), pending.attack());
                    } catch (RuntimeException ex) {
                        Msg.send(p, ex.getMessage());
                    }
                });
        } else if (status.name().startsWith("FAILED") || status.name().equals("INVALID_URL") || status.name().equals("DECLINED") || status.name().equals("DISCARDED")) {
            packLoaded.remove(p.getUniqueId());
            packRequested.remove(p.getUniqueId());
            pendingTests.remove(p.getUniqueId());
            Msg.send(p, "The boss resource pack was not loaded · /witherboss pack to try again.");
            if (alive(p)) stop(true, "A participant lost the resource pack.");
        }
    }

    public void tick() {
        if (lobby != null) {
            try {
                lobbyTick();
            } catch (Throwable error) {
                plugin.getLogger().log(java.util.logging.Level.SEVERE, "The preparation was aborted by a technical error.", error);
                abortLobby("Technical error.");
            }
        }
        if (!active()) return;
        try {
            encounter.tickEncounter();
        } catch (Throwable error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "The fight was aborted by a technical error.", error);
            stop(true, "Technical error · the fight was aborted.");
        }
    }

    public void stop(boolean technical, String reason) {
        abortLobby(reason);
        WitherEncounter current = encounter;
        if (current == null) return;
        encounter = null;
        try {
            current.broadcast(Component.text(reason, Msg.LIGHT));
            current.close();
        } finally {
            if (store.attempts.containsKey(current.id())) {
                if (technical) settleFailedStart(current.id(), current.leader());
                else {
                    store.status(current.id(), "DEFEAT");
                    if (!current.preview()) store.finished(clock.get().toEpochMilli());
                }
            }
        }
    }

    public void win(WitherEncounter fight) {
        if (encounter != fight) return;
        if (fight.preview()) {
            store.status(fight.id(), "PREVIEW");
            fight.broadcast(Component.text("Test won · no rewards.", Msg.LIGHT));
            encounter = null;
            fight.close();
            return;
        }
        double minimum = config().getDouble("loot.min-damage", 20);
        List<LootTable.Participant> eligible = new ArrayList<>();
        fight.state().members().forEach((id, m) -> {
            if (m.eligible(minimum)) eligible.add(new LootTable.Participant(id, m.damage));
        });
        boolean first = store.victory(fight.id(), eligible.stream().map(LootTable.Participant::id).toList());
        rewards.queue(fight.id(), plugin.loot().roll(eligible, fight.random()));
        store.finished(clock.get().toEpochMilli());
        fight.broadcast(Component.text("The Ash Colossus is defeated · rewards are handed out now (or /witherboss claim).", Msg.LIGHT));
        if (first && config().getBoolean("event.announce-first-victory", true))
            Bukkit.getServer().sendMessage(Msg.title("✦ The Ash Colossus was defeated for the first time on this server!"));
        plugin.discord().bossDefeated(fight.state().members().keySet().stream().map(this::name).toList(), fight.tick() / 20L, first);
        List<Player> group = fight.players();
        encounter = null;
        fight.close();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : group) {
                if (!p.isOnline() || p.isDead()) continue;
                List<String> received = rewards.deliver(p);
                if (!received.isEmpty()) Msg.send(p, "Loot: " + String.join(", ", received) + ".");
                int left = store.pending(p.getUniqueId()).size();
                if (left > 0) Msg.send(p, left + " reward(s) are waiting. Free inventory space and run /witherboss claim.");
            }
            plugin.credits().victory(group);
        }, 20L);
    }

    private void join(Player p, String name) {
        boolean rehearsal = lobby != null && lobby.rehearsal() || active() && encounter.preview() && !config().getBoolean("test.enabled", false);
        if (rehearsal && !p.hasPermission("witherboss.admin")) throw new IllegalArgumentException("There is no boss fight right now.");
        if (lobby != null) {
            if (name != null && !name.equals(lobby.arena())) throw new IllegalArgumentException("Active arena: " + lobby.arena());
            if (lobby.has(p.getUniqueId())) accept(p);
            else admit(p, store.arenas.get(lobby.arena()), true);
            return;
        }
        if (!active()) throw new IllegalArgumentException("There is no boss fight right now.");
        if (name != null && !name.equals(encounter.arena().name())) throw new IllegalArgumentException("Active arena: " + encounter.arena().name());
        if (member(p.getUniqueId())) throw new IllegalArgumentException("You are already part of this fight; fallen fighters need a revive.");
        if (encounter.state().phase() == RaidState.Phase.DYING || encounter.state().phase() == RaidState.Phase.ENDED || encounter.closing())
            throw new IllegalArgumentException("The fight is already decided.");
        GameMode mode = p.getGameMode();
        Location origin = p.getLocation();
        if (encounter.preview()) {
            if (p.isDead() || !hasPack(p)) throw new IllegalArgumentException("You need to be alive and have the resource pack loaded.");
            p.setGameMode(GameMode.SURVIVAL);
        } else {
            if (!hasPack(p) && !packUrl().isBlank()) {
                if (!packRequested.contains(p.getUniqueId())) offerPack(p);
                throw new IllegalArgumentException("The server resource pack is loading. Run /witherboss join again once it is loaded.");
            }
            checkPlayer(p);
        }
        if (encounter.preview()) encounter.rememberTest(p, mode, origin);
        try {
            encounter.enlist(p);
        } catch (IllegalArgumentException refused) {
            if (encounter.preview()) encounter.restoreTestMode(p);
            throw refused;
        }
        try {
            store.participant(encounter.id(), p.getUniqueId());
        } catch (RuntimeException | Error error) {
            stop(true, "Your participation could not be saved safely; the fight was ended.");
            throw error;
        }
        Msg.send(p, "You are fighting · " + encounter.state().revives() + " shared revives left.");
    }

    private String name(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        String known = Bukkit.getOfflinePlayer(id).getName();
        return known == null ? id.toString().substring(0, 8) : known;
    }

    private WitherArena arena(String name) {
        WitherArena arena = store.arenas.get(name);
        if (arena == null) throw new IllegalArgumentException("Unknown arena · /witherboss arena list");
        return arena;
    }

    private void checkEligible(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL || p.isDead()) throw new IllegalArgumentException(p.getName() + ": must be alive in survival mode.");
    }

    private void checkPlayer(Player p) {
        if (p.getGameMode() != GameMode.SURVIVAL || p.isDead()) throw new IllegalArgumentException(p.getName() + ": must be alive in survival mode.");
        if (!hasPack(p)) throw new IllegalArgumentException(p.getName() + ": resource pack not confirmed. /witherboss pack");
    }

    private void launch(Player leader, WitherArena arena, List<UUID> ids, BossDirector.Attack attack) {
        UUID id = UUID.randomUUID();
        WitherEncounter created = new WitherEncounter(this, id, leader.getUniqueId(), arena, ids, true);
        if (attack == null) created.fullTest();
        else created.previewAttack(attack);
        encounter = created;
        try {
            created.spawn();
            for (Player p : arena.world().getPlayers())
                if (!ids.contains(p.getUniqueId()) && arena.contains(p.getLocation())) p.teleport(arena.waitPoint());
            store.attempt(new WitherStore.Attempt(id, leader.getUniqueId(), ids, arena.name(), "RUNNING", true, new WitherStore.Materials(0, 0, 0, 0)));
        } catch (Throwable error) {
            encounter = null;
            created.close();
            if (store.attempts.containsKey(id) && "RUNNING".equals(store.attempts.get(id).status())) store.forfeit(id);
            throw error;
        }
    }

    public boolean death(org.bukkit.event.entity.PlayerDeathEvent event) {
        if (!member(event.getPlayer().getUniqueId())) return false;
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.getItemsToKeep().clear();
        encounter.state().fallen(event.getPlayer().getUniqueId(), false, System.currentTimeMillis());
        encounter.hurt(event.getPlayer());
        boolean hardcore = tuning().loseInventoryOnDefeat() || tuning().loseInventoryOnLogout();
        Msg.send(event.getPlayer(), hardcore ? "You fell. Your items stay with you for now, but are lost if the group is defeated or you log out." : "You fell. Your items and XP are safe, wait for a revive.");
        return true;
    }

    public void logout(Player p) {
        if (lobby != null && lobby.has(p.getUniqueId())) {
            Location origin = lobby.remove(p.getUniqueId());
            if (origin != null) pendingReturns.put(p.getUniqueId(), origin);
        }
        packLoaded.remove(p.getUniqueId());
        packRequested.remove(p.getUniqueId());
        agreeOnPack.remove(p.getUniqueId());
        pendingTests.remove(p.getUniqueId());
        if (member(p.getUniqueId())) {
            if (tuning().loseInventoryOnLogout()) clearRaidInventory(p);
            encounter.state().fallen(p.getUniqueId(), true, System.currentTimeMillis());
            encounter.hurt(p);
            encounter.restoreTestMode(p);
        }
    }

    void clearRaidInventory(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
    }

    void returnAfterFight(Player p, Location origin) {
        if (p.isDead() || !p.isOnline()) pendingReturns.put(p.getUniqueId(), origin.clone());
        else if (!p.teleport(origin)) pendingReturns.put(p.getUniqueId(), origin.clone());
    }

    Location takeReturn(UUID player) {
        return pendingReturns.remove(player);
    }

    public void reconnect(Player p) {
        if (!member(p.getUniqueId())
                && !inLobby(p.getUniqueId())
                && !pendingReturns.containsKey(p.getUniqueId())
                && !p.hasPermission("witherboss.admin")
                && !protectedLocation(p.getLocation())
                && store.arenas.values().stream().anyMatch(a -> a.contains(p.getLocation()) || a.waiting(p.getLocation())))
            p.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
        if (!member(p.getUniqueId()) && !p.isDead()) {
            Location origin = takeReturn(p.getUniqueId());
            if (origin != null) returnAfterFight(p, origin);
        }
        if (member(p.getUniqueId())) {
            if (!hasPack(p)) offerPack(p);
            encounter.reconnect(p);
            encounter.state().reconnect(p.getUniqueId(), System.currentTimeMillis());
            p.teleport(encounter.arena().waitPoint());
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || store.pending(p.getUniqueId()).isEmpty()) return;
            List<String> received = rewards.deliver(p);
            if (!received.isEmpty()) Msg.send(p, "Waiting rewards delivered: " + String.join(", ", received) + ".");
            if (!store.pending(p.getUniqueId()).isEmpty()) Msg.send(p, "You still have rewards waiting. Free inventory space and run /witherboss claim.");
        }, 40L);
    }

    public void command(CommandSender sender, String[] args) throws Exception {
        boolean admin = sender.hasPermission("witherboss.admin");
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info", "status" -> info(sender);
            case "join" -> {
                Player p = player(sender);
                if (args.length > 2) throw new IllegalArgumentException("/witherboss join [arena]");
                join(p, args.length == 2 ? args[1] : null);
            }
            case "accept" -> accept(player(sender));
            case "leave" -> {
                Player p = player(sender);
                if (inLobby(p.getUniqueId())) {
                    lobbyLeave(p);
                    return;
                }
                if (member(p.getUniqueId()))
                    throw new IllegalArgumentException("Leaving counts as dropping out during the fight; rejoining does not replace a revive.");
                Msg.send(p, "You are not in a boss fight.");
            }
            case "pack" -> {
                if (packUrl().isBlank()) throw new IllegalArgumentException("No resource pack is configured.");
                offerPack(player(sender));
            }
            case "claim" -> rewards.claim(player(sender));
            case "title" -> {
                Player p = player(sender);
                if (!store.titles.contains(p.getUniqueId())) throw new IllegalArgumentException("Win a fight first.");
                p.showTitle(net.kyori.adventure.title.Title.title(Msg.title("Colossus Slayer"), Msg.text("Conqueror of the Ash Colossus")));
            }
            case "summon" -> {
                Player p = player(sender);
                if (!admin && !(trigger() == Trigger.COMMAND && p.hasPermission("witherboss.summon"))) throw noPermission();
                summonByCommand(p, admin && !(args.length == 2 && args[1].equalsIgnoreCase("checked")));
            }
            case "test" -> {
                if (args.length > 3) throw new IllegalArgumentException("/witherboss test [arena] [attack]");
                BossDirector.Attack attack = null;
                if (args.length == 3)
                    attack = Arrays.stream(BossDirector.Attack.values()).filter(t -> t.key().equalsIgnoreCase(args[2])).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown attack · use tab completion."));
                startTest(player(sender), args.length >= 2 ? args[1] : null, attack);
            }
            case "stop" -> {
                if (admin) {
                    if (lobby != null && args.length == 1) {
                        abortLobby("Staff ended the preparation.");
                        return;
                    }
                    if (args.length > 2 || !active() || args.length == 2 && !encounter.arena().name().equals(args[1])) throw new IllegalArgumentException("/witherboss stop [active arena]");
                    stop(true, "Staff ended the fight.");
                    return;
                }
                Player p = player(sender);
                if (!active()) throw new IllegalArgumentException("No boss test is running.");
                if (!encounter.preview() || !encounter.leader().equals(p.getUniqueId())) throw new IllegalArgumentException("Only the leader can stop their own test.");
                stop(true, "Boss test ended.");
            }
            case "rehearse" -> {
                if (!admin) throw noPermission();
                if (args.length > 2) throw new IllegalArgumentException("/witherboss rehearse [seconds]");
                rehearse(player(sender), args.length == 2 ? args[1] : null);
            }
            case "arena" -> {
                if (!admin) throw noPermission();
                configureArena(sender, args);
            }
            case "resolve" -> {
                if (!admin) throw noPermission();
                if (args.length != 3) throw new IllegalArgumentException("/witherboss resolve <claim-id> <delivered|retry>");
                WitherStore.Claim c = store.claims.get(args[1]);
                if (c == null || c.state != WitherStore.Delivery.DELIVERING) throw new IllegalArgumentException("No unresolved delivery with that id.");
                store.delivery(c, switch (args[2]) {
                    case "delivered" -> WitherStore.Delivery.DELIVERED;
                    case "retry" -> WitherStore.Delivery.PENDING;
                    default -> throw new IllegalArgumentException("Use delivered or retry after checking the inventory.");
                });
                Msg.send(sender, "Claim resolved.");
            }
            default -> throw new IllegalArgumentException("Unknown sub-command · /witherboss help");
        }
    }

    private static IllegalArgumentException noPermission() {
        return new IllegalArgumentException("You do not have permission for that.");
    }

    private Player player(CommandSender sender) {
        if (sender instanceof Player p) return p;
        throw new IllegalArgumentException("This command needs a player.");
    }

    private void configureArena(CommandSender sender, String[] args) {
        if (args.length < 2) throw new IllegalArgumentException("/witherboss arena set|build|rebuild|list|remove <name>");
        if (args[1].equals("list")) {
            store.arenas.forEach((name, a) -> Msg.send(sender, name + " · " + a.worldId() + " · " + a.x() + "/" + a.y() + "/" + a.z()));
            return;
        }
        if (args.length != 3) throw new IllegalArgumentException("/witherboss arena set|build|rebuild|remove <name>");
        String name = args[2];
        if (!name.matches("[a-z0-9_-]{1,32}")) throw new IllegalArgumentException("Invalid arena name.");
        if (active() || lobby != null) throw new IllegalArgumentException("End the active fight first.");
        if (args[1].equals("remove")) {
            arena(name);
            store.arenas.remove(name);
            store.managedArenas.remove(name);
            store.save(true);
            Msg.send(sender, "Arena unregistered. The world and its blocks stay.");
            return;
        }
        if (args[1].equals("rebuild")) {
            WitherArena built = arena(name);
            if (!store.managedArenas.contains(name) || built.world() == null) throw new IllegalArgumentException("Only automatically built arenas can be rebuilt.");
            Msg.send(sender, "Rebuilding the arena, this takes a moment.");
            WitherArenaBuilder.renovate(built.world());
            Msg.send(sender, "Arena " + name + " rebuilt.");
            return;
        }
        restoreArenas();
        if (store.arenas.containsKey(name)) {
            WitherArena preset = WitherArenaBuilder.preset(name, store.arenas.get(name).world());
            if (preset == null) throw new IllegalArgumentException("An arena with that name already exists.");
            if (!List.of("build", "set").contains(args[1])) throw new IllegalArgumentException("set, build, rebuild, list or remove.");
            if (sender instanceof Player p) p.teleport(preset.waitPoint());
            Msg.send(sender, "Preset arena ready · /witherboss test");
            return;
        }
        WitherArena a;
        if (args[1].equals("build")) {
            a = WitherArenaBuilder.build(name);
            store.managedArenas.add(name);
        } else if (args[1].equals("set")) {
            Location l = player(sender).getLocation();
            WitherArena preset = WitherArenaBuilder.preset(name, l.getWorld());
            a = preset != null ? preset : new WitherArena(name, l.getWorld().getUID(), l.getBlockX() + .5, l.getBlockY(), l.getBlockZ() + .5);
            if (preset != null) store.managedArenas.add(name);
        } else throw new IllegalArgumentException("set, build, rebuild, list or remove.");
        for (WitherArena existing : store.arenas.values())
            if (existing.worldId().equals(a.worldId()) && existing.center().distanceSquared(a.center()) < 170 * 170)
                throw new IllegalArgumentException("Arenas need 170 blocks of distance.");
        store.arenas.put(name, a);
        store.save(true);
        Msg.send(sender, "Arena " + name + " registered · " + a.x() + "/" + a.y() + "/" + a.z());
        if (sender instanceof Player p && args[1].equals("build")) p.teleport(a.waitPoint());
    }

    private void info(CommandSender sender) {
        boolean staff = sender.hasPermission("witherboss.admin");
        String status = !enabled() ? "disabled" : oneTimeLocked() ? "already defeated (one-time event)" : active() ? "fight running" : lobby != null ? "preparing" : cooldownSeconds() > 0 ? "recovering for " + (cooldownSeconds() / 60 + 1) + " more min" : "ready";
        Msg.send(sender, "Ash Colossus · " + status + " · trigger " + trigger() + " · " + tuning().minParticipants() + "+ fighters · " + victoriesText());
        if (trigger() == Trigger.SIGIL) Msg.send(sender, "Hold a Colossus Sigil in your other hand while building a Wither to awaken it.");
        if (trigger() == Trigger.WITHER_BUILD) Msg.send(sender, "Build a Wither (soul sand T plus three skulls) to awaken it.");
        if (trigger() == Trigger.COMMAND) Msg.send(sender, "Staff start the fight with /witherboss summon.");
        if (lobby != null)
            Msg.send(sender, "A boss fight is preparing · " + lobby.size() + " in the arena · " + Math.max(0, lobby.remaining() / 20) + " s left · " + lobby.confirmedCount() + " confirmed · /witherboss join");
        if (active())
            Msg.send(sender, encounter.arena().name() + " · " + encounter.state().phase() + " · " + (int) encounter.state().health() + " HP · " + encounter.state().revives() + " revives");
        if (staff) {
            Msg.send(sender, tuning().describe(5));
            store.claims.values().stream().filter(c -> c.state == WitherStore.Delivery.DELIVERING).forEach(c -> Msg.send(sender, "Check delivery: " + c.id + " · " + c.owner));
        }
        int waiting = sender instanceof Player p ? store.pending(p.getUniqueId()).size() : 0;
        if (waiting > 0) Msg.send(sender, waiting + " reward(s) are waiting for you · /witherboss claim");
    }

    private String victoriesText() {
        return store.victories() + (store.victories() == 1 ? " victory" : " victories");
    }

    public List<String> complete(CommandSender sender, String[] args) {
        boolean admin = sender.hasPermission("witherboss.admin");
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.addAll(List.of("info", "join", "accept", "leave", "pack", "claim", "title", "help", "about"));
            if (admin) out.addAll(List.of("summon", "stop", "test", "rehearse", "arena", "loot", "give", "reload", "config", "difficulty", "cooldown", "resolve"));
            else if (config().getBoolean("test.enabled", false)) out.addAll(List.of("test", "stop"));
        }
        if (args.length == 2) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("arena") && admin) out.addAll(List.of("set", "build", "rebuild", "list", "remove"));
            else if (List.of("join", "test", "stop").contains(first)) out.addAll(store.arenas.keySet());
            else if (first.equals("resolve") && admin) store.claims.values().stream().filter(c -> c.state == WitherStore.Delivery.DELIVERING).forEach(c -> out.add(c.id));
        }
        if (args.length == 3) {
            String first = args[0].toLowerCase(Locale.ROOT);
            if (first.equals("test")) Arrays.stream(BossDirector.Attack.values()).map(BossDirector.Attack::key).forEach(out::add);
            if (first.equals("arena") && admin) out.addAll(args[1].equalsIgnoreCase("remove") ? store.arenas.keySet() : List.of("forge"));
            if (first.equals("resolve") && admin) out.addAll(List.of("delivered", "retry"));
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(s -> s.toLowerCase(Locale.ROOT).startsWith(prefix)).distinct().sorted().toList();
    }

    public void trophyEffect(Player p) {
        if (!rewards.trophy(p.getInventory().getItemInMainHand(), p) || System.currentTimeMillis() < trophyCooldown.getOrDefault(p.getUniqueId(), 0L)) return;
        trophyCooldown.put(p.getUniqueId(), System.currentTimeMillis() + 10_000);
        p.playSound(p.getLocation(), WitherRewards.NAMESPACE + ":cleanse", SoundCategory.PLAYERS, .8f, 1);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            p.spawnParticle(Particle.SOUL, p.getLocation().add(Math.cos(a), .8, Math.sin(a)), 1, 0, 0, 0, 0);
        }
        p.sendActionBar(Msg.title("✦ Forge Echo"));
    }

    @Override
    public void close() {
        try {
            abortLobby("Server stopping · the boss fight was cancelled.");
            stop(true, "Server stopping · the boss fight was cancelled.");
        } finally {
            if (packHost != null) packHost.close();
            store.close();
            packLoaded.clear();
            packRequested.clear();
            pendingTests.clear();
        }
    }
}
