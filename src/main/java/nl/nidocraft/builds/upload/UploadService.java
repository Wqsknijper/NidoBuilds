package nl.nidocraft.builds.upload;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.ReplaceOptions;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import nl.nidocraft.builds.storage.BuildRepository;
import nl.nidocraft.builds.model.BuildVersion;
import nl.nidocraft.builds.world.SchematicService;
import org.bson.Document;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Filters.ne;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

public final class UploadService implements AutoCloseable {
    private final JavaPlugin plugin;
    private final BuildRepository repository;
    private final MongoCollection<Document> uploads;
    private final SchematicService schematics;
    private final Path root;
    private final Path versionsRoot;
    private final SecureRandom random = new SecureRandom();
    private final long maxBytes;
    private final int maxDimension;
    private final long maxVolume;
    private final int maxEntities;
    private final long validMillis;
    private final String publicUrl;
    private HttpServer server;

    public UploadService(JavaPlugin plugin, BuildRepository repository, SchematicService schematics, Path storageRoot) throws IOException {
        this.plugin = plugin; this.repository = repository; this.uploads = repository.uploads(); this.schematics = schematics;
        root = storageRoot.resolve("uploads").toAbsolutePath().normalize(); Files.createDirectories(root);
        versionsRoot = storageRoot.resolve("versions").toAbsolutePath().normalize();
        maxBytes = plugin.getConfig().getLong("upload.max-bytes", 16 * 1024 * 1024L);
        maxDimension = plugin.getConfig().getInt("upload.max-dimension", 512);
        maxVolume = plugin.getConfig().getLong("upload.max-volume", 64L * 1024 * 1024);
        maxEntities = plugin.getConfig().getInt("upload.max-entities", 5000);
        validMillis = Duration.ofMinutes(plugin.getConfig().getInt("upload.token-valid-minutes", 10)).toMillis();
        publicUrl = plugin.getConfig().getString("upload.public-url", "http://localhost:8082").replaceAll("/+$", "");
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(plugin.getConfig().getString("upload.bind-host", "127.0.0.1"), plugin.getConfig().getInt("upload.port", 8082)), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String createLink(Player player) throws Exception {
        if (!player.isOnline()) throw new IllegalStateException("Player must be online.");
        byte[] bytes = new byte[32]; random.nextBytes(bytes); String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); String hash = hash(token.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        uploads.updateMany(and(eq("playerId", player.getUniqueId().toString()), eq("status", "TOKEN")), set("status", "SUPERSEDED"));
        uploads.replaceOne(eq("_id", hash), new Document("_id", hash).append("playerId", player.getUniqueId().toString()).append("playerName", player.getName())
                .append("status", "TOKEN").append("createdAt", now).append("expiresAt", now + validMillis), new ReplaceOptions().upsert(true));
        repository.audit(player.getUniqueId(), "UPLOAD_TOKEN_CREATE", null, new Document("tokenHash", hash));
        return publicUrl + "/?token=" + token;
    }

    public String createDownloadLink(Player player, BuildVersion version) throws Exception {
        if (!player.isOnline() || !player.hasPermission("nidobuilds.backup.download")) throw new SecurityException("Admin or owner permission required.");
        Path path = version.schematic().toAbsolutePath().normalize();
        if (!path.startsWith(versionsRoot) || !Files.isRegularFile(path)) throw new SecurityException("Backup file is outside secure version storage or missing.");
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(token.getBytes(StandardCharsets.UTF_8)); long now = System.currentTimeMillis();
        uploads.updateMany(and(eq("playerId", player.getUniqueId().toString()), eq("status", "DOWNLOAD_TOKEN")), set("status", "SUPERSEDED"));
        uploads.replaceOne(eq("_id", hash), new Document("_id", hash).append("playerId", player.getUniqueId().toString()).append("playerName", player.getName())
                .append("status", "DOWNLOAD_TOKEN").append("createdAt", now).append("expiresAt", now + validMillis)
                .append("worldId", version.worldId()).append("version", version.number()).append("path", path.toString())
                .append("size", version.size()).append("filename", version.worldId() + "-v" + version.number() + ".schem"), new ReplaceOptions().upsert(true));
        repository.audit(player.getUniqueId(), "BACKUP_DOWNLOAD_TOKEN_CREATE", version.worldId(), new Document("version", version.number()).append("tokenHash", hash));
        return publicUrl + "/?download=" + token;
    }

    public Document ready(UUID playerId) { return uploads.find(and(eq("playerId", playerId.toString()), eq("status", "READY"))).sort(new Document("createdAt", -1)).first(); }

    public void beginPaste(Document upload) {
        long changed = uploads.updateOne(and(eq("_id", upload.getString("_id")), eq("status", "READY")), combine(set("status", "PASTING"), set("pasteStartedAt", System.currentTimeMillis()))).getModifiedCount();
        if (changed != 1) throw new IllegalStateException("Upload is already being pasted or consumed.");
    }

    public void recordUndo(Document upload, String worldId, long version) {
        uploads.updateOne(eq("_id", upload.getString("_id")), combine(set("undoWorldId", worldId), set("undoVersion", version), set("undone", false)));
    }

    public Document latestUndo(UUID playerId) {
        return uploads.find(and(eq("playerId", playerId.toString()), eq("status", "CONSUMED"), ne("undone", true), ne("undoVersion", null)))
                .sort(new Document("consumedAt", -1)).first();
    }

    public void markUndone(Document upload, UUID actor) {
        uploads.updateOne(eq("_id", upload.getString("_id")), combine(set("undone", true), set("undoneAt", System.currentTimeMillis())));
        repository.audit(actor, "UPLOAD_PASTE_UNDO", upload.getString("undoWorldId"), new Document("uploadId", upload.getString("_id")).append("version", upload.get("undoVersion")));
    }

    public Path safePath(Document upload) {
        Path path = Path.of(upload.getString("path")).toAbsolutePath().normalize();
        if (!path.startsWith(root)) throw new SecurityException("Unsafe upload path rejected.");
        return path;
    }

    public void consumed(Document upload, UUID actor) throws IOException {
        Path path = safePath(upload); Files.deleteIfExists(path);
        uploads.updateOne(eq("_id", upload.getString("_id")), combine(set("status", "CONSUMED"), set("consumedAt", System.currentTimeMillis())));
        repository.audit(actor, "UPLOAD_PASTE_CONSUMED", null, new Document("uploadId", upload.getString("_id")).append("sha256", upload.getString("sha256")));
    }

    public void failedPaste(Document upload, UUID actor, String message) {
        uploads.updateOne(eq("_id", upload.getString("_id")), combine(set("status", "READY"), set("lastPasteError", message)));
        repository.audit(actor, "UPLOAD_PASTE_FAILED", null, new Document("uploadId", upload.getString("_id")).append("error", message));
    }

    public void cancel(UUID playerId) {
        Document upload = ready(playerId); if (upload == null) return;
        try { Files.deleteIfExists(safePath(upload)); } catch (IOException ignored) { }
        uploads.updateOne(eq("_id", upload.getString("_id")), set("status", "CANCELLED"));
        repository.audit(playerId, "UPLOAD_CANCEL", null, new Document("uploadId", upload.getString("_id")));
    }

    private void handle(HttpExchange exchange) throws IOException {
        securityHeaders(exchange);
        try {
            if (!exchange.getRequestURI().getPath().equals("/")) { respond(exchange, 404, "Not found", "text/plain; charset=utf-8"); return; }
            String downloadToken = queryToken(exchange.getRequestURI(), "download");
            if (downloadToken != null) { download(exchange, downloadToken); return; }
            String token = queryToken(exchange.getRequestURI(), "token");
            Document invitation = token == null ? null : uploads.find(eq("_id", hash(token.getBytes(StandardCharsets.UTF_8)))).first();
            if (!valid(invitation)) { respond(exchange, 403, "This upload link is invalid, expired or already used.", "text/plain; charset=utf-8"); return; }
            UUID playerId = UUID.fromString(invitation.getString("playerId"));
            if (!isOnline(playerId)) { respond(exchange, 403, "You must remain online on the build server.", "text/plain; charset=utf-8"); return; }
            if (exchange.getRequestMethod().equalsIgnoreCase("GET")) { respond(exchange, 200, page(token), "text/html; charset=utf-8"); return; }
            if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) { respond(exchange, 405, "Method not allowed", "text/plain; charset=utf-8"); return; }
            accept(exchange, invitation, playerId);
        } catch (Exception exception) {
            plugin.getLogger().warning("Schematic upload rejected: " + exception.getMessage());
            if (exchange.getResponseCode() < 0) respond(exchange, 400, "Upload rejected: " + safeMessage(exception), "text/plain; charset=utf-8");
        } finally { exchange.close(); }
    }

