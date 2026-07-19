package net.nidocraft.builds.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.nidocraft.builds.model.BuildVersion;
import net.nidocraft.builds.model.BuildWorld;
import net.nidocraft.builds.storage.BuildRepository;
import net.nidocraft.builds.upload.UploadService;
import net.nidocraft.builds.world.BuildWorldService;
import net.nidocraft.builds.world.SchematicService;
import org.bson.Document;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BuildUploadCommand implements CommandExecutor {
    private final UploadService uploads;
    private final BuildRepository repository;
    private final BuildWorldService worlds;
    private final SchematicService schematics;
    public BuildUploadCommand(UploadService uploads, BuildRepository repository, BuildWorldService worlds, SchematicService schematics) {
        this.uploads = uploads; this.repository = repository; this.worlds = worlds; this.schematics = schematics;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("Player-only."); return true; }
        if (!player.hasPermission("nidobuilds.upload")) { player.sendMessage(Component.text("Designer or higher required.", NamedTextColor.RED)); return true; }
        try {
            if (args.length > 0 && args[0].equalsIgnoreCase("cancel")) { uploads.cancel(player.getUniqueId()); player.sendMessage(Component.text("Pending upload cancelled.", NamedTextColor.GREEN)); return true; }
            if (args.length > 0 && args[0].equalsIgnoreCase("undo")) {
                Document upload = uploads.latestUndo(player.getUniqueId()); if (upload == null) throw new IllegalStateException("You have no schematic paste to undo.");
                String worldId = upload.getString("undoWorldId"); String current = player.getWorld().getName();
                if (!current.equals("build_" + worldId)) throw new IllegalStateException("Load build world " + worldId + " before undoing this paste.");
                Number number = upload.get("undoVersion", Number.class); if (number == null) throw new IllegalStateException("Undo backup is missing.");
                worlds.restore(worldId, number.longValue(), player.getUniqueId()); uploads.markUndone(upload, player.getUniqueId());
                player.sendMessage(Component.text("Your latest schematic paste was undone. A pre-undo backup was retained.", NamedTextColor.GREEN)); return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("paste")) {
                Document upload = uploads.ready(player.getUniqueId()); if (upload == null) throw new IllegalStateException("No validated upload is waiting.");
                String worldName = player.getWorld().getName(); if (!worldName.startsWith("build_")) throw new IllegalStateException("Load a build world first.");
                BuildWorld build = repository.find(worldName.substring(6)).orElseThrow(); uploads.beginPaste(upload);
                try {
                    BuildVersion undo = worlds.save(build.id(), "before-upload", player.getUniqueId()); uploads.recordUndo(upload, build.id(), undo.number());
                    schematics.pasteAt(uploads.safePath(upload), worlds.load(build), player.getLocation(), build); uploads.consumed(upload, player.getUniqueId());
                    player.sendMessage(Component.text("Schematic pasted at your location. Use /buildupload undo to undo it.", NamedTextColor.GREEN));
                }
                catch (Exception exception) { uploads.failedPaste(upload, player.getUniqueId(), exception.getMessage()); throw exception; }
                return true;
            }
            String link = uploads.createLink(player);
            Component here = Component.text("CLICK HERE", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                    .clickEvent(ClickEvent.openUrl(link)).hoverEvent(HoverEvent.showText(Component.text("Open the secure upload page", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("Upload your schematic: ", NamedTextColor.AQUA).append(here));
            player.sendMessage(Component.text("Stay online. The link expires soon and accepts one .schem.", NamedTextColor.GRAY));
        } catch (Exception exception) { player.sendMessage(Component.text(exception.getMessage() == null ? "Upload failed." : exception.getMessage(), NamedTextColor.RED)); }
        return true;
    }
}
