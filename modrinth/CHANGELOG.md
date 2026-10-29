# Changelog

## 1.0.0

First public release, extracted from the Buffed SMP "Strength Devourer" raid boss and reworked for general servers.

- New standalone plugin `WitherBoss`, no other plugin needed
- Fully translated to English (chat, titles, boss bar, Discord, resource pack subtitles)
- Boss renamed to **Ash Colossus**, all attacks renamed accordingly
- In-game loot editor (`/witherboss loot`) with persistent `loot.yml`: items, commands, XP, chance, amount, scope
- Rewards are stored as claims and delivered safely (`/witherboss claim`, delivery on join)
- Difficulty presets and a complete `config.yml`: boss HP, damage per attack and phase, enrage, minions, revives, time limit, meltdown, pauses, disabled attacks, retribution, player damage multipliers
- Rebalanced defaults: late-fight damage is roughly half of the original, more revives, longer time limit, weaker sentinels
- Trigger modes `SIGIL` (new default), `WITHER_BUILD` and `COMMAND`, craftable Colossus Sigil
- Cooldown, time window, allowed worlds, one-time mode, minimum players, refunds for failed starts
- Items are never lost by default, hardcore options available
- Configurable resource pack delivery: repository download, own URL, built-in host, or disabled
- Discord announcements for summoning and victory
- Credit line for fighters at the start and end of a fight, `/witherboss about`
- Boss now reacts to vanilla weapons: axes stun minions, shields destroy close skulls, Fire Aspect burns
- New arena world name `wither_boss_forge`, new data folder `plugins/WitherBoss`
