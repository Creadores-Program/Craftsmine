package org.CreadoresProgram.Craftsmine.protocol;

import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.IntTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.LongTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.nbt.tag.StringTag;
import cn.nukkit.nbt.tag.Tag;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Conversor y sanitizador de NBT entre NetworkLittleEndianNBT (usado por Nukkit-MOT v113
 * en chunks, BlockEntityData y slots network) y LittleEndianNBT (usado por MCPE 0.15.x < 90).
 */
public final class LegacyNbtConverter {

    private static final int MAX_DEPTH = 48;

    private static final Set<String> SUPPORTED_TILES_PRE_90 = Set.of(
            "Chest", "Sign", "Furnace", "MobSpawner", "FlowerPot",
            "BrewingStand", "EnchantTable", "Cauldron", "Hopper",
            "Dropper", "Dispenser", "DaylightDetector", "Comparator",
            "ItemFrame", "PistonArm", "Skull", "Music"
    );

    private static final Set<String> SUPPORTED_TILES_90_91 = Set.of(
            "Chest", "Sign", "Furnace", "MobSpawner", "FlowerPot",
            "BrewingStand", "EnchantTable", "Cauldron", "Hopper",
            "Dropper", "Dispenser", "DaylightDetector", "Comparator",
            "ItemFrame", "PistonArm", "Skull", "Music", "Beacon"
    );

    private static final Set<String> SUPPORTED_TILES_92_102 = Set.of(
            "Chest", "Sign", "Furnace", "MobSpawner", "FlowerPot",
            "BrewingStand", "EnchantTable", "Cauldron", "Hopper",
            "Dropper", "Dispenser", "DaylightDetector", "Comparator",
            "ItemFrame", "PistonArm", "Skull", "Music",
            "Beacon", "EnderChest", "EndGateway"
    );

    private static final Set<String> SUPPORTED_TILES_105_109 = Set.of(
            "Chest", "Sign", "Furnace", "MobSpawner", "FlowerPot",
            "BrewingStand", "EnchantTable", "Cauldron", "Hopper",
            "Dropper", "Dispenser", "DaylightDetector", "Comparator",
            "ItemFrame", "PistonArm", "Skull", "Music",
            "Beacon", "EnderChest", "EndGateway", "CommandBlock"
    );

    private static final Set<String> SUPPORTED_TILES_110_112 = Set.of(
            "Chest", "Sign", "Furnace", "MobSpawner", "FlowerPot",
            "BrewingStand", "EnchantTable", "Cauldron", "Hopper",
            "Dropper", "Dispenser", "DaylightDetector", "Comparator",
            "ItemFrame", "PistonArm", "Skull", "Music",
            "Beacon", "EnderChest", "EndGateway", "CommandBlock",
            "ShulkerBox", "Bed"
    );

    private LegacyNbtConverter() {
    }

    /**
     * Lee un payload NetworkLittleEndianNBT (con 1 byte version prefix en slots >= 1.12 cuando len=0xFFFF)
     * o raw NetworkLittleEndianNBT, avanzando el puntero de {@code in}, y devuelve su equivalente en LittleEndianNBT.
     */
    public static byte[] convertNetworkNbtStreamSlice(LegacyStream in, boolean withSlotVersionByte) {
        if (in == null || in.remaining() <= 0) {
            return new byte[0];
        }
        int startPos = in.position();
        try {
            if (withSlotVersionByte) {
                int ver = in.readByte();
                if (ver <= 0) {
                    return new byte[0];
                }
            }
            LegacyStream leOut = LegacyStream.writer(64);
            if (transcodeRootTag(in, leOut, true, false)) {
                return leOut.toByteArray();
            }
        } catch (RuntimeException ignored) {
            // Rebobinar si el stream no era NBT valido
            in.position(startPos);
        }
        return new byte[0];
    }

