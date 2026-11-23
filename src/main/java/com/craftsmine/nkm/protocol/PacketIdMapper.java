package com.craftsmine.nkm.protocol;

import cn.nukkit.network.protocol.v113.ProtocolInfo_v113;

import java.util.EnumMap;
import java.util.Map;

/**
 * Mapeo bidireccional exacto de IDs de paquetes entre Nukkit-MOT v113 (1.1.0)
 * y cada familia de protocolos legados (80-112) basada en MySoft PacketsIds81/91/100/102/105/110.
 */
public final class PacketIdMapper {

    public static final int UNMAPPED = -1;

    /**
     * MySoft PacketPool no define tabla para el protocolo 90: getPacketIdByMagic() (PacketPool.php:429-442)
     * resuelve hacia abajo hasta PacketsIds81, donde PLAYER_ACTION_PACKET = 0x20 (PacketsIds81.php:57),
     * aunque la serializacion de campos ya es VarInt/VarLong (umbral >= 90, PlayerActionPacket.php:94-99).
     * La familia V0_16 (tabla v91) mapea 0x24 -> PLAYER_ACTION y 0x20 -> MOB_EQUIPMENT, de modo que un
     * cliente 0.15.90 que presiona Shift enviaba 0x20 con action=11/12 (START/STOP_SNEAK, mismos valores
     * en la tabla PROTOCOL_81 de PlayerActionIds.php:372-373 y en v113) y el bridge lo decodificaba como
     * MobEquipment: el sneak y toda accion de jugador se perdia en protocolo 90.
     * Mismo patron P90 ya aplicado a RemoveEntity (0x0c) y TakeItemEntity (0x0e) en PacketTranslator.
     */
    private static final int PLAYER_ACTION_LEGACY_PID_P90 = 0x20;

    private static final Map<LegacyProtocol.Family, int[]> LEGACY_TO_V113 = new EnumMap<>(LegacyProtocol.Family.class);
    private static final Map<LegacyProtocol.Family, int[]> V113_TO_LEGACY = new EnumMap<>(LegacyProtocol.Family.class);

    static {
        for (LegacyProtocol.Family family : LegacyProtocol.Family.values()) {
            int[] toV113 = new int[256];
            int[] fromV113 = new int[256];
            java.util.Arrays.fill(toV113, UNMAPPED);
            java.util.Arrays.fill(fromV113, UNMAPPED);
            LEGACY_TO_V113.put(family, toV113);
            V113_TO_LEGACY.put(family, fromV113);
        }

        initV015();
        initV016();
        initV100();
        initV104();
        initV105();
        initV110Early();
        initV110Canonical();
    }

    private PacketIdMapper() {
    }

    private static void map(LegacyProtocol.Family family, int legacyId, byte v113Id) {
        int canonical = v113Id & 0xFF;
        int legacy = legacyId & 0xFF;
        LEGACY_TO_V113.get(family)[legacy] = canonical;
        V113_TO_LEGACY.get(family)[canonical] = legacy;
    }

    private static void initCommonHandshake(LegacyProtocol.Family family) {
        map(family, 0x01, ProtocolInfo_v113.LOGIN_PACKET);
        map(family, 0x02, ProtocolInfo_v113.PLAY_STATUS_PACKET);
        map(family, 0x03, ProtocolInfo_v113.SERVER_TO_CLIENT_HANDSHAKE_PACKET);
        map(family, 0x04, ProtocolInfo_v113.CLIENT_TO_SERVER_HANDSHAKE_PACKET);
        map(family, 0x05, ProtocolInfo_v113.DISCONNECT_PACKET);
    }

