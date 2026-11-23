package com.craftsmine.nkm.protocol;

import cn.nukkit.network.protocol.v113.ProtocolInfo_v113;
import com.craftsmine.nkm.network.SessionProtocolRegistry.EntitySpawnInfo;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Mapeador integral de contenido multiversion para protocolos legados (80-112 -> 113):
 * - Bloques y metadatos por familia (0.15.x, 0.16.x, 1.0.x, 1.1.0)
 * - Items, inventario creativo y NBT seguro (sin fuegos artificiales, estandartes, shulkers, totems, etc.)
 * - Entidades / mobs / proyectiles (downgrade o supresion si no existen en el cliente)
 * - Metadatos de entidades (0.15.x key map 0..24 + byte flags, 90..109 flag shifts)
 * - Sonidos (LevelSoundEvent y puente LevelSound -> LevelEvent en 0.15.x), particulas y eventos
 * - Skins RGBA (downscale 128x128 / 128x64 -> 64x64 para < 100)
 */
public final class LegacyContentMapper {

    public static final int DATA_TYPE_BYTE = 0;
    public static final int DATA_TYPE_SHORT = 1;
    public static final int DATA_TYPE_INT = 2;
    public static final int DATA_TYPE_FLOAT = 3;
    public static final int DATA_TYPE_STRING = 4;
    public static final int DATA_TYPE_SLOT = 5;
    public static final int DATA_TYPE_POS = 6;
    public static final int DATA_TYPE_LONG = 7;
    public static final int DATA_TYPE_VECTOR3F = 8;

    private static final int EVENT_ADD_PARTICLE_MASK = 0x4000;

    private LegacyContentMapper() {
    }

    // =========================================================================
    // 1. Bloques y metadatos de bloque
    // =========================================================================

    /**
     * Convierte (blockId, meta) canonico de v113 a un bloque valido para {@code dst}.
     * Retorna valor empaquetado {@code (mappedId << 8) | (mappedMeta & 0x0F)}.
     */
    public static int mapBlockAndMeta(int blockId, int meta, LegacyProtocol dst) {
        int id = blockId & 0xFF;
        int m = meta & 0x0F;

        if (!dst.needsTranslation()) {
            return (id << 8) | m;
        }

        // Bloques 1.2+ / Education / wrapped (>255) que nunca deben llegar crudos a < 113
        if (blockId < 0 || blockId > 255) {
            // Nukkit-MOT Level.sendBlocks envia bl.getId() crudo a protocolos <= 201 y algunos bloques
            // modernos usan IDs internos >= 256 que tienen equivalente exacto en MCPE 0.15..1.1:
            switch (blockId) {
                case 472 -> { // PISTON_HEAD_STICKY (BlockID.java:428) -> PISTON_ARM_COLLISION (34)
                    // Nukkit-Retro coloca el brazo de ambos pistones como BlockPistonHead(this.meta) = 34
                    // (BlockPistonBase.java:202), meta = facing 0..5 sin bit sticky (BlockPistonHead.java:76-83);
                    // la pegajosidad la transporta el tile PistonArm "Sticky" (ya reenviado por LegacyNbtConverter).
                    id = 34;
                    m = meta & 0x07;
                }
                case 1220 -> { // WITHER_SKELETON_SKULL (BlockID.java:825) -> SKULL_BLOCK (144)
                    // meta = face index 0..5 (BlockSkullSkeleton.java:104); el tipo va en el tile Skull
                    // "SkullType" (Nukkit-Retro BlockSkull.java:69,79), ya sanitizado por LegacyNbtConverter.
                    id = 144;
                    m = meta & 0x07;
                }
                default -> {
                    id = 1; // Stone fallback si llega un bloque moderno o negativo sin convertir
                    m = 0;
                }
            }
        } else {
            switch (id) {
                case 84 -> { // JUKEBOX (1.2+) -> NOTEBLOCK
                    id = 25;
                    m = 0;
                }
                case 160 -> { // STAINED_GLASS_PANE (1.2+) -> GLASS_PANE
                    id = 102;
                    m = 0;
                }
                case 166 -> { // BARRIER / GLOW_STICK
                    id = 20;
                    m = 0;
                }
                case 176, 177 -> { // BANNER -> Sign / Wall Sign
                    id = (id == 176) ? 63 : 68;
                    m = m & 0x07;
                }
                case 190, 191, 253, 254 -> { // HARD_GLASS / STAINED / wrapped modern blocks
                    id = 20;
                    m = 0;
                }
                case 192, 238 -> { // CHEMICAL_HEAT / CHEMISTRY_TABLE
                    id = 58;
                    m = 0;
                }
                case 202, 204, 239 -> { // COLORED / UNDERWATER TORCH
                    id = 50;
                    m = Math.min(5, m);
                }
                case 241 -> { // STAINED_GLASS (1.2+) -> Glass
                    id = 20;
                    m = 0;
                }
                case 242 -> { // CAMERA_BLOCK -> Cobblestone
                    id = 4;
                    m = 0;
                }
                case 248, 249, 250 -> { // INFO_UPDATE / MOVING_BLOCK (incl. wrapped Warped Fungus 506->250) -> Stone
                    id = 1;
                    m = 0;
                }
                case 255 -> { // RESERVED6 / wrapped 511 -> Air
                    id = 0;
                    m = 0;
                }
                default -> {
                }
            }
        }

        // 1.1.0 bloques (205, 218 Shulker, 219..237 GlazedTerracotta/Concrete) no existen en < 110
        if (dst.isBefore110()) {
            switch (id) {
                case 205, 218 -> { // Shulker box -> Chest
                    id = 54;
                    m = 2;
                }
                case 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235 -> {
                    int color = switch (id) {
                        case 219 -> 10; // purple
                        case 220 -> 0;  // white
                        case 221 -> 1;  // orange
                        case 222 -> 2;  // magenta
                        case 223 -> 3;  // light_blue
                        case 224 -> 4;  // yellow
                        case 225 -> 5;  // lime
                        case 226 -> 6;  // pink
                        case 227 -> 7;  // gray
                        case 228 -> 8;  // silver
                        case 229 -> 9;  // cyan
                        case 230 -> 10; // purple extra
                        case 231 -> 11; // blue
                        case 232 -> 12; // brown
                        case 233 -> 13; // green
                        case 234 -> 14; // red
                        default -> 15;  // black
                    };
                    id = 159;
                    m = color;
                }
                case 236 -> { // Concrete -> Stained clay
                    id = 159;
                    m = m & 0x0F;
                }
                case 237 -> { // Concrete powder -> Sand
                    id = 12;
                    m = 0;
                }
                case 207 -> { // Frosted ice -> Ice
                    id = 79;
                    m = 0;
                }
                default -> {
                }
            }
        }

        // 1.0.x bloques (Command Blocks / Magma / Bone / Polar) no existen en < 92 (0.15 - 0.16)
        // NOTA: End Stone (121), End Portal Frame (120) y End Portal (119) SI existen en 0.15.x/0.16.x
        // (MySoft creative_items/81/82 incluye 120 y 121; Nukkit-Retro Block.java:475-477 registra 119/120/121
        // para clientes 0.15.x), por lo que se envian sin reemplazo hacia cobblestone/sandstone.
        // El bit 0x04 del End Portal Frame (ojo de ender colocado) es valido en 0.15.x (MySoft EndPortalFrame.php:64).
        if (dst.isBefore92()) {
            switch (id) {
                case 119 -> { id = 90; m = 0; }    // END_PORTAL -> PORTAL
                case 122 -> { id = 49; m = 0; }    // DRAGON_EGG -> OBSIDIAN
                case 130 -> { id = 54; m = 2; }    // ENDER_CHEST -> CHEST
                case 137, 188, 189 -> { id = 159; m = 1; } // COMMAND_BLOCK* -> ORANGE_CLAY
                case 200, 240 -> { id = 175; m = 0; } // CHORUS -> DOUBLE_PLANT
                case 201 -> { id = 155; m = 0; }   // PURPUR_BLOCK -> QUARTZ_BLOCK
                case 203 -> { id = 156; m = m & 7; } // PURPUR_STAIRS -> QUARTZ_STAIRS
                case 206 -> { id = 24; m = 2; }    // END_BRICKS -> SMOOTH_SANDSTONE
                case 208 -> { id = 50; m = 5; }    // END_ROD -> TORCH
                case 209 -> { id = 49; m = 0; }    // END_GATEWAY -> OBSIDIAN
                case 210, 211, 212 -> { id = 7; m = 0; } // ALLOW/DENY/BORDER -> BEDROCK
                case 213 -> { id = 87; m = 0; }    // MAGMA -> NETHERRACK
                case 214, 215 -> { id = 112; m = 0; } // NETHER_WART_BLOCK / RED_NETHER_BRICK -> NETHER_BRICK
                case 216 -> { id = 155; m = 0; }   // BONE_BLOCK -> QUARTZ_BLOCK
                case 217 -> { id = 0; m = 0; }     // STRUCTURE_VOID -> AIR
                case 252 -> { id = 7; m = 0; }     // STRUCTURE_BLOCK -> BEDROCK
                default -> {
                }
            }
        }

        // 0.16.x bloques (Beacon 138, Prismarine 168, SeaLantern 169) no existen en 0.15.x (< 90)
        if (dst.isBefore90()) {
            switch (id) {
                case 138 -> { id = 41; m = 0; }    // BEACON -> GOLD_BLOCK
                case 168 -> {                      // PRISMARINE -> STONE_BRICKS / MOSSY
                    id = 98;
                    m = (m == 2) ? 1 : 0;
                }
                case 169 -> { id = 89; m = 0; }    // SEA_LANTERN -> GLOWSTONE
                case 246 -> { id = 49; m = 0; }    // GLOWING_OBSIDIAN -> OBSIDIAN
                case 247 -> { id = 42; m = 0; }    // NETHER_REACTOR -> IRON_BLOCK
                case 251 -> m = Math.min(5, m);    // OBSERVER facing clamp
                default -> {
                }
            }
            // Sanitizar metadata de bloques antiguos para evitar crash de renderer en 0.15.x
            if (id == 3 && m > 2) {
                m = 0;
            } else if (id == 19 && m > 0) { // WET SPONGE -> DRY SPONGE (0.16+)
                m = 0;
            } else if (id == 162 && (m & 0x03) > 1) { // LOG2 only acacia (0) & dark oak (1)
                m = m & 0xFC;
            } else if (id == 175 && (m & 0x07) > 5) { // DOUBLE_PLANT max variant 5
                m = (m & 0x08);
            } else if (id == 1 && m > 6) {
                m = 0;
            } else if (id == 5 && m > 5) {
                m = 0;
            } else if (id == 6 && m > 13) {
                m = m & 0x03;
            } else if (id == 38 && m > 8) {
                m = 0;
            } else if (id == 97 && m > 5) {
                m = 0;
            } else if (id == 98 && m > 3) {
                m = 0;
            } else if (id == 155 && m > 2) {
                m = 0;
            } else if (id == 243) {
                m = 0;
            } else if (id == 244) {
                m = m & 0x07;
            } else if (id == 245) {
                m = 0;
            }
        }

        // Clamping de variantes post-1.1 (1.9/1.10/1.14) presentes en bloques de Nukkit-MOT V1_1_0
        switch (id) {
            case 19 -> {
                if (dst.isBefore110() && m > 0) {
                    m = 0; // Wet Sponge (19:1) se anadio en 1.1.0
                }
            }
            case 24, 155, 179 -> {
                if (m > 2) {
                    m = 0; // Smooth variants (meta 3) son 1.10+
                }
            }
            case 38 -> {
                if (m > 8) {
                    m = 0; // Cornflower / Lily of the Valley son 1.14+
                }
            }
            case 139 -> {
                if (m > 1) {
                    m = 0; // Solo cobblestone/mossy wall existen en <= 1.1
                }
            }
            case 182 -> {
                int maxSlab = dst.isBefore92() ? 0 : 1;
                if (m > maxSlab) {
                    m = 0; // Purpur slab en 1.0+, variantes 2..7 son 1.9+
                }
            }
            default -> {
            }
        }

        return (id << 8) | (m & 0x0F);
    }