    /**
     * Convierte un bloque de multiples CompoundTags concatenados (tile entities al final de un chunk v113)
     * desde NetworkLittleEndianNBT al formato esperado por {@code dst}, eliminando tile entities no soportados.
     */
    public static byte[] convertChunkTileEntities(byte[] v113Tiles, LegacyProtocol dst) {
        if (v113Tiles == null || v113Tiles.length == 0) {
            return new byte[0];
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(v113Tiles);
        LegacyStream out = LegacyStream.writer(v113Tiles.length);
        boolean writeNetworkNbt = !dst.isBefore90();

        while (bais.available() > 0) {
            try {
                Tag parsed = NBTIO.readTag(bais, ByteOrder.LITTLE_ENDIAN, true);
                if (!(parsed instanceof CompoundTag compound)) {
                    break;
                }
                CompoundTag sanitized = sanitizeTileCompound(compound, dst);
                if (sanitized == null) {
                    continue;
                }
                byte[] encoded = NBTIO.write(sanitized, ByteOrder.LITTLE_ENDIAN, writeNetworkNbt);
                out.writeBytes(encoded);
            } catch (IOException e) {
                break;
            }
        }
        return out.toByteArray();
    }

    /**
     * Convierte y sanitiza el CompoundTag de un BlockEntityDataPacket (0x38 -> 0x32) para {@code dst}.
     * Retorna {@code null} si el tile entity no existe en la version destino y el paquete debe suprimirse.
     */
    public static byte[] convertSingleBlockEntity(byte[] v113Nbt, LegacyProtocol dst) {
        if (v113Nbt == null || v113Nbt.length == 0) {
            return null;
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(v113Nbt)) {
            Tag parsed = NBTIO.readTag(bais, ByteOrder.LITTLE_ENDIAN, true);
            if (!(parsed instanceof CompoundTag compound)) {
                return null;
            }
            CompoundTag sanitized = sanitizeTileCompound(compound, dst);
            if (sanitized == null) {
                return null;
            }
            return NBTIO.write(sanitized, ByteOrder.LITTLE_ENDIAN, !dst.isBefore90());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Sanitiza el CompoundTag de un item (slot NBT, siempre LittleEndianNBT en v113 y 0.15-1.1.0).
     */
    public static byte[] sanitizeItemNbt(byte[] littleEndianNbt, LegacyProtocol dst) {
        if (littleEndianNbt == null || littleEndianNbt.length == 0) {
            return new byte[0];
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(littleEndianNbt)) {
            Tag parsed = NBTIO.readTag(bais, ByteOrder.LITTLE_ENDIAN, false);
            if (!(parsed instanceof CompoundTag compound)) {
                return new byte[0];
            }
            CompoundTag copy = compound.copy();
            sanitizeItemCompoundInPlace(copy, dst);
            if (copy.isEmpty()) {
                return new byte[0];
            }
            return NBTIO.write(copy, ByteOrder.LITTLE_ENDIAN, false);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    public static CompoundTag sanitizeTileCompound(CompoundTag source, LegacyProtocol dst) {
        if (source == null) {
            return null;
        }
        String tileId = source.getString("id");
        if (tileId == null || tileId.isEmpty()) {
            return null;
        }

        if (dst.isBefore90()) {
            if (!SUPPORTED_TILES_PRE_90.contains(tileId)) {
                return null;
            }
        } else if (dst.isBefore92()) {
            if (!SUPPORTED_TILES_90_91.contains(tileId)) {
                return null;
            }
        } else if (dst.getProtocol() < 105) {
            if (!SUPPORTED_TILES_92_102.contains(tileId)) {
                return null;
            }
        } else if (dst.isBefore110()) {
            if (!SUPPORTED_TILES_105_109.contains(tileId)) {
                return null;
            }
        } else if (dst.needsTranslation()) {
            if (!SUPPORTED_TILES_110_112.contains(tileId)) {
                return null;
            }
        }

        CompoundTag clean = source.copy();
        if ("Skull".equals(tileId) && dst.isBefore92()) {
            byte skullType = (byte) clean.getByte("SkullType");
            if (skullType > 4 || skullType < 0) {
                clean.putByte("SkullType", 0);
            }
        } else if ("Skull".equals(tileId) && dst.needsTranslation()) {
            byte skullType = (byte) clean.getByte("SkullType");
            if (skullType > 5 || skullType < 0) {
                clean.putByte("SkullType", 0);
            }
        }

        if ("MobSpawner".equals(tileId) && clean.contains("EntityId")) {
            int rawMobId = clean.getInt("EntityId");
            int mappedMob = LegacyContentMapper.mapEntityTypeOutbound(rawMobId, dst);
            clean.putInt("EntityId", mappedMob >= 0 ? mappedMob : 32);
        }

        if ("FlowerPot".equals(tileId) && clean.contains("item")) {
            int rawItem = clean.getShort("item");
            if (rawItem > 0) {
                int packed = LegacyContentMapper.mapBlockAndMeta(rawItem, clean.getInt("mData"), dst);
                clean.putShort("item", (packed >>> 8) & 0xFF);
                clean.putInt("mData", packed & 0x0F);
            }
        }

        if ("ItemFrame".equals(tileId) && clean.contains("Item") && clean.get("Item") instanceof CompoundTag itemTag) {
            if (dst.needsTranslation() && itemTag.get("map_uuid") instanceof LongTag mapUuidLong) {
                long v = mapUuidLong.data;
                itemTag.remove("map_uuid");
                itemTag.putString("map_uuid", Long.toString(v));
            }
            sanitizeNestedSerializedItem(itemTag, dst);
            if (!itemTag.contains("id") || itemTag.getShort("id") <= 0) {
                clean.remove("Item");
            }
        }

        if (clean.contains("Items") && clean.get("Items") instanceof ListTag<?> itemsList) {
            for (int i = 0; i < itemsList.size(); i++) {
                if (itemsList.get(i) instanceof CompoundTag itemTag) {
                    sanitizeNestedSerializedItem(itemTag, dst);
                }
            }
        }
        return clean;
    }

    private static void sanitizeNestedSerializedItem(CompoundTag itemTag, LegacyProtocol dst) {
        if (itemTag == null || !itemTag.contains("id")) {
            return;
        }
        int id = itemTag.getShort("id");
        int meta = itemTag.getShort("Damage");
        int count = itemTag.getByte("Count") & 0xFF;
        LegacyStream.LegacySlot mapped = LegacyContentMapper.mapSlotOutbound(
                new LegacyStream.LegacySlot(id, meta, Math.max(1, count), new byte[0], new String[0], new String[0]),
                dst,
                false
        );
        if (mapped == null || mapped.id() <= 0) {
            itemTag.putShort("id", 0);
            itemTag.putShort("Damage", 0);
            itemTag.putByte("Count", 0);
            itemTag.remove("tag");
            return;
        }
        itemTag.putShort("id", mapped.id());
        itemTag.putShort("Damage", Math.max(0, mapped.meta()));
        if (itemTag.contains("tag") && itemTag.get("tag") instanceof CompoundTag inner) {
            sanitizeItemCompoundInPlace(inner, dst);
            if (inner.isEmpty()) {
                itemTag.remove("tag");
            }
        }
    }

    private static void sanitizeItemCompoundInPlace(CompoundTag tag, LegacyProtocol dst) {
        if (dst.isBefore110()) {
            tag.remove("CanPlaceOn");
            tag.remove("CanDestroy");
        }
        if (dst.needsTranslation()) {
            tag.remove("Fireworks");
            tag.remove("FireworksItem");
            tag.remove("CustomColor");
            tag.remove("customColor");

            // Limitar enchantment IDs: <= 24 en < 110, <= 26 en 110..112 (MySoft 110 creative)
            int maxEnchantId = dst.isBefore110() ? 24 : 26;
            if (tag.contains("ench") && tag.get("ench") instanceof ListTag<?> enchList) {
                @SuppressWarnings("unchecked")
                ListTag<CompoundTag> filtered = new ListTag<>("ench");
                for (int i = 0; i < enchList.size(); i++) {
                    if (enchList.get(i) instanceof CompoundTag e) {
                        short eid = (short) e.getShort("id");
                        short lvl = (short) Math.max(1, Math.min(10, e.getShort("lvl")));
                        if (eid >= 0 && eid <= maxEnchantId) {
                            CompoundTag copy = new CompoundTag();
                            copy.put("id", new ShortTag("id", eid));
                            copy.put("lvl", new ShortTag("lvl", lvl));
                            filtered.add(copy);
                        }
                    }
                }
                if (filtered.size() == 0) {
                    tag.remove("ench");
                } else {
                    tag.putList(filtered);
                }
            }

            if (tag.contains("map_uuid") && tag.get("map_uuid") instanceof LongTag lt) {
                long val = lt.data;
                tag.remove("map_uuid");
                tag.put("map_uuid", new StringTag("map_uuid", Long.toString(val)));
            }
        }
        if (dst.isBefore90()) {
            tag.remove("RepairCost");
            if (tag.contains("display") && tag.get("display") instanceof CompoundTag display) {
                if (display.contains("Name")) {
                    String name = display.getString("Name");
                    if (name.length() > 64) {
                        display.put("Name", new StringTag("Name", name.substring(0, 64)));
                    }
                }
                if (display.contains("Lore")) {
                    display.remove("Lore");
                }
            }
            if (tag.contains("BlockEntityTag")) {
                tag.remove("BlockEntityTag");
            }
        }
    }

    /**
     * Transcodifica directamente un CompoundTag raiz entre formato network y little-endian sin usar NBTIO.
     */
    public static boolean transcodeRootTag(
            LegacyStream in,
            LegacyStream out,
            boolean inNetwork,
            boolean outNetwork
    ) {
        if (in.remaining() <= 0) {
            return false;
        }
        int rootType = in.readByte();
        if (rootType != 10) { // TAG_Compound
            return false;
        }
        out.writeByte(rootType);
        String rootName = readNbtString(in, inNetwork);
        writeNbtString(out, rootName, outNetwork);
        transcodePayload(in, out, rootType, inNetwork, outNetwork, 0);
        return true;
    }

    private static void transcodePayload(
            LegacyStream in,
            LegacyStream out,
            int tagType,
            boolean inNetwork,
            boolean outNetwork,
            int depth
    ) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException("NBT depth exceeded");
        }
        switch (tagType) {
            case 0 -> { // TAG_End
            }
            case 1 -> out.writeByte(in.readByte()); // TAG_Byte
            case 2 -> out.writeShortLE(in.readShortLE()); // TAG_Short
            case 3 -> { // TAG_Int
                int v = inNetwork ? in.readVarInt() : in.readIntLE();
                if (outNetwork) {
                    out.writeVarInt(v);
                } else {
                    out.writeIntLE(v);
                }
            }
            case 4 -> { // TAG_Long
                long v = inNetwork ? in.readVarLong() : in.readLongLE();
                if (outNetwork) {
                    out.writeVarLong(v);
                } else {
                    out.writeLongLE(v);
                }
            }
            case 5 -> out.writeIntLE(in.readIntLE()); // TAG_Float
            case 6 -> out.writeLongLE(in.readLongLE()); // TAG_Double
            case 7 -> { // TAG_ByteArray
                int len = inNetwork ? in.readUnsignedVarInt() : in.readIntLE();
                byte[] data = in.readBytes(len);
                if (outNetwork) {
                    out.writeUnsignedVarInt(data.length);
                } else {
                    out.writeIntLE(data.length);
                }
                out.writeBytes(data);
            }
            case 8 -> { // TAG_String
                String s = readNbtString(in, inNetwork);
                writeNbtString(out, s, outNetwork);
            }
            case 9 -> { // TAG_List
                int elemType = in.readByte();
                int count = inNetwork ? in.readVarInt() : in.readIntLE();
                out.writeByte(elemType);
                if (outNetwork) {
                    out.writeVarInt(count);
                } else {
                    out.writeIntLE(count);
                }
                for (int i = 0; i < count; i++) {
                    transcodePayload(in, out, elemType, inNetwork, outNetwork, depth + 1);
                }
            }
            case 10 -> { // TAG_Compound
                while (in.remaining() > 0) {
                    int childType = in.readByte();
                    out.writeByte(childType);
                    if (childType == 0) {
                        break;
                    }
                    String childName = readNbtString(in, inNetwork);
                    writeNbtString(out, childName, outNetwork);
                    transcodePayload(in, out, childType, inNetwork, outNetwork, depth + 1);
                }
            }
            case 11 -> { // TAG_IntArray
                int count = inNetwork ? in.readVarInt() : in.readIntLE();
                if (outNetwork) {
                    out.writeVarInt(count);
                } else {
                    out.writeIntLE(count);
                }
                for (int i = 0; i < count; i++) {
                    int v = inNetwork ? in.readVarInt() : in.readIntLE();
                    if (outNetwork) {
                        out.writeVarInt(v);
                    } else {
                        out.writeIntLE(v);
                    }
                }
            }
            case 12 -> { // TAG_LongArray
                int count = inNetwork ? in.readVarInt() : in.readIntLE();
                if (outNetwork) {
                    out.writeVarInt(count);
                } else {
                    out.writeIntLE(count);
                }
                for (int i = 0; i < count; i++) {
                    long v = inNetwork ? in.readVarLong() : in.readLongLE();
                    if (outNetwork) {
                        out.writeVarLong(v);
                    } else {
                        out.writeLongLE(v);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown NBT tag type: " + tagType);
        }
    }

    private static String readNbtString(LegacyStream in, boolean network) {
        int len = network ? in.readUnsignedVarInt() : in.readShortLE();
        if (len <= 0) {
            return "";
        }
        return new String(in.readBytes(len), StandardCharsets.UTF_8);
    }

    private static void writeNbtString(LegacyStream out, String s, boolean network) {
        byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (network) {
            out.writeUnsignedVarInt(bytes.length);
        } else {
            out.writeShortLE(bytes.length);
        }
        out.writeBytes(bytes);
    }
}
