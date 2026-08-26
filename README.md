<div align="center">
  <h1>mClans</h1>
  <p>Persistent, levelled clans for modern Minecraft networks.</p>

  <p>
    <a href="https://papermc.io/software/paper"><img alt="Paper" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/paper_vector.svg"></a>
    <a href="https://purpurmc.org"><img alt="Purpur" height="56" src="https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/purpur_vector.svg"></a>
    <a href="https://papermc.io/software/folia"><img alt="Folia" height="56" src="https://raw.githubusercontent.com/miklires/mCommand/main/docs/assets/folia-available.png"></a>
  </p>
</div>

## What it does

- Implements create, invite, join, leave, kick, promote, demote and confirmed disband flows.
- Enforces unique names/tags, reserved words, owner protection and role hierarchy.
- Starts clans at five members and expands the configured limit through point levels.
- Awards PvP points with a per-killer/per-victim anti-farm cooldown.
- Provides Folia-safe friendly-fire protection and private clan chat.
- Stores the clan bank and every balance change in an append-only ledger.
- Shows clan progression and members in a read-only GUI.
- Exposes membership and ally checks through Bukkit Services.

This release focuses on the dependable social core. Claims, wars, auctions and cross-proxy synchronization are intentionally left for later releases.

## Requirements

- Java 25
- Paper, Purpur or Folia 26.2

Vault, mReputation, mBadges and PlaceholderAPI are optional integration targets. The core plugin starts without them.

## Commands

- `/clan create <name> <tag>`
- `/clan invite <player>` and `/clan accept <name|tag>`
- `/clan info [name|tag]`
- `/clan leave`, `/clan kick <player>`
- `/clan promote|demote <player>`
- `/clan ff <on|off>`
- `/clan bank`
- `/clan disband confirm`
- `/cc <message>`
- `/clan reload`

Permissions: `mclans.use`, `mclans.admin`, `mclans.reload`.

## Storage

Clan state uses a versioned local H2 database on a dedicated virtual thread. Creation and joining are transactional. Names and tags are unique at the database layer, member capacity is rechecked while accepting an invite, a clan owner cannot silently leave, and the bank cannot become negative.

## Build

```bash
./gradlew clean build
```

The plugin JAR is written to `build/libs`; the Bukkit Services API is built in `api/build/libs`.

Licensed under the MIT License.