    private static void initV015() {
        LegacyProtocol.Family f = LegacyProtocol.Family.V0_15;
        initCommonHandshake(f);
        map(f, 0x06, ProtocolInfo_v113.BATCH_PACKET);
        map(f, 0x07, ProtocolInfo_v113.TEXT_PACKET);
        map(f, 0x08, ProtocolInfo_v113.SET_TIME_PACKET);
        map(f, 0x09, ProtocolInfo_v113.START_GAME_PACKET);
        map(f, 0x0A, ProtocolInfo_v113.ADD_PLAYER_PACKET);
        map(f, 0x0B, ProtocolInfo_v113.ADD_ENTITY_PACKET);
        map(f, 0x0C, ProtocolInfo_v113.REMOVE_ENTITY_PACKET);
        map(f, 0x0D, ProtocolInfo_v113.ADD_ITEM_ENTITY_PACKET);
        map(f, 0x0E, ProtocolInfo_v113.TAKE_ITEM_ENTITY_PACKET);
        map(f, 0x0F, ProtocolInfo_v113.MOVE_ENTITY_PACKET);
        map(f, 0x10, ProtocolInfo_v113.MOVE_PLAYER_PACKET);
        map(f, 0x11, ProtocolInfo_v113.RIDER_JUMP_PACKET);
        map(f, 0x12, ProtocolInfo_v113.REMOVE_BLOCK_PACKET);
        map(f, 0x13, ProtocolInfo_v113.UPDATE_BLOCK_PACKET);
        map(f, 0x14, ProtocolInfo_v113.ADD_PAINTING_PACKET);
        map(f, 0x15, ProtocolInfo_v113.EXPLODE_PACKET);
        map(f, 0x16, ProtocolInfo_v113.LEVEL_EVENT_PACKET);
        map(f, 0x17, ProtocolInfo_v113.BLOCK_EVENT_PACKET);
        map(f, 0x18, ProtocolInfo_v113.ENTITY_EVENT_PACKET);
        map(f, 0x19, ProtocolInfo_v113.MOB_EFFECT_PACKET);
        map(f, 0x1A, ProtocolInfo_v113.UPDATE_ATTRIBUTES_PACKET);
        map(f, 0x1B, ProtocolInfo_v113.MOB_EQUIPMENT_PACKET);
        map(f, 0x1C, ProtocolInfo_v113.MOB_ARMOR_EQUIPMENT_PACKET);
        map(f, 0x1E, ProtocolInfo_v113.INTERACT_PACKET);
        map(f, 0x1F, ProtocolInfo_v113.USE_ITEM_PACKET);
        map(f, 0x20, ProtocolInfo_v113.PLAYER_ACTION_PACKET);
        map(f, 0x21, ProtocolInfo_v113.HURT_ARMOR_PACKET);
        map(f, 0x22, ProtocolInfo_v113.SET_ENTITY_DATA_PACKET);
        map(f, 0x23, ProtocolInfo_v113.SET_ENTITY_MOTION_PACKET);
        map(f, 0x24, ProtocolInfo_v113.SET_ENTITY_LINK_PACKET);
        map(f, 0x25, ProtocolInfo_v113.SET_HEALTH_PACKET);
        map(f, 0x26, ProtocolInfo_v113.SET_SPAWN_POSITION_PACKET);
        map(f, 0x27, ProtocolInfo_v113.ANIMATE_PACKET);
        map(f, 0x28, ProtocolInfo_v113.RESPAWN_PACKET);
        map(f, 0x29, ProtocolInfo_v113.DROP_ITEM_PACKET);
        map(f, 0x2A, ProtocolInfo_v113.CONTAINER_OPEN_PACKET);
        map(f, 0x2B, ProtocolInfo_v113.CONTAINER_CLOSE_PACKET);
        map(f, 0x2C, ProtocolInfo_v113.CONTAINER_SET_SLOT_PACKET);
        map(f, 0x2D, ProtocolInfo_v113.CONTAINER_SET_DATA_PACKET);
        map(f, 0x2E, ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET);
        map(f, 0x2F, ProtocolInfo_v113.CRAFTING_DATA_PACKET);
        map(f, 0x30, ProtocolInfo_v113.CRAFTING_EVENT_PACKET);
        map(f, 0x31, ProtocolInfo_v113.ADVENTURE_SETTINGS_PACKET);
        map(f, 0x32, ProtocolInfo_v113.BLOCK_ENTITY_DATA_PACKET);
        map(f, 0x33, ProtocolInfo_v113.PLAYER_INPUT_PACKET);
        map(f, 0x34, ProtocolInfo_v113.FULL_CHUNK_DATA_PACKET);
        map(f, 0x35, ProtocolInfo_v113.SET_DIFFICULTY_PACKET);
        map(f, 0x36, ProtocolInfo_v113.CHANGE_DIMENSION_PACKET);
        map(f, 0x37, ProtocolInfo_v113.SET_PLAYER_GAME_TYPE_PACKET);
        map(f, 0x38, ProtocolInfo_v113.PLAYER_LIST_PACKET);
        map(f, 0x39, ProtocolInfo_v113.SIMPLE_EVENT_PACKET);
        map(f, 0x3A, ProtocolInfo_v113.SPAWN_EXPERIENCE_ORB_PACKET);
        map(f, 0x3B, ProtocolInfo_v113.CLIENTBOUND_MAP_ITEM_DATA_PACKET);
        map(f, 0x3C, ProtocolInfo_v113.MAP_INFO_REQUEST_PACKET);
        map(f, 0x3D, ProtocolInfo_v113.REQUEST_CHUNK_RADIUS_PACKET);
        map(f, 0x3E, ProtocolInfo_v113.CHUNK_RADIUS_UPDATED_PACKET);
        map(f, 0x3F, ProtocolInfo_v113.ITEM_FRAME_DROP_ITEM_PACKET);
        map(f, 0x41, ProtocolInfo_v113.ADD_ITEM_PACKET);
    }

