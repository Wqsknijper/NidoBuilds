package nl.nidocraft.builds.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.nidocraft.builds.model.BuildWorld;
import nl.nidocraft.builds.storage.BuildRepository;
import nl.nidocraft.builds.upload.UploadService;
import nl.nidocraft.builds.world.BuildWorldService;
import nl.nidocraft.builds.world.SchematicService;
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
            if (args.length > 0 && args[0].equalsIgnoreCase("paste")) {
                Document upload = uploads.ready(player.getUniqueId()); if (upload == null) throw new IllegalStateException("No validated upload is waiting.");
                String worldName = player.getWorld().getName(); if (!worldName.startsWith("build_")) throw new IllegalStateException("Load a build world first.");
                BuildWorld build = repository.find(worldName.substring(6)).orElseThrow(); uploads.beginPaste(upload);
                try { schematics.paste(uploads.safePath(upload), worlds.load(build), build); uploads.consumed(upload, player.getUniqueId()); player.sendMessage(Component.text("Schematic pasted and securely consumed.", NamedTextColor.GREEN)); }
                catch (Exception exception) { uploads.failedPaste(upload, player.getUniqueId(), exception.getMessage()); throw exception; }
                return true;
            }
            String link = uploads.createLink(player);
            player.sendMessage(Component.text("Open secure one-time schematic upload", NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(link)));
            player.sendMessage(Component.text("Stay online. The link expires soon and accepts one .schem.", NamedTextColor.GRAY));
        } catch (Exception exception) { player.sendMessage(Component.text(exception.getMessage() == null ? "Upload failed." : exception.getMessage(), NamedTextColor.RED)); }
        return true;
    }
}
