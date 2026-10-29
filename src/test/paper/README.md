# Paper runtime checks

A separate test plugin for a **local test server only**. It is not part of the production jar.

It drives the real boss code on a Paper server with six player test objects (proxies, no network clients): intro, every attack, group mechanics (chain lock, comet sharing, brand spreading, pillar cover, shockwave jumps), phase gates, melt boundary, death, revive, logout, late join, wipe, meltdown, victory, cleanup, damage calibration against a full netherite Protection IV reference, and the full preparation, start, victory, loot and cooldown flow.

1. Build the production plugin with `mvn package`. Compile `WitherRuntimeChecks.java` against the plugin classes and Paper dependencies, and package the result with `wither-plugin.yml` as `plugin.yml` into a separate test-plugin jar.
2. Put `WitherBoss-1.0.0.jar` and `WitherRuntimeChecks.jar` into `plugins/` of an empty Paper 26.2 test server (accepted EULA, `timeout-time` in `spigot.yml` raised to 3600).
3. Start it. The log ends with `WITHER_RUNTIME_CHECKS_PASS checks=...` or lists `FAILED:` lines.
4. Stop the server and remove the check jar.

Not covered: client rendering, real network physics, TPS under load.