    private void download(HttpExchange exchange, String token) throws Exception {
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) { respond(exchange, 405, "Method not allowed", "text/plain; charset=utf-8"); return; }
        String tokenHash = hash(token.getBytes(StandardCharsets.UTF_8));
        Document invitation = uploads.find(eq("_id", tokenHash)).first();
        Number expires = invitation == null ? null : invitation.get("expiresAt", Number.class);
        if (invitation == null || !"DOWNLOAD_TOKEN".equals(invitation.getString("status")) || expires == null || expires.longValue() < System.currentTimeMillis()) {
            respond(exchange, 403, "This download link is invalid, expired or already used.", "text/plain; charset=utf-8"); return;
        }
        UUID playerId = UUID.fromString(invitation.getString("playerId"));
        if (!isOnline(playerId, "nidobuilds.backup.download")) { respond(exchange, 403, "You must remain online on the build server.", "text/plain; charset=utf-8"); return; }
        Path path = Path.of(invitation.getString("path")).toAbsolutePath().normalize();
        if (!path.startsWith(versionsRoot) || !Files.isRegularFile(path)) { respond(exchange, 404, "Backup file not found.", "text/plain; charset=utf-8"); return; }
        long changed = uploads.updateOne(and(eq("_id", tokenHash), eq("status", "DOWNLOAD_TOKEN")), combine(set("status", "DOWNLOADING"), set("downloadStartedAt", System.currentTimeMillis()))).getModifiedCount();
        if (changed != 1) { respond(exchange, 409, "This link is already being used.", "text/plain; charset=utf-8"); return; }
        String filename = invitation.getString("filename");
        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        exchange.sendResponseHeaders(200, Files.size(path));
        try (InputStream input = Files.newInputStream(path); OutputStream output = exchange.getResponseBody()) { input.transferTo(output); }
        uploads.updateOne(eq("_id", tokenHash), combine(set("status", "CONSUMED"), set("consumedAt", System.currentTimeMillis())));
        repository.audit(playerId, "BACKUP_DOWNLOAD_CONSUMED", invitation.getString("worldId"), new Document("version", invitation.get("version"))
                .append("remoteAddress", exchange.getRemoteAddress().getAddress().getHostAddress()));
    }

    private synchronized void accept(HttpExchange exchange, Document invitation, UUID playerId) throws Exception {
        String fileName = exchange.getRequestHeaders().getFirst("X-Filename");
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".schem")) throw new IllegalArgumentException("Only .schem files are allowed.");
        long declared = parseLength(exchange.getRequestHeaders().getFirst("Content-Length"));
        if (declared <= 0 || declared > maxBytes) throw new IllegalArgumentException("File must be 1-" + maxBytes + " bytes.");
        Path playerRoot = root.resolve(playerId.toString()).normalize(); if (!playerRoot.startsWith(root)) throw new SecurityException("Unsafe player path."); Files.createDirectories(playerRoot);
        String uploadId = invitation.getString("_id"); Path temporary = playerRoot.resolve("." + uploadId + ".uploading"); Path target = playerRoot.resolve(uploadId + ".schem");
        long actual;
        try (InputStream input = exchange.getRequestBody(); OutputStream output = Files.newOutputStream(temporary)) { actual = copyBounded(input, output, maxBytes); }
        try {
            if (actual != declared) throw new IllegalArgumentException("Incomplete upload.");
            SchematicService.Validation validation = schematics.validate(temporary, maxDimension, maxVolume, maxEntities);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            String checksum = hashFile(target); String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
            uploads.updateOne(and(eq("_id", uploadId), eq("status", "TOKEN")), combine(set("status", "READY"), set("path", target.toString()), set("size", actual),
                    set("sha256", checksum), set("remoteAddress", ip), set("uploadedAt", System.currentTimeMillis()),
                    set("width", validation.width()), set("height", validation.height()), set("length", validation.length()), set("entities", validation.entities())));
            repository.audit(playerId, "UPLOAD_ACCEPT", null, new Document("uploadId", uploadId).append("sha256", checksum).append("size", actual).append("ip", ip).append("entities", validation.entities()));
            notifyReady(playerId);
            respond(exchange, 200, "Upload accepted. Return in-game and run /buildupload paste. This file can be pasted once.", "text/plain; charset=utf-8");
        } catch (Exception exception) {
            Files.deleteIfExists(temporary); Files.deleteIfExists(target);
            uploads.updateOne(eq("_id", uploadId), combine(set("status", "REJECTED"), set("reason", safeMessage(exception))));
            repository.audit(playerId, "UPLOAD_REJECT", null, new Document("uploadId", uploadId).append("reason", safeMessage(exception)));
            throw exception;
        }
    }

    private boolean valid(Document invitation) {
        if (invitation == null || !"TOKEN".equals(invitation.getString("status"))) return false;
        Number expires = invitation.get("expiresAt", Number.class); return expires != null && expires.longValue() >= System.currentTimeMillis();
    }

    private boolean isOnline(UUID playerId) throws Exception { return isOnline(playerId, "nidobuilds.upload"); }

    private boolean isOnline(UUID playerId, String permission) throws Exception {
        Future<Boolean> result = Bukkit.getScheduler().callSyncMethod(plugin, (Callable<Boolean>) () -> { Player player = Bukkit.getPlayer(playerId); return player != null && player.isOnline() && player.hasPermission(permission); });
        return result.get();
    }

    private long copyBounded(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[8192]; long total = 0; int read;
        while ((read = input.read(buffer)) >= 0) { total += read; if (total > limit) throw new IOException("File exceeds upload limit."); output.write(buffer, 0, read); }
        return total;
    }

    private String page(String token) {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width'><title>NidoBuilds upload</title>" +
                "<style>body{font:16px system-ui;background:#0c1520;color:#eef5ff;display:grid;place-items:center;height:100vh;margin:0}.card{background:#142437;padding:32px;border-radius:18px;max-width:520px;box-shadow:0 20px 60px #0008}button{background:#37d39a;border:0;padding:12px 18px;border-radius:9px;font-weight:700}input{margin:18px 0}</style></head>" +
                "<body><main class=card><h1>NidoBuilds</h1><p>Select one WorldEdit <code>.schem</code>. It will be validated and can be pasted once while you remain online.</p>" +
                "<input id=f type=file accept=.schem><button id=u>Upload securely</button><p id=s></p></main><script>u.onclick=async()=>{if(!f.files[0])return;s.textContent='Uploading…';" +
                "let r=await fetch('/?token=" + token + "',{method:'POST',headers:{'Content-Type':'application/octet-stream','X-Filename':f.files[0].name},body:f.files[0]});s.textContent=await r.text()}</script></body></html>";
    }

    private void notifyReady(UUID playerId) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(playerId); if (player == null) return;
            Component here = Component.text("HERE", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.runCommand("/buildupload paste"))
                    .hoverEvent(HoverEvent.showText(Component.text("Paste at your current location", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("Upload complete — click ", NamedTextColor.AQUA).append(here)
                    .append(Component.text(" or use /buildupload paste to paste it.", NamedTextColor.AQUA)));
        });
    }

    private String queryToken(URI uri, String key) {
        String query = uri.getRawQuery(); if (query == null || !query.startsWith(key + "=")) return null;
        String token = query.substring(key.length() + 1); return token.matches("[A-Za-z0-9_-]{43}") ? token : null;
    }
    private long parseLength(String value) { try { return Long.parseLong(value); } catch (Exception ignored) { return -1; } }
    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private String hashFile(Path path) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read); } return HexFormat.of().formatHex(digest.digest()); }
    private void securityHeaders(HttpExchange exchange) { exchange.getResponseHeaders().set("Cache-Control", "no-store"); exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'"); exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer"); }
    private void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", contentType); exchange.sendResponseHeaders(status, bytes.length); try (OutputStream output = exchange.getResponseBody()) { output.write(bytes); } }
    private String safeMessage(Exception exception) { String value = exception.getMessage(); return value == null ? "invalid schematic" : value.replaceAll("[\\r\\n]", " ").substring(0, Math.min(value.length(), 200)); }
    @Override public void close() { if (server != null) server.stop(1); }
}
