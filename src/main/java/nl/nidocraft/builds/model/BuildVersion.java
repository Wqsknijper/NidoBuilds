package nl.nidocraft.builds.model;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

public record BuildVersion(String id, String worldId, long number, String kind, Path schematic,
                           UUID createdBy, Instant createdAt, long size, String sha256) { }
