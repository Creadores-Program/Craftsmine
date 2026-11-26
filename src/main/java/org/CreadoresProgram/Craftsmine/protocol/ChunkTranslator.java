package org.CreadoresProgram.Craftsmine.protocol;

/**
 * Convierte LevelChunkPacket (0x3a en v113) al formato de columnas 128-bloques requerido
 * por clientes MCPE 0.15.0-0.15.10 (80-84) y 0.16.0-0.17.0 (90-91), asi como los ajustes
 * de framing para 1.0.x-1.1.0.9 (92-112).
 */
public final class ChunkTranslator {

    private static final int COLUMN_BLOCK_BYTES = 16 * 16 * 128; // 32768
    private static final int COLUMN_NIBBLE_BYTES = COLUMN_BLOCK_BYTES / 2; // 16384

    private ChunkTranslator() {
    }

    /**
     * Traduce el payload del LevelChunkPacket de v113 (sin el byte de ID) al protocolo legado.
     */
    public static byte[] translateOutbound(byte[] v113Payload, LegacyProtocol target) {
        LegacyStream in = LegacyStream.reader(v113Payload);
        int chunkX = in.readVarInt();
        int chunkZ = in.readVarInt();
        byte[] chunkData = in.readByteArray(LegacyProtocol.PROTOCOL_113);

        LegacyStream out = LegacyStream.writer(chunkData.length + 128);
        if (target.isBefore90()) {
            out.writeIntBE(chunkX);
            out.writeIntBE(chunkZ);
            out.writeByte(0); // ORDER_COLUMNS
            byte[] converted = convertV113ChunkDataToPre92(chunkData, target);
            out.writeByteArray(target, converted);
            return out.toByteArray();
        }
        if (target.isBefore92()) {
            out.writeVarInt(chunkX);
            out.writeVarInt(chunkZ);
            out.writeByte(0); // ORDER_COLUMNS
            byte[] converted = convertV113ChunkDataToPre92(chunkData, target);
            out.writeByteArray(target, converted);
            return out.toByteArray();
        }
        // 92..112: subchunks XZY + downgrade de bloques/tiles para 1.0.x (< 110) o limpieza post-1.1
        byte[] patchedChunk = patchSubChunkFormatChunkData(chunkData, target);
        out.writeVarInt(chunkX);
        out.writeVarInt(chunkZ);
        out.writeByteArray(target, patchedChunk);
        return out.toByteArray();
    }

    public static byte[] convertV113ChunkDataToPre92(byte[] v113ChunkData) {
        return convertV113ChunkDataToPre92(v113ChunkData, LegacyProtocol.PROTOCOL_84);
    }

