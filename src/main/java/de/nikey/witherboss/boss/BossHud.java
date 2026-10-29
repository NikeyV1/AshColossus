package de.nikey.witherboss.boss;

import de.nikey.witherboss.ui.Msg;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;

import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.*;

public final class BossHud implements AutoCloseable {
    public static final TextColor SOUL = TextColor.color(0x3FE6D2);
    public static final TextColor GOLD = TextColor.color(0xFFC83D);
    public static final TextColor DANGER = TextColor.color(0xFF4B55);
    public static final TextColor ASH = TextColor.color(0xB9AFAA);

    private final BossBar health = BossBar.bossBar(Component.text("The Ash Colossus"), 1, BossBar.Color.RED, BossBar.Overlay.NOTCHED_6);
    private final BossBar mechanic = BossBar.bossBar(Component.empty(), 0, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
    private final Map<UUID, Player> viewers = new HashMap<>();
    private final Map<UUID, Integer> actionUntil = new HashMap<>();
    private final Map<UUID, Set<String>> hinted = new HashMap<>();
    private boolean mechanicVisible;

    public static Component plain(String s, TextColor c) {
        return Component.text(s, c).decoration(TextDecoration.ITALIC, false);
    }

    public static Component bold(String s, TextColor c) {
        return plain(s, c).decorate(TextDecoration.BOLD);
    }

    public static String phaseName(RaidState.Phase p) {
        return switch (p) {
            case INTRO -> "Awakening";
            case GLUT -> "I · Embers";
            case HAMMER -> "II · Consuming Hammer";
            case CORE -> "III · Core Meltdown";
            case DYING -> "Collapse";
            case ENDED -> "End";
        };
    }

    public void health(RaidState state, boolean exposed, boolean shielded) {
        BossBar.Color color =
                switch (state.phase()) {
                    case HAMMER -> BossBar.Color.PURPLE;
                    case CORE -> BossBar.Color.YELLOW;
                    default -> BossBar.Color.RED;
                };
        health.color(color);
        Component name =
                bold("The Ash Colossus", DANGER)
                        .append(plain(" · " + phaseName(state.phase()), ASH))
                        .append(plain(" · " + (int) Math.ceil(state.health()) + " HP", Msg.LIGHT));
        if (exposed) name = name.append(plain("  ✦ Heart open", GOLD));
        if (shielded) name = name.append(plain("  ⛨ invulnerable", ASH));
        health.name(name);
        health.progress((float) state.fraction());
    }

    public void mechanic(String label, String hint, float progress, BossBar.Color color) {
        if (label == null) {
            mechanicVisible = false;
            mechanic.progress(0);
            mechanic.name(Component.empty());
            return;
        }
        mechanicVisible = true;
        Component name = bold(label, color == BossBar.Color.YELLOW ? GOLD : color == BossBar.Color.RED ? DANGER : Msg.LIGHT);
        if (hint != null && !hint.isEmpty()) name = name.append(plain(" · " + hint, Msg.LIGHT));
        mechanic.name(name);
        mechanic.color(color);
        mechanic.progress(Math.clamp(progress, 0, 1));
    }

    public void viewers(Collection<Player> players) {
        Set<UUID> current = new HashSet<>();
        for (Player p : players) {
            current.add(p.getUniqueId());
            if (viewers.put(p.getUniqueId(), p) == null) {
                p.showBossBar(health);
                p.showBossBar(mechanic);
            }
        }
        viewers.entrySet()
                .removeIf(e -> {
                    if (current.contains(e.getKey())) return false;
                    e.getValue().hideBossBar(health);
                    e.getValue().hideBossBar(mechanic);
                    return true;
                });
    }

    public void action(Player p, Component text, int now, int hold) {
        p.sendActionBar(text);
        actionUntil.put(p.getUniqueId(), now + hold);
    }

    public boolean actionFree(Player p, int now) {
        return now >= actionUntil.getOrDefault(p.getUniqueId(), 0);
    }

    public boolean firstTime(Player p, String key) {
        return hinted.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(key);
    }

    public void title(Collection<Player> players, Component main, Component sub, int stay) {
        Title t = Title.title(main, sub, Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(stay * 50L), Duration.ofMillis(700)));
        for (Player p : players) p.showTitle(t);
    }

    public static Component mark(String text) {
        return plain("⚠ ", DANGER).append(bold(text, NamedTextColor.WHITE));
    }

    @Override
    public void close() {
        for (Player p : viewers.values()) {
            p.hideBossBar(health);
            p.hideBossBar(mechanic);
        }
        viewers.clear();
    }

    public boolean mechanicVisible() {
        return mechanicVisible;
    }
}
