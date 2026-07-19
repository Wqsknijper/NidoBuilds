# NidoBuilds

NidoBuilds is the static build-service plugin for Nidocraft. Editable Bukkit worlds live only on `build-1`; every manual save, autosave, publish, pre-restore and delete produces an immutable Sponge `.schem` version under `Network/data/builds/versions`. Metadata, gamemode assignments and audit events are stored in MongoDB.

## Main flows

- `/buildserver` connects an authorized Designer, Admin or Owner from another network service to `build-1`.
- `/build` opens the 5-row world menu. The top 4 rows contain worlds; the bottom row contains sort, status filter, sign search, paging, refresh and creation.
- `/build create <id> [icon] [name]`, `/build load [id]`, `/build save [id]`, `/build ready [id]`, `/build publish [id]`, `/build delete <id>`.
- `/build spawn <set|remove|default> <world> <id>` and `/build npc <set|remove> <world> <id>` store deployable locations.
- `/build backup list <world>` and `/build backup load <world> <version>` are admin/owner operations. Restore first creates a new pre-restore backup.
- `/build gamemode toggle <world> <gamemode>` selects a world for a gamemode. After publishing, `activate` selects the exact immutable version that new services load.
- `/buildupload` generates an expiring link for one validated `.schem`; `/buildupload paste` consumes it after one successful paste.

The export and import paths enable entity copying, so armor stands and other schematic entities are retained. Deleted world folders are archived and their final schematic backup is never removed automatically.
