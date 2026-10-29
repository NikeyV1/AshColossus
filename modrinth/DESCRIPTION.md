# Ash Colossus · Wither Boss

![Ash Colossus](https://raw.githubusercontent.com/NikeyV1/AshColossus/master/modrinth/banner.png)

**A three-phase Wither raid boss for Paper servers, with its own model, animations, music and arena. Resource pack included.**

## Where it comes from

Ash Colossus was originally built for **our own server, Buffed SMP**, as a one-time server event. Our group fought it, learned it, and had a great time doing it. We are publishing it now because we want other servers to get the chance to face this boss too. It is free and open source, and the resource pack is included, so you can run it with one jar.

This public version is not a copy of our event. We rebuilt the parts a server owner needs: loot you define in-game, difficulty settings, tuning for every number, safe defaults, cooldowns and more.

## Features

- **Three phases, 21 attacks.** Every attack is telegraphed: red zones fill up before they hit, gold marks what you should do. Soul Chain, Grave Fist, Hammerfall, Consuming Comet, Ember Grid, Consuming Storm and many more.
- **Minions and mechanics.** Ash Sentinels and the Ashbreaker, Consuming Beacons that shield the boss, a golden heart you must expose, a melting arena rim, shared revives with soul lanterns.
- **Custom loot, in-game.** `/witherboss loot` opens an editor. Drop in any item (enchantments, names and custom data are kept), set the chance, the amount and who receives it. Add command rewards and XP. Everything is saved to `loot.yml`. Rewards are stored before they are handed out, so a full inventory or a crash never loses loot.
- **Difficulty that is fair.** Presets EASY, NORMAL, HARD and NIGHTMARE. On Buffed SMP the boss was easy until half of its health and then nearly impossible, we needed Turtle Master potions. The default is tuned so the second half is demanding but fair for players in diamond or netherite.
- **Tune everything.** Boss HP per player, damage per attack and per phase, enrage speed, minion health, damage and count, revive charges, time limit, meltdown timer, pause between attacks, disabled attacks. `/witherboss config set` changes values live.
- **Made for servers.** Trigger via Colossus Sigil (vanilla Withers stay untouched), classic Wither build, or command. Cooldown, daily time window, allowed worlds, one-time event mode, refunds when a fight never starts, Discord webhook announcements.
- **Resource pack included.** Models, animations, sounds and music ship inside the jar and are offered when players enter the arena. Host it yourself, use the default download, or let the plugin serve it.
- **Safe by default.** Players never lose items in this fight unless you switch hardcore options on.

## Quick start

1. Paper 26.2, Java 25. Drop the jar into `plugins/`, start the server.
2. Craft a **Colossus Sigil** (4 Wither skeleton skulls, 4 soul sand, 1 nether star) or run `/witherboss give sigil`.
3. Hold it in your other hand while building a Wither. Gather at least two fighters.

Prefer the classic behaviour where every Wither build awakens the boss? Set `event.trigger: WITHER_BUILD`. Staff can use `/witherboss summon` any time and `/witherboss rehearse` for a private run.

## Commands and permissions

`/witherboss` (aliases `/wboss`, `/colossus`): `info`, `join`, `accept`, `leave`, `claim`, `pack`, `about` for everyone. Staff: `summon`, `rehearse`, `test`, `stop`, `loot`, `give`, `difficulty`, `config`, `cooldown`, `reload`, `arena`. Permissions `witherboss.admin` and `witherboss.summon`.

## Links

- Source code and docs: https://github.com/NikeyV1/AshColossus
- Fight guide: https://github.com/NikeyV1/AshColossus/blob/master/docs/FIGHT-GUIDE.md
- Issues and ideas: https://github.com/NikeyV1/AshColossus/issues

## Credits

Ash Colossus by **NikeyV1** for **Buffed SMP**. Code under GPL-3.0. Models, textures, animations, sounds and music are original work released as CC0. Minecraft is a trademark of Mojang Studios, this project is not affiliated with Mojang or Microsoft.
