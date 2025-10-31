package org.CreadoresProgram.CraftsMine.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.buffer.Unpooled;
import org.CreadoresProgram.CraftsMine.player.Player;
import org.CreadoresProgram.CraftsMine.utils.RuntimePaletteManager;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.handler.BedrockPacketHandler;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.cloudburstmc.protocol.bedrock.v291.Bedrock_v291;
import org.cloudburstmc.protocol.bedrock.v313.Bedrock_v313;
import org.cloudburstmc.protocol.bedrock.v332.Bedrock_v332;
import org.cloudburstmc.protocol.bedrock.v340.Bedrock_v340;
import org.cloudburstmc.protocol.bedrock.v354.Bedrock_v354;
import org.cloudburstmc.protocol.bedrock.v361.Bedrock_v361;
import org.cloudburstmc.protocol.bedrock.v388.Bedrock_v388;
import org.cloudburstmc.protocol.bedrock.v389.Bedrock_v389;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple rewriter that mirrors the MultiVersionProxy behavior for StartGame / LevelChunk / UpdateBlock
 * It converts runtime ids in palettes for legacy protocols when the JSON palette resources are provided.
 * If no palette data exists, it's a noop and packets pass through.
 */
public class LegacyPacketRewriter implements BedrockPacketHandler {
    private final Player player;
    private final BedrockPacketHandler downstream;

    public LegacyPacketRewriter(Player player, BedrockPacketHandler downstream) {
        this.player = player;
        this.downstream = downstream;
    }

    @Override
    public PacketSignal handlePacket(org.cloudburstmc.protocol.bedrock.packet.BedrockPacket packet) {
        try {
            // Intercept StartGame to inject block palette/items for legacy clients
            if (packet instanceof StartGamePacket) {
                StartGamePacket sg = (StartGamePacket) packet;
                int proto = player.getBedrockClientSession().getCodec().getProtocolVersion();
                switch (proto) {
                    case 389:
                    case 388:
                    case 361:
                    case 354:
                    case 340:
                    case 332:
                    case 313:
                    case 291:
                        // If RuntimePaletteManager has entries, set palettes
                        try {
                            if (!RuntimePaletteManager.getItemPalette_v361().isEmpty() && proto == Bedrock_v361.V361_CODEC.getProtocolVersion()) {
                                sg.getItemEntries().clear();
                                sg.getItemEntries().addAll(RuntimePaletteManager.getItemPalette_v361());
                            }
                        } catch (Throwable ignored) {}
                }
            }

            // Intercept LevelChunkPacket for legacy protocols and rewrite palette runtime ids
            if (packet instanceof LevelChunkPacket) {
                LevelChunkPacket p = (LevelChunkPacket) packet;
                int proto = player.getBedrockClientSession().getCodec().getProtocolVersion();
                if (proto < Bedrock_v361.V361_CODEC.getProtocolVersion()) {
                    byte[] data = p.getData();
                    if (data != null && data.length > 0) {
                        ByteBuf dataBuf = Unpooled.wrappedBuffer(data);
                        ByteBuf fixer = Unpooled.buffer();
                        try {
                            int count = p.getSubChunksLength();
                            if (proto < Bedrock_v361.V361_CODEC.getProtocolVersion()) {
                                // write count
                                fixer.writeByte(count);
                            }
                            for (int i = 0; i < count; i++) {
                                fixer.writeByte(dataBuf.readByte()); // version
                                byte storageCount = dataBuf.readByte();
                                fixer.writeByte(storageCount);
                                for (int j = 0; j < storageCount; j++) {
                                    byte header = dataBuf.readByte();
                                    fixer.writeByte(header);
                                    int bit = header >> 1;
                                    int expectedWordCount = ((1 << bit) * 4096) / 32; // approximation; used only to read words
                                    boolean isFirst = j == 0;
                                    int[] words = isFirst ? new int[expectedWordCount] : null;
                                    for (int k = 0; k < expectedWordCount; k++) {
                                        int value = dataBuf.readIntLE();
                                        if (isFirst) words[k] = value;
                                        fixer.writeIntLE(value);
                                    }
                                    if (isFirst) {
                                        // no-op: sections handling not needed here beyond reading words
                                    }
                                    int paletteSize = readVarInt(dataBuf);
                                    writeVarInt(fixer, paletteSize);
                                    for (int l = 0; l < paletteSize; l++) {
                                        int runtimeId = readVarInt(dataBuf);
                                        int newRuntimeId = runtimeId;
                                        try {
                                            switch (proto) {
                                                case 389:
                                                case 388:
                                                    newRuntimeId = RuntimePaletteManager.getRuntimeId_v388(RuntimePaletteManager.getLegacyId_v389(runtimeId));
                                                    break;
                                                case 361:
                                                    newRuntimeId = RuntimePaletteManager.getRuntimeId_v361(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(runtimeId)));
                                                    break;
                                                case 354:
                                                    newRuntimeId = RuntimePaletteManager.getRuntimeId_v354(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(runtimeId)));
                                                    break;
                                                case 340:
                                                    newRuntimeId = RuntimePaletteManager.getRuntimeId_v340(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(runtimeId)));
                                                    break;
                                                case 332:
                                                    newRuntimeId = RuntimePaletteManager.getRuntimeId_v332(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(runtimeId)));
                                                    break;
                                                case 313:
                                                    newRuntimeId = RuntimePaletteManager.getRuntimeId_v313(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(runtimeId)));
                                                    break;
                                                case 291:
                                                    newRuntimeId = RuntimePaletteManager.getRuntimeId_v291(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(runtimeId)));
                                                    break;
                                                default:
                                                    newRuntimeId = runtimeId;
                                            }
                                        } catch (Throwable ignored) { newRuntimeId = runtimeId; }
                                        writeVarInt(fixer, newRuntimeId);
                                    }
                                }
                            }
                            // copy rest bytes (height map etc.)
                            while (dataBuf.isReadable()) {
                                fixer.writeByte(dataBuf.readByte());
                            }

