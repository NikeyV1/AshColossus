package de.nikey.witherboss.discord;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import de.nikey.witherboss.WitherBossPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class DiscordNotifier {
    static final int RED = 0xE64B55, GOLD = 0xF2B84B;
    private static final String URL_PATTERN = "https://(?:(?:ptb|canary)\\.)?discord(?:app)?\\.com/api/webhooks/\\d+/[\\w-]+";

    private final WitherBossPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Set<CompletableFuture<?>> pending = ConcurrentHashMap.newKeySet();
    private boolean warned;

    public DiscordNotifier(WitherBossPlugin plugin) {
        this.plugin = plugin;
    }

    public record Field(String name, String value, boolean inline) {}

    public static boolean validUrl(String url) {
        return url != null && url.matches(URL_PATTERN);
    }

    static String escape(String text) {
        return text.replaceAll("([\\\\*_~`|>])", "\\\\$1");
    }

    static String clock(long seconds) {
        long s = Math.max(0, seconds);
        return s / 60 + ":" + String.format("%02d", s % 60);
    }

    static String list(Collection<String> names, String empty) {
        if (names.isEmpty()) return empty;
        StringBuilder result = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            String line = "• " + escape(name) + "\n";
            if (result.length() + line.length() > 980) break;
            result.append(line);
            shown++;
        }
        if (shown < names.size()) result.append("… and ").append(names.size() - shown).append(" more");
        return result.toString().strip();
    }

    static JsonObject embed(String title, String description, int color, String footer, List<Field> fields) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("description", description);
        embed.addProperty("color", color);
        embed.addProperty("timestamp", Instant.now().toString());
        JsonObject foot = new JsonObject();
        foot.addProperty("text", footer);
        embed.add("footer", foot);
        JsonArray array = new JsonArray();
        for (Field field : fields) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", field.name());
            entry.addProperty("value", field.value());
            entry.addProperty("inline", field.inline());
            array.add(entry);
        }
        embed.add("fields", array);
        return embed;
    }

    static JsonObject summonedEmbed(String summoner, int seconds, int minimum, int inArena, Instant now, String footer) {
        Instant decision = now.plusSeconds(seconds);
        return embed(
                "⚔️ Ash Colossus event started",
                "**" + escape(summoner) + "** has awakened the Ash Colossus. A boss event is starting on the server.",
                RED,
                footer,
                List.of(
                        new Field("⏳ Decision", "<t:" + decision.getEpochSecond() + ":t> · <t:" + decision.getEpochSecond() + ":R>", true),
                        new Field("👥 Already in the arena", Integer.toString(inArena), true),
                        new Field("⚔️ At least", minimum + " confirmed fighters", true),
                        new Field("🚪 Join", "Click **[Join]** in chat or type `/witherboss join`. Taking part is at your own risk.", false)));
    }

    static JsonObject defeatedEmbed(List<String> fighters, long seconds, boolean first, String footer) {
        return embed(
                "🏆 Ash Colossus defeated",
                first ? "The Ash Colossus was defeated for the first time on this server." : "The Ash Colossus was defeated.",
                GOLD,
                footer,
                List.of(new Field("⏱️ Fight duration", clock(seconds), true), new Field("👥 Fighters (" + fighters.size() + ")", list(fighters, "Nobody"), false)));
    }

    private boolean enabled(String event) {
        var config = plugin.getConfig();
        if (!config.getBoolean("discord.enabled") || !config.getBoolean("discord.events." + event, true)) return false;
        if (validUrl(config.getString("discord.webhook-url", ""))) return true;
        if (!warned) {
            warned = true;
            plugin.getLogger().warning("discord.webhook-url is not a valid Discord webhook URL; announcements are disabled.");
        }
        return false;
    }

    private String footer() {
        String name = plugin.getConfig().getString("discord.username", "");
        return (name.isBlank() ? "Ash Colossus" : name) + " · Boss event · Ash Colossus by NikeyV1";
    }

    public void bossSummoned(String summoner, int seconds, int minimum, int inArena) {
        if (!enabled("boss-summoned")) return;
        post(summonedEmbed(summoner, seconds, minimum, inArena, Instant.now(), footer()));
    }

    public void bossDefeated(List<String> fighters, long seconds, boolean first) {
        if (!enabled("boss-defeated")) return;
        post(defeatedEmbed(fighters, seconds, first, footer()));
    }

    private void post(JsonObject embed) {
        var config = plugin.getConfig();
        JsonObject payload = new JsonObject();
        String username = config.getString("discord.username", "");
        String avatar = config.getString("discord.avatar-url", "");
        if (!username.isBlank()) payload.addProperty("username", username);
        if (avatar.startsWith("https://")) payload.addProperty("avatar_url", avatar);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", mentions);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.getString("discord.webhook-url")))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        CompletableFuture<HttpResponse<Void>> future = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
        pending.add(future);
        future.whenComplete((response, error) -> {
            pending.remove(future);
            if (error != null) plugin.getLogger().warning("Discord announcement failed: " + error.getClass().getSimpleName());
            else if (response.statusCode() >= 300) plugin.getLogger().warning("Discord announcement rejected: HTTP " + response.statusCode());
        });
    }

    public void flush() {
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
