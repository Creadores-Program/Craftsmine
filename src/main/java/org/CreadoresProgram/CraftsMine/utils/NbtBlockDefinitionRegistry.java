package org.CreadoresProgram.CraftsMine.utils;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import lombok.Getter;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.common.DefinitionRegistry;

import java.util.List;

public class NbtBlockDefinitionRegistry implements DefinitionRegistry<BlockDefinition> {

    private final Int2ObjectMap<NbtBlockDefinition> definitions = new Int2ObjectOpenHashMap<>();

    public NbtBlockDefinitionRegistry(List<NbtMap> definitions) {
        int counter = 0;
        for (NbtMap definition : definitions) {
            int runtimeId = -1;
            // Try common keys that may contain the runtime id in different palettes
            if (definition.containsKey("runtime_id")) {
                try {
                    runtimeId = definition.getInt("runtime_id");
                } catch (Exception ignored) {
                }
            }
            if (runtimeId == -1 && definition.containsKey("runtimeId")) {
                try {
                    runtimeId = definition.getInt("runtimeId");
                } catch (Exception ignored) {
                }
            }
            if (runtimeId == -1 && definition.containsKey("v")) {
                try {
                    runtimeId = definition.getInt("v");
                } catch (Exception ignored) {
                }
            }
            // Fallback: assign incremental id if none provided
            if (runtimeId == -1) {
                runtimeId = counter++;
            }
            this.definitions.put(runtimeId, new NbtBlockDefinition(runtimeId, definition));
        }
    }

    @Override
    public BlockDefinition getDefinition(int runtimeId) {
        return definitions.get(runtimeId);
    }

    @Override
    public boolean isRegistered(BlockDefinition definition) {
        return definitions.get(definition.getRuntimeId()) == definition;
    }

    /**
     * Expose a simple mapping from runtime id -> underlying NbtMap for external use.
     * This is a convenience for RuntimePaletteManager to try populating legacy mappings.
     */
    public Int2ObjectMap<NbtMap> getRuntimeToDefinitionMap() {
        Int2ObjectMap<NbtMap> map = new Int2ObjectOpenHashMap<>();
        this.definitions.forEach((k, v) -> map.put(k, v.definition));
        return map;
    }

    private static class NbtBlockDefinition implements BlockDefinition {
        @Getter
        private final int runtimeId;
        @Getter
        private final NbtMap definition;

        public NbtBlockDefinition(int runtimeId, NbtMap definition) {
            this.runtimeId = runtimeId;
            this.definition = definition;
        }
    }
}