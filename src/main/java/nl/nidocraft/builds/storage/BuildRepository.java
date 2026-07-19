package nl.nidocraft.builds.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import nl.nidocraft.builds.model.BuildLocation;
import nl.nidocraft.builds.model.BuildStatus;
import nl.nidocraft.builds.model.BuildVersion;
import nl.nidocraft.builds.model.BuildWorld;
import nl.nidocraft.builds.world.BuildGameRules;
import org.bson.Document;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

public final class BuildRepository implements AutoCloseable {
    private final MongoClient client;
    private final MongoCollection<Document> worlds;
    private final MongoCollection<Document> versions;
    private final MongoCollection<Document> gamemodes;
    private final MongoCollection<Document> audit;
    private final MongoCollection<Document> uploads;

    public BuildRepository(String uri, String databaseName) {
        client = MongoClients.create(uri);
        MongoDatabase database = client.getDatabase(databaseName);
        database.runCommand(new Document("ping", 1));
        worlds = database.getCollection("build_worlds");
        versions = database.getCollection("build_versions");
        gamemodes = database.getCollection("gamemodes");
        audit = database.getCollection("build_audit");
        uploads = database.getCollection("build_uploads");
        worlds.createIndex(Indexes.ascending("name"));
        versions.createIndex(Indexes.compoundIndex(Indexes.ascending("worldId"), Indexes.descending("number")));
        versions.createIndex(Indexes.compoundIndex(Indexes.ascending("worldId"), Indexes.ascending("number")), new IndexOptions().unique(true));
        audit.createIndex(Indexes.descending("createdAt"));
        uploads.createIndex(Indexes.ascending("expiresAt"));
        seedGamemode("lobby", "Lobby");
        seedGamemode("build", "Build");
        ensureGameRuleDefaults();
    }

    public synchronized BuildWorld create(String id, String name, String icon, int radius, UUID actor) {
        if (worlds.find(eq("_id", id)).first() != null) throw new IllegalArgumentException("World-id bestaat al.");
        long now = System.currentTimeMillis();
        Document value = new Document("_id", id).append("name", name).append("status", BuildStatus.EMPTY.name())
                .append("icon", icon).append("theme", "Unspecified").append("radius", radius)
                .append("gamemodes", List.of()).append("spawns", List.of()).append("npcs", List.of())
                .append("gameRules", new Document(BuildGameRules.defaults()))
                .append("defaultSpawnId", null).append("currentVersion", 0L).append("publishedVersion", null)
                .append("updatedAt", now).append("deleted", false).append("createdBy", actor.toString());
        worlds.insertOne(value);
        audit(actor, "WORLD_CREATE", id, new Document("name", name).append("icon", icon).append("radius", radius));
        return decodeWorld(value);
    }

    public Optional<BuildWorld> find(String id) {
        Document document = worlds.find(eq("_id", id.toLowerCase(Locale.ROOT))).first();
        return Optional.ofNullable(document).map(this::decodeWorld);
    }

    public List<BuildWorld> list(boolean includeDeleted) {
        List<BuildWorld> result = new ArrayList<>();
        for (Document document : includeDeleted ? worlds.find() : worlds.find(ne("deleted", true))) result.add(decodeWorld(document));
        result.sort(Comparator.comparing(BuildWorld::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public synchronized long nextVersion(String worldId) {
        BuildWorld world = find(worldId).orElseThrow();
        return world.currentVersion() + 1;
    }

    public synchronized void addVersion(BuildVersion version, BuildStatus status) {
        versions.insertOne(new Document("_id", version.id()).append("worldId", version.worldId())
                .append("number", version.number()).append("kind", version.kind())
                .append("schematic", version.schematic().toAbsolutePath().normalize().toString())
                .append("createdBy", version.createdBy().toString()).append("createdAt", version.createdAt().toEpochMilli())
                .append("size", version.size()).append("sha256", version.sha256()).append("immutable", true));
        worlds.updateOne(eq("_id", version.worldId()), combine(set("currentVersion", version.number()),
                set("status", status.name()), set("updatedAt", System.currentTimeMillis())));
        audit(version.createdBy(), "WORLD_SAVE_" + version.kind().toUpperCase(Locale.ROOT), version.worldId(),
                new Document("version", version.number()).append("sha256", version.sha256()).append("size", version.size()));
    }

    public List<BuildVersion> versions(String worldId) {
        List<BuildVersion> result = new ArrayList<>();
        for (Document document : versions.find(eq("worldId", worldId)).sort(new Document("number", -1))) result.add(decodeVersion(document));
        return result;
    }

    public Optional<BuildVersion> version(String worldId, long number) {
        return Optional.ofNullable(versions.find(and(eq("worldId", worldId), eq("number", number))).first()).map(this::decodeVersion);
    }

    public void publish(String worldId, long version, UUID actor) {
        worlds.updateOne(eq("_id", worldId), combine(set("publishedVersion", version), set("status", BuildStatus.PUBLISHED.name()), set("updatedAt", System.currentTimeMillis())));
        audit(actor, "WORLD_PUBLISH", worldId, new Document("version", version));
    }

    public void markDeleted(String worldId, UUID actor) {
        worlds.updateOne(eq("_id", worldId), combine(set("deleted", true), set("status", BuildStatus.DELETED.name()), set("updatedAt", System.currentTimeMillis())));
        gamemodes.updateMany(eq("activeWorldId", worldId), combine(set("activeWorldId", null), set("activeVersion", null)));
        audit(actor, "WORLD_DELETE", worldId, new Document("backupRetainedForever", true));
    }

    public void setStatus(String worldId, BuildStatus status, UUID actor) {
        worlds.updateOne(eq("_id", worldId), combine(set("status", status.name()), set("updatedAt", System.currentTimeMillis())));
        audit(actor, "WORLD_STATUS", worldId, new Document("status", status.name()));
    }

    public void setText(String worldId, String field, String value, UUID actor) {
        if (!Set.of("name", "theme", "icon").contains(field)) throw new IllegalArgumentException("Onveilig veld.");
        worlds.updateOne(eq("_id", worldId), combine(set(field, value), set("updatedAt", System.currentTimeMillis())));
        audit(actor, "WORLD_EDIT_" + field.toUpperCase(Locale.ROOT), worldId, new Document("value", value));
    }

    public void setLocations(String worldId, String field, List<BuildLocation> locations, String defaultSpawnId, UUID actor) {
        if (!Set.of("spawns", "npcs").contains(field)) throw new IllegalArgumentException("Onveilig locatieveld.");
        List<Document> encoded = locations.stream().map(BuildLocation::toDocument).toList();
        List<org.bson.conversions.Bson> updates = new ArrayList<>(List.of(set(field, encoded), set("updatedAt", System.currentTimeMillis())));
        if (field.equals("spawns")) updates.add(set("defaultSpawnId", defaultSpawnId));
        worlds.updateOne(eq("_id", worldId), combine(updates));
        audit(actor, "WORLD_EDIT_" + field.toUpperCase(Locale.ROOT), worldId, new Document("count", locations.size()).append("default", defaultSpawnId));
    }

    public void setGameRule(String worldId, String ruleName, String value, UUID actor) {
        String name = BuildGameRules.normalizeName(ruleName); String normalized = BuildGameRules.normalizeValue(name, value);
        worlds.updateOne(eq("_id", worldId), combine(set("gameRules." + name, normalized), set("status", BuildStatus.EDITED.name()), set("updatedAt", System.currentTimeMillis())));
        audit(actor, "WORLD_GAMERULE_SET", worldId, new Document("rule", name).append("value", normalized));
    }

    public List<Document> gamemodes() {
        List<Document> result = new ArrayList<>();
        gamemodes.find().sort(new Document("name", 1)).into(result);
        return result;
    }

    public void toggleGamemode(String worldId, String gamemodeId, UUID actor) {
        Document game = gamemodes.find(eq("_id", gamemodeId)).first();
        if (game == null) throw new IllegalArgumentException("Onbekende gamemode: " + gamemodeId);
        BuildWorld world = find(worldId).orElseThrow();
        List<String> selected = new ArrayList<>(world.gamemodes());
        if (!selected.remove(gamemodeId)) selected.add(gamemodeId);
        worlds.updateOne(eq("_id", worldId), combine(set("gamemodes", selected), set("updatedAt", System.currentTimeMillis())));
        audit(actor, "WORLD_GAMEMODE_TOGGLE", worldId, new Document("gamemode", gamemodeId).append("selected", selected.contains(gamemodeId)));
    }

    public void activateForGamemode(String worldId, String gamemodeId, UUID actor) {
        BuildWorld world = find(worldId).orElseThrow();
        if (world.publishedVersion() == null) throw new IllegalStateException("Publiceer deze world eerst.");
        if (!world.gamemodes().contains(gamemodeId)) throw new IllegalStateException("Selecteer deze gamemode eerst voor de world.");
        gamemodes.updateOne(eq("_id", gamemodeId), combine(set("activeWorldId", worldId), set("activeVersion", world.publishedVersion()), set("updatedAt", System.currentTimeMillis())));
        audit(actor, "GAMEMODE_WORLD_ACTIVATE", worldId, new Document("gamemode", gamemodeId).append("version", world.publishedVersion()));
    }

    public void audit(UUID actor, String action, String worldId, Document details) {
        audit.insertOne(new Document("createdAt", System.currentTimeMillis()).append("actorId", actor == null ? "system" : actor.toString())
                .append("action", action).append("worldId", worldId).append("details", details == null ? new Document() : details));
    }

    public MongoCollection<Document> uploads() { return uploads; }

    private void seedGamemode(String id, String name) {
        if (gamemodes.find(eq("_id", id)).first() == null) gamemodes.insertOne(new Document("_id", id).append("name", name).append("system", true));
    }

    private BuildWorld decodeWorld(Document document) {
        List<BuildLocation> spawns = documents(document, "spawns").stream().map(BuildLocation::from).toList();
        List<BuildLocation> npcs = documents(document, "npcs").stream().map(BuildLocation::from).toList();
        Number current = document.get("currentVersion", Number.class);
        Number published = document.get("publishedVersion", Number.class);
        Number updated = document.get("updatedAt", Number.class);
        return new BuildWorld(document.getString("_id"), document.getString("name"), BuildStatus.valueOf(document.getString("status")),
                document.getString("icon"), document.getString("theme"), document.getInteger("radius", 64),
                document.getList("gamemodes", String.class, List.of()), spawns, npcs, document.getString("defaultSpawnId"), gameRules(document),
                current == null ? 0 : current.longValue(), published == null ? null : published.longValue(),
                updated == null ? 0 : updated.longValue(), document.getBoolean("deleted", false));
    }

    private Map<String, String> gameRules(Document document) {
        Map<String, String> result = new LinkedHashMap<>(BuildGameRules.defaults());
        Document stored = document.get("gameRules", Document.class);
        if (stored != null) stored.forEach((name, value) -> { if (value != null) result.put(name, String.valueOf(value)); });
        return Map.copyOf(result);
    }

    private void ensureGameRuleDefaults() {
        for (Document world : worlds.find()) {
            Document stored = world.get("gameRules", Document.class); Document merged = new Document(BuildGameRules.defaults());
            if (stored != null) stored.forEach(merged::put);
            worlds.updateOne(eq("_id", world.getString("_id")), set("gameRules", merged));
        }
    }

    @SuppressWarnings("unchecked")
    private List<Document> documents(Document document, String key) {
        Object value = document.get(key);
        return value instanceof List<?> list ? list.stream().filter(Document.class::isInstance).map(Document.class::cast).toList() : List.of();
    }

    private BuildVersion decodeVersion(Document document) {
        Number number = document.get("number", Number.class); Number createdAt = document.get("createdAt", Number.class); Number size = document.get("size", Number.class);
        return new BuildVersion(document.getString("_id"), document.getString("worldId"), number.longValue(), document.getString("kind"),
                Path.of(document.getString("schematic")), UUID.fromString(document.getString("createdBy")), Instant.ofEpochMilli(createdAt.longValue()),
                size.longValue(), document.getString("sha256"));
    }

    @Override public void close() { client.close(); }
}