    // =========================================================================
    // 2. Items, creativo y slots
    // =========================================================================

    /**
     * Traduce/downgradea un slot saliente para {@code dst}.
     * Si {@code creativeMode == true} y el item no existe en el cliente destino, retorna {@code null}
     * para omitirlo del inventario creativo sin provocar crashes en 0.15.10 / 0.16 / 1.0.
     */
    public static LegacyStream.LegacySlot mapSlotOutbound(
            LegacyStream.LegacySlot slot,
            LegacyProtocol dst,
            boolean creativeMode
    ) {
        if (slot == null || slot.id() <= 0) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        int id = slot.id();
        int meta = slot.meta();
        int count = Math.max(1, Math.min(255, slot.count()));
        byte[] nbt = slot.nbt();
        String[] canPlace = dst.isBefore110() ? new String[0] : slot.canPlaceOn();
        String[] canDestroy = dst.isBefore110() ? new String[0] : slot.canDestroy();

        // Bloque como item (1..255): sin traductor a items parecidos (creativo -> null, normal -> AIR)
        if (id >= 1 && id <= 255) {
            if (creativeMode) {
                if (!isCreativeBlockSupported(id, meta, dst)) {
                    return null;
                }
            } else if (!isBlockSupportedAsItem(id, meta, dst)) {
                return LegacyStream.LegacySlot.AIR;
            }
            int safeMeta = meta < 0 ? meta : (meta & 0x0F);
            byte[] safeNbt = sanitizeItemNbtForSlot(nbt, dst);
            return new LegacyStream.LegacySlot(id, safeMeta, count, safeNbt, canPlace, canDestroy);
        }

        // Items > 1.1 (464, 465, 467..499, discos 500+; 466 = appleEnchanted valido en <= 1.1)
        if (id == 464 || id == 465 || id >= 467) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        // Book & Quill (386), Fireworks (401), Firework Star (402), Armor Stand (425), Banner Pattern (434),
        // Banner (446), Respawn/Chem/Edu (442, 447..449, 451, 453..456)
        if (id == 386 || id == 401 || id == 402 || id == 425 || id == 434 || id == 442
                || (id >= 446 && id <= 449) || id == 451 || (id >= 453 && id <= 456)) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        if (dst.isBefore110()) {
            // Camas de colores 355:1..15 se introdujeron en 1.1.0 (antes solo 355:0)
            if (id == 355 && meta != 0) {
                return creativeMode ? null : LegacyStream.LegacySlot.AIR;
            }
            // Explorer Map (395:2) se introdujo en 1.1.0
            if (id == 395 && meta != 0) {
                return creativeMode ? null : LegacyStream.LegacySlot.AIR;
            }
            // Items exclusivos de 1.1.0: Totem (450), Iron Nugget (452)
            if (id == 450 || id == 452) {
                return creativeMode ? null : LegacyStream.LegacySlot.AIR;
            }
        }

        if (dst.isBefore92()) {
            // Items exclusivos de 1.0.x+: Ender Pearl (368), Eye of Ender (381), End Crystal (426),
            // Chorus (432,433), Dragon Breath (437), Lingering Potion (441), Command Minecart (443), Elytra (444), Shulker Shell (445)
            if (id == 368 || id == 381 || id == 426 || id == 432 || id == 433 || id == 437
                    || id == 441 || id == 443 || id == 444 || id == 445) {
                return creativeMode ? null : LegacyStream.LegacySlot.AIR;
            }
            // Dragon Head (397:5) se introdujo en 1.0.0
            if (id == 397 && meta > 4) {
                return creativeMode ? null : LegacyStream.LegacySlot.AIR;
            }
        }

        if (dst.isBefore90()) {
            // Items de 0.16+: Nether Star (399), Prismarine Shard (409), Prismarine Crystals (422)
            if (id == 399 || id == 409 || id == 422) {
                return creativeMode ? null : LegacyStream.LegacySlot.AIR;
            }
        }

        // Spawn Eggs (383): eliminar en creativo y convertir a AIR en inventario si el mob no existe nativamente en dst.
        // El icono del egg se renderiza en el cliente mientras la entidad exista en esa version,
        // asi que el gating por lista vanilla creativa NO aplica aqui (0.15.10 no crashea con eggs extra).
        if (id == 383) {
            int mappedMob = mapEntityTypeOutbound(meta, dst);
            if (mappedMob < 0 || mappedMob != meta) {
                return creativeMode ? null : LegacyStream.LegacySlot.AIR;
            }
        }

        // Cubos (325): solo vacio(0), leche(1), agua(8), lava(10); peces 2..5 son 1.4+
        if (id == 325 && meta >= 0 && meta != 0 && meta != 1 && meta != 8 && meta != 10) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        // Tintes (351): solo 0..15 en <= 1.1 (16..19 son 1.14+)
        if (id == 351 && meta > 15) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        // Cabezas (397): max Dragon Head (5) en <= 1.1
        if (id == 397 && meta > 5) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        // Pociones / Splash / Lingering (373, 438, 441): max 36 en <= 1.1 (37..42 son 1.2+/Turtle/SlowFalling)
        if ((id == 373 || id == 438 || id == 441) && meta > 36) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        // Tipped Arrows (262): metas 1..5 nunca existen; 6..36 existen desde 0.15.0 (version exclusive,
        // hechas con calderos de pocion); 37 desde 0.16 (P91).
        // El gating por protocolo (P90 sin 262:37) lo cubre isItemSupportedOnClient.
        if (id == 262 && meta > 0 && (meta < 6 || meta > 37)) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        // Items validos en 1.1.0 (Nukkit-MOT los envia tal cual) pero ausentes en clientes 80..112.
        // Umbrales verificados contra MySoft creative_items/{81,82,91,100,110}.json y
        // Nukkit-Retro Item*.isSupportedOn (v0_14_0=45..70, v1_0_0=92, v1_1_0=110).
        if (!isItemSupportedOnClient(id, meta, dst)) {
            return creativeMode ? null : LegacyStream.LegacySlot.AIR;
        }

        byte[] safeNbt = sanitizeItemNbtForSlot(nbt, dst);
        if (id == 403 && creativeMode && nbt != null && nbt.length > 0 && (safeNbt == null || safeNbt.length == 0)) {
            return null; // libro encantado cuyo encantamiento no existe en la version destino
        }

        return new LegacyStream.LegacySlot(id, meta, count, safeNbt, canPlace, canDestroy);
    }

