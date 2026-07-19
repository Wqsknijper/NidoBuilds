package net.nidocraft.builds.model;

public enum BuildStatus {
    EMPTY, UNUSED, EDITED, READY_TO_PUBLISH, PUBLISHED, DELETED;

    public String displayName() {
        return name().toLowerCase().replace('_', ' ');
    }
}