    private static void initV016() {
        LegacyProtocol.Family f = LegacyProtocol.Family.V0_16;
        initCommonHandshake(f);
        map(f, 0x06, ProtocolInfo_v113.BATCH_PACKET);
        map(f, 0x07, ProtocolInfo_v113.RESOURCE_PACKS_INFO_PACKET);
        map(f, 0x08, ProtocolInfo_v113.RESOURCE_PACK_STACK_PACKET);
        map(f, 0x09, ProtocolInfo_v113.RESOURCE_PACK_CLIENT_RESPONSE_PACKET);
        map(f, 0x0A, ProtocolInfo_v113.TEXT_PACKET);
        map(f, 0x0B, ProtocolInfo_v113.SET_TIME_PACKET);
        map(f, 0x0C, ProtocolInfo_v113.START_GAME_PACKET);
        map(f, 0x0D, ProtocolInfo_v113.ADD_PLAYER_PACKET);
        map(f, 0x0E, ProtocolInfo_v113.ADD_ENTITY_PACKET);
        map(f, 0x0F, ProtocolInfo_v113.REMOVE_ENTITY_PACKET);
        map(f, 0x10, ProtocolInfo_v113.ADD_ITEM_ENTITY_PACKET);
        map(f, 0x11, ProtocolInfo_v113.ADD_HANGING_ENTITY_PACKET);
        map(f, 0x12, ProtocolInfo_v113.TAKE_ITEM_ENTITY_PACKET);
        map(f, 0x13, ProtocolInfo_v113.MOVE_ENTITY_PACKET);
        map(f, 0x14, ProtocolInfo_v113.MOVE_PLAYER_PACKET);
        map(f, 0x15, ProtocolInfo_v113.RIDER_JUMP_PACKET);
        map(f, 0x16, ProtocolInfo_v113.REMOVE_BLOCK_PACKET);
        map(f, 0x17, ProtocolInfo_v113.UPDATE_BLOCK_PACKET);
        map(f, 0x18, ProtocolInfo_v113.ADD_PAINTING_PACKET);
        map(f, 0x19, ProtocolInfo_v113.EXPLODE_PACKET);
        map(f, 0x1A, ProtocolInfo_v113.LEVEL_SOUND_EVENT_PACKET);
        map(f, 0x1B, ProtocolInfo_v113.LEVEL_EVENT_PACKET);
        map(f, 0x1C, ProtocolInfo_v113.BLOCK_EVENT_PACKET);
        map(f, 0x1D, ProtocolInfo_v113.ENTITY_EVENT_PACKET);
        map(f, 0x1E, ProtocolInfo_v113.MOB_EFFECT_PACKET);
        map(f, 0x1F, ProtocolInfo_v113.UPDATE_ATTRIBUTES_PACKET);
        map(f, 0x20, ProtocolInfo_v113.MOB_EQUIPMENT_PACKET);
        map(f, 0x21, ProtocolInfo_v113.MOB_ARMOR_EQUIPMENT_PACKET);
        map(f, 0x22, ProtocolInfo_v113.INTERACT_PACKET);
        map(f, 0x23, ProtocolInfo_v113.USE_ITEM_PACKET);
        map(f, 0x24, ProtocolInfo_v113.PLAYER_ACTION_PACKET);
        map(f, 0x25, ProtocolInfo_v113.HURT_ARMOR_PACKET);
        map(f, 0x26, ProtocolInfo_v113.SET_ENTITY_DATA_PACKET);
        map(f, 0x27, ProtocolInfo_v113.SET_ENTITY_MOTION_PACKET);
        map(f, 0x28, ProtocolInfo_v113.SET_ENTITY_LINK_PACKET);
        map(f, 0x29, ProtocolInfo_v113.SET_HEALTH_PACKET);
        map(f, 0x2A, ProtocolInfo_v113.SET_SPAWN_POSITION_PACKET);
        map(f, 0x2B, ProtocolInfo_v113.ANIMATE_PACKET);
        map(f, 0x2C, ProtocolInfo_v113.RESPAWN_PACKET);
        map(f, 0x2D, ProtocolInfo_v113.DROP_ITEM_PACKET);
        map(f, 0x2E, ProtocolInfo_v113.INVENTORY_ACTION_PACKET);
        map(f, 0x2F, ProtocolInfo_v113.CONTAINER_OPEN_PACKET);
        map(f, 0x30, ProtocolInfo_v113.CONTAINER_CLOSE_PACKET);
        map(f, 0x31, ProtocolInfo_v113.CONTAINER_SET_SLOT_PACKET);
        map(f, 0x32, ProtocolInfo_v113.CONTAINER_SET_DATA_PACKET);
        map(f, 0x33, ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET);
        map(f, 0x34, ProtocolInfo_v113.CRAFTING_DATA_PACKET);
        map(f, 0x35, ProtocolInfo_v113.CRAFTING_EVENT_PACKET);
        map(f, 0x36, ProtocolInfo_v113.ADVENTURE_SETTINGS_PACKET);
        map(f, 0x37, ProtocolInfo_v113.BLOCK_ENTITY_DATA_PACKET);
        map(f, 0x38, ProtocolInfo_v113.PLAYER_INPUT_PACKET);
        map(f, 0x39, ProtocolInfo_v113.FULL_CHUNK_DATA_PACKET);
        map(f, 0x3A, ProtocolInfo_v113.SET_COMMANDS_ENABLED_PACKET);
        map(f, 0x3B, ProtocolInfo_v113.SET_DIFFICULTY_PACKET);
        map(f, 0x3C, ProtocolInfo_v113.CHANGE_DIMENSION_PACKET);
        map(f, 0x3D, ProtocolInfo_v113.SET_PLAYER_GAME_TYPE_PACKET);
        map(f, 0x3E, ProtocolInfo_v113.PLAYER_LIST_PACKET);
        map(f, 0x3F, ProtocolInfo_v113.SIMPLE_EVENT_PACKET);
        map(f, 0x40, ProtocolInfo_v113.SPAWN_EXPERIENCE_ORB_PACKET);
        map(f, 0x41, ProtocolInfo_v113.CLIENTBOUND_MAP_ITEM_DATA_PACKET);
        map(f, 0x42, ProtocolInfo_v113.MAP_INFO_REQUEST_PACKET);
        map(f, 0x43, ProtocolInfo_v113.REQUEST_CHUNK_RADIUS_PACKET);
        map(f, 0x44, ProtocolInfo_v113.CHUNK_RADIUS_UPDATED_PACKET);
        map(f, 0x45, ProtocolInfo_v113.ITEM_FRAME_DROP_ITEM_PACKET);
        map(f, 0x46, ProtocolInfo_v113.REPLACE_ITEM_IN_SLOT_PACKET);
        map(f, 0x47, ProtocolInfo_v113.GAME_RULES_CHANGED_PACKET);
        map(f, 0x48, ProtocolInfo_v113.CAMERA_PACKET);
        map(f, 0x49, ProtocolInfo_v113.ADD_ITEM_PACKET);
        map(f, 0x4A, ProtocolInfo_v113.BOSS_EVENT_PACKET);
        map(f, 0x4B, ProtocolInfo_v113.AVAILABLE_COMMANDS_PACKET);
        map(f, 0x4C, ProtocolInfo_v113.COMMAND_STEP_PACKET);
    }

