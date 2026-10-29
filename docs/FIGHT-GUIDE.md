# Ash Colossus: fight guide

How the fight works, with the numbers from the default `config.yml`. Damage values are the **base** values (`damage.attacks.*`). The final hit is base x `damage.global-multiplier` x phase multiplier (0.9 / 0.7 / 0.6) x enrage x difficulty preset, before armor, enchantments and absorption.

## Flow

1. **Summoning.** See the README. Conditions: enough players online (`event.min-online`), optional time window, cooldown, allowed world. Nothing is consumed unless all conditions are met.
2. **Preparation (45 s).** The summoner and everyone nearby are pulled into the arena, everybody else gets a clickable **[Join]**. Pulled-in players count as agreed and can leave with `/witherboss leave`. Waiting players cannot be hurt. The resource pack loads during this time.
3. **Start.** With at least `event.min-participants` confirmed fighters the boss assembles itself (14 s intro, invulnerable). Otherwise everyone goes back and the summoning materials are returned (`event.refund-on-failed-start`).
4. **Fight.** Three phases, max 25 minutes.
5. **Victory.** Loot is rolled for every eligible fighter, handed out, and the cooldown starts.

## Phases

| Phase | HP | Notes |
| --- | --- | --- |
| Intro | 100 % | Invulnerable. Skulls fly in, the colossus assembles and roars. |
| I · Embers | 100 to 66 % | Single attacks with long telegraphs, first sentinel waves. |
| Break | at 66 % | Invulnerable for about 5 s, he tears the Consuming Hammer from the forge. |
| II · Consuming Hammer | 66 to 33 % | Hammer attacks, beacons, ember grid, miasma. Companion attacks become common. |
| Meltdown | at 33 % | Invulnerable for about 6 s, the hammer shatters. |
| III · Core Meltdown | 33 to 0 % | Heart permanently exposed, body takes 80 %. The rim melts in three steps, a final meltdown ends the fight after `fight.meltdown.after-seconds`. |
| Victory | 0 % | Death animation, music, loot. |

Phase thresholds are hard: overkill damage is discarded, one huge hit cannot skip a phase.

## Health and damage

- Boss HP: `health.per-player` (2000) x fighters, never below `health.min-scaling-players`. Late joiners raise the maximum, progress is kept.
- Your damage: the armored body takes 50 % (80 % in phase III), the golden heart takes 150 %. The heart is exposed after heavy attacks (Grave Fist, Hammerfall, a cut Soul Chain, destroyed beacons) and for good in phase III. Hit it with the crosshair or an arrow.
- Beacons shield the boss while they stand (35 % damage taken at four beacons).
- **Retribution:** if three fighters deal more than 0.5 % of the boss HP within 2.5 s, a red ring around the boss answers. Do not all stack on him.
- Fighters with an axe stun minions and extend the exhaustion window once by 1 s, a raised shield destroys skulls that come close.

## Enrage

Every 75 s the colossus gets angrier (max 5 stages): +2 % damage per stage, shorter pauses, more helpers and zones. Configure with `enrage.*`.

## Death, revives and logout

Dead fighters respawn in the waiting gallery. A soul lantern with the name appears at the arena entrance. A living fighter right-clicks it and stands still for 5 s to bring the fighter back. The pool is shared (`fight.revive.charges`, 5 by default). Revived fighters get 3 s of protection. If everyone is down, the group is defeated.

Items and XP are safe by default. Hardcore servers can enable `death.lose-inventory-on-defeat` and `death.lose-inventory-on-logout`. Logging out counts as dropping out, coming back within 60 s waits for a revive.

## Attacks

| Attack | Phase | What to do | Base dmg |
| --- | --- | --- | --- |
| Ash Slash | I, III | Red 110 degree cone. Get behind or beside the boss. | 38.1 |
| Grave Fist | I, III | Circle follows a target, then a shockwave. Jump over the wave. Boss is exhausted afterwards. | 39.7 / 29.8 |
| Soul Beam | all | Lane locks onto you. Leave it or hide behind a pillar. | 43.1 |
| Consuming Skulls | all | Homing skulls. Smash them (2 to 3 hits, arrows work), shield them, or hook around. | 29.8 |
| Soul Chain | all | Pulls a player in. Hit the golden lock three times to cut it, otherwise it hurts and heals the boss. | 43.1 |
| Bone Spikes | all | A line of spikes grows toward you. Run sideways. | 34.8 |
| Forge Collapse | all | Boulders from the pillars. Leave the red circles. | 39.7 |
| Chain Wheel | all | Two rotating chains. Jump over or hide behind a pillar, pillars stop chains. | 38.1 |
| Ash Sentinels | all | Summons wither skeletons (and an Ashbreaker with 5 or more fighters). Kill them. | 16.6 |
| Hammerfall | II | 5-wide lane then a fissure. Leave the lane, do not walk back through the fissure. | 46.4 / 26.5 |
| Ember Whirl | II, III | Ring around the boss. Get close, the center is safe. | 38.1 |
| Core Nova | II, III | Circle around the boss. Keep your distance. | 44.7 |
| Ember Rain | II, III | Three waves of circles. Dodge. | 29.8 |
| Consuming Comet | II, III | Gather in the golden circle, damage is split. Too few means pain for everyone. | 48.0 |
| Brand Mark | II, III | Marked players get a circle that follows them. Spread out. | 31.5 |
| Ember Grid | II, III | Red lanes fill up. Stand between lanes, jumping saves you. | 41.4 |
| Consuming Miasma | II, III | Poison fog circles that grow. Leave and do not come back. | 10.8 per tick |
| Consuming Beacons | II, III | Beacons ring the arena, boss takes less damage. Destroy all together. They overload after 45 s. | 30.0 / 44.7 |
| Consuming Storm | III | Three rotating beams. Run with the rotation, pillars block. | 24.8 |
| Consuming Maw | III | Pull toward the center then a blast. Run against the pull or hide behind a pillar. | 48.0 |
| Retribution | reaction | See above. | 39.7 |
| Melting rim | III | Safe radius shrinks to 30, 24, 18 blocks. Stand inside. | 33.1 per second |

Use `/witherboss test <arena> <attack>` to watch a single attack, and `fight.disabled-attacks` to remove attacks you do not want.

## Arena

The plugin builds the arena `forge` in its own world: a 36-block fighting circle with three cover pillars, a lava moat, spectator stands, a wall with twelve towers and a waiting gallery in the north. Players cannot leave while alive, build, or use ender pearls, elytra, chorus fruit or portals during the fight. Natural monsters do not spawn in arena worlds.

## Performance

The boss uses display entities. The plugin caps effects per attack, removes everything after the fight and stops the fight safely when the TPS drops below `event.minimum-tps`.