    /**
     * Items que existen en el creativo v113 de Nukkit-MOT pero que un cliente 80..112 no conoce
     * ( IDs sin textura/registro local -> crash del inventario al abrir/navegar la pestana ).
     * Umbrales verificados contra Nukkit-Retro Item*.isSupportedOn (0 umbrales entre v0_14_0=45 y
     * v0_16_0=91: 0.14..0.15.10 comparten el mismo inventario; pociones/brewing/doors/item frames/
     * rabbits/mutton/lead/name tag/tipped arrows son de 0.14 o 0.15.0) y las adiciones de 0.15.0.
     * NOTA: la lista MySoft creative_items/81 es incompleta y NO refleja el inventario real de 0.15.0.
     */
    private static boolean isItemSupportedOnClient(int id, int meta, LegacyProtocol dst) {
        if (dst.getProtocol() < 91) {
            // Introducidos en 0.16/0.17 (P91+): potion 373:36 (wither), splash 438:36,
            // brick 336, netherbrick 405, prismarine shard 409, prismarine crystals 422, tipped arrow 262:37.
            // NOTA: enchanted book (403) NO se filtra aqui: existe desde 0.12/0.14 (encantamientos),
            // el encantamiento invalido se maneja por NBT en sanitizeItemNbtForSlot.
            // rabbit hide (415) y fish (460..463) tampoco: items de 0.13/0.14 (rabbits/pesca).
            if (id == 336 || id == 405 || id == 409 || id == 422) {
                return false;
            }
            if (id == 373 && meta == 36) {
                return false;
            }
            if (id == 438 && meta == 36) {
                return false;
            }
            if (id == 262 && meta == 37) {
                return false;
            }
        }
        // beetroot 457, beetroot seeds 458 y beetroot soup 459 NO se filtran: los beetroots son
        // exclusivos de Pocket Edition desde 0.8.0 (llegaron a Java en 1.8). Salmon 460, clownfish 461,
        // pufferfish 462 y cooked salmon 463 tampoco: tipos de pescado de la pesca de 0.11.
        // Introducidos despues de 1.1.0 (solo en v113+): kelp 335, boats 333:6..10
        if (id == 335) {
            return false;
        }
        if (id == 333 && meta >= 6) {
            return false;
        }
        return true;
    }

