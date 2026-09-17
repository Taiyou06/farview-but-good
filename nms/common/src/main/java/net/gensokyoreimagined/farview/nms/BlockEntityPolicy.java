package net.gensokyoreimagined.farview.nms;

import java.util.Set;

public record BlockEntityPolicy(boolean enabled, int maxPerChunk, Set<String> types) {
    public boolean allows(String identifier) {
        return enabled && types.contains(identifier);
    }
}
