package net.nidocraft.builds.ui;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public final class SignPrompt implements Listener {
    private final JavaPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public SignPrompt(JavaPlugin plugin) { this.plugin = plugin; }

    public void open(Player player, String hint, Consumer<String> result) {
        cancel(player.getUniqueId());
        Block block = null;
        for (int offset = 2; offset <= 6; offset++) {
            Block candidate = player.getLocation().getBlock().getRelative(0, offset, 0);
            if (candidate.getType().isAir()) { block = candidate; break; }
        }
        if (block == null) throw new IllegalStateException("No safe location was found for the search sign.");
        BlockState original = block.getState();
        block.setType(Material.OAK_SIGN, false);
        Sign sign = (Sign) block.getState();
        sign.getSide(Side.FRONT).line(1, net.kyori.adventure.text.Component.text("^^^^^^^^^^^^^^^"));
        sign.getSide(Side.FRONT).line(2, net.kyori.adventure.text.Component.text(hint));
        sign.update(true, false);
        pending.put(player.getUniqueId(), new Pending(block, original, result));
        player.openSign(sign, Side.FRONT);
        UUID id = player.getUniqueId();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> cancel(id), 20L * 60);
    }

    @EventHandler public void onSign(SignChangeEvent event) {
        Pending value = pending.get(event.getPlayer().getUniqueId());
        if (value == null || !event.getBlock().equals(value.block())) return;
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.line(0)).trim();
        pending.remove(event.getPlayer().getUniqueId());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            value.original().update(true, false);
            value.result().accept(text);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { cancel(event.getPlayer().getUniqueId()); }

    public void cancelAll() { for (UUID id : pending.keySet().toArray(UUID[]::new)) cancel(id); }

    private void cancel(UUID id) {
        Pending value = pending.remove(id);
        if (value != null) value.original().update(true, false);
    }

    private record Pending(Block block, BlockState original, Consumer<String> result) { }
}