    private static boolean isBlockSupportedAsItem(int blockId, int meta, LegacyProtocol dst) {
        if (blockId <= 0 || blockId > 255 || blockId == 255 || blockId == 250 || blockId == 248 || blockId == 249) {
            return false;
        }
        if (blockId == 84 || blockId == 160 || blockId == 166 || blockId == 176 || blockId == 177
                || blockId == 190 || blockId == 191 || blockId == 192 || blockId == 202 || blockId == 204
                || blockId == 238 || blockId == 239 || blockId == 241 || blockId == 242
                || blockId == 253 || blockId == 254) {
            return false;
        }
        if (dst.isBefore110() && (blockId == 205 || blockId == 207 || (blockId >= 218 && blockId <= 237))) {
            return false;
        }
        // End Stone (121) y End Portal Frame (120) existen como bloque/item desde 0.15.x
        // (MySoft creative_items/81: 120 y 121 presentes; Nukkit-Retro Item.java:212 registra END_PORTAL=119
        // y Block.java:475-477 mapea 119/120/121 para clientes 0.12-1.1), por lo que se permiten como item.
        if (dst.isBefore92() && (blockId == 119 || blockId == 122
                || blockId == 130 || blockId == 137 || blockId == 188 || blockId == 189
                || (blockId >= 200 && blockId <= 217) || blockId == 240 || blockId == 252)) {
            return false;
        }
        if (dst.isBefore90() && (blockId == 138 || blockId == 168 || blockId == 169 || blockId == 246 || blockId == 247)) {
            return false;
        }
        if (meta >= 0) {
            if ((blockId == 24 || blockId == 155 || blockId == 179) && meta > 2) {
                return false;
            }
            if (blockId == 38 && meta > 8) {
                return false;
            }
            if (blockId == 139 && meta > 1) {
                return false;
            }
            if (blockId == 182 && meta > (dst.isBefore92() ? 0 : 1)) {
                return false;
            }
            if (dst.isBefore110() && blockId == 19 && meta > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCreativeBlockSupported(int blockId, int meta, LegacyProtocol dst) {
        // (MySoft creative_items/81 no lo incluye, igual que el portal del Nether 90), pero si puede
        // fluir por chunks/updates, por lo que NO se excluye del mapeo de bloques; solo del creativo.
        if (blockId == 0 || blockId == 255 || blockId == 250 || blockId == 119 || blockId == 209 || blockId == 246 || blockId == 248 || blockId == 249) {
            return false;
        }
        if (blockId == 84 || blockId == 160 || blockId == 166 || blockId == 176 || blockId == 177 || blockId == 190 || blockId == 191 || blockId == 192
                || blockId == 202 || blockId == 204 || blockId == 238 || blockId == 239 || blockId == 241
                || blockId == 242 || blockId == 253 || blockId == 254 || blockId > 255) {
            return false;
        }
        if ((blockId == 24 || blockId == 155 || blockId == 179) && meta > 2) {
            return false;
        }
        if (blockId == 38 && meta > 8) {
            return false;
        }
        if (blockId == 139 && meta > 1) {
            return false;
        }
        if (blockId == 182 && meta > (dst.isBefore92() ? 0 : 1)) {
            return false;
        }
        if (dst.isBefore110()) {
            if (blockId == 205 || blockId == 207 || (blockId >= 218 && blockId <= 237) || (blockId == 19 && meta > 0)) {
                return false;
            }
        }
        if (dst.isBefore92()) {
            // End Stone (121) y End Portal Frame (120) SI estan en el creativo vanilla de 0.15.x/0.16.x
            // (MySoft creative_items/81 y /91 los incluyen); se excluyen solo los bloques 1.0.x+
            if (blockId == 122 || blockId == 130 || blockId == 137
                    || blockId == 188 || blockId == 189 || (blockId >= 200 && blockId <= 217)
                    || blockId == 240 || blockId == 252) {
                return false;
            }
        }
        if (dst.isBefore90()) {
            if (blockId == 138 || blockId == 168 || blockId == 169 || blockId == 247) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sanitizeItemNbtForSlot(byte[] nbt, LegacyProtocol dst) {
        if (nbt == null || nbt.length == 0) {
            return new byte[0];
        }
        if (dst.needsTranslation()) {
            return LegacyNbtConverter.sanitizeItemNbt(nbt, dst);
        }
        return nbt;
    }

    // =========================================================================
    // 3. Entidades, Mobs y Proyectiles
    // =========================================================================

    /**
     * Mapea o reemplaza un Entity Network ID de <= 1.1 hacia {@code dst} buscando la entidad
     * equivalente mas parecida soportada por la version destino (las entidades > 1.1 ya las
     * maneja automaticamente Nukkit-MOT). Marcadores sin equivalente (AreaEffectCloud, Fireworks) retornan -1.
     */
    public static int mapEntityTypeOutbound(int entityType, LegacyProtocol dst) {
        if (!dst.needsTranslation()) {
            return entityType;
        }

        int mapped = entityType;

        // 1) Entidades de 1.1.0 -> reemplazo equivalente para < 110 (0.15 - 1.0.x)
        if (dst.isBefore110()) {
            mapped = switch (mapped) {
                case 29 -> 24;   // Llama -> Donkey
                case 57 -> 45;   // Vindicator -> Witch
                case 104 -> 45;  // Evoker -> Witch
                case 105 -> 19;  // Vex -> Bat
                case 102 -> 81;  // LlamaSpit -> Snowball
                case 72, 103 -> -1; // Fireworks / EvocationFangs (sin entidad base segura en < 110)
                default -> mapped;
            };
            if (mapped < 0) {
                return -1;
            }
        }

        // 3) Entidades de 1.0.x -> reemplazo equivalente para < 92 (0.15 - 0.16)
        if (dst.isBefore92()) {
            mapped = switch (mapped) {
                case 28 -> 14;   // PolarBear -> Wolf
                case 53 -> 41;   // EnderDragon -> Ghast
                case 54 -> 43;   // Shulker -> Blaze
                case 55 -> 39;   // Endermite -> Silverfish
                case 70 -> 81;   // EnderEye -> Snowball
                case 76 -> 94;   // ShulkerBullet -> SmallFireBall
                case 79 -> 85;   // DragonFireball -> Fireball
                case 87 -> 81;   // EnderPearl -> Snowball
                case 91 -> 89;   // WitherSkullDangerous -> WitherSkull
                case 100 -> 84;  // CommandMinecart -> MinecartEmpty
                case 101 -> 86;  // LingeringPotion -> ThrownPotion
                case 71 -> -1;   // EndCrystal
                default -> mapped;
            };
            if (mapped < 0) {
                return -1;
            }
        }

        // 4) Entidades de 0.16.x -> reemplazo equivalente para < 90 (0.15.x)
        if (dst.isBefore90()) {
            mapped = switch (mapped) {
                case 49 -> 17;   // Guardian -> Squid
                case 50 -> 17;   // ElderGuardian -> Squid
                case 51 -> 15;   // NPC -> Villager
                case 52 -> 48;   // Wither -> WitherSkeleton
                case 95, 99 -> -1; // AreaEffectCloud / Chalkboard
                default -> mapped;
            };
            if (mapped < 0) {
                return -1;
            }
        }

        // Verificacion estricta de IDs validos conocidos en <= 1.1
        return switch (mapped) {
            case 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
                    32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 57,
                    63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 76, 77, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 93, 94, 95,
                    96, 97, 98, 100, 101, 102, 103, 104, 105 -> mapped;
            default -> -1;
        };
    }

    /**
     * Mapea nombres de atributos de v113 a nombres legados de MCPE 0.15.x (< 90).
     * Retorna null si el atributo no existe en el cliente destino.
     */
    public static String mapAttributeNameOutbound(String v113Name, LegacyProtocol dst) {
        if (v113Name == null || v113Name.isEmpty()) {
            return null;
        }
        if (!dst.isBefore90()) {
            return v113Name;
        }
        return switch (v113Name) {
            case "minecraft:health", "generic.health" -> "generic.health";
            case "minecraft:absorption", "generic.absorption" -> "generic.absorption";
            case "minecraft:player.hunger", "player.hunger" -> "player.hunger";
            case "minecraft:player.saturation", "player.saturation" -> "player.saturation";
            case "minecraft:player.exhaustion", "player.exhaustion" -> "player.exhaustion";
            case "minecraft:player.level", "player.level" -> "player.level";
            case "minecraft:player.experience", "player.experience" -> "player.experience";
            case "minecraft:movement", "generic.movementSpeed" -> "generic.movementSpeed";
            case "minecraft:knockback_resistance", "generic.knockbackResistance" -> "generic.knockbackResistance";
            case "minecraft:follow_range", "generic.followRange" -> "generic.followRange";
            case "minecraft:attack_damage", "generic.attackDamage" -> "generic.attackDamage";
            case "minecraft:horse.jump_strength", "horse.jumpStrength" -> "horse.jumpStrength";
            case "minecraft:zombie.spawn_reinforcements", "zombie.spawnReinforcements" -> "zombie.spawnReinforcements";
            default -> null;
        };
    }

    // =========================================================================
    // 4. Metadatos de Entidades (v113 -> 0.15.x y v113 -> 0.16..1.0.9)
    // =========================================================================

    public static byte[] translateMetadataOutbound(LegacyStream in, LegacyProtocol dst) {
        return translateMetadataOutbound(in, dst, null);
    }

    /**
     * Traduce EntityMetadata desde formato v113 al formato exacto del cliente {@code dst},
     * filtrando propiedades especificas de entidades reemplazadas.
     */
    public static byte[] translateMetadataOutbound(LegacyStream in, LegacyProtocol dst, EntitySpawnInfo spawnInfo) {
        if (dst.isBefore90()) {
            return translateMetadataToPre90(in, dst, spawnInfo);
        }
        return translateMetadataTo90Through112(in, dst, spawnInfo);
    }

    /**
     * Convierte metadata v113 (VarInt key/type) al mapa de 0.15.x (ActorMetadataList::PROTOCOL_81):
     * keys 0,1,2,3,4,7,8,14,15,16,17,18,19,20,21,23,24 + terminador 0x7F.
     * En Nukkit-MOT V1_1_0: DATA_ENTITY_AGE=24, DATA_PLAYER_FLAGS=26, DATA_PLAYER_BED_POSITION=28,
     * DATA_LEAD_HOLDER_EID=38 (Binary.writeMetadata +1 para keys >= 29).
     */
    private static byte[] translateMetadataToPre90(LegacyStream in, LegacyProtocol dst, EntitySpawnInfo spawnInfo) {
        LegacyStream out = LegacyStream.writer(96);
        int count = in.remaining() > 0 ? in.readUnsignedVarInt() : 0;
        boolean wroteShowNametag = false;
        boolean wroteSilent = false;
        boolean wroteNoAi = false;
        boolean replaced = spawnInfo != null && spawnInfo.isReplaced();
        int mappedType = spawnInfo != null ? spawnInfo.mappedType() : -1;

        for (int i = 0; i < count && in.remaining() > 0; i++) {
            int key = in.readUnsignedVarInt();
            int type = in.readUnsignedVarInt();

            // DATA_FLAGS (key 0, long en v113 -> byte en 0.15.x con solo bits 0..5 + extrae flags booleanos)
            if (key == 0 && type == DATA_TYPE_LONG) {
                long flags = in.readVarLong();
                long safeLow = flags & 0x3FL;
                if (replaced) {
                    safeLow &= ~((1L << 4) | (1L << 2)); // limpiar flags de estado especifico (saddled/riding/tamed etc.) salvo onFire/sneaking
                }
                out.writeByte((DATA_TYPE_BYTE << 5) | 0);
                out.writeByte((int) safeLow);

                boolean alwaysShowName = (flags & (1L << 15)) != 0 || (flags & (1L << 14)) != 0;
                if (!wroteShowNametag) {
                    wroteShowNametag = true;
                    out.writeByte((DATA_TYPE_BYTE << 5) | 3); // DATA_SHOW_NAMETAG
                    out.writeByte(alwaysShowName ? 1 : 0);
                }
                if (!wroteSilent) {
                    wroteSilent = true;
                    out.writeByte((DATA_TYPE_BYTE << 5) | 4); // DATA_SILENT
                    out.writeByte((flags & (1L << 17)) != 0 ? 1 : 0);
                }
                if (!wroteNoAi) {
                    wroteNoAi = true;
                    out.writeByte((DATA_TYPE_BYTE << 5) | 15); // DATA_NO_AI
                    out.writeByte((flags & (1L << 16)) != 0 ? 1 : 0);
                }
                if (!replaced && (flags & (1L << 7)) != 0) { // DATA_FLAG_INLOVE
                    out.writeByte((DATA_TYPE_BYTE << 5) | 21); // DATA_IN_LOVE
                    out.writeByte(1);
                }
                if ((flags & (1L << 30)) != 0) { // DATA_FLAG_LEASHED (v113 bit 30)
                    out.writeByte((DATA_TYPE_BYTE << 5) | 24); // DATA_LEAD
                    out.writeByte(1);
                }
                continue;
            }

            switch (key) {
                case 7 -> { // DATA_AIR (v113 key 7 short -> 0.15 key 1 short)
                    int air = readMetaAsInt(in, type);
                    out.writeByte((DATA_TYPE_SHORT << 5) | 1);
                    out.writeShortLE(air);
                }
                case 4 -> { // DATA_NAMETAG (v113 key 4 string -> 0.15 key 2 string)
                    String tag = readMetaAsString(in, type);
                    out.writeByte((DATA_TYPE_STRING << 5) | 2);
                    writeLegacyMetaString(out, tag);
                }
                case 8 -> { // DATA_POTION_COLOR (v113 key 8 int -> 0.15 key 7 int)
                    int color = readMetaAsInt(in, type);
                    out.writeByte((DATA_TYPE_INT << 5) | 7);
                    out.writeIntLE(color);
                }
                case 9 -> { // DATA_POTION_AMBIENT (v113 key 9 byte -> 0.15 key 8 byte)
                    int amb = readMetaAsInt(in, type);
                    out.writeByte((DATA_TYPE_BYTE << 5) | 8);
                    out.writeByte(amb);
                }
                case 24 -> { // DATA_ENTITY_AGE (v113 wire key 24 -> 0.15 key 14 byte)
                    int age = readMetaAsInt(in, type);
                    out.writeByte((DATA_TYPE_BYTE << 5) | 14);
                    out.writeByte(age != 0 ? 1 : 0);
                }
                case 26 -> { // DATA_PLAYER_FLAGS (v113 wire key 26 -> 0.15 key 16 byte)
                    int pf = readMetaAsInt(in, type);
                    out.writeByte((DATA_TYPE_BYTE << 5) | 16);
                    out.writeByte(pf);
                }
                case 28 -> { // DATA_PLAYER_BED_POSITION (v113 wire key 28 -> 0.15 key 17 pos)
                    if (type == DATA_TYPE_POS) {
                        int[] bp = in.readSignedBlockPosition(LegacyProtocol.PROTOCOL_113);
                        out.writeByte((DATA_TYPE_POS << 5) | 17);
                        out.writeIntLE(bp[0]);
                        out.writeIntLE(bp[1]);
                        out.writeIntLE(bp[2]);
                    } else {
                        skipMetaValue(in, type);
                    }
                }
                case 2 -> { // DATA_VARIANT (v113 key 2 int -> 0.15 key 18 int)
                    int var = readMetaAsInt(in, type);
                    if (!replaced) {
                        out.writeByte((DATA_TYPE_INT << 5) | 18);
                        out.writeIntLE(var);
                    }
                }
                case 19 -> { // DATA_SWELL (v113 key 19 -> 0.15 key 19 byte, solo Creeper 33)
                    int swell = readMetaAsInt(in, type);
                    if (!replaced || mappedType == 33) {
                        out.writeByte((DATA_TYPE_BYTE << 5) | 19);
                        out.writeByte(swell);
                    }
                }
                case 3 -> { // DATA_COLOUR (v113 key 3 byte -> 0.15 key 20 byte)
                    int col = readMetaAsInt(in, type);
                    if (!replaced || mappedType == 13 || mappedType == 14 || mappedType == 90) {
                        out.writeByte((DATA_TYPE_BYTE << 5) | 20);
                        out.writeByte(col & 0x0F);
                    }
                }
                case 38 -> { // DATA_LEAD_HOLDER_EID (v113 wire key 38 long -> 0.15 key 23 long)
                    long holder = (type == DATA_TYPE_LONG) ? in.readVarLong() : readMetaAsInt(in, type);
                    out.writeByte((DATA_TYPE_LONG << 5) | 23);
                    out.writeLongLE(holder);
                }
                default -> skipMetaValue(in, type);
            }
        }
        out.writeByte(0x7F); // Terminador 0.15.x
        return out.toByteArray();
    }

    /**
     * Traduce metadata v113 para clientes 90..112:
     * - En < 110 corrige desplazamiento de bits 19..41 en DATA_FLAGS (+3 bits en 110+)
     * - Remapea keys 40..44 segun ActorMetadataList PROTOCOL_90 vs PROTOCOL_110
     * - Filtra VECTOR3F y keys especificas de entidades reemplazadas
     */
    private static byte[] translateMetadataTo90Through112(LegacyStream in, LegacyProtocol dst, EntitySpawnInfo spawnInfo) {
        int count = in.remaining() > 0 ? in.readUnsignedVarInt() : 0;
        LegacyStream entries = LegacyStream.writer(96);
        int writtenCount = 0;
        boolean replaced = spawnInfo != null && spawnInfo.isReplaced();
        int mappedType = spawnInfo != null ? spawnInfo.mappedType() : -1;

        for (int i = 0; i < count && in.remaining() > 0; i++) {
            int key = in.readUnsignedVarInt();
            int type = in.readUnsignedVarInt();

            if (type == DATA_TYPE_VECTOR3F) {
                skipMetaValue(in, type);
                continue;
            }

            if (key == 0 && type == DATA_TYPE_LONG) {
                long flags = in.readVarLong();
                if (dst.isBefore110()) {
                    // ActorMetadataList::PROTOCOL_90: 0..18 iguales, 23..28 -> 19..24, 30..41 -> 25..36
                    long low = flags & 0x7FFFFL;
                    long mid = ((flags >>> 23) & 0x3FL) << 19;
                    long high = ((flags >>> 30) & 0xFFFL) << 25;
                    flags = low | mid | high;
                } else {
                    // ActorMetadataList::PROTOCOL_110: omite WALKER(22), ORPHANED(29), CAN_DASH(46) y HAS_COLLISION(48+)
                    long low = flags & 0x3FFFFFL;
                    long mid = ((flags >>> 23) & 0x3FL) << 22;
                    long high = ((flags >>> 30) & 0x3FFFL) << 28;
                    long mount = ((flags >>> 44) & 0x3L) << 43;
                    long linger = ((flags >>> 47) & 0x1L) << 45;
                    flags = low | mid | high | mount | linger;
                }
                writtenCount++;
                entries.writeUnsignedVarInt(0);
                entries.writeUnsignedVarInt(DATA_TYPE_LONG);
                entries.writeVarLong(flags);
                continue;
            }

            // Filtrar metadatos exclusivos de entidad cuando el mob fue reemplazado
            if (replaced && (key == 2 || key == 3 || key == 19 || key == 20 || key == 21
                    || key == 22 || key == 35 || key == 36 || key == 37 || key == 40
                    || (key >= 46 && key <= 60))) {
                if (!(key == 3 && mappedType == 13)) {
                    skipMetaValue(in, type);
                    continue;
                }
            }

            // Remapeo de keys entre v113 y 90..112
            int mappedKey = key;
            if (dst.isBefore110()) {
                if (key == 41 || key == 42 || key == 43 || key == 44) {
                    mappedKey = key + 1; // 41->42, 42->43, 43->44, 44->45
                } else if (key == 45) {
                    mappedKey = 44;
                } else if (key > 60 || key == 46 || key == 47 || (key >= 48 && key <= 52 && dst.isBefore92())) {
                    skipMetaValue(in, type);
                    continue;
                }
            } else if (key > 77) {
                skipMetaValue(in, type);
                continue;
            }

            // Remapear bloque display de minecart (key 16 en INT cuando > 0)
            if (key == 16 && type == DATA_TYPE_INT) {
                int display = in.readVarInt();
                if (display > 0) {
                    int bid = display & 0xFF;
                    int bmeta = (display >> 16) & 0x0F;
                    int packed = mapBlockAndMeta(bid, bmeta, dst);
                    display = ((packed >>> 8) & 0xFF) | ((packed & 0x0F) << 16);
                }
                writtenCount++;
                entries.writeUnsignedVarInt(mappedKey);
                entries.writeUnsignedVarInt(DATA_TYPE_INT);
                entries.writeVarInt(display);
                continue;
            }

            if (type == DATA_TYPE_SLOT) {
                LegacyStream.LegacySlot slot = mapSlotOutbound(in.readSlot(LegacyProtocol.PROTOCOL_113), dst, false);
                writtenCount++;
                entries.writeUnsignedVarInt(mappedKey);
                entries.writeUnsignedVarInt(DATA_TYPE_SLOT);
                entries.writeSlot(dst, slot == null ? LegacyStream.LegacySlot.AIR : slot);
                continue;
            }

            writtenCount++;
            entries.writeUnsignedVarInt(mappedKey);
            entries.writeUnsignedVarInt(type);
            copyMetaValue90(in, entries, type);
        }

        LegacyStream out = LegacyStream.writer(entries.toByteArray().length + 8);
        out.writeUnsignedVarInt(writtenCount);
        out.writeBytes(entries.toByteArray());
        return out.toByteArray();
    }

    private static void copyMetaValue90(LegacyStream in, LegacyStream out, int type) {
        switch (type) {
            case DATA_TYPE_BYTE -> out.writeByte(in.readByte());
            case DATA_TYPE_SHORT -> out.writeShortLE(in.readShortLE());
            case DATA_TYPE_INT -> out.writeVarInt(in.readVarInt());
            case DATA_TYPE_FLOAT -> out.writeFloatLE(in.readFloatLE());
            case DATA_TYPE_STRING -> out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(LegacyProtocol.PROTOCOL_113));
            case DATA_TYPE_POS -> {
                int[] p = in.readSignedBlockPosition(LegacyProtocol.PROTOCOL_113);
                out.writeVarInt(p[0]);
                out.writeVarInt(p[1]);
                out.writeVarInt(p[2]);
            }
            case DATA_TYPE_LONG -> out.writeVarLong(in.readVarLong());
            default -> {
            }
        }
    }

    private static int readMetaAsInt(LegacyStream in, int type) {
        return switch (type) {
            case DATA_TYPE_BYTE -> in.readByte();
            case DATA_TYPE_SHORT -> in.readSignedShortLE();
            case DATA_TYPE_INT -> in.readVarInt();
            case DATA_TYPE_LONG -> (int) in.readVarLong();
            case DATA_TYPE_FLOAT -> (int) in.readFloatLE();
            default -> {
                skipMetaValue(in, type);
                yield 0;
            }
        };
    }

    private static String readMetaAsString(LegacyStream in, int type) {
        if (type == DATA_TYPE_STRING) {
            return in.readString(LegacyProtocol.PROTOCOL_113);
        }
        skipMetaValue(in, type);
        return "";
    }

    private static void writeLegacyMetaString(LegacyStream out, String s) {
        byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        out.writeShortLE(bytes.length);
        out.writeBytes(bytes);
    }

    public static void skipMetaValue(LegacyStream in, int type) {
        switch (type) {
            case DATA_TYPE_BYTE -> in.readByte();
            case DATA_TYPE_SHORT -> in.readShortLE();
            case DATA_TYPE_INT -> in.readVarInt();
            case DATA_TYPE_FLOAT -> in.readFloatLE();
            case DATA_TYPE_STRING -> in.readString(LegacyProtocol.PROTOCOL_113);
            case DATA_TYPE_SLOT -> in.readSlot(LegacyProtocol.PROTOCOL_113);
            case DATA_TYPE_POS -> in.readSignedBlockPosition(LegacyProtocol.PROTOCOL_113);
            case DATA_TYPE_LONG -> in.readVarLong();
            case DATA_TYPE_VECTOR3F -> in.readVector3(LegacyProtocol.PROTOCOL_113);
            default -> {
            }
        }
    }

    // =========================================================================
    // 5. Sonidos, LevelEvents, Particulas y EntityEvents
    // =========================================================================

    /**
     * Traduce LevelSoundEventPacketV1 (v113 0x19) para el protocolo destino:
     * - En >= 110: valida limites y bloque extraData
     * - En 90..109: mapea la tabla PROTOCOL_110 (0..107) -> PROTOCOL_81 (0..97) sin corrimiento de IDs
     * - En < 90 (0.15.x): convierte sonidos equivalentes en LevelEventPacket (0x16) para que se escuchen!
     */
    public static PacketTranslator.OutboundResult translateLevelSoundEventOutbound(byte[] v113Payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(v113Payload);
        int sound = in.readByte();
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        int extraData = in.remaining() > 0 ? in.readVarInt() : -1;
        int pitch = in.remaining() > 0 ? in.readVarInt() : 1;
        boolean isBabyMob = in.remaining() > 0 && in.readBoolean();
        boolean isGlobal = in.remaining() > 0 && in.readBoolean();

        // Puente de sonido hacia LevelEventPacket (0x16) para MCPE 0.15.x (< 90)
        if (dst.isBefore90()) {
            int levelEventId = mapLevelSoundToPre90LevelEvent(sound);
            if (levelEventId < 0) {
                return PacketTranslator.OutboundResult.drop(false);
            }
            LegacyStream out = LegacyStream.writer(24);
            out.writeByte(PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.LEVEL_EVENT_PACKET & 0xFF));
            out.writeShortBE(levelEventId);
            out.writeVector3(dst, pos[0], pos[1], pos[2]);
            out.writeIntBE(Math.max(0, extraData));
            return PacketTranslator.OutboundResult.forward(out.toByteArray());
        }

        int mappedSound = mapLevelSoundId(sound, dst);
        if (mappedSound < 0) {
            return PacketTranslator.OutboundResult.drop(false);
        }

        // Si el extraData codifica un bloque (0..5 o 52 = break/step/place/hit), ajustarlo a bloques validos del destino
        if ((sound >= 0 && sound <= 5) || sound == 52) {
            if (extraData >= 0) {
                int bid = extraData & 0xFF;
                int bmeta = (extraData >> 8) & 0x0F;
                int packed = mapBlockAndMeta(bid, bmeta, dst);
                extraData = ((packed >>> 8) & 0xFF) | ((packed & 0x0F) << 8);
            }
        }

        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.LEVEL_SOUND_EVENT_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return PacketTranslator.OutboundResult.drop(false);
        }

        LegacyStream out = LegacyStream.writer(32);
        out.writeByte(legacyId & 0xFF);
        out.writeByte(mappedSound);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        out.writeVarInt(extraData);
        out.writeVarInt(pitch);
        out.writeBoolean(isBabyMob);
        out.writeBoolean(isGlobal);
        return PacketTranslator.OutboundResult.forward(out.toByteArray());
    }

    private static int mapLevelSoundId(int v113Sound, LegacyProtocol dst) {
        if (!dst.isBefore110()) {
            return v113Sound <= 107 ? v113Sound : 106; // <= SOUND_UNDEFINED
        }
        // Tabla 90..109 (PROTOCOL_81 en MySoft LevelSoundIds.php)
        if (v113Sound <= 61) {
            return v113Sound;
        }
        if (v113Sound == 62 || v113Sound == 63) { // SHULKERBOX_OPEN / CLOSED (1.1)
            return 60 + (v113Sound - 62); // fallback a CHEST_OPEN / CHEST_CLOSED
        }
        if (v113Sound >= 64 && v113Sound <= 97) {
            return v113Sound - 2; // 64..97 -> 62..95 exacto
        }
        if (v113Sound == 106) {
            return 96; // SOUND_DEFAULT
        }
        if (v113Sound == 107) {
            return 97; // SOUND_UNDEFINED
        }
        // 98..105 (Glass/Illager/Camera en 1.1) o > 107
        return -1;
    }

    private static int mapLevelSoundToPre90LevelEvent(int v113Sound) {
        return switch (v113Sound) {
            case 20, 51 -> 1002;           // BOW / SHOOT -> EVENT_SOUND_SHOOT
            case 26, 58, 59 -> 1004;       // FIZZ / EXTINGUISH_FIRE -> EVENT_SOUND_FIZZ
            case 45 -> 1005;               // EXPLODE -> EVENT_SOUND_TNT
            case 60, 61 -> 1003;           // CHEST_OPEN/CLOSED -> EVENT_SOUND_DOOR (suave) o 1000 click
            case 38 -> 1031;               // THROW -> EVENT_SOUND_ITEM_THROWN
            case 70, 71 -> 1030;           // POP / DROP_SLOT -> EVENT_SOUND_ITEM_DROP
            case 76 -> 1032;               // PORTAL -> EVENT_SOUND_PORTAL
            case 89 -> 1018;               // TELEPORT -> EVENT_SOUND_ENDERMAN_TELEPORT
            default -> -1;
        };
    }

    /**
     * Traduce PlaySoundPacket (v113 0x57) para clientes sin PlaySound (< 105) o >= 105.
     */
    public static PacketTranslator.OutboundResult translatePlaySoundOutbound(byte[] v113Payload, LegacyProtocol dst) {
        if (dst.getProtocol() >= 105) {
            int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.PLAY_SOUND_PACKET & 0xFF);
            if (legacyId == PacketIdMapper.UNMAPPED) {
                return PacketTranslator.OutboundResult.drop(false);
            }
            byte[] out = new byte[1 + v113Payload.length];
            out[0] = (byte) (legacyId & 0xFF);
            System.arraycopy(v113Payload, 0, out, 1, v113Payload.length);
            return PacketTranslator.OutboundResult.forward(out);
        }

        // En < 105, traducir sonidos conocidos a LevelEvent (< 90) o LevelSoundEvent (90..104)
        LegacyStream in = LegacyStream.reader(v113Payload);
        String name = in.readString(LegacyProtocol.PROTOCOL_113).toLowerCase(java.util.Locale.ROOT);
        int[] bp = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        float x = bp[0] / 8.0f;
        float y = bp[1] / 8.0f;
        float z = bp[2] / 8.0f;

        if (dst.isBefore90()) {
            int lev = mapNamedSoundToLevelEvent(name);
            if (lev < 0) {
                return PacketTranslator.OutboundResult.drop(false);
            }
            LegacyStream out = LegacyStream.writer(20);
            out.writeByte(PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.LEVEL_EVENT_PACKET & 0xFF));
            out.writeShortBE(lev);
            out.writeVector3(dst, x, y, z);
            out.writeIntBE(0);
            return PacketTranslator.OutboundResult.forward(out.toByteArray());
        }
        return PacketTranslator.OutboundResult.drop(false);
    }

