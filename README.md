# NidoBuilds

NidoBuilds is the static build-service plugin for Nidocraft. Editable Bukkit worlds live only on `build-1`; every manual save, autosave, publish, pre-restore and delete produces an immutable Sponge `.schem` version under `Network/data/builds/versions`. Metadata, gamemode assignments and audit events are stored in MongoDB.

## Main flows

- `/buildserver` connects an authorized Designer, Admin or Owner from another network service to `build-1`.
- `/build` opens the 5-row world menu. The top 4 rows contain worlds; the bottom row contains sort, status filter, sign search, paging, refresh and creation.
- Every world detail screen has a paginated backup browser. Each immutable snapshot shows its type, creator, UTC timestamp, age, size and checksum. Admins/owners can restore it in place after confirmation, recover it as a separate new world, or create a short-lived single-use download link while they remain online. Deleted worlds remain available through **Deleted worlds** and keep all snapshots forever.
- `/build create <id> [icon] [name]`, `/build load [id]`, `/build save [id]`, `/build ready [id]`, `/build publish [id]`, `/build delete <id>`.
- `/build spawn <set|remove|default> <world> <id>` and `/build npc <set|remove> <world> <id>` store deployable locations.
- Configured spawns appear in loaded build worlds as runtime-only holograms with their id and coordinates; the default spawn is highlighted. Markers are removed before save/publish/backup and never written into schematics.
- `/build backup list <world>` and `/build backup load <world> <version>` are admin/owner operations. Restore first creates a new pre-restore backup.
- `/build gamemode toggle <world> <gamemode>` selects or unselects a world for a gamemode; it does not deploy it. After publishing, `/build gamemode activate <world> <gamemode>` selects the exact immutable version that new or restarted services load.
- `/build gamerule list [world]` and `/build gamerule <world> <rule> <value>` inspect or change any boolean/integer gamerule. The detail menu also has a Game rules screen.
- `/buildupload` generates an expiring link for one validated `.schem`; `/buildupload paste` pastes it at the player's current location and consumes it after one successful paste. `/buildupload undo` restores the immutable pre-paste version.

The export and import paths enable entity copying, so armor stands and other schematic entities are retained. Deleted world folders are archived and their final schematic backup is never removed automatically.

Fresh void worlds and the build lobby use a 5x5 barrier platform at Y=10 with the spawn at Y=11.

Every build starts with weather/daylight cycles, mob spawning, mob loot, advancement announcements, fire tick and spectator chunk generation disabled, plus `randomTickSpeed=0`. Overrides are stored with the world in MongoDB and NidoCore reapplies them when a published map is deployed.
