package nl.nidocraft.builds.world;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.session.ClipboardHolder;
import nl.nidocraft.builds.model.BuildVersion;
import nl.nidocraft.builds.model.BuildWorld;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class SchematicService {
    private final Path versionsRoot;

    public SchematicService(Path storageRoot) throws IOException {
        versionsRoot = storageRoot.resolve("versions").toAbsolutePath().normalize();
        Files.createDirectories(versionsRoot);
    }

    public BuildVersion save(BuildWorld build, World world, long number, String kind, UUID actor) throws Exception {
        Path directory = versionsRoot.resolve(build.id()).normalize();
        requireInside(directory, versionsRoot);
        Files.createDirectories(directory);
        String id = UUID.randomUUID().toString();
        Path target = directory.resolve(String.format("%08d-%s-%s.schem", number, kind, id));
        Path temporary = directory.resolve("." + id + ".writing");
        com.sk89q.worldedit.world.World adapted = BukkitAdapter.adapt(world);
        CuboidRegion region = new CuboidRegion(adapted,
                BlockVector3.at(-build.radius(), world.getMinHeight(), -build.radius()),
                BlockVector3.at(build.radius(), world.getMaxHeight() - 1, build.radius()));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(BlockVector3.at(0, world.getMinHeight(), 0));
        ForwardExtentCopy copy = new ForwardExtentCopy(adapted, region, clipboard, region.getMinimumPoint());
        copy.setCopyingEntities(true);
        copy.setCopyingBiomes(true);
        Operations.complete(copy);
        try (OutputStream output = Files.newOutputStream(temporary);
             ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_SCHEMATIC.getWriter(output)) {
            writer.write(clipboard);
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        return new BuildVersion(id, build.id(), number, kind, target, actor, Instant.now(), Files.size(target), sha256(target));
    }

    public void paste(Path schematic, World target, BuildWorld build) throws Exception {
        Clipboard clipboard = read(schematic);
        for (Entity entity : target.getEntities()) {
            if (!(entity instanceof Player) && Math.abs(entity.getX()) <= build.radius() + 1 && Math.abs(entity.getZ()) <= build.radius() + 1) entity.remove();
        }
        try (EditSession editSession = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(target))) {
            Operations.complete(new ClipboardHolder(clipboard).createPaste(editSession)
                    .to(BlockVector3.at(0, target.getMinHeight(), 0)).ignoreAirBlocks(false)
                    .copyEntities(true).copyBiomes(true).build());
            editSession.flushQueue();
        }
    }

    public Validation validate(Path schematic, int maxDimension, long maxVolume, int maxEntities) throws Exception {
        Clipboard clipboard = read(schematic);
        BlockVector3 dimensions = clipboard.getDimensions();
        long volume = Math.multiplyExact(Math.multiplyExact((long) dimensions.x(), dimensions.y()), dimensions.z());
        int entities = clipboard.getEntities().size();
        if (dimensions.x() > maxDimension || dimensions.y() > maxDimension || dimensions.z() > maxDimension)
            throw new IllegalArgumentException("Schematic is groter dan " + maxDimension + " blocks in een dimensie.");
        if (volume > maxVolume) throw new IllegalArgumentException("Schematicvolume is te groot.");
        if (entities > maxEntities) throw new IllegalArgumentException("Schematic bevat te veel entities.");
        return new Validation(dimensions.x(), dimensions.y(), dimensions.z(), volume, entities);
    }

    public Clipboard read(Path schematic) throws IOException {
        Path normalized = schematic.toAbsolutePath().normalize();
        ClipboardFormat format = ClipboardFormats.findByFile(normalized.toFile());
        if (format == null) throw new IllegalArgumentException("Geen geldige WorldEdit .schem.");
        try (InputStream input = Files.newInputStream(normalized); ClipboardReader reader = format.getReader(input)) {
            return reader.read();
        }
    }

    private String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192]; int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private void requireInside(Path path, Path root) {
        if (!path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) throw new SecurityException("Onveilig opslagpad.");
    }

    public record Validation(int width, int height, int length, long volume, int entities) { }
}