    private static int mapNamedSoundToLevelEvent(String name) {
        if (name.contains("explode")) return 1005;
        if (name.contains("bow")) return 1002;
        if (name.contains("door")) return 1003;
        if (name.contains("fizz") || name.contains("extinguish")) return 1004;
        if (name.contains("anvil.break")) return 1020;
        if (name.contains("anvil.use")) return 1021;
        if (name.contains("anvil.land")) return 1022;
        if (name.contains("portal")) return 1032;
        if (name.contains("teleport")) return 1018;
        if (name.contains("click")) return 1000;
        return -1;
    }

    /**
     * Traduce y sanitiza LevelEventPacket (0x1a -> 0x16 en < 90):
     * - Ajusta bloques en EVENT_PARTICLE_DESTROY (2001) y EVENT_PARTICLE_PUNCH_BLOCK (2014)
     * - Remapea IDs de particulas de Nukkit-MOT V1_1_0 (0x4000 | pid) a IDs legados de 0.15/1.0
     */
    public static PacketTranslator.OutboundResult translateLevelEventOutbound(byte[] v113Payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(v113Payload);
        int evid = in.readVarInt();
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        int data = in.readVarInt();

        // Remapear y validar rango de particulas (0x4000 | particleId)
        if ((evid & EVENT_ADD_PARTICLE_MASK) != 0) {
            int rawPid = evid & ~EVENT_ADD_PARTICLE_MASK;
            int mappedPid = mapV113ParticleId(rawPid, dst);
            if (mappedPid < 0) {
                return PacketTranslator.OutboundResult.drop(false);
            }
            evid = EVENT_ADD_PARTICLE_MASK | mappedPid;
        } else if (dst.isBefore90()) {
            // 0.15.x soporta sonidos 1000..1044, particulas 2000..2015, clima 3001..3004 y eventos 3500..3508
            if ((evid >= 1050 && evid < 2000) || (evid >= 2016 && evid < 3000) || evid > 3508) {
                return PacketTranslator.OutboundResult.drop(false);
            }
        } else if (dst.isBefore110()) {
            if ((evid >= 1052 && evid < 2000) || (evid >= 2017 && evid < 3000) || evid > 3510) {
                return PacketTranslator.OutboundResult.drop(false);
            }
        }

        // Remapear bloque en particulas de destruccion / golpeo de bloque
        if (evid == 2001) { // EVENT_PARTICLE_DESTROY: blockId | (damage << 8)
            int packed = mapBlockAndMeta(data & 0xFF, (data >> 8) & 0x0F, dst);
            data = ((packed >>> 8) & 0xFF) | ((packed & 0x0F) << 8);
        } else if (evid == 2014) { // EVENT_PARTICLE_PUNCH_BLOCK: blockId | (damage << 8) | (face << 16)
            int face = (data >> 16) & 0xFF;
            int packed = mapBlockAndMeta(data & 0xFF, (data >> 8) & 0x0F, dst);
            data = ((packed >>> 8) & 0xFF) | ((packed & 0x0F) << 8) | (face << 16);
        }

        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.LEVEL_EVENT_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return PacketTranslator.OutboundResult.drop(false);
        }