    private static void initV100() {
        LegacyProtocol.Family f = LegacyProtocol.Family.V1_0_0;
        initCommonHandshake(f);
        map(f, 0x06, ProtocolInfo_v113.BATCH_PACKET);
        map(f, 0x07, ProtocolInfo_v113.RESOURCE_PACKS_INFO_PACKET);
        map(f, 0x08, ProtocolInfo_v113.RESOURCE_PACK_STACK_PACKET);
        map(f, 0x09, ProtocolInfo_v113.RESOURCE_PACK_CLIENT_RESPONSE_PACKET);
        map(f, 0x0A, ProtocolInfo_v113.TEXT_PACKET);
        map(f, 0x0B, ProtocolInfo_v113.SET_TIME_PACKET);
        map(f, 0x0C, ProtocolInfo_v113.START_GAME_PACKET);
        map(f, 0x0D, ProtocolInfo_v113.ADD_PLAYER_PACKET);
        map(f, 0x0E, ProtocolInfo_v113.ADD_ENTITY_PACKET);
        map(f, 0x0F, ProtocolInfo_v113.REMOVE_ENTITY_PACKET);
        map(f, 0x10, ProtocolInfo_v113.ADD_ITEM_ENTITY_PACKET);
        map(f, 0x11, ProtocolInfo_v113.ADD_HANGING_ENTITY_PACKET);
        map(f, 0x12, ProtocolInfo_v113.TAKE_ITEM_ENTITY_PACKET);
        map(f, 0x13, ProtocolInfo_v113.MOVE_ENTITY_PACKET);
        map(f, 0x14, ProtocolInfo_v113.MOVE_PLAYER_PACKET);
        map(f, 0x15, ProtocolInfo_v113.RIDER_JUMP_PACKET);
        map(f, 0x16, ProtocolInfo_v113.REMOVE_BLOCK_PACKET);
        map(f, 0x17, ProtocolInfo_v113.UPDATE_BLOCK_PACKET);
        map(f, 0x18, ProtocolInfo_v113.ADD_PAINTING_PACKET);
        map(f, 0x19, ProtocolInfo_v113.EXPLODE_PACKET);
        map(f, 0x1A, ProtocolInfo_v113.LEVEL_SOUND_EVENT_PACKET);
        map(f, 0x1B, ProtocolInfo_v113.LEVEL_EVENT_PACKET);
        map(f, 0x1C, ProtocolInfo_v113.BLOCK_EVENT_PACKET);
        map(f, 0x1D, ProtocolInfo_v113.ENTITY_EVENT_PACKET);
        map(f, 0x1E, ProtocolInfo_v113.MOB_EFFECT_PACKET);
        map(f, 0x1F, ProtocolInfo_v113.UPDATE_ATTRIBUTES_PACKET);
        map(f, 0x20, ProtocolInfo_v113.MOB_EQUIPMENT_PACKET);
        map(f, 0x21, ProtocolInfo_v113.MOB_ARMOR_EQUIPMENT_PACKET);
        map(f, 0x22, ProtocolInfo_v113.INTERACT_PACKET);
        map(f, 0x23, ProtocolInfo_v113.USE_ITEM_PACKET);
        map(f, 0x24, ProtocolInfo_v113.PLAYER_ACTION_PACKET);
        map(f, 0x25, ProtocolInfo_v113.ENTITY_FALL_PACKET);
        map(f, 0x26, ProtocolInfo_v113.HURT_ARMOR_PACKET);
        map(f, 0x27, ProtocolInfo_v113.SET_ENTITY_DATA_PACKET);
        map(f, 0x28, ProtocolInfo_v113.SET_ENTITY_MOTION_PACKET);
        map(f, 0x29, ProtocolInfo_v113.SET_ENTITY_LINK_PACKET);
        map(f, 0x2A, ProtocolInfo_v113.SET_HEALTH_PACKET);
        map(f, 0x2B, ProtocolInfo_v113.SET_SPAWN_POSITION_PACKET);
        map(f, 0x2C, ProtocolInfo_v113.ANIMATE_PACKET);
        map(f, 0x2D, ProtocolInfo_v113.RESPAWN_PACKET);
        map(f, 0x2E, ProtocolInfo_v113.DROP_ITEM_PACKET);
        map(f, 0x2F, ProtocolInfo_v113.INVENTORY_ACTION_PACKET);
        map(f, 0x30, ProtocolInfo_v113.CONTAINER_OPEN_PACKET);
        map(f, 0x31, ProtocolInfo_v113.CONTAINER_CLOSE_PACKET);
        map(f, 0x32, ProtocolInfo_v113.CONTAINER_SET_SLOT_PACKET);
        map(f, 0x33, ProtocolInfo_v113.CONTAINER_SET_DATA_PACKET);
        map(f, 0x34, ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET);
        map(f, 0x35, ProtocolInfo_v113.CRAFTING_DATA_PACKET);
        map(f, 0x36, ProtocolInfo_v113.CRAFTING_EVENT_PACKET);
        map(f, 0x37, ProtocolInfo_v113.ADVENTURE_SETTINGS_PACKET);
        map(f, 0x38, ProtocolInfo_v113.BLOCK_ENTITY_DATA_PACKET);
        map(f, 0x39, ProtocolInfo_v113.PLAYER_INPUT_PACKET);
        map(f, 0x3A, ProtocolInfo_v113.FULL_CHUNK_DATA_PACKET);
        map(f, 0x3B, ProtocolInfo_v113.SET_COMMANDS_ENABLED_PACKET);
        map(f, 0x3C, ProtocolInfo_v113.SET_DIFFICULTY_PACKET);
        map(f, 0x3D, ProtocolInfo_v113.CHANGE_DIMENSION_PACKET);
        map(f, 0x3E, ProtocolInfo_v113.SET_PLAYER_GAME_TYPE_PACKET);
        map(f, 0x3F, ProtocolInfo_v113.PLAYER_LIST_PACKET);
        map(f, 0x40, ProtocolInfo_v113.SIMPLE_EVENT_PACKET);
        map(f, 0x41, ProtocolInfo_v113.SPAWN_EXPERIENCE_ORB_PACKET);
        map(f, 0x42, ProtocolInfo_v113.CLIENTBOUND_MAP_ITEM_DATA_PACKET);
        map(f, 0x43, ProtocolInfo_v113.MAP_INFO_REQUEST_PACKET);
        map(f, 0x44, ProtocolInfo_v113.REQUEST_CHUNK_RADIUS_PACKET);
        map(f, 0x45, ProtocolInfo_v113.CHUNK_RADIUS_UPDATED_PACKET);
        map(f, 0x46, ProtocolInfo_v113.ITEM_FRAME_DROP_ITEM_PACKET);
        map(f, 0x47, ProtocolInfo_v113.REPLACE_ITEM_IN_SLOT_PACKET);
        map(f, 0x48, ProtocolInfo_v113.GAME_RULES_CHANGED_PACKET);
        map(f, 0x49, ProtocolInfo_v113.CAMERA_PACKET);
        map(f, 0x4A, ProtocolInfo_v113.ADD_ITEM_PACKET);
        map(f, 0x4B, ProtocolInfo_v113.BOSS_EVENT_PACKET);
        map(f, 0x4C, ProtocolInfo_v113.SHOW_CREDITS_PACKET);
        map(f, 0x4D, ProtocolInfo_v113.AVAILABLE_COMMANDS_PACKET);
        map(f, 0x4E, ProtocolInfo_v113.COMMAND_STEP_PACKET);
        map(f, 0x4F, ProtocolInfo_v113.RESOURCE_PACK_DATA_INFO_PACKET);
        map(f, 0x50, ProtocolInfo_v113.RESOURCE_PACK_CHUNK_DATA_PACKET);
        map(f, 0x51, ProtocolInfo_v113.RESOURCE_PACK_CHUNK_REQUEST_PACKET);
        map(f, 0x52, ProtocolInfo_v113.TRANSFER_PACKET);
    }