    /**
     * Convierte chunkData serializado por Nukkit-MOT v113 (subchunks XZY de 16x16x16)
     * al formato plano de columnas de 128 bloques + heightmap + biomas ARGB + extraData + tile entities
     * realizando downgrade de bloques/metadatos y conversion NBT para 0.15.x/0.16.x.
     */
    public static byte[] convertV113ChunkDataToPre92(byte[] v113ChunkData, LegacyProtocol target) {
        LegacyStream in = LegacyStream.reader(v113ChunkData);
        byte[] blockIds = new byte[COLUMN_BLOCK_BYTES];
        byte[] blockData = new byte[COLUMN_NIBBLE_BYTES];
        byte[] skyLight = new byte[COLUMN_NIBBLE_BYTES];
        byte[] blockLight = new byte[COLUMN_NIBBLE_BYTES];
        java.util.Arrays.fill(skyLight, (byte) 0xFF);

        int subChunkCount = in.remaining() > 0 ? in.readByte() : 0;
        for (int s = 0; s < subChunkCount; s++) {
            if (in.remaining() < 1) {
                break;
            }
            int version = in.readByte();
            if (version != 0 && in.remaining() < 4096) {
                break;
            }
            if (in.remaining() < 4096 + 2048 + 2048 + 2048) {
                break;
            }
            byte[] subIds = in.readBytes(4096);
            byte[] subData = in.readBytes(2048);
            byte[] subSky = in.readBytes(2048);
            byte[] subBlock = in.readBytes(2048);

            // Downgrade de bloques/metadatos dentro del subchunk si hay bloques > 118 o post-0.15/0.16
            patchSubChunkBlocks(subIds, subData, target);

            // Solo los primeros 8 subchunks (Y = 0..127) caben en el cliente pre-92
            if (s < 8) {
                int yBase = s << 4;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int subColOffset = (x << 8) | (z << 4);
                        int colBase = (x << 11) | (z << 7) | yBase;
                        System.arraycopy(subIds, subColOffset, blockIds, colBase, 16);

                        // Nibble copy: 16 bloques en Y = 8 bytes contiguos en ambos formatos (XZY)
                        int subNibbleOffset = (x << 7) | (z << 3);
                        int colNibbleBase = (x << 10) | (z << 6) | (yBase >> 1);
                        System.arraycopy(subData, subNibbleOffset, blockData, colNibbleBase, 8);
                        System.arraycopy(subSky, subNibbleOffset, skyLight, colNibbleBase, 8);
                        System.arraycopy(subBlock, subNibbleOffset, blockLight, colNibbleBase, 8);
                    }
                }
            }
        }

        // Heightmap en Nukkit-MOT (< v1_12_0): 256 unsigned bytes de altura seguidos de 256 bytes PAD_256 (total 512B)
        byte[] heightMap = new byte[256];
        if (in.remaining() >= 512) {
            byte[] rawHeights = in.readBytes(256);
            for (int i = 0; i < 256; i++) {
                heightMap[i] = (byte) Math.min(127, rawHeights[i] & 0xFF);
            }
            in.readBytes(256); // PAD_256
        }

        // Biome IDs: 256 bytes -> 256 ints ARGB (1024 bytes)
        byte[] biomeIds = in.remaining() >= 256 ? in.readBytes(256) : new byte[256];

        // En v113: borderBlocks(1 byte) + extraData(varint) + tiles NBT (NetworkLittleEndianNBT)
        if (in.remaining() >= 1) {
            int borderCount = in.readByte();
            for (int i = 0; i < borderCount && in.remaining() > 0; i++) {
                in.readByte();
            }
        }
        if (in.remaining() >= 1) {
            int extraCount = in.readVarInt();
            for (int i = 0; i < extraCount && in.remaining() >= 4; i++) {
                in.readVarInt();
                in.readShortLE();
            }
        }

        byte[] rawTiles = in.readRemaining();
        byte[] convertedTiles = LegacyNbtConverter.convertChunkTileEntities(rawTiles, target);

        LegacyStream out = LegacyStream.writer(COLUMN_BLOCK_BYTES + COLUMN_NIBBLE_BYTES * 3 + 256 + 1024 + 4 + convertedTiles.length);
        out.writeBytes(blockIds);
        out.writeBytes(blockData);
        out.writeBytes(skyLight);
        out.writeBytes(blockLight);
        out.writeBytes(heightMap);
        for (int i = 0; i < 256; i++) {
            int argb = biomeIdToArgb(sanitizeBiomeId(biomeIds[i] & 0xFF, target));
            out.writeIntBE(argb);
        }
        out.writeIntLE(0); // extraData count (0)
        out.writeBytes(convertedTiles); // Tile entities sanitizadas y convertidas a LittleEndianNBT (<90) o NetworkNBT (90..91)
        return out.toByteArray();
    }

    private static byte[] patchSubChunkFormatChunkData(byte[] v113ChunkData, LegacyProtocol target) {
        if (!target.needsTranslation() || v113ChunkData.length < 2) {
            return v113ChunkData;
        }
        LegacyStream in = LegacyStream.reader(v113ChunkData);
        LegacyStream out = LegacyStream.writer(v113ChunkData.length);

        int subChunkCount = in.readByte();
        out.writeByte(subChunkCount);
        for (int s = 0; s < subChunkCount; s++) {
            if (in.remaining() < 1) {
                break;
            }
            int version = in.readByte();
            out.writeByte(version);
            if (in.remaining() < 4096 + 2048 + 2048 + 2048) {
                break;
            }
            byte[] subIds = in.readBytes(4096);
            byte[] subData = in.readBytes(2048);
            byte[] subSky = in.readBytes(2048);
            byte[] subBlock = in.readBytes(2048);
            patchSubChunkBlocks(subIds, subData, target);
            out.writeBytes(subIds);
            out.writeBytes(subData);
            out.writeBytes(subSky);
            out.writeBytes(subBlock);
        }
        // Heightmap (512B) + biomes (256B) + borderBlocks
        if (in.remaining() >= 512 + 256) {
            out.writeBytes(in.readBytes(512));
            byte[] biomes = in.readBytes(256);
            for (int i = 0; i < 256; i++) {
                biomes[i] = (byte) sanitizeBiomeId(biomes[i] & 0xFF, target);
            }
            out.writeBytes(biomes);
        }
        if (in.remaining() >= 1) {
            int borderCount = in.readByte();
            out.writeByte(borderCount);
            for (int i = 0; i < borderCount && in.remaining() > 0; i++) {
                out.writeByte(in.readByte());
            }
        }
        if (in.remaining() >= 1) {
            int extraCount = in.readVarInt();
            out.writeVarInt(extraCount);
            for (int i = 0; i < extraCount && in.remaining() >= 3; i++) {
                out.writeVarInt(in.readVarInt());
                out.writeShortLE(in.readShortLE());
            }
        }
        byte[] tiles = LegacyNbtConverter.convertChunkTileEntities(in.readRemaining(), target);
        out.writeBytes(tiles);
        return out.toByteArray();
    }

    private static void patchSubChunkBlocks(byte[] subIds, byte[] subData, LegacyProtocol target) {
        for (int idx = 0; idx < 4096; idx++) {
            int id = subIds[idx] & 0xFF;
            if (id == 0) {
                continue;
            }
            int nibbleIdx = idx >> 1;
            int nibbleShift = (idx & 1) << 2;
            int meta = (subData[nibbleIdx] >>> nibbleShift) & 0x0F;
            int packed = LegacyContentMapper.mapBlockAndMeta(id, meta, target);
            int newId = (packed >>> 8) & 0xFF;
            int newMeta = packed & 0x0F;
            if (newId != id) {
                subIds[idx] = (byte) newId;
            }
            if (newMeta != meta) {
                int mask = ~(0x0F << nibbleShift);
                subData[nibbleIdx] = (byte) ((subData[nibbleIdx] & mask) | (newMeta << nibbleShift));
            }
        }
    }

    private static int sanitizeBiomeId(int biomeId, LegacyProtocol target) {
        if (biomeId >= 178 && biomeId <= 186) {
            return 8; // Biomas modernos del Nether (Warped Forest, Crimson Forest, etc.) -> Hell (8)
        }
        if (target.isBefore90() && biomeId > 39) {
            return 1; // Plains fallback seguro en 0.15.x
        }
        if (biomeId > 39 && (biomeId < 129 || biomeId > 167)) {
            return 1;
        }
        return biomeId;
    }

    private static int biomeIdToArgb(int biomeId) {
        return switch (biomeId) {
            case 2, 17 -> 0xFFBFB755; // Desert
            case 4, 18 -> 0xFF79C05A; // Forest
            case 5, 19 -> 0xFF596651; // Taiga
            case 6 -> 0xFF6A7039;     // Swampland
            case 8, 178, 179, 180, 181 -> 0xFF905957; // Nether / Hell
            case 12, 13 -> 0xFF80B497; // Ice Plains
            case 14, 15 -> 0xFF55C93F; // Mushroom
            case 21, 22, 23 -> 0xFF59C93C; // Jungle
            case 35, 36 -> 0xFFAEA42A; // Savanna
            case 37, 38, 39 -> 0xFF90814D; // Mesa
            default -> 0xFF91BD59;     // Plains / Ocean / Default grass tint
        };
    }
}