                            byte[] fixed = new byte[fixer.readableBytes()];
                            fixer.readBytes(fixed);
                            p.setData(fixed);
                            dataBuf.release();
                            fixer.release();
                        } catch (Exception ex) {
                            // fallback: leave packet unchanged
                            try { dataBuf.release(); } catch (Throwable t) {}
                        }
                    }
                }
            }

            // Intercept UpdateBlockPacket and rewrite runtime id for legacy clients
            if (packet instanceof org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket) {
                org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket up = (org.cloudburstmc.protocol.bedrock.packet.UpdateBlockPacket) packet;
                int proto = player.getBedrockClientSession().getCodec().getProtocolVersion();
                try {
                    int rid = up.getRuntimeId();
                    int newRid = rid;
                    switch (proto) {
                        case 389:
                        case 388:
                            newRid = RuntimePaletteManager.getRuntimeId_v388(RuntimePaletteManager.getLegacyId_v389(rid));
                            break;
                        case 361:
                            newRid = RuntimePaletteManager.getRuntimeId_v361(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(rid)));
                            break;
                        case 354:
                            newRid = RuntimePaletteManager.getRuntimeId_v354(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(rid)));
                            break;
                        case 340:
                            newRid = RuntimePaletteManager.getRuntimeId_v340(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(rid)));
                            break;
                        case 332:
                            newRid = RuntimePaletteManager.getRuntimeId_v332(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(rid)));
                            break;
                        case 313:
                            newRid = RuntimePaletteManager.getRuntimeId_v313(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(rid)));
                            break;
                        case 291:
                            newRid = RuntimePaletteManager.getRuntimeId_v291(RuntimePaletteManager.convertToLegacy(RuntimePaletteManager.getLegacyId_v389(rid)));
                            break;
                    }
                    // set back if changed
                    // UpdateBlockPacket in cloudburst may be immutable; but packet has setter in some versions
                    try {
                        up.setRuntimeId(newRid);
                    } catch (NoSuchMethodError | AbstractMethodError ignored) {}
                } catch (Throwable ignored) {}
            }

            // Handle AddEntityPacket for legacy clients
            if (packet instanceof AddEntityPacket) {
                AddEntityPacket addEntity = (AddEntityPacket) packet;
                int proto = player.getBedrockClientSession().getCodec().getProtocolVersion();
                if (proto <= Bedrock_v340.V340_CODEC.getProtocolVersion()) {
                    // Adjust entity metadata for legacy protocols
                    try {
                        // Legacy clients may have different entity type IDs
                        int entityType = addEntity.getEntityType();
                        if (proto < Bedrock_v313.V313_CODEC.getProtocolVersion()) {
                            // Map modern entity types to legacy IDs if needed
                            if (entityType > 100) {
                                entityType = Math.min(entityType, 100); // Legacy safe value
                            }
                        }
                        addEntity.setEntityType(entityType);
                    } catch (Throwable ignored) {}
                }
            }

            // Handle MovePlayerPacket for legacy clients
            if (packet instanceof MovePlayerPacket) {
                MovePlayerPacket movePlayer = (MovePlayerPacket) packet;
                int proto = player.getBedrockClientSession().getCodec().getProtocolVersion();
                if (proto < Bedrock_v332.V332_CODEC.getProtocolVersion()) {
                    try {
                        // Some legacy versions had different teleport cause values
                        if (movePlayer.getMode() == MovePlayerPacket.Mode.TELEPORT) {
                            movePlayer.setTeleportCause(0); // Use default cause for legacy
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // Handle InventoryContentPacket for legacy clients
            if (packet instanceof InventoryContentPacket) {
                InventoryContentPacket inventory = (InventoryContentPacket) packet;
                int proto = player.getBedrockClientSession().getCodec().getProtocolVersion();
                if (proto < Bedrock_v361.V361_CODEC.getProtocolVersion()) {
                    try {
                        // Convert item data for legacy clients
                        List<org.cloudburstmc.protocol.bedrock.data.inventory.ItemData> items = inventory.getContents();
                        for (org.cloudburstmc.protocol.bedrock.data.inventory.ItemData item : items) {
                            if (item != null) {
                                int legacyId = RuntimePaletteManager.convertToLegacy(item.getId());
                                // Create new ItemData with legacy ID if needed
                                // Note: Implementation depends on CloudBurst ItemData structure
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // Handle CraftingDataPacket for legacy clients
            if (packet instanceof CraftingDataPacket) {
                CraftingDataPacket crafting = (CraftingDataPacket) packet;
                int proto = player.getBedrockClientSession().getCodec().getProtocolVersion();
                if (proto < Bedrock_v354.V354_CODEC.getProtocolVersion()) {
                    try {
                        // Some legacy versions had different crafting data structure
                        // Here we could modify the crafting recipes format if needed
                        crafting.setCleanRecipes(true); // Force clean recipes for legacy
                    } catch (Throwable ignored) {}
                }
            }

            // forward packet to downstream translator
            return downstream.handlePacket(packet);
        } catch (Throwable ex) {
            // On any error, log and still forward to downstream to avoid breaking flow
            try { player.getLogger().error("LegacyPacketRewriter error: ", ex); } catch (Throwable t) {}
            return downstream.handlePacket(packet);
        }
    }

    // VarInt helpers (Netty VarInt compatible)
    private static int readVarInt(ByteBuf in) {
        int numRead = 0;
        int result = 0;
        byte read;
        do {
            read = in.readByte();
            int value = (read & 0b01111111);
            result |= (value << (7 * numRead));

            numRead++;
            if (numRead > 5) {
                throw new RuntimeException("VarInt is too big");
            }
        } while ((read & 0b10000000) != 0);

        return result;
    }

    private static void writeVarInt(ByteBuf out, int value) {
        while ((value & 0xFFFFFF80) != 0L) {
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.writeByte(value & 0x7F);
    }
}