    private static void initV104() {
        LegacyProtocol.Family f = LegacyProtocol.Family.V1_0_4;
        System.arraycopy(LEGACY_TO_V113.get(LegacyProtocol.Family.V1_0_0), 0, LEGACY_TO_V113.get(f), 0, 256);
        System.arraycopy(V113_TO_LEGACY.get(LegacyProtocol.Family.V1_0_0), 0, V113_TO_LEGACY.get(f), 0, 256);
        map(f, 0x4F, ProtocolInfo_v113.UPDATE_TRADE_PACKET);
        map(f, 0x50, ProtocolInfo_v113.RESOURCE_PACK_DATA_INFO_PACKET);
        map(f, 0x51, ProtocolInfo_v113.RESOURCE_PACK_CHUNK_DATA_PACKET);
        map(f, 0x52, ProtocolInfo_v113.RESOURCE_PACK_CHUNK_REQUEST_PACKET);
        map(f, 0x53, ProtocolInfo_v113.TRANSFER_PACKET);
    }

    private static void initV105() {
        LegacyProtocol.Family f = LegacyProtocol.Family.V1_0_5;
        initCommonHandshake(f);
        map(f, 0x06, ProtocolInfo_v113.BATCH_PACKET);
        map(f, 0x07, ProtocolInfo_v113.RESOURCE_PACKS_INFO_PACKET);
        map(f, 0x08, ProtocolInfo_v113.RESOURCE_PACK_STACK_PACKET);
        map(f, 0x09, ProtocolInfo_v113.RESOURCE_PACK_CLIENT_RESPONSE_PACKET);
        map(f, 0x0A, ProtocolInfo_v113.TEXT_PACKET);
        map(f, 0x0B, ProtocolInfo_v113.SET_TIME_PACKET);
        map(f, 0x0C, ProtocolInfo_v113.START_GAME_PACKET);
        map(f, 0x0D, ProtocolInfo_v113.ADD_PLAYER_PACKET);
        map(f, 0x0E, ProtocolInfo_v113.ADD_ENTITY_PACKET);
        map(f, 0x0F, ProtocolInfo_v113.REMOVE_ENTITY_PACKET);
        map(f, 0x10, ProtocolInfo_v113.ADD_ITEM_ENTITY_PACKET);
        map(f, 0x11, ProtocolInfo_v113.ADD_HANGING_ENTITY_PACKET);
        map(f, 0x12, ProtocolInfo_v113.TAKE_ITEM_ENTITY_PACKET);
        map(f, 0x13, ProtocolInfo_v113.MOVE_ENTITY_PACKET);
        map(f, 0x14, ProtocolInfo_v113.MOVE_PLAYER_PACKET);
        map(f, 0x15, ProtocolInfo_v113.RIDER_JUMP_PACKET);
        map(f, 0x16, ProtocolInfo_v113.REMOVE_BLOCK_PACKET);
        map(f, 0x17, ProtocolInfo_v113.UPDATE_BLOCK_PACKET);
        map(f, 0x18, ProtocolInfo_v113.ADD_PAINTING_PACKET);
        map(f, 0x19, ProtocolInfo_v113.EXPLODE_PACKET);
        map(f, 0x1A, ProtocolInfo_v113.LEVEL_SOUND_EVENT_PACKET);
        map(f, 0x1B, ProtocolInfo_v113.LEVEL_EVENT_PACKET);
        map(f, 0x1C, ProtocolInfo_v113.BLOCK_EVENT_PACKET);
        map(f, 0x1D, ProtocolInfo_v113.ENTITY_EVENT_PACKET);
        map(f, 0x1E, ProtocolInfo_v113.MOB_EFFECT_PACKET);
        map(f, 0x1F, ProtocolInfo_v113.UPDATE_ATTRIBUTES_PACKET);
        map(f, 0x20, ProtocolInfo_v113.MOB_EQUIPMENT_PACKET);
        map(f, 0x21, ProtocolInfo_v113.MOB_ARMOR_EQUIPMENT_PACKET);
        map(f, 0x22, ProtocolInfo_v113.INTERACT_PACKET);
        map(f, 0x23, ProtocolInfo_v113.BLOCK_PICK_REQUEST_PACKET);
        map(f, 0x24, ProtocolInfo_v113.USE_ITEM_PACKET);
        map(f, 0x25, ProtocolInfo_v113.PLAYER_ACTION_PACKET);
        map(f, 0x26, ProtocolInfo_v113.ENTITY_FALL_PACKET);
        map(f, 0x27, ProtocolInfo_v113.HURT_ARMOR_PACKET);
        map(f, 0x28, ProtocolInfo_v113.SET_ENTITY_DATA_PACKET);
        map(f, 0x29, ProtocolInfo_v113.SET_ENTITY_MOTION_PACKET);
        map(f, 0x2A, ProtocolInfo_v113.SET_ENTITY_LINK_PACKET);
        map(f, 0x2B, ProtocolInfo_v113.SET_HEALTH_PACKET);
        map(f, 0x2C, ProtocolInfo_v113.SET_SPAWN_POSITION_PACKET);
        map(f, 0x2D, ProtocolInfo_v113.ANIMATE_PACKET);
        map(f, 0x2E, ProtocolInfo_v113.RESPAWN_PACKET);
        map(f, 0x2F, ProtocolInfo_v113.DROP_ITEM_PACKET);
        map(f, 0x30, ProtocolInfo_v113.INVENTORY_ACTION_PACKET);
        map(f, 0x31, ProtocolInfo_v113.CONTAINER_OPEN_PACKET);
        map(f, 0x32, ProtocolInfo_v113.CONTAINER_CLOSE_PACKET);
        map(f, 0x33, ProtocolInfo_v113.CONTAINER_SET_SLOT_PACKET);
        map(f, 0x34, ProtocolInfo_v113.CONTAINER_SET_DATA_PACKET);
        map(f, 0x35, ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET);
        map(f, 0x36, ProtocolInfo_v113.CRAFTING_DATA_PACKET);
        map(f, 0x37, ProtocolInfo_v113.CRAFTING_EVENT_PACKET);
        map(f, 0x38, ProtocolInfo_v113.ADVENTURE_SETTINGS_PACKET);
        map(f, 0x39, ProtocolInfo_v113.BLOCK_ENTITY_DATA_PACKET);
        map(f, 0x3A, ProtocolInfo_v113.PLAYER_INPUT_PACKET);
        map(f, 0x3B, ProtocolInfo_v113.FULL_CHUNK_DATA_PACKET);
        map(f, 0x3C, ProtocolInfo_v113.SET_COMMANDS_ENABLED_PACKET);
        map(f, 0x3D, ProtocolInfo_v113.SET_DIFFICULTY_PACKET);
        map(f, 0x3E, ProtocolInfo_v113.CHANGE_DIMENSION_PACKET);
        map(f, 0x3F, ProtocolInfo_v113.SET_PLAYER_GAME_TYPE_PACKET);
        map(f, 0x40, ProtocolInfo_v113.PLAYER_LIST_PACKET);
        map(f, 0x41, ProtocolInfo_v113.SIMPLE_EVENT_PACKET);
        map(f, 0x42, ProtocolInfo_v113.SPAWN_EXPERIENCE_ORB_PACKET);
        map(f, 0x43, ProtocolInfo_v113.CLIENTBOUND_MAP_ITEM_DATA_PACKET);
        map(f, 0x44, ProtocolInfo_v113.MAP_INFO_REQUEST_PACKET);
        map(f, 0x45, ProtocolInfo_v113.REQUEST_CHUNK_RADIUS_PACKET);
        map(f, 0x46, ProtocolInfo_v113.CHUNK_RADIUS_UPDATED_PACKET);
        map(f, 0x47, ProtocolInfo_v113.ITEM_FRAME_DROP_ITEM_PACKET);
        map(f, 0x48, ProtocolInfo_v113.REPLACE_ITEM_IN_SLOT_PACKET);
        map(f, 0x49, ProtocolInfo_v113.GAME_RULES_CHANGED_PACKET);
        map(f, 0x4A, ProtocolInfo_v113.CAMERA_PACKET);
        map(f, 0x4B, ProtocolInfo_v113.ADD_ITEM_PACKET);
        map(f, 0x4C, ProtocolInfo_v113.BOSS_EVENT_PACKET);
        map(f, 0x4D, ProtocolInfo_v113.SHOW_CREDITS_PACKET);
        map(f, 0x4E, ProtocolInfo_v113.AVAILABLE_COMMANDS_PACKET);
        map(f, 0x4F, ProtocolInfo_v113.COMMAND_STEP_PACKET);
        map(f, 0x50, ProtocolInfo_v113.COMMAND_BLOCK_UPDATE_PACKET);
        map(f, 0x51, ProtocolInfo_v113.UPDATE_TRADE_PACKET);
        map(f, 0x52, ProtocolInfo_v113.RESOURCE_PACK_DATA_INFO_PACKET);
        map(f, 0x53, ProtocolInfo_v113.RESOURCE_PACK_CHUNK_DATA_PACKET);
        map(f, 0x54, ProtocolInfo_v113.RESOURCE_PACK_CHUNK_REQUEST_PACKET);
        map(f, 0x55, ProtocolInfo_v113.TRANSFER_PACKET);
        map(f, 0x56, ProtocolInfo_v113.PLAY_SOUND_PACKET);
        map(f, 0x57, ProtocolInfo_v113.STOP_SOUND_PACKET);
        map(f, 0x58, ProtocolInfo_v113.SET_TITLE_PACKET);
    }

