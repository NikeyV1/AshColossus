# Modrinth upload kit

Everything for the Modrinth project page lives here.

| File | Use |
| --- | --- |
| `icon.png` | Project icon (512x512) |
| `banner.png` | Wide banner, also used in the description |
| `gallery/*.png` | Gallery images (1920x1080). Set `01-ash-colossus.png` as the featured image |
| `DESCRIPTION.md` | Project description (Markdown body) |
| `CHANGELOG.md` | Version changelog, copy the 1.0.0 block into the version form |

## Suggested project settings

- **Name:** Ash Colossus (Wither Boss)
- **Summary:** A three-phase Wither raid boss with custom model, 21 attacks, in-game loot editor and difficulty tuning. Resource pack included.
- **Project type:** Plugin
- **Loaders:** Paper
- **Game version:** 26.2
- **Categories:** Game mechanics, Adventure, Mobs, Equipment
- **Environment:** Server
- **License:** GPL-3.0-only, link https://www.gnu.org/licenses/gpl-3.0.html
- **Links:** Source https://github.com/NikeyV1/AshColossus, Issues https://github.com/NikeyV1/AshColossus/issues
- **File to upload:** `target/WitherBoss-1.0.0.jar` (after `mvn verify`)

Also upload `resource-pack/WitherBoss-Pack-1.0.0.zip` as an additional file of the version or as a separate resource pack project if you want it listed on its own.

Gallery titles and descriptions:

1. `01-ash-colossus.png`: "Ash Colossus" / A three-phase Wither raid boss for Paper servers.
2. `02-three-phases.png`: "Three phases" / Embers, Consuming Hammer, Core Meltdown.
3. `03-attacks.png`: "21 attacks" / Every attack is telegraphed.
4. `04-hammerfall.png`: "Hammerfall" / Leave the lane before the hammer lands.
5. `05-server-owner-features.png`: "Built for server owners" / Loot editor, presets, triggers, safe defaults.
6. `06-config-and-loot.png`: "Config and loot" / One switch for the feel, every detail one line away.
7. `07-victory.png`: "Victory" / Loot for everyone who fought.

The images are rendered from the boss model.