        LegacyStream out = LegacyStream.writer(24);
        out.writeByte(legacyId & 0xFF);
        if (dst.isBefore90()) {
            out.writeShortBE(evid);
            out.writeVector3(dst, pos[0], pos[1], pos[2]);
            out.writeIntBE(data);
        } else {
            out.writeVarInt(evid);
            out.writeVector3(dst, pos[0], pos[1], pos[2]);
            out.writeVarInt(data);
        }
        return PacketTranslator.OutboundResult.forward(out.toByteArray());
    }

    private static int mapV113ParticleId(int pid, LegacyProtocol dst) {
        if (!dst.isBefore110()) {
            return pid <= 47 ? pid : -1;
        }
        if (pid <= 0) {
            return -1;
        }
        if (pid == 1 || pid == 2) {
            return 1; // BUBBLE / BUBBLE_MANUAL -> BUBBLE (1)
        }
        if (pid >= 3 && pid <= 21) {
            return pid - 1; // 2..20 (CRITICAL..PORTAL)
        }
        if (pid == 22) {
            return 20; // PORTAL / EYE_DESPAWN
        }
        if (pid == 23 || pid == 24) {
            return 21; // SPLASH
        }
        if (pid >= 25 && pid <= 27) {
            return pid - 3; // 22..24 (WATER_WAKE, DRIP_WATER, DRIP_LAVA)
        }
        if (pid == 28) {
            return 24; // DRIP_HONEY -> DRIP_LAVA
        }
        if (pid == 29) {
            return dst.isBefore90() ? 19 : 25; // FALLING_DUST -> BLOCK_FORCE_FIELD (19) en 0.15 o 25 en 0.16..1.0
        }
        if (pid >= 30 && pid <= 42) {
            return pid - 4; // 26..38 (MOB_SPELL..CARROT)
        }
        if (dst.isBefore90()) {
            return -1; // > 38 no existen en 0.15.x
        }
        if (pid == 43) {
            return 38; // SPIT fallback
        }
        if (pid >= 44 && pid <= 45) {
            return pid - 5; // 39..40 (END_ROD / DRAGON_BREATH)
        }
        return -1;
    }

    public static PacketTranslator.OutboundResult translateEntityEventOutbound(byte[] v113Payload, LegacyProtocol dst) {
        return translateEntityEventOutbound(v113Payload, dst, null);
    }

    /**
     * Filtra y traduce EntityEventPacket (0x1c -> 0x18) evitando IDs de eventos desconocidos en 0.15.x
     * y eventos incompatibles con entidades reemplazadas.
     */
    public static PacketTranslator.OutboundResult translateEntityEventOutbound(
            byte[] v113Payload,
            LegacyProtocol dst,
            EntitySpawnInfo spawnInfo
    ) {
        LegacyStream in = LegacyStream.reader(v113Payload);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        int event = in.readByte();
        int data = in.remaining() > 0 ? in.readVarInt() : 0;

        if (dst.isBefore90()) {
            if (event == 28 || (event >= 34 && event <= 37) || event >= 40) {
                return PacketTranslator.OutboundResult.drop(false);
            }
        } else if (dst.isBefore110() && event > 64) {
            return PacketTranslator.OutboundResult.drop(false);
        }

        if (spawnInfo != null && spawnInfo.isReplaced()) {
            // Para entidades reemplazadas solo permitir eventos universales seguros (hurt/death/arm/love/etc.)
            boolean allowedForReplaced = switch (event) {
                case 1, 2, 3, 4, 5, 18, 19, 20, 21, 57 -> event != 57 || spawnInfo.mappedType() == 13;
                default -> false;
            };
            if (!allowedForReplaced) {
                return PacketTranslator.OutboundResult.drop(false);
            }
        }

        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.ENTITY_EVENT_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return PacketTranslator.OutboundResult.drop(false);
        }

        LegacyStream out = LegacyStream.writer(16);
        out.writeByte(legacyId & 0xFF);
        out.writeEntityRuntimeId(dst, eid);
        out.writeByte(event);
        if (!dst.isBefore90()) {
            out.writeVarInt(data);
        }
        return PacketTranslator.OutboundResult.forward(out.toByteArray());
    }

    // =========================================================================
    // 6. Normalizacion de Skins RGBA (128x128 -> 64x64 para <= 0.16)
    // =========================================================================

    /**
     * Garantiza que los bytes RGBA de la skin tengan un tamano valido para clientes legados:
     * En < 100 (0.15.x - 0.16.x) solo existen 64x32 (8192 B) y 64x64 (16384 B).
     * Un skin HD 128x128 (65536 B) desbordaria writeShortBE en 0.15.x y crashearia el cliente.
     */
    public static byte[] normalizeSkinData(byte[] skinData, LegacyProtocol dst) {
        if (skinData == null || skinData.length == 0) {
            return defaultBlankSkin(16384);
        }
        if (dst.getProtocol() >= 100) {
            if (skinData.length == 8192 || skinData.length == 16384 || skinData.length == 65536) {
                return skinData;
            }
            return resizeRgbaNearest(skinData, 64, 64);
        }
        if (skinData.length == 8192 || skinData.length == 16384) {
            return skinData;
        }
        if (skinData.length == 32768) {
            return downsampleRgba(skinData, 128, 64, 64, 32);
        }
        if (skinData.length == 65536) {
            return downsampleRgba(skinData, 128, 128, 64, 64);
        }
        return resizeRgbaNearest(skinData, 64, 64);
    }

    private static byte[] downsampleRgba(byte[] src, int srcW, int srcH, int dstW, int dstH) {
        byte[] dst = new byte[dstW * dstH * 4];
        for (int y = 0; y < dstH; y++) {
            int sy = (y * srcH) / dstH;
            for (int x = 0; x < dstW; x++) {
                int sx = (x * srcW) / dstW;
                int sIdx = (sy * srcW + sx) * 4;
                int dIdx = (y * dstW + x) * 4;
                if (sIdx + 3 < src.length) {
                    dst[dIdx] = src[sIdx];
                    dst[dIdx + 1] = src[sIdx + 1];
                    dst[dIdx + 2] = src[sIdx + 2];
                    dst[dIdx + 3] = src[sIdx + 3];
                }
            }
        }
        return dst;
    }

    private static byte[] resizeRgbaNearest(byte[] src, int dstW, int dstH) {
        int pixels = src.length / 4;
        if (pixels <= 0) {
            return defaultBlankSkin(dstW * dstH * 4);
        }
        int srcW = (int) Math.max(1, Math.round(Math.sqrt(pixels)));
        int srcH = Math.max(1, pixels / srcW);
        return downsampleRgba(src, srcW, srcH, dstW, dstH);
    }

    private static byte[] defaultBlankSkin(int size) {
        byte[] blank = new byte[size];
        Arrays.fill(blank, (byte) 0xFF);
        return blank;
    }
}
