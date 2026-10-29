package de.nikey.witherboss.boss;

import de.nikey.witherboss.util.AtomicFiles;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

public final class PackInstaller {
    public static final String FILE = "wither-boss-pack.zip";

    private PackInstaller() {}

    public static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    public static boolean automatic(String configured) {
        return configured == null || configured.isBlank() || configured.equalsIgnoreCase("auto");
    }

    public static String install(JavaPlugin plugin, String configured) throws Exception {
        byte[] embedded = null;
        String version = "?";
        try (InputStream meta = plugin.getResource("pack.properties")) {
            if (meta != null) {
                Properties p = new Properties();
                p.load(meta);
                version = p.getProperty("version", "?");
                try (InputStream zip = plugin.getResource("pack/" + p.getProperty("file", ""))) {
                    if (zip != null) embedded = zip.readAllBytes();
                }
            }
        }
        return install(plugin.getDataFolder().toPath(), embedded, version, configured, plugin.getLogger());
    }

    static String install(Path folder, byte[] embedded, String version, String configured, Logger log) throws Exception {
        Files.createDirectories(folder);
        Path target = folder.resolve(FILE);
        String current = Files.exists(target) ? sha1(Files.readAllBytes(target)) : null;
        if (embedded != null) {
            String hash = sha1(embedded);
            if (!hash.equals(current)) {
                if (current != null)
                    Files.copy(target, folder.resolve(FILE + "." + current.substring(0, 8) + ".bak"), StandardCopyOption.REPLACE_EXISTING);
                Path temp = folder.resolve(FILE + ".tmp");
                Files.write(temp, embedded);
                AtomicFiles.replace(temp, target);
                if (log != null) log.info("Resource pack " + version + " installed, SHA1 " + hash);
            }
            current = hash;
        }
        if (automatic(configured)) {
            if (current == null) throw new IOException("No resource pack found in the plugin jar or data folder.");
            return current;
        }
        String explicit = configured.toLowerCase(Locale.ROOT);
        if (log != null && current != null && !explicit.equals(current))
            log.warning("The configured resource-pack.sha1 differs from the installed pack. Set it to auto unless you host a modified pack.");
        return explicit;
    }
}
