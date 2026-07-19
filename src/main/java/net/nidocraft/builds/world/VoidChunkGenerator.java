package net.nidocraft.builds.world;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

public final class VoidChunkGenerator extends ChunkGenerator {
    @Override public void generateSurface(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) { }
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, Math.clamp(11, world.getMinHeight() + 1, world.getMaxHeight() - 1), 0.5);
    }
    @Override public int getBaseHeight(WorldInfo worldInfo, Random random, int x, int z, HeightMap heightMap) { return worldInfo.getMinHeight(); }
}