    private static void initV110Early() {
        LegacyProtocol.Family f = LegacyProtocol.Family.V1_1_0_EARLY;
        initV110Table(f);
        map(f, 0x11, ProtocolInfo_v113.ADD_HANGING_ENTITY_PACKET);
        map(f, 0x11, ProtocolInfo_v113.TAKE_ITEM_ENTITY_PACKET);
    }

    private static void initV110Canonical() {
        LegacyProtocol.Family f = LegacyProtocol.Family.V1_1_0;
        initV110Table(f);
    }

    private static void initV110Table(LegacyProtocol.Family f) {
        for (int i = 0x01; i <= 0x5B; i++) {
            map(f, i, (byte) i);
        }
        map(f, 0xFF, ProtocolInfo_v113.BATCH_PACKET);
    }

    public static int toV113(LegacyProtocol protocol, int legacyPacketId) {
        int id = legacyPacketId & 0xFF;
        // Protocolo 90 (0.15.90): MySoft resuelve los IDs con la tabla 81 (sin entrada propia en
        // PacketPool.php:115-124), donde PLAYER_ACTION_PACKET = 0x20. Corrige el Shift/sneak perdido:
        // sin esto, 0x20 caia en MOB_EQUIPMENT de la tabla v91 y la accion nunca llegaba al servidor.
        if (protocol.getProtocol() == 90 && id == PLAYER_ACTION_LEGACY_PID_P90) {
            return ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF;
        }
        return LEGACY_TO_V113.get(protocol.getFamily())[id];
    }

    public static int toLegacy(LegacyProtocol protocol, int v113PacketId) {
        int id = v113PacketId & 0xFF;
        // Simetrico a toV113: PLAYER_ACTION v113 debe emitirse como 0x20 (tabla 81) en protocolo 90.
        if (protocol.getProtocol() == 90 && id == (ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF)) {
            return PLAYER_ACTION_LEGACY_PID_P90;
        }
        return V113_TO_LEGACY.get(protocol.getFamily())[id];
    }
}
