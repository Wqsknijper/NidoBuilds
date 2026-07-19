package nl.nidocraft.builds.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import nl.nidocraft.builds.model.BuildWorld;
import nl.nidocraft.builds.storage.BuildRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class BuildScoreboard {
    private final BuildRepository repository;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    public BuildScoreboard(BuildRepository repository) { this.repository = repository; }

    public void update() {
        for (Player player : Bukkit.getOnlinePlayers()) update(player);
        boards.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    public void update(Player player) {
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), ignored -> create());
        cloneTeams(Bukkit.getScoreboardManager().getMainScoreboard(), board);
        Objective objective = board.getObjective("nidobuilds"); if (objective == null) objective = board.registerNewObjective("nidobuilds", "dummy", Component.text("NIDO BUILDS", NamedTextColor.AQUA));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        board.getEntries().stream().filter(entry -> entry.startsWith("§")).forEach(board::resetScores);
        String worldName = player.getWorld().getName(); BuildWorld build = worldName.startsWith("build_") ? repository.find(worldName.substring(6)).orElse(null) : null;
        objective.getScore("§fWorld: §b" + (build == null ? "Build Lobby" : trim(build.name(), 18))).setScore(5);
        objective.getScore("§fStatus: §7" + (build == null ? "lobby" : build.status().displayName())).setScore(4);
        objective.getScore("§fTheme: §7" + (build == null ? "-" : trim(build.theme(), 18))).setScore(3);
        objective.getScore("§fVersion: §7" + (build == null ? "-" : build.currentVersion())).setScore(2);
        objective.getScore("§fTime: §7" + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))).setScore(1);
        player.setScoreboard(board);
    }

    public void remove(Player player) { boards.remove(player.getUniqueId()); player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard()); }

    private Scoreboard create() { return Bukkit.getScoreboardManager().getNewScoreboard(); }
    private void cloneTeams(Scoreboard source, Scoreboard target) {
        for (Team original : source.getTeams()) {
            Team team = target.getTeam(original.getName()); if (team == null) team = target.registerNewTeam(original.getName());
            team.color(NamedTextColor.nearestTo(original.color())); team.prefix(original.prefix()); team.suffix(original.suffix());
            for (String entry : original.getEntries()) if (!team.hasEntry(entry)) team.addEntry(entry);
        }
    }
    private String trim(String value, int length) { return value.length() <= length ? value : value.substring(0, length - 1) + "…"; }
}
