package de.nikey.witherboss.boss;

import de.nikey.witherboss.ui.Msg;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.*;

import java.util.*;

public final class WitherListener implements Listener {
    private final WitherService service;
    private final Map<UUID, Location> respawns = new HashMap<>();

    public WitherListener(WitherService service) {
        this.service = service;
    }

    private Player attacker(Entity e) {
        if (e instanceof Player p) return p;
        if (e instanceof Projectile p && p.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void localDamage(EntityDamageByEntityEvent e) {
        Player source = attacker(e.getDamager());
        WitherEncounter fight = service.encounter();
        if (e.getEntity() instanceof Player victim) {
            if (source != null && service.localPvp(source, victim)) {
                e.setCancelled(true);
                return;
            }
            if (fight != null && service.member(victim.getUniqueId()) && e.getDamager() instanceof LivingEntity striker && fight.minion(striker)) {
                e.setCancelled(true);
                fight.minionStrike(striker, victim);
                return;
            }
            if (fight != null && service.member(victim.getUniqueId())) {
                Entity actualSource =
                        e.getDamager() instanceof Projectile projectile
                                        && projectile.getShooter() instanceof Entity shooter
                                ? shooter
                                : e.getDamager();
                if (actualSource instanceof LivingEntity
                        && source == null
                        && !service.tagged(actualSource)) {
                    e.setCancelled(true);
                    return;
                }
                if (!fight.state().living(victim.getUniqueId(), fight.tick())
                        || fight.state().phase() == RaidState.Phase.INTRO
                        || fight.state().phase() == RaidState.Phase.DYING) {
                    e.setCancelled(true);
                    return;
                }
                if (service.tagged(e.getDamager()) && !fight.dealing()) {
                    e.setCancelled(true);
                    return;
                }
                if (service.ground(victim)
                        && e.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING))
                    e.setDamage(EntityDamageEvent.DamageModifier.BLOCKING, 0);
            }
        }
        if (service.target(e.getEntity())
                && (source == null
                        || !service.member(source.getUniqueId())
                        || !service.canAttack(source))) {
            e.setCancelled(true);
            return;
        }
        if (source != null
                && service.member(source.getUniqueId())
                && (!service.canAttack(source) || !service.target(e.getEntity())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void proxyDamage(EntityDamageEvent e) {
        if (!service.active() || !service.tagged(e.getEntity())) return;
        WitherEncounter fight = service.encounter();
        WitherHitboxes boxes = fight.hitboxes();
        if (fight.minion(e.getEntity())) {
            if (e instanceof EntityDamageByEntityEvent hit) {
                Player attacker = attacker(hit.getDamager());
                if (attacker != null && service.member(attacker.getUniqueId()) && service.canAttack(attacker) && fight.vulnerable()) return;
            }
            e.setCancelled(true);
            return;
        }
        if (!boxes.target(e.getEntity())) {
            e.setCancelled(true);
            return;
        }
        if (!e.isCancelled() && e instanceof EntityDamageByEntityEvent hit && hit.getCause() != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            Player attacker = attacker(hit.getDamager());
            if (attacker != null && service.member(attacker.getUniqueId())) {
                double amount = e.getFinalDamage();
                boolean accepted = boxes.body(e.getEntity())
                        ? fight.hitBody(attacker, amount, hit.getDamager())
                        : fight.state().living(attacker.getUniqueId(), fight.tick())
                                && fight.state().projectile(hit.getDamager() instanceof Projectile ? hit.getDamager().getUniqueId() : null)
                                && boxes.hitProxy(e.getEntity(), attacker, amount, hit.getDamager());
                if (accepted) {
                    e.setDamage(.001);
                    return;
                }
            }
        }
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hurt(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p
                && service.member(p.getUniqueId())
                && e.getFinalDamage() > 0) service.encounter().hurt(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void lobbyProtection(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && service.inLobby(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void invulnerability(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p
                && service.member(p.getUniqueId())
                && (!service.encounter().state().living(p.getUniqueId(), service.encounter().tick())
                        || service.encounter().state().phase() == RaidState.Phase.INTRO
                        || service.encounter().state().phase() == RaidState.Phase.DYING))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void deaths(EntityDeathEvent e) {
        if (service.tagged(e.getEntity())) {
            e.getDrops().clear();
            e.setDroppedExp(0);
            if (service.active() && service.encounter().minion(e.getEntity())) service.encounter().minionDied(e.getEntity(), e.getEntity().getKiller());
        }
        if (e instanceof PlayerDeathEvent death) {
            if (service.member(death.getPlayer().getUniqueId())) {
                service.death(death);
                respawns.put(death.getPlayer().getUniqueId(), service.encounter().arena().waitPoint());
            } else {
                List<ItemStack> bound =
                        death.getDrops().stream().filter(service.rewards()::bound).toList();
                death.getItemsToKeep().addAll(bound);
                death.getDrops().removeAll(bound);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void respawn(PlayerRespawnEvent e) {
        Location exit = service.takeReturn(e.getPlayer().getUniqueId());
        Location l = respawns.remove(e.getPlayer().getUniqueId());
        if (exit != null) e.setRespawnLocation(exit);
        else if (l != null) e.setRespawnLocation(l);
        else if (service.member(e.getPlayer().getUniqueId()))
            e.setRespawnLocation(service.encounter().arena().waitPoint());
    }

    @EventHandler
    public void quit(PlayerQuitEvent e) {
        respawns.remove(e.getPlayer().getUniqueId());
        service.logout(e.getPlayer());
    }

    @EventHandler
    public void join(PlayerJoinEvent e) {
        service.reconnect(e.getPlayer());
    }

    @EventHandler
    public void pack(PlayerResourcePackStatusEvent e) {
        service.packStatus(e.getPlayer(), e.getID(), e.getStatus());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void move(PlayerMoveEvent e) {
        if (!e.hasChangedPosition()) return;
        if (service.inLobby(e.getPlayer().getUniqueId())) {
            if (!service.lobbyContains(e.getTo()) && service.lobbyContains(e.getFrom())) {
                e.setTo(e.getFrom());
                e.getPlayer().setVelocity(new org.bukkit.util.Vector());
            }
            return;
        }
        if (!service.active()) return;
        WitherEncounter f = service.encounter();
        Player p = e.getPlayer();
        if (service.alive(p)) {
            if (!f.arena().contains(e.getTo()) && f.arena().contains(e.getFrom())) {
                e.setTo(e.getFrom());
                p.setVelocity(new org.bukkit.util.Vector());
            }
        } else if (f.arena().contains(e.getTo())) e.setTo(e.getFrom());
        else if (service.member(p.getUniqueId()) && !f.arena().waiting(e.getTo()))
            e.setTo(f.arena().waiting(e.getFrom()) ? e.getFrom() : f.arena().waitPoint());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent e) {
        if (service.inLobby(e.getPlayer().getUniqueId())
                && (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                        || e.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                        || e.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                        || e.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT)) {
            e.setCancelled(true);
            return;
        }
        if (!service.active()) return;
        WitherEncounter f = service.encounter();
        if (service.alive(e.getPlayer())
                && f.arena().contains(e.getFrom())
                && !f.arena().contains(e.getTo())) e.setCancelled(true);
        if (!service.alive(e.getPlayer()) && f.arena().contains(e.getTo())) e.setCancelled(true);
        if (service.member(e.getPlayer().getUniqueId())
                && !service.alive(e.getPlayer())
                && !f.arena().waiting(e.getTo())) e.setCancelled(true);
        if (f.arena().contains(e.getFrom())
                && (e.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                        || e.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                        || e.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL
                        || e.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void gliding(EntityToggleGlideEvent e) {
        if (e.isGliding() && e.getEntity() instanceof Player p && service.member(p.getUniqueId()))
            e.setCancelled(true);
    }

    @EventHandler
    public void mode(PlayerGameModeChangeEvent e) {
        if (e.getNewGameMode() != GameMode.SURVIVAL && service.alive(e.getPlayer())) {
            service.encounter()
                    .state()
                    .fallen(e.getPlayer().getUniqueId(), true, System.currentTimeMillis());
            Bukkit.getScheduler()
                    .runTask(
                            service.plugin(),
                            () -> {
                                if (service.member(e.getPlayer().getUniqueId()))
                                    e.getPlayer().teleport(service.encounter().arena().waitPoint());
                            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent e) {
        if (service.protectedLocation(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void place(BlockPlaceEvent e) {
        if (service.protectedLocation(e.getBlock().getLocation())) {
            e.setCancelled(true);
            return;
        }
        if (!e.canBuild() || !service.enabled() || service.oneTimeLocked()) return;
        WitherService.Trigger trigger = service.trigger();
        if (trigger == WitherService.Trigger.COMMAND) return;
        WitherSummoning.Structure structure = WitherSummoning.find(e.getBlockPlaced());
        if (structure == null) return;
        EquipmentSlot sigilHand = null;
        if (trigger == WitherService.Trigger.SIGIL) {
            sigilHand = service.sigilSlot(e.getPlayer(), e.getHand());
            if (sigilHand == null) return;
        }
        String blocked = service.summonBlocked(e.getBlock().getWorld(), false);
        if (blocked != null) {
            if (trigger == WitherService.Trigger.SIGIL || !service.config().getBoolean("event.allow-vanilla-when-unavailable", false))
                e.setCancelled(true);
            Msg.send(e.getPlayer(), blocked);
            return;
        }
        e.setCancelled(true);
        var replaced = e.getBlockReplacedState().getBlockData();
        EquipmentSlot sigil = sigilHand;
        Bukkit.getScheduler()
                .runTask(
                        service.plugin(),
                        () -> {
                            try {
                                if (e.getPlayer().isOnline()) service.summon(e.getPlayer(), structure, replaced, e.getHand(), sigil);
                            } catch (RuntimeException error) {
                                Msg.send(e.getPlayer(), error.getMessage());
                                if (!(error instanceof IllegalArgumentException))
                                    service.plugin().getLogger().log(java.util.logging.Level.SEVERE, "Summoning the boss failed.", error);
                            }
                        });
    }

    private final Map<UUID, Integer> lastAxe = new HashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void weaponEffects(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || !service.active() || !service.member(p.getUniqueId()) || !service.target(e.getEntity())) return;
        WitherEncounter fight = service.encounter();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().name().endsWith("_AXE") && fight.tick() - lastAxe.getOrDefault(p.getUniqueId(), -100) >= 20 && p.getAttackCooldown() >= 0.9f) {
            lastAxe.put(p.getUniqueId(), fight.tick());
            fight.axe(p, e.getEntity());
        }
        int fire = hand.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FIRE_ASPECT);
        if (fire > 0) fight.ignite(p, e.getEntity(), fire * 80);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void burn(BlockBurnEvent e) {
        if (service.protectedLocation(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void ignite(BlockIgniteEvent e) {
        if (service.protectedLocation(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void piston(BlockPistonExtendEvent e) {
        if (e.getBlocks().stream()
                .anyMatch(
                        b ->
                                service.protectedLocation(b.getLocation())
                                        || service.protectedLocation(
                                                b.getRelative(e.getDirection()).getLocation())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void piston(BlockPistonRetractEvent e) {
        if (e.getBlocks().stream()
                .anyMatch(
                        b ->
                                service.protectedLocation(b.getLocation())
                                        || service.protectedLocation(
                                                b.getRelative(e.getDirection()).getLocation())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void flow(BlockFromToEvent e) {
        if (service.protectedLocation(e.getToBlock().getLocation())
                || service.protectedLocation(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucket(PlayerBucketEmptyEvent e) {
        if (service.protectedLocation(e.getBlock().getLocation())
                || service.member(e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void bucketTake(PlayerBucketFillEvent e) {
        if (service.protectedLocation(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explosion(EntityExplodeEvent e) {
        if (service.tagged(e.getEntity())
                || service.protectedLocation(e.getLocation())
                || e.blockList().stream().anyMatch(b -> service.protectedLocation(b.getLocation())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void explosion(BlockExplodeEvent e) {
        if (service.protectedLocation(e.getBlock().getLocation())
                || e.blockList().stream().anyMatch(b -> service.protectedLocation(b.getLocation())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void change(EntityChangeBlockEvent e) {
        if (service.tagged(e.getEntity()) || service.protectedLocation(e.getBlock().getLocation()))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void regenerate(EntityRegainHealthEvent e) {
        if (service.tagged(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void projectiles(ProjectileLaunchEvent e) {
        if (e.getEntity().getShooter() instanceof Entity shooter && service.tagged(shooter))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void target(EntityTargetLivingEntityEvent e) {
        if (service.tagged(e.getEntity())
                && (e.getTarget() == null
                        || !(e.getTarget() instanceof Player p)
                        || !service.alive(p))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(EntityPortalEvent e) {
        if (service.tagged(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void split(SlimeSplitEvent e) {
        if (service.tagged(e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void spawn(CreatureSpawnEvent e) {
        if (service.blocksVanillaWither()
                && e.getEntity() instanceof Wither
                && !service.tagged(e.getEntity())) e.setCancelled(true);
        if (service.protectedLocation(e.getLocation()) && !service.tagged(e.getEntity()))
            e.setCancelled(true);
        if (!service.tagged(e.getEntity())
                && service.suppressSpawns(e.getLocation())
                && (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL
                        || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CHUNK_GEN
                        || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS
                        || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.PATROL))
            e.setCancelled(true);
    }

    @EventHandler
    public void chunks(ChunkLoadEvent e) {
        for (Entity entity : e.getChunk().getEntities())
            if (service.tagged(entity) && (!service.active() || !service.encounter().owns(entity)))
                entity.remove();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void interact(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (service.rewards().trophy(e.getItem(), e.getPlayer())) {
            e.setCancelled(true);
            service.trophyEffect(e.getPlayer());
            return;
        }
        if (!service.active()) return;
        if (e.getClickedBlock() != null
                && service.protectedLocation(e.getClickedBlock().getLocation())) {
            if (e.getAction() == Action.RIGHT_CLICK_BLOCK) {
                e.setUseInteractedBlock(Event.Result.DENY);
                if (service.alive(e.getPlayer())
                                && service.encounter().channel(e.getPlayer(), e.getClickedBlock().getLocation())
                        || altersWorld(e.getItem())) e.setUseItemInHand(Event.Result.DENY);
            }
        }
        if (service.member(e.getPlayer().getUniqueId())
                && e.getItem() != null
                && (e.getItem().getType() == Material.ENDER_PEARL
                        || e.getItem().getType() == Material.FIREWORK_ROCKET
                        || e.getItem().getType() == Material.FLINT_AND_STEEL)) e.setCancelled(true);
    }

    private static boolean altersWorld(ItemStack item) {
        if (item == null) return false;
        String name = item.getType().name();
        return name.endsWith("_HOE")
                || name.endsWith("_AXE")
                || name.endsWith("_SHOVEL")
                || name.endsWith("_SPAWN_EGG")
                || name.endsWith("_BOAT")
                || name.endsWith("_RAFT")
                || name.endsWith("MINECART")
                || name.endsWith("ITEM_FRAME")
                || name.endsWith("BUCKET")
                || name.equals("BONE_MEAL")
                || name.equals("FIRE_CHARGE")
                || name.equals("FLINT_AND_STEEL")
                || name.equals("ARMOR_STAND")
                || name.equals("END_CRYSTAL")
                || name.equals("PAINTING");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void consume(PlayerItemConsumeEvent e) {
        if (service.member(e.getPlayer().getUniqueId()) && e.getItem().getType() == Material.CHORUS_FRUIT)
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent e) {
        if (service.rewards().bound(e.getItemDrop().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void moveItem(InventoryMoveItemEvent e) {
        if (service.rewards().bound(e.getItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopperPickup(InventoryPickupItemEvent e) {
        if (service.rewards().bound(e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) {
        if (service.rewards().bound(e.getItem().getItemStack())
                && (!(e.getEntity() instanceof Player p)
                        || !service.rewards().owned(e.getItem().getItemStack(), p)))
            e.setCancelled(true);
    }

    @EventHandler
    public void craft(PrepareItemCraftEvent e) {
        if (Arrays.stream(e.getInventory().getMatrix()).anyMatch(service.rewards()::bound))
            e.getInventory().setResult(ItemStack.empty());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void inventory(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getType() == InventoryType.CRAFTING) return;
        if (service.rewards().bound(e.getCursor())
                        && e.getClickedInventory() == e.getView().getTopInventory()
                || service.rewards().bound(e.getCurrentItem()) && e.isShiftClick())
            e.setCancelled(true);
        if (e.getClickedInventory() == e.getView().getTopInventory()
                && e.getWhoClicked() instanceof Player p
                && (e.getHotbarButton() >= 0
                                && service.rewards()
                                        .bound(p.getInventory().getItem(e.getHotbarButton()))
                        || e.getClick() == ClickType.SWAP_OFFHAND
                                && service.rewards().bound(p.getInventory().getItemInOffHand())))
            e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent e) {
        if (service.rewards().bound(e.getOldCursor())
                && e.getRawSlots().stream()
                        .anyMatch(s -> s < e.getView().getTopInventory().getSize()))
            e.setCancelled(true);
    }
}
