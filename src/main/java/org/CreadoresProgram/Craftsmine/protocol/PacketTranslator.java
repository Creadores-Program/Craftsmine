package org.CreadoresProgram.Craftsmine.protocol;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.network.protocol.TextPacket;
import cn.nukkit.network.protocol.v113.ProtocolInfo_v113;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.DataFormatException;

import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry.EntitySpawnInfo;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry.LegacySessionState;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry.SkinSnapshot;

/**
 * Traductor de paquetes bidireccional entre los protocolos legados 41-112 y el protocolo canonico
 * 113 (v1_1_0) de Nukkit-MOT, con parches completos de entidades, bloques, items, NBT, skins y sonidos.
 */
public final class PacketTranslator {

    private static final int SPECIAL_CREATIVE_WINDOW = 0x79;

    /** TYPE_SYSTEM en el alambre legado 80..112: valor 5 de la tabla PROTOCOL_81 de MySoft
     *  (TextPacketTypeIds.php). Esa tabla rige todo 81..132 porque MultiversionEnums elige la
     *  primera tabla con clave menor o igual al protocolo destino (la siguiente es PROTOCOL_133). */
    private static final int TEXT_TYPE_SYSTEM_LEGACY_WIRE = 5;

    public record InboundResult(byte[] canonicalPacket, boolean suppress) {
        public static InboundResult forward(byte[] canonicalPacket) {
            return new InboundResult(canonicalPacket, false);
        }

        public static InboundResult drop() {
            return new InboundResult(null, true);
        }
    }

    public record OutboundResult(byte[] legacyPacket, boolean suppress, boolean synthesizeResourceComplete) {
        public static OutboundResult forward(byte[] legacyPacket) {
            return new OutboundResult(legacyPacket, false, false);
        }

        public static OutboundResult drop(boolean synthesizeResourceComplete) {
            return new OutboundResult(null, true, synthesizeResourceComplete);
        }
    }

    public record LoginSniffResult(int rawProtocol, String username, byte[] canonicalV113LoginPacket) {
    }

    private PacketTranslator() {
    }

    /**
     * Detecta y normaliza un LoginPacket entrante (desde 80..112 hacia canonico 113).
     * Retorna null si el paquete ya es >= 113 o no es LoginPacket.
     */
    public static LoginSniffResult tryNormalizeLogin(byte[] rawPacket, int minAllowed, int maxAllowed) throws DataFormatException {
        if (rawPacket == null || rawPacket.length < 5) {
            return null;
        }
        int pid = rawPacket[0] & 0xFF;
        if (pid == 0x8F) {
            return tryNormalizePre80Login(rawPacket, minAllowed, maxAllowed);
        }
        if (pid != (ProtocolInfo_v113.LOGIN_PACKET & 0xFF)) {
            return null;
        }
        LegacyStream in = LegacyStream.reader(rawPacket, 1, rawPacket.length - 1);
        int proto = in.readIntBE();
        if (proto == 0 && in.remaining() >= 4) {
            in.position(in.position() - 2);
            proto = in.readIntBE();
        }
        if (proto >= LegacyProtocol.CANONICAL_NUKKIT_PROTOCOL || proto < minAllowed || proto > maxAllowed) {
            return null;
        }

        LegacyProtocol source = LegacyProtocol.resolve(proto);
        if (proto >= 90 && in.remaining() > 0) {
            in.readByte(); // gameEdition en 90..129
        }

        byte[] connectionPayload;
        if (proto < 110) {
            byte[] compressed = in.readByteArray(source);
            connectionPayload = BatchCodec.inflateZlibOrRaw(compressed, 0, compressed.length, 8 * 1024 * 1024);
        } else {
            connectionPayload = in.readByteArray(LegacyProtocol.PROTOCOL_113);
        }

        String username = extractDisplayName(connectionPayload);

        LegacyStream out = LegacyStream.writer(connectionPayload.length + 16);
        out.writeByte(ProtocolInfo_v113.LOGIN_PACKET & 0xFF);
        out.writeIntBE(LegacyProtocol.CANONICAL_NUKKIT_PROTOCOL);
        out.writeByte(0);
        out.writeByteArray(LegacyProtocol.PROTOCOL_113, connectionPayload);
        return new LoginSniffResult(proto, username, out.toByteArray());
    }

    public static byte[] buildSyntheticResourcePackCompleteV113() {
        LegacyStream out = LegacyStream.writer(8);
        out.writeByte(ProtocolInfo_v113.RESOURCE_PACK_CLIENT_RESPONSE_PACKET & 0xFF);
        out.writeByte(4); // STATUS_COMPLETED
        out.writeShortLE(0);
        return out.toByteArray();
    }

    /**
     * Traduce un sub-paquete entrante desde el protocolo legado hacia v113 canonico.
     */
    public static InboundResult translateInbound(byte[] rawPacket, LegacyProtocol source) {
        return translateInbound(rawPacket, source, null);
    }

    /**
     * Traduce un sub-paquete entrante desde el protocolo legado hacia v113 canonico con contexto de sesion.
     */
    public static InboundResult translateInbound(byte[] rawPacket, LegacyProtocol source, LegacySessionState state) {
        if (rawPacket == null || rawPacket.length == 0) {
            return InboundResult.drop();
        }
        int legacyId = rawPacket[0] & 0xFF;
        int v113Id = PacketIdMapper.toV113(source, legacyId);
        if (v113Id == PacketIdMapper.UNMAPPED) {
            return InboundResult.drop();
        }

        byte[] payload = java.util.Arrays.copyOfRange(rawPacket, 1, rawPacket.length);

        // En 0.15.x (< 90), los comandos "/" y "./" se envian en TextPacket tipo CHAT -> CommandStepPacket_v113 (0x4f)
        if ((v113Id & 0xFF) == 0x09 && source.isBefore90()) {
            InboundResult cmdStep = tryTranslatePre90CommandTextInbound(payload, source);
            if (cmdStep != null) {
                return cmdStep;
            }
        }

        // En 0.15.x (< 90), RespawnPacket del cliente solicita reaparicion (Nukkit < 388 lo ignora -> usar PlayerAction ACTION_RESPAWN=7)
        if ((v113Id & 0xFF) == 0x2d && source.isBefore90()) {
            return InboundResult.forward(buildPre90RespawnActionPacket(state));
        }

        byte[] translatedPayload = switch (v113Id & 0xFF) {
            case 0x09 -> translateTextInbound(payload, source);
            case 0x13 -> translateMovePlayerInbound(payload, source);
            case 0x15 -> translateRemoveBlockInbound(payload, source);
            case 0x1f -> translateMobEquipmentInbound(payload, source);
            case 0x20 -> translateMobArmorInbound(payload, source);
            case 0x21 -> translateInteractInbound(payload, source);
            case 0x23 -> translateUseItemInbound(payload, source);
            case 0x24 -> translatePlayerActionInbound(payload, source);
            case 0x2c -> translateAnimateInbound(payload, source);
            case 0x2d -> translateRespawnInbound(payload, source);
            case 0x2e -> translateDropItemInbound(payload, source);
            case 0x31 -> translateContainerCloseInbound(payload, source);
            case 0x32 -> translateContainerSetSlotInbound(payload, source);
            case 0x36 -> translateCraftingEventInbound(payload, source);
            case 0x37 -> translateAdventureSettingsInbound(payload, source);
            case 0x39 -> translatePlayerInputInbound(payload, source);
            case 0x44 -> translateMapInfoRequestInbound(payload, source);
            case 0x45 -> translateRequestChunkRadiusInbound(payload, source);
            case 0x47 -> translateItemFrameDropInbound(payload, source);
            default -> payload;
        };

        byte[] result = new byte[1 + translatedPayload.length];
        result[0] = (byte) (v113Id & 0xFF);
        System.arraycopy(translatedPayload, 0, result, 1, translatedPayload.length);
        return InboundResult.forward(result);
    }

    /**
     * Traduce un sub-paquete saliente desde v113 canonico hacia el protocolo del cliente legado.
     */
    public static OutboundResult translateOutbound(byte[] v113Packet, LegacyProtocol target, boolean bypassResourcePacks015) {
        return translateOutbound(v113Packet, target, bypassResourcePacks015, null, null);
    }

    /**
     * Traduce un sub-paquete saliente desde v113 canonico hacia el protocolo legado usando cache de skins y estado de sesion.
     */
    public static OutboundResult translateOutbound(
            byte[] v113Packet,
            LegacyProtocol target,
            boolean bypassResourcePacks015,
            SessionProtocolRegistry registry,
            LegacySessionState state
    ) {
        if (v113Packet == null || v113Packet.length == 0) {
            return OutboundResult.drop(false);
        }
        int v113Id = v113Packet[0] & 0xFF;

        if (target.isBefore90() && bypassResourcePacks015) {
            if (v113Id == (ProtocolInfo_v113.RESOURCE_PACKS_INFO_PACKET & 0xFF)) {
                return OutboundResult.drop(true);
            }
            if (v113Id == (ProtocolInfo_v113.RESOURCE_PACK_STACK_PACKET & 0xFF)) {
                return OutboundResult.drop(false);
            }
        }

        byte[] payload = java.util.Arrays.copyOfRange(v113Packet, 1, v113Packet.length);

        // Paquetes con logica especial de supresion o puente entre tipos
        switch (v113Id) {
            case 0x0d -> {
                return translateAddEntityOutbound(payload, target, state);
            }
            case 0x0e -> {
                return translateRemoveEntityOutbound(payload, target, state);
            }
            case 0x0f -> {
                return translateAddItemEntityOutbound(payload, target);
            }
            case 0x11 -> {
                return translateTakeItemEntityOutbound(payload, target);
            }
            case 0x19 -> {
                return LegacyContentMapper.translateLevelSoundEventOutbound(payload, target);
            }
            case 0x1a -> {
                return LegacyContentMapper.translateLevelEventOutbound(payload, target);
            }
            case 0x1c -> {
                EntitySpawnInfo spawnInfo = resolveEntitySpawnInfo(payload, state, true);
                return LegacyContentMapper.translateEntityEventOutbound(payload, target, spawnInfo);
            }
            case 0x1d -> {
                return translateMobEffectOutboundResult(payload, target);
            }
            case 0x29 -> {
                return translateSetEntityLinkOutbound(payload, target);
            }
            case 0x31 -> {
                // Evita reenviar inventarios modernos mal mapeados a 0x31 como ContainerClosePacket (1 byte)
                if (payload.length > 1) {
                    return OutboundResult.drop(false);
                }
            }
            case 0x32 -> {
                return translateContainerSetSlotOutboundResult(payload, target);
            }
            case 0x34 -> {
                return translateContainerSetContentOutboundResult(payload, target);
            }
            case 0x38 -> {
                return translateBlockEntityDataOutbound(payload, target);
            }
            case 0x43 -> {
                return translateMapItemDataOutbound(payload, target);
            }
            case 0x57 -> {
                return LegacyContentMapper.translatePlaySoundOutbound(payload, target);
            }
            default -> {
            }
        }

        int legacyId = PacketIdMapper.toLegacy(target, v113Id);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }

        byte[] translatedPayload = switch (v113Id) {
            case 0x05 -> translateDisconnectOutbound(payload, target);
            case 0x06 -> translateResourcePacksInfoOutbound(payload, target);
            case 0x07 -> translateResourcePackStackOutbound(payload, target);
            case 0x09 -> translateTextOutbound(payload, target);
            case 0x0a -> translateSetTimeOutbound(payload, target);
            case 0x0b -> translateStartGameOutbound(payload, target, state);
            case 0x0c -> translateAddPlayerOutbound(payload, target, state);
            // 0x0e (RemoveEntity) y 0x11 (TakeItemEntity) se manejan en el switch superior
            // con traduccion completa (pid + payload) via OutboundResult
            case 0x12 -> translateMoveEntityOutbound(payload, target);
            case 0x13 -> translateMovePlayerOutbound(payload, target);
            case 0x16 -> translateUpdateBlockOutbound(payload, target);
            case 0x17 -> translateAddPaintingOutbound(payload, target);
            case 0x18 -> translateExplodeOutbound(payload, target);
            case 0x1b -> translateBlockEventOutbound(payload, target);
            case 0x1e -> translateUpdateAttributesOutbound(payload, target);
            case 0x1f -> translateMobEquipmentOutbound(payload, target);
            case 0x20 -> translateMobArmorOutbound(payload, target);
            case 0x24 -> translatePlayerActionOutbound(payload, target);
            case 0x26 -> translateHurtArmorOutbound(payload, target);
            case 0x27 -> translateSetEntityDataOutbound(payload, target, state);
            case 0x28 -> translateSetEntityMotionOutbound(payload, target);
            case 0x2a -> translateSetHealthOutbound(payload, target);
            case 0x2b -> translateSetSpawnPositionOutbound(payload, target);
            case 0x2c -> translateAnimateOutbound(payload, target);
            case 0x2d -> translateRespawnOutbound(payload, target);
            case 0x30 -> translateContainerOpenOutbound(payload, target);
            case 0x31 -> translateContainerCloseOutbound(payload, target);
            case 0x33 -> translateContainerSetDataOutbound(payload, target);
            case 0x35 -> translateCraftingDataOutbound(payload, target);
            case 0x37 -> translateAdventureSettingsOutbound(payload, target);
            case 0x3a -> ChunkTranslator.translateOutbound(payload, target);
            case 0x3c -> translateSetDifficultyOutbound(payload, target);
            case 0x3d -> {
                if (state != null) {
                    state.clearEntities();
                }
                yield translateChangeDimensionOutbound(payload, target);
            }
            case 0x3e -> translateSetPlayerGameTypeOutbound(payload, target);
            case 0x3f -> translatePlayerListOutbound(payload, target, registry);
            case 0x42 -> translateSpawnExperienceOrbOutbound(payload, target);
            case 0x46 -> translateChunkRadiusUpdatedOutbound(payload, target);
            case 0x49 -> translateGameRulesChangedOutbound(payload, target);
            default -> payload;
        };

        byte[] result = new byte[1 + translatedPayload.length];
        result[0] = (byte) (legacyId & 0xFF);
        System.arraycopy(translatedPayload, 0, result, 1, translatedPayload.length);
        return OutboundResult.forward(result);
    }

    private static EntitySpawnInfo resolveEntitySpawnInfo(byte[] v113Payload, LegacySessionState state, boolean runtimeId) {
        if (state == null || v113Payload == null || v113Payload.length == 0) {
            return null;
        }
        try {
            LegacyStream r = LegacyStream.reader(v113Payload);
            long eid = runtimeId
                    ? r.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113)
                    : r.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
            return state.getEntity(eid);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static InboundResult tryTranslatePre90CommandTextInbound(byte[] payload, LegacyProtocol src) {
        if (payload.length < 3) {
            return null;
        }
        LegacyStream in = LegacyStream.reader(payload);
        int type = in.readByte();
        if (type != 1 || in.remaining() < 4) { // TYPE_CHAT
            return null;
        }
        in.readString(src); // source
        String message = in.readString(src);
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (!trimmed.startsWith("/") && !trimmed.startsWith("./")) {
            return null;
        }
        String cmd = trimmed.startsWith("./") ? trimmed.substring(2).trim() : trimmed.substring(1).trim();
        if (cmd.isEmpty()) {
            return InboundResult.drop();
        }
        LegacyStream out = LegacyStream.writer(cmd.length() + 32);
        out.writeByte(ProtocolInfo_v113.COMMAND_STEP_PACKET & 0xFF);
        out.writeString(LegacyProtocol.PROTOCOL_113, cmd);
        out.writeString(LegacyProtocol.PROTOCOL_113, "default");
        out.writeUnsignedVarInt(0); // uvarint1
        out.writeUnsignedVarInt(0); // currentStep
        out.writeBoolean(true);     // done
        out.writeUnsignedVarInt(0); // clientId
        out.writeString(LegacyProtocol.PROTOCOL_113, "{}");
        out.writeString(LegacyProtocol.PROTOCOL_113, "");
        return InboundResult.forward(out.toByteArray());
    }

    private static byte[] buildPre90RespawnActionPacket(LegacySessionState state) {
        long eid = (state != null && state.getPlayerEntityId() > 0) ? state.getPlayerEntityId() : 1L;
        LegacyStream out = LegacyStream.writer(24);
        out.writeByte(ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF);
        out.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, eid);
        out.writeVarInt(7); // PlayerActionPacket.ACTION_RESPAWN
        out.writeBlockPosition(LegacyProtocol.PROTOCOL_113, 0, 0, 0);
        out.writeVarInt(0);
        return out.toByteArray();
    }

    // =========================================================================
    // Inbound translators (Client [80..112] -> Canonical [113])
    // =========================================================================

    private static byte[] translateTextInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(payload.length + 8);
        int type = in.readByte();
        if (src.isBefore80()) {
            switch (type) {
                case 1 -> { // TYPE_CHAT
                    out.writeByte(TextPacket.TYPE_CHAT);
                    out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                    out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                }
                case 2 -> { // TYPE_TRANSLATION
                    out.writeByte(TextPacket.TYPE_TRANSLATION);
                    out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                    int count = in.readByte();
                    out.writeUnsignedVarInt(count);
                    for (int i = 0; i < count && in.remaining() > 0; i++) {
                        out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                    }
                }
                case 3 -> { // TYPE_POPUP (VeoZax: source+message | Retro: message)
                    String first = in.readString(src);
                    String source = "";
                    String message = first;
                    if (in.remaining() >= 2) {
                        source = first;
                        message = in.readString(src);
                    }
                    out.writeByte(TextPacket.TYPE_POPUP);
                    out.writeString(LegacyProtocol.PROTOCOL_113, message);
                    if (!source.isEmpty()) {
                        out.writeUnsignedVarInt(1);
                        out.writeString(LegacyProtocol.PROTOCOL_113, source);
                    } else {
                        out.writeUnsignedVarInt(0);
                    }
                }
                case 4 -> { // 0.14 TYPE_TIP -> v113 TYPE_TIP (5, solo message)
                    String first = in.readString(src);
                    String message = in.remaining() >= 2 ? in.readString(src) : first;
                    out.writeByte(TextPacket.TYPE_TIP);
                    out.writeString(LegacyProtocol.PROTOCOL_113, message);
                }
                case 5 -> { // 0.14 TYPE_SYSTEM -> v113 TYPE_SYSTEM (6)
                    out.writeByte(TextPacket.TYPE_SYSTEM);
                    out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                }
                default -> { // TYPE_RAW (0) u otros
                    out.writeByte(type);
                    out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                }
            }
            return out.toByteArray();
        }
        out.writeByte(type);
        switch (type) {
            case 1, 7, 8 -> {
                out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
            }
            case 3 -> {
                out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
            }
            case 2 -> {
                out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                int count = in.readByte();
                out.writeUnsignedVarInt(count);
                for (int i = 0; i < count && in.remaining() > 0; i++) {
                    out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
                }
            }
            default -> out.writeString(LegacyProtocol.PROTOCOL_113, in.readString(src));
        }
        return out.toByteArray();
    }

    private static byte[] translateMovePlayerInbound(byte[] payload, LegacyProtocol src) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(64);
        long eid = in.readEntityRuntimeId(src);
        float[] pos = in.readVector3(src);
        float pitch, yaw, headYaw;
        if (src.isBefore90()) {
            yaw = in.readFloatBE();
            headYaw = in.readFloatBE();
            pitch = in.readFloatBE();
        } else {
            pitch = in.readFloatLE();
            yaw = in.readFloatLE();
            headYaw = in.readFloatLE();
        }
        int mode = in.readByte();
        if (src.isBefore80() && mode == 2) {
            mode = 3; // 0.14 MODE_ROTATION (2) -> v113 MODE_PITCH (3)
        }
        boolean onGround = in.readBoolean();
        long ridingEid = !src.isBefore110() ? in.readEntityRuntimeId(src) : 0L;
        int teleportCause = 0;
        int teleportItem = 0;
        if (mode == 2 && !src.isBefore110() && in.remaining() >= 8) {
            teleportCause = in.readIntLE();
            teleportItem = in.readIntLE();
        }

        out.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, eid);
        out.writeVector3(LegacyProtocol.PROTOCOL_113, pos[0], pos[1], pos[2]);
        out.writeFloatLE(pitch);
        out.writeFloatLE(yaw);
        out.writeFloatLE(headYaw);
        out.writeByte(mode);
        out.writeBoolean(onGround);
        out.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, ridingEid);
        if (mode == 2) {
            out.writeIntLE(teleportCause);
            out.writeIntLE(teleportItem);
        }
        return out.toByteArray();
    }

    private static byte[] translateRemoveBlockInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(16);
        if (src.isBefore80() && payload.length == 12) {
            int x = in.readIntBE();
            int y = in.readIntBE();
            int z = in.readIntBE();
            out.writeBlockPosition(LegacyProtocol.PROTOCOL_113, x, y, z);
            return out.toByteArray();
        }
        in.readEntityRuntimeId(src);
        int x = in.readIntBE();
        int z = in.readIntBE();
        int y = in.readByte();
        out.writeBlockPosition(LegacyProtocol.PROTOCOL_113, x, y, z);
        return out.toByteArray();
    }

    private static byte[] translateUseItemInbound(byte[] payload, LegacyProtocol src) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(64);
        int[] bp = in.readBlockPosition(src);
        int blockId = src.getProtocol() >= 92 ? in.readUnsignedVarInt() : 0;
        // En 0.15.x face es signed byte (0xFF = -1 para usar item en el aire / comer / lanzar)
        int face = src.isBefore90() ? (byte) in.readByte() : in.readVarInt();
        float[] clickPos = in.readVector3(src);
        float[] playerPos = in.readVector3(src);
        int slot;
        if (src.isBefore80()) {
            slot = canReadPre80SlotAfterInt(in) ? in.readIntBE() : 0;
        } else {
            slot = src.isBefore90() ? in.readIntBE() : in.readVarInt();
        }
        LegacyStream.LegacySlot item = in.readSlot(src);

        out.writeBlockPosition(LegacyProtocol.PROTOCOL_113, bp[0], bp[1], bp[2]);
        out.writeUnsignedVarInt(blockId);
        out.writeVarInt(face);
        out.writeVector3(LegacyProtocol.PROTOCOL_113, clickPos[0], clickPos[1], clickPos[2]);
        out.writeVector3(LegacyProtocol.PROTOCOL_113, playerPos[0], playerPos[1], playerPos[2]);
        out.writeByte(slot);
        out.writeSlot(LegacyProtocol.PROTOCOL_113, item);
        return out.toByteArray();
    }

    private static byte[] translatePlayerActionInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90() && !src.isBefore92()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(32);
        long eid = in.readEntityRuntimeId(src);
        int action = src.isBefore90() ? in.readIntBE() : in.readVarInt();
        int[] bp = in.readBlockPosition(src);
        int face = src.isBefore90() ? in.readIntBE() : in.readVarInt();

        out.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, eid);
        out.writeVarInt(action);
        out.writeBlockPosition(LegacyProtocol.PROTOCOL_113, bp[0], bp[1], bp[2]);
        out.writeVarInt(face);
        return out.toByteArray();
    }

    private static byte[] translatePlayerActionOutbound(byte[] payload, LegacyProtocol dst) {
        // Espejo exacto del inbound: < 90 usa Long + IntBE x5 (Nukkit-Retro PlayerActionPacket.java:49-56;
        // MySoft PlayerActionPacket.php:107-109,115-118); 90..91 usan VarLong/VarInt con Y como byte
        // (MySoft NetworkBinaryStream.php:1120-1128, umbral PROTOCOL_92 para Y UnsignedVarInt);
        // >= 92 comparte layout con v113. Los valores de action 0..18 son identicos en la tabla
        // PROTOCOL_81 de MySoft (PlayerActionIds.php:360-380, rige 81..129) y en v113: no se remapean.
        if (!dst.isBefore90() && !dst.isBefore92()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(32);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        int action = in.readVarInt();
        int[] bp = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        int face = in.readVarInt();

        out.writeEntityRuntimeId(dst, eid);
        if (dst.isBefore90()) {
            out.writeIntBE(action);
            out.writeBlockPosition(dst, bp[0], bp[1], bp[2]);
            out.writeIntBE(face);
        } else {
            out.writeVarInt(action);
            out.writeBlockPosition(dst, bp[0], bp[1], bp[2]);
            out.writeVarInt(face);
        }
        return out.toByteArray();
    }

    private static byte[] translateInteractInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(16);
        int action = in.readByte();
        long target = in.readEntityRuntimeId(src);
        out.writeByte(action);
        out.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, target);
        return out.toByteArray();
    }

    private static byte[] translateAnimateInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(16);
        int action = in.readByte();
        long eid = in.readEntityRuntimeId(src);
        out.writeVarInt(action);
        out.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, eid);
        return out.toByteArray();
    }

    private static byte[] translateRespawnInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(16);
        float[] pos = in.readVector3(src);
        out.writeVector3(LegacyProtocol.PROTOCOL_113, pos[0], pos[1], pos[2]);
        return out.toByteArray();
    }

    private static byte[] translateDropItemInbound(byte[] payload, LegacyProtocol src) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(32);
        int type = in.readByte();
        LegacyStream.LegacySlot item = in.readSlot(src);
        out.writeByte(type);
        out.writeSlot(LegacyProtocol.PROTOCOL_113, item);
        return out.toByteArray();
    }

    private static byte[] translateMobEquipmentInbound(byte[] payload, LegacyProtocol src) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(32);
        long eid = in.readEntityRuntimeId(src);
        LegacyStream.LegacySlot item = in.readSlot(src);
        int invSlot = in.readByte();
        int hotbarSlot = in.readByte();
        int windowId = src.isBefore90() ? 0 : in.readByte();

        out.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, eid);
        out.writeSlot(LegacyProtocol.PROTOCOL_113, item);
        out.writeByte(invSlot);
        out.writeByte(hotbarSlot);
        out.writeByte(windowId);
        return out.toByteArray();
    }

    private static byte[] translateMobArmorInbound(byte[] payload, LegacyProtocol src) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(64);
        long eid = in.readEntityRuntimeId(src);
        out.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, eid);
        for (int i = 0; i < 4; i++) {
            out.writeSlot(LegacyProtocol.PROTOCOL_113, in.readSlot(src));
        }
        return out.toByteArray();
    }

    private static byte[] translateContainerCloseInbound(byte[] payload, LegacyProtocol src) {
        return payload;
    }

    private static byte[] translateContainerSetSlotInbound(byte[] payload, LegacyProtocol src) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(48);
        int windowId = in.readByte();
        int slot = src.isBefore90() ? (short) in.readShortBE() : in.readVarInt();
        int hotbar = src.isBefore90() ? (short) in.readShortBE() : in.readVarInt();
        LegacyStream.LegacySlot item = in.readSlot(src);
        int selectSlot = src.getProtocol() >= 92 ? in.readByte() : 0;

        out.writeByte(windowId);
        out.writeVarInt(slot);
        out.writeVarInt(hotbar);
        out.writeSlot(LegacyProtocol.PROTOCOL_113, item);
        out.writeByte(selectSlot);
        return out.toByteArray();
    }

    private static byte[] translateCraftingEventInbound(byte[] payload, LegacyProtocol src) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(64);
        int windowId = in.readByte();
        int type = src.isBefore90() ? in.readIntBE() : in.readVarInt();
        UUID uuid = in.readUUID(src);
        int inCount = src.isBefore90() ? in.readIntBE() : in.readUnsignedVarInt();
        out.writeByte(windowId);
        out.writeVarInt(type);
        out.writeUUID(uuid);
        out.writeUnsignedVarInt(inCount);
        for (int i = 0; i < inCount && in.remaining() > 0; i++) {
            out.writeSlot(LegacyProtocol.PROTOCOL_113, in.readSlot(src));
        }
        int outCount = in.remaining() > 0 ? (src.isBefore90() ? in.readIntBE() : in.readUnsignedVarInt()) : 0;
        out.writeUnsignedVarInt(outCount);
        for (int i = 0; i < outCount && in.remaining() > 0; i++) {
            out.writeSlot(LegacyProtocol.PROTOCOL_113, in.readSlot(src));
        }
        return out.toByteArray();
    }

    private static byte[] translateAdventureSettingsInbound(byte[] payload, LegacyProtocol src) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(16);
        int flags = src.isBefore90() ? in.readIntBE() : in.readUnsignedVarInt();
        int cmdPerm = src.isBefore90() ? in.readIntBE() : in.readUnsignedVarInt();
        if (src.isBefore90() && (flags & 0x1C0) != 0) {
            int mapped = flags & 0x03;
            if ((flags & 0x40) != 0) {
                mapped |= 0x20; // PRE_V_0_15_0_AUTO_JUMP -> AUTO_JUMP
            }
            if ((flags & 0x80) != 0) {
                mapped |= 0x40; // PRE_V_0_15_0_ALLOW_FLIGHT -> ALLOW_FLIGHT
            }
            if ((flags & 0x100) != 0) {
                mapped |= 0x80; // PRE_V_0_15_0_NO_CLIP -> NO_CLIP
            }
            flags = mapped;
        }
        out.writeUnsignedVarInt(flags);
        out.writeUnsignedVarInt(cmdPerm);
        return out.toByteArray();
    }

    private static byte[] translatePlayerInputInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(12);
        out.writeFloatLE(in.readFloatBE());
        out.writeFloatLE(in.readFloatBE());
        if (src.isBefore80() && in.remaining() == 1) {
            int flags = in.readByte();
            out.writeBoolean((flags & 0x80) != 0);
            out.writeBoolean((flags & 0x40) != 0);
        } else {
            out.writeBoolean(in.readBoolean());
            out.writeBoolean(in.readBoolean());
        }
        return out.toByteArray();
    }

    private static byte[] translateMapInfoRequestInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(8);
        out.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, in.readLongBE());
        return out.toByteArray();
    }

    private static byte[] translateRequestChunkRadiusInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(8);
        out.writeVarInt(in.readIntBE());
        return out.toByteArray();
    }

    private static byte[] translateItemFrameDropInbound(byte[] payload, LegacyProtocol src) {
        if (!src.isBefore90() && !src.isBefore92()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        int[] bp = in.readBlockPosition(src);
        LegacyStream out = LegacyStream.writer(16);
        out.writeBlockPosition(LegacyProtocol.PROTOCOL_113, bp[0], bp[1], bp[2]);
        return out.toByteArray();
    }

    // =========================================================================
    // Outbound translators (Canonical [113] -> Client [80..112])
    // =========================================================================

    private static byte[] translateDisconnectOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        boolean hideScreen = in.readBoolean();
        String msg = hideScreen ? "" : in.readString(LegacyProtocol.PROTOCOL_113);
        if (dst.isBefore80()) {
            // 0.14.x: el cliente real lee DisconnectPacket::read como un unico readString
            // (libminecraftpe.so 0.14.3: _ZN16DisconnectPacket4readEPN6RakNet9BitStreamE = tail-call
            // a readString). Coincide con Nukkit-Retro (DisconnectPacket.encode isBefore0160:
            // putString sin booleano) y con VeoZax p70/p84 (putString sin booleano).
            // El booleano extra desplazaba la longitud short del string: el cliente 0.14 leia una
            // longitud 0x0000 y descartaba el mensaje de expulsion, quedandose colgado en
            // "Localizando servidor" en vez de mostrar el motivo del kick.
            LegacyStream out14 = LegacyStream.writer(msg.length() + 4);
            out14.writeString(dst, msg);
            return out14.toByteArray();
        }
        LegacyStream out = LegacyStream.writer(msg.length() + 8);
        out.writeBoolean(hideScreen);
        if (!hideScreen) {
            out.writeString(dst, msg);
        }
        return out.toByteArray();
    }

    private static byte[] translateResourcePacksInfoOutbound(byte[] payload, LegacyProtocol dst) {
        if (dst.getProtocol() >= 110) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(payload.length);
        boolean mustAccept = in.readBoolean();
        out.writeBoolean(mustAccept);
        int bCount = in.readShortLE();
        out.writeShortLE(bCount);
        for (int i = 0; i < bCount; i++) {
            out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
            out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
            out.writeLongLE(in.readLongLE());
            in.readString(LegacyProtocol.PROTOCOL_113);
        }
        int rCount = in.readShortLE();
        out.writeShortLE(rCount);
        for (int i = 0; i < rCount; i++) {
            out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
            out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
            out.writeLongLE(in.readLongLE());
            in.readString(LegacyProtocol.PROTOCOL_113);
        }
        return out.toByteArray();
    }

    private static byte[] translateResourcePackStackOutbound(byte[] payload, LegacyProtocol dst) {
        return payload;
    }

    private static byte[] translateTextOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            // 90..112 comparten numeracion y layout de campos con v113; solo degrada TYPE_ANNOUNCEMENT,
            // invalido para todo destino < 113 (tabla PROTOCOL_81 de MySoft: valor maximo 7).
            if (payload.length == 0 || payload[0] != TextPacket.TYPE_ANNOUNCEMENT) {
                return payload;
            }
            LegacyStream in = LegacyStream.reader(payload);
            in.readByte(); // type
            in.readString(LegacyProtocol.PROTOCOL_113); // source: TYPE_SYSTEM no lo serializa
            String message = in.readString(LegacyProtocol.PROTOCOL_113);
            LegacyStream out = LegacyStream.writer(payload.length);
            out.writeByte(TEXT_TYPE_SYSTEM_LEGACY_WIRE);
            out.writeString(dst, message);
            return out.toByteArray();
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(payload.length + 8);
        int type = in.readByte();
        if (dst.isBefore80()) {
            // 0.14.x: TYPE_RAW=0, CHAT=1, TRANSLATION=2, POPUP=3 (source+msg), TIP->POPUP(3), SYSTEM=5
            switch (type) {
                case 0 -> {
                    out.writeByte(0);
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                }
                case 1 -> {
                    out.writeByte(1);
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                }
                case 2 -> {
                    out.writeByte(2);
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                    int count = Math.min(255, in.readUnsignedVarInt());
                    out.writeByte(count);
                    for (int i = 0; i < count && in.remaining() > 0; i++) {
                        out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                    }
                }
                case 3, 4 -> { // POPUP / JUKEBOX_POPUP -> 0.14 POPUP (3)
                    String message = in.readString(LegacyProtocol.PROTOCOL_113);
                    int pCount = in.remaining() > 0 ? Math.min(255, in.readUnsignedVarInt()) : 0;
                    String title = "";
                    for (int i = 0; i < pCount && in.remaining() > 0; i++) {
                        String param = in.readString(LegacyProtocol.PROTOCOL_113);
                        if (i == 0 && type == 3) {
                            title = param;
                        }
                    }
                    out.writeByte(3);
                    out.writeString(dst, title);
                    out.writeString(dst, message);
                }
                case 5 -> { // v113 TYPE_TIP -> 0.14 TYPE_POPUP (3)
                    out.writeByte(3);
                    out.writeString(dst, "");
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                }
                case 7, 8 -> { // WHISPER / ANNOUNCEMENT -> 0.14 TYPE_SYSTEM (5) sin source
                    in.readString(LegacyProtocol.PROTOCOL_113);
                    out.writeByte(TEXT_TYPE_SYSTEM_LEGACY_WIRE);
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                }
                case 6 -> { // v113 TYPE_SYSTEM (6) -> 0.14 TYPE_SYSTEM (5)
                    out.writeByte(TEXT_TYPE_SYSTEM_LEGACY_WIRE);
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                }
                default -> {
                    out.writeByte(0);
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                }
            }
            return out.toByteArray();
        }
        // < 90 (anterior a 0.16.0): TYPE_WHISPER y TYPE_ANNOUNCEMENT se degradan a TYPE_SYSTEM
        // (solo message, sin source). La regla < 113 para TYPE_ANNOUNCEMENT queda subsumida:
        // todo destino < 90 tambien es < 113.
        boolean degradeToSystem = type == TextPacket.TYPE_WHISPER || type == TextPacket.TYPE_ANNOUNCEMENT;
        out.writeByte(degradeToSystem ? TEXT_TYPE_SYSTEM_LEGACY_WIRE : type);
        switch (type) {
            case 1, 7, 8 -> {
                String source = in.readString(LegacyProtocol.PROTOCOL_113);
                String message = in.readString(LegacyProtocol.PROTOCOL_113);
                if (type == TextPacket.TYPE_CHAT) { // TYPE_CHAT conserva el source; los degradados lo descartan
                    out.writeString(dst, source);
                }
                out.writeString(dst, message);
            }
            case 3 -> {
                String message = in.readString(LegacyProtocol.PROTOCOL_113);
                int pCount = in.remaining() > 0 ? Math.min(255, in.readUnsignedVarInt()) : 0;
                String title = "";
                for (int i = 0; i < pCount && in.remaining() > 0; i++) {
                    String param = in.readString(LegacyProtocol.PROTOCOL_113);
                    if (i == 0) {
                        title = param;
                    }
                }
                out.writeString(dst, title);
                out.writeString(dst, message);
            }
            case 2 -> {
                out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                int count = Math.min(255, in.readUnsignedVarInt());
                out.writeByte(count);
                for (int i = 0; i < count; i++) {
                    out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
                }
            }
            default -> out.writeString(dst, in.readString(LegacyProtocol.PROTOCOL_113));
        }
        return out.toByteArray();
    }

    private static byte[] translateSetTimeOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(12);
        int time = in.readVarInt();
        if (dst.isBefore90()) {
            out.writeIntBE(time);
            out.writeBoolean(true);
        } else if (dst.isBefore110()) {
            out.writeVarInt(time);
            out.writeBoolean(true);
        } else {
            out.writeVarInt(time);
        }
        return out.toByteArray();
    }

    private static byte[] translateStartGameOutbound(byte[] payload, LegacyProtocol dst, LegacySessionState state) {
        LegacyStream in = LegacyStream.reader(payload);
        long entityUniqueId = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        long entityRuntimeId = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        if (state != null) {
            state.setPlayerEntityId(entityRuntimeId);
        }
        int playerGamemode = in.readVarInt();
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        float pitch = in.readFloatLE();
        float yaw = in.readFloatLE();
        int seed = in.readVarInt();
        int dimension = in.readVarInt();
        int generator = in.readVarInt();
        int worldGamemode = in.readVarInt();
        int difficulty = in.readVarInt();
        int[] spawn = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        boolean hasAchievementsDisabled = in.readBoolean();
        int time = in.readVarInt();
        boolean eduMode = in.readBoolean();
        float rainLevel = in.readFloatLE();
        float lightningLevel = in.readFloatLE();
        boolean commandsEnabled = in.readBoolean();
        boolean textureRequired = in.readBoolean();
        int ruleCount = in.remaining() > 0 ? in.readUnsignedVarInt() : 0;
        byte[] rulesFiltered = ruleCount > 0 ? filterEncodedGameRules(in, ruleCount, dst) : new byte[]{0};
        String levelId = in.remaining() > 0 ? in.readString(LegacyProtocol.PROTOCOL_113) : "";
        String worldName = in.remaining() > 0 ? in.readString(LegacyProtocol.PROTOCOL_113) : "World";
        String premiumTemplate = in.remaining() > 0 ? in.readString(LegacyProtocol.PROTOCOL_113) : "";
        boolean isTrial = in.remaining() > 0 && in.readBoolean();
        long currentTick = in.remaining() >= 8 ? in.readLongLE() : 0L;
        int safeDim = clampDimension(dimension, dst);

        LegacyStream out = LegacyStream.writer(256);
        if (dst.isBefore90()) {
            out.writeIntBE(seed);
            out.writeByte(safeDim);
            out.writeIntBE(generator);
            out.writeIntBE(worldGamemode);
            out.writeEntityRuntimeId(dst, entityRuntimeId);
            out.writeIntBE(spawn[0]);
            out.writeIntBE(spawn[1]);
            out.writeIntBE(spawn[2]);
            out.writeVector3(dst, pos[0], pos[1], pos[2]);
            if (dst.getProtocol() < 45) {
                // 0.14.0 betas (41-44) usan el cierre pre-0.14 (Nukkit-Retro StartGamePacket.encode
                // isBefore0140: putByte(0) y return; MPMPESCore: "0.13: ...[0] (47 bytes)"):
                // tras las floats solo va un byte 0, sin flags ni string.
                out.writeByte(0);
                return out.toByteArray();
            }
            out.writeBoolean(hasAchievementsDisabled);
            // 0.14.x (< 80): segundo flag = true como en las referencias probadas con clientes
            // reales 0.14.3 (Nukkit-Retro StartGamePacket isBefore0160: b1=b2=true, b3=false;
            // VeoZax p70 StartGamePacket: chr(1) chr(1) chr(0)). El cliente 0.14.3 lo lee y lo
            // pasa a la inicializacion del nivel (LegacyClientNetworkHandler::handle(StartGamePacket)
            // 0x3e8708: ldr r8, [r5, #0x30] -> startGame). 0.15.x (80-112) conserva false.
            out.writeBoolean(dst.isBefore80());
            out.writeBoolean(eduMode);
            out.writeString(dst, levelId);
            return out.toByteArray();
        }

        out.writeEntityUniqueId(dst, entityUniqueId);
        out.writeEntityRuntimeId(dst, entityRuntimeId);
        if (!dst.isBefore110()) {
            out.writeVarInt(playerGamemode);
        }
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        out.writeFloatLE(pitch);
        out.writeFloatLE(yaw);
        out.writeVarInt(seed);
        out.writeVarInt(safeDim);
        out.writeVarInt(generator);
        out.writeVarInt(worldGamemode);
        out.writeVarInt(difficulty);
        out.writeBlockPosition(dst, spawn[0], spawn[1], spawn[2]);
        out.writeBoolean(hasAchievementsDisabled);
        out.writeVarInt(time);
        out.writeBoolean(eduMode);
        out.writeFloatLE(rainLevel);
        out.writeFloatLE(lightningLevel);
        out.writeBoolean(commandsEnabled);
        out.writeBoolean(textureRequired);
        if (!dst.isBefore110()) {
            out.writeBytes(rulesFiltered);
        }
        out.writeString(dst, levelId);
        out.writeString(dst, worldName);
        if (!dst.isBefore110()) {
            out.writeString(dst, premiumTemplate);
            out.writeBoolean(isTrial);
            out.writeLongLE(currentTick);
        }
        return out.toByteArray();
    }

    private static byte[] translateAddPlayerOutbound(byte[] payload, LegacyProtocol dst, LegacySessionState state) {
        LegacyStream in = LegacyStream.reader(payload);
        UUID uuid = in.readUUID();
        String username = in.readString(LegacyProtocol.PROTOCOL_113);
        long eidUnique = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        long eidRuntime = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        if (dst.isBefore80() && state != null) {
            state.trackPlayerUuid(eidRuntime, uuid);
            if (eidUnique != eidRuntime) {
                state.trackPlayerUuid(eidUnique, uuid);
            }
        }
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        float[] motion = in.readVector3(LegacyProtocol.PROTOCOL_113);
        float pitch = in.readFloatLE();
        float yaw = in.readFloatLE();
        float headYaw = in.readFloatLE();
        LegacyStream.LegacySlot item = LegacyContentMapper.mapSlotOutbound(in.readSlot(LegacyProtocol.PROTOCOL_113), dst, false);
        byte[] metadata = LegacyContentMapper.translateMetadataOutbound(in, dst);

        LegacyStream out = LegacyStream.writer(128);
        out.writeUUID(dst, uuid);
        out.writeString(dst, username);
        if (!dst.isBefore90()) {
            out.writeEntityUniqueId(dst, eidUnique);
        }
        out.writeEntityRuntimeId(dst, eidRuntime);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        out.writeVector3(dst, motion[0], motion[1], motion[2]);
        if (dst.isBefore90()) {
            out.writeFloatBE(yaw);
            out.writeFloatBE(headYaw);
            out.writeFloatBE(pitch);
        } else {
            out.writeFloatLE(pitch);
            out.writeFloatLE(yaw);
            out.writeFloatLE(headYaw);
        }
        out.writeSlot(dst, item);
        out.writeBytes(metadata);
        return out.toByteArray();
    }

    private static OutboundResult translateAddEntityOutbound(byte[] payload, LegacyProtocol dst, LegacySessionState state) {
        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.ADD_ENTITY_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        long eidUnique = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        long eidRuntime = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        int rawType = in.readUnsignedVarInt();
        int mappedType = LegacyContentMapper.mapEntityTypeOutbound(rawType, dst);
        if (mappedType < 0) {
            return OutboundResult.drop(false);
        }
        EntitySpawnInfo spawnInfo = new EntitySpawnInfo(rawType, mappedType);
        if (state != null) {
            state.trackEntity(eidRuntime, rawType, mappedType);
            if (eidUnique != eidRuntime) {
                state.trackEntity(eidUnique, rawType, mappedType);
            }
        }
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        float[] motion = in.readVector3(LegacyProtocol.PROTOCOL_113);
        float pitch = in.readFloatLE();
        float yaw = in.readFloatLE();

        int attrCount = in.readUnsignedVarInt();
        record AttrEntry(String name, float min, float max, float val) {
        }
        List<AttrEntry> attrs = new ArrayList<>(attrCount);
        for (int i = 0; i < attrCount && in.remaining() > 0; i++) {
            String rawName = in.readString(LegacyProtocol.PROTOCOL_113);
            float min = in.readFloatLE();
            float val = in.readFloatLE();
            float max = in.readFloatLE();
            String mappedName = LegacyContentMapper.mapAttributeNameOutbound(rawName, dst);
            if (mappedName != null) {
                attrs.add(new AttrEntry(mappedName, min, max, val));
            }
        }
        byte[] meta = LegacyContentMapper.translateMetadataOutbound(in, dst, spawnInfo);

        LegacyStream out = LegacyStream.writer(128);
        out.writeByte(legacyId & 0xFF);
        if (dst.isBefore90()) {
            // Formato AddActorPacket < 90 (MySoft lines 189-231):
            // entityRuntimeId + intBE(type) + pos BE + motion BE + yaw BE + pitch BE + modifiers(intBE 0) + meta + links(shortBE 0)
            out.writeEntityRuntimeId(dst, eidRuntime);
            out.writeIntBE(mappedType);
            out.writeVector3(dst, pos[0], pos[1], pos[2]);
            out.writeVector3(dst, motion[0], motion[1], motion[2]);
            out.writeFloatBE(yaw);
            out.writeFloatBE(pitch);
            out.writeIntBE(0);
            out.writeBytes(meta);
            out.writeShortBE(0);
            return OutboundResult.forward(out.toByteArray());
        }

        out.writeEntityUniqueId(dst, eidUnique);
        out.writeEntityRuntimeId(dst, eidRuntime);
        out.writeUnsignedVarInt(mappedType);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        out.writeVector3(dst, motion[0], motion[1], motion[2]);
        out.writeFloatLE(pitch);
        out.writeFloatLE(yaw);
        out.writeUnsignedVarInt(attrs.size());
        for (AttrEntry a : attrs) {
            out.writeString(dst, a.name());
            out.writeFloatLE(a.min());
            out.writeFloatLE(a.val());
            out.writeFloatLE(a.max());
        }
        out.writeBytes(meta);
        out.writeUnsignedVarInt(0); // links
        return OutboundResult.forward(out.toByteArray());
    }

    private static OutboundResult translateRemoveEntityOutbound(byte[] payload, LegacyProtocol dst, LegacySessionState state) {
        // MySoft PacketPool no define tabla para el protocolo 90: getPacketIdByMagic() resuelve
        // hacia abajo hasta PacketsIds81 (REMOVE_ACTOR_PACKET = 0x0c) aunque la serializacion de
        // campos ya es varlong (umbral >= 90). Usar la tabla 91 (0x0f) hace que el cliente 0.15.90
        // lea un MoveActorAbsolute y la entidad nunca desaparezca.
        int legacyId = dst.getProtocol() == 90
                ? 0x0c
                : PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.REMOVE_ENTITY_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        // Nukkit-MOT v113 (protocol < 137) escribe TODOS los entity ids como UnsignedVarInt
        // (override DataPacket.putEntityUniqueId/putEntityRuntimeId -> putUnsignedVarInt), no como
        // VarLong zigzag. La lectura debe seguir la misma convencion que usan los Add* hacia cada
        // familia para que el cliente vea el MISMO id en el spawn y en el remove:
        // - dst < 90: los Add* exponen el VALOR real del id (longBE del runtimeId leido como
        //   unsigned); leer el uniqueId como VarLong zigzag corrompe el valor (p.ej. eid 77 -> -39)
        //   y la entidad jamas desaparece en clientes 0.15.x (80-84).
        // - dst >= 90: los Add* preservan los bytes originales del campo uniqueId mediante la
        //   involucion del zigzag (varlong(zigzagdec(x)) == x); hay que preservar esos mismos bytes.
        long eid = dst.isBefore90()
                ? in.readUnsignedVarLong()
                : in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        UUID playerUuid = null;
        if (state != null) {
            state.removeEntity(eid);
            if (dst.isBefore80()) {
                playerUuid = state.removePlayerUuid(eid);
            }
        }
        if (dst.isBefore80() && playerUuid != null) {
            LegacyStream out = LegacyStream.writer(28);
            out.writeByte(0x97); // RemovePlayerPacket en 0.14.x
            out.writeEntityUniqueId(dst, eid);
            out.writeUUID(dst, playerUuid);
            return OutboundResult.forward(out.toByteArray());
        }
        LegacyStream out = LegacyStream.writer(12);
        out.writeByte(legacyId & 0xFF);
        out.writeEntityUniqueId(dst, eid);
        return OutboundResult.forward(out.toByteArray());
    }

    private static OutboundResult translateAddItemEntityOutbound(byte[] payload, LegacyProtocol dst) {
        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.ADD_ITEM_ENTITY_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        long eidUnique = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        long eidRuntime = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        LegacyStream.LegacySlot item = LegacyContentMapper.mapSlotOutbound(in.readSlot(LegacyProtocol.PROTOCOL_113), dst, false);
        if (item == null || item.id() <= 0) {
            return OutboundResult.drop(false);
        }
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        float[] motion = in.readVector3(LegacyProtocol.PROTOCOL_113);

        LegacyStream out = LegacyStream.writer(64);
        out.writeByte(legacyId & 0xFF);
        if (!dst.isBefore90()) {
            out.writeEntityUniqueId(dst, eidUnique);
        }
        out.writeEntityRuntimeId(dst, eidRuntime);
        out.writeSlot(dst, item);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        out.writeVector3(dst, motion[0], motion[1], motion[2]);
        return OutboundResult.forward(out.toByteArray());
    }

    private static OutboundResult translateTakeItemEntityOutbound(byte[] payload, LegacyProtocol dst) {
        // MySoft PacketPool no define tabla para el protocolo 90: getPacketIdByMagic() resuelve
        // hacia abajo hasta PacketsIds81 (TAKE_ITEM_ACTOR_PACKET = 0x0e) aunque la serializacion de
        // campos ya es UnsignedVarLong (umbral >= 90). Usar la tabla 91 (0x12) hace que el cliente
        // 0.15.90 lea un RemoveBlock y el item recogido nunca desaparezca del suelo.
        int legacyId = dst.getProtocol() == 90
                ? 0x0e
                : PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.TAKE_ITEM_ENTITY_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        long target = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        LegacyStream out = LegacyStream.writer(16);
        out.writeByte(legacyId & 0xFF);
        out.writeEntityRuntimeId(dst, target);
        out.writeEntityRuntimeId(dst, eid);
        return OutboundResult.forward(out.toByteArray());
    }

    private static byte[] translateMoveEntityOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        int pitch = in.readByte();
        int yaw = in.readByte();
        int headYaw = in.readByte();
        int flags = in.remaining() > 0 ? in.readByte() : 0;

        LegacyStream out = LegacyStream.writer(40);
        if (dst.isBefore80()) {
            // 0.14.x: intBE(count=1) + eid + pos BE + floatBE yaw, headYaw, pitch (en grados)
            out.writeIntBE(1);
            out.writeEntityRuntimeId(dst, eid);
            out.writeVector3(dst, pos[0], pos[1], pos[2]);
            out.writeFloatBE((yaw & 0xFF) * (360.0f / 256.0f));
            out.writeFloatBE((headYaw & 0xFF) * (360.0f / 256.0f));
            out.writeFloatBE((pitch & 0xFF) * (360.0f / 256.0f));
            return out.toByteArray();
        }
        out.writeEntityRuntimeId(dst, eid);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        if (dst.isBefore90()) {
            // MySoft < 90: pitch, yaw, headYaw (sin flags)
            out.writeByte(pitch);
            out.writeByte(yaw);
            out.writeByte(headYaw);
        } else if (dst.isBefore110()) {
            // MySoft 90..109: pitch, headYaw, yaw (sin flags)
            out.writeByte(pitch);
            out.writeByte(headYaw);
            out.writeByte(yaw);
        } else {
            // MySoft 110..112: pitch, headYaw, yaw + boolean onGround + boolean teleport
            out.writeByte(pitch);
            out.writeByte(headYaw);
            out.writeByte(yaw);
            out.writeBoolean((flags & 0x01) != 0);
            out.writeBoolean((flags & 0x02) != 0);
        }
        return out.toByteArray();
    }

    private static byte[] translateMovePlayerOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        float pitch = in.readFloatLE();
        float yaw = in.readFloatLE();
        float headYaw = in.readFloatLE();
        int mode = in.readByte();
        boolean onGround = in.readBoolean();
        long ridingEid = in.remaining() > 0 ? in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113) : 0L;
        int teleportCause = 0;
        int teleportItem = 0;
        if (mode == 2 && in.remaining() >= 8) {
            teleportCause = in.readIntLE();
            teleportItem = in.readIntLE();
        }

        LegacyStream out = LegacyStream.writer(56);
        out.writeEntityRuntimeId(dst, eid);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        if (dst.isBefore90()) {
            out.writeFloatBE(yaw);
            out.writeFloatBE(headYaw);
            out.writeFloatBE(pitch);
        } else {
            out.writeFloatLE(pitch);
            out.writeFloatLE(yaw);
            out.writeFloatLE(headYaw);
        }
        int wireMode = dst.isBefore80() ? switch (mode) {
            case 2 -> 1; // TELEPORT -> MODE_RESET (0.14.x)
            case 3 -> 2; // PITCH -> MODE_ROTATION (0.14.x)
            default -> mode;
        } : mode;
        out.writeByte(wireMode);
        out.writeBoolean(onGround);
        if (!dst.isBefore110()) {
            out.writeEntityRuntimeId(dst, ridingEid);
            if (mode == 2) {
                out.writeIntLE(teleportCause);
                out.writeIntLE(teleportItem);
            }
        }
        return out.toByteArray();
    }

    private static byte[] translateUpdateBlockOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        int[] bp = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        int rawBlockId = in.readUnsignedVarInt();
        int aux = in.readUnsignedVarInt();
        int flags = (aux >> 4) & 0x0F;
        int rawMeta = aux & 0x0F;

        int packed = LegacyContentMapper.mapBlockAndMeta(rawBlockId, rawMeta, dst);
        int mappedId = (packed >>> 8) & 0xFF;
        int mappedAux = (flags << 4) | (packed & 0x0F);

        LegacyStream out = LegacyStream.writer(16);
        if (dst.isBefore90()) {
            if (dst.isBefore80()) {
                out.writeIntBE(1);
            }
            out.writeIntBE(bp[0]);
            out.writeIntBE(bp[2]);
            out.writeByte(bp[1]);
            out.writeByte(mappedId);
            out.writeByte(mappedAux);
        } else {
            out.writeBlockPosition(dst, bp[0], bp[1], bp[2]);
            out.writeUnsignedVarInt(mappedId);
            out.writeUnsignedVarInt(mappedAux);
        }
        return out.toByteArray();
    }

    private static byte[] translateAddPaintingOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        int[] bp = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        int direction = in.readVarInt();
        String title = in.readString(LegacyProtocol.PROTOCOL_113);

        LegacyStream out = LegacyStream.writer(32 + title.length());
        out.writeEntityRuntimeId(dst, eid);
        out.writeBlockPosition(dst, bp[0], bp[1], bp[2]);
        out.writeIntBE(direction);
        out.writeString(dst, title);
        return out.toByteArray();
    }

    private static byte[] translateExplodeOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore110()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        float radius = in.readVarInt() / 32.0f;
        int count = in.readUnsignedVarInt();

        LegacyStream out = LegacyStream.writer(32 + count * 6);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        if (dst.isBefore90()) {
            out.writeFloatBE(radius);
            out.writeIntBE(count);
            for (int i = 0; i < count && in.remaining() > 0; i++) {
                int[] off = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
                out.writeByte(off[0]);
                out.writeByte(off[1]);
                out.writeByte(off[2]);
            }
        } else {
            out.writeFloatLE(radius);
            out.writeUnsignedVarInt(count);
            for (int i = 0; i < count && in.remaining() > 0; i++) {
                int[] off = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
                out.writeSignedBlockPosition(dst, off[0], off[1], off[2]);
            }
        }
        return out.toByteArray();
    }

    private static byte[] translateBlockEventOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90() && !dst.isBefore92()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        int[] bp = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        int c1 = in.readVarInt();
        int c2 = in.readVarInt();

        LegacyStream out = LegacyStream.writer(24);
        out.writeBlockPosition(dst, bp[0], bp[1], bp[2]);
        if (dst.isBefore90()) {
            out.writeIntBE(c1);
            out.writeIntBE(c2);
        } else {
            out.writeVarInt(c1);
            out.writeVarInt(c2);
        }
        return out.toByteArray();
    }

    private static OutboundResult translateMobEffectOutboundResult(byte[] payload, LegacyProtocol dst) {
        // MySoft PacketPool no define tabla para el protocolo 90: getPacketIdByMagic() resuelve
        // hacia abajo hasta PacketsIds81 (MOB_EFFECT_PACKET = 0x19) aunque la serializacion de
        // campos ya es VarInt/UnsignedVarLong (umbral >= 90, MobEffectPacket.php:54-87). Usar la
        // tabla 91 (0x1e) hace que el cliente 0.15.90 lea un InteractPacket (PacketsIds81.php:55)
        // y el efecto nunca se aplique.
        int legacyId = dst.getProtocol() == 90
                ? 0x19
                : PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.MOB_EFFECT_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        int eventId = in.readByte();
        int effectId = in.readVarInt();
        int amplifier = in.readVarInt();
        boolean particles = in.readBoolean();
        int duration = in.readVarInt();

        // Filtro de efectos inexistentes en la version del cliente. Nukkit-MOT conoce efectos hasta
        // RAID_OMEN=36 (Effect.java:26-68) y su gate canReceiveEffectPacket (Player.java:2674-2682)
        // solo excluye los 4 efectos de 1.21.0 (WIND_CHARGED/WEAVING/OOZING/INFESTED) por debajo de
        // v1_21_0: en la sesion puente P113 los IDs 24-31 y 36 llegan hasta aqui y el puente debe
        // filtrarlos. Validos por version: 1-23 existen desde 0.11 (todos los legacy); LEVITATION (24)
        // llega con 1.0.0 (Ender Update, shulkers), no en 0.15.x/0.16.x, y la frontera de version es
        // el protocolo 92 (LegacyProtocol.java:18-20: P90/P91 = 0.15.90/0.16.x Family V0_16, P92 =
        // 1.0.0.0 Family V1_0_0; MySoft ProtocolInfo.php:202-204); 25+ (FATAL_POISON...RAID_OMEN,
        // Effect.java:57-68) no existen en ninguna version <= 1.1.13 y se descartan siempre.
        // isBefore92() delimita la version del cliente; isBefore90() mas abajo solo la serializacion.
        if (effectId <= 0 || (dst.isBefore92() && effectId > 23) || effectId > 24) {
            return OutboundResult.drop(false);
        }

        LegacyStream out = LegacyStream.writer(24);
        out.writeByte(legacyId & 0xFF);
        out.writeEntityRuntimeId(dst, eid);
        out.writeByte(eventId);
        if (!dst.isBefore90()) {
            out.writeVarInt(effectId);
            out.writeVarInt(amplifier);
            out.writeBoolean(particles);
            out.writeVarInt(duration);
        } else {
            out.writeByte(effectId);
            out.writeByte(amplifier);
            out.writeBoolean(particles);
            out.writeIntBE(duration);
        }
        return OutboundResult.forward(out.toByteArray());
    }

    private static byte[] translateUpdateAttributesOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        long eid = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        int count = in.readUnsignedVarInt();

        record AttrValue(float min, float max, float current, float defVal, String name) {
        }
        List<AttrValue> mapped = new ArrayList<>(count);
        for (int i = 0; i < count && in.remaining() > 0; i++) {
            float min = in.readFloatLE();
            float max = in.readFloatLE();
            float current = in.readFloatLE();
            float defVal = in.readFloatLE();
            String rawName = in.readString(LegacyProtocol.PROTOCOL_113);
            String name = LegacyContentMapper.mapAttributeNameOutbound(rawName, dst);
            if (name != null) {
                mapped.add(new AttrValue(min, max, current, defVal, name));
            }
        }

        if (!dst.isBefore90()) {
            LegacyStream out = LegacyStream.writer(payload.length + 8);
            out.writeEntityRuntimeId(dst, eid);
            out.writeUnsignedVarInt(mapped.size());
            for (AttrValue a : mapped) {
                out.writeFloatLE(a.min());
                out.writeFloatLE(a.max());
                out.writeFloatLE(a.current());
                out.writeFloatLE(a.defVal());
                out.writeString(dst, a.name());
            }
            return out.toByteArray();
        }

        LegacyStream out = LegacyStream.writer(32 + mapped.size() * 24);
        out.writeEntityRuntimeId(dst, eid);
        out.writeShortBE(mapped.size());
        for (AttrValue a : mapped) {
            out.writeFloatBE(a.min());
            out.writeFloatBE(a.max());
            out.writeFloatBE(a.current());
            out.writeString(dst, a.name());
        }
        return out.toByteArray();
    }

    private static byte[] translateMobEquipmentOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        LegacyStream.LegacySlot item = LegacyContentMapper.mapSlotOutbound(in.readSlot(LegacyProtocol.PROTOCOL_113), dst, false);
        int invSlot = in.readByte();
        int hotbarSlot = in.readByte();
        int windowId = in.remaining() > 0 ? in.readByte() : 0;

        LegacyStream out = LegacyStream.writer(40);
        out.writeEntityRuntimeId(dst, eid);
        out.writeSlot(dst, item);
        out.writeByte(invSlot);
        out.writeByte(hotbarSlot);
        if (!dst.isBefore90()) {
            out.writeByte(windowId);
        }
        return out.toByteArray();
    }

    private static byte[] translateMobArmorOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(64);
        out.writeEntityRuntimeId(dst, in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113));
        for (int i = 0; i < 4; i++) {
            LegacyStream.LegacySlot armor = LegacyContentMapper.mapSlotOutbound(in.readSlot(LegacyProtocol.PROTOCOL_113), dst, false);
            out.writeSlot(dst, armor);
        }
        return out.toByteArray();
    }

    private static byte[] translateHurtArmorOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        int health = in.readVarInt();
        LegacyStream out = LegacyStream.writer(4);
        out.writeByte(health);
        return out.toByteArray();
    }

    private static byte[] translateSetEntityDataOutbound(byte[] payload, LegacyProtocol dst, LegacySessionState state) {
        LegacyStream in = LegacyStream.reader(payload);
        // Nukkit-MOT v113 escribe el eid de SetEntityData con putEntityUniqueId -> putUnsignedVarInt
        // (DataPacket.java:151-158, protocol < v1_2_0=137; SetEntityDataPacket.java:38-39), sin zigzag.
        // readEntityUniqueId(PROTOCOL_113) usa VarLong zigzag y corrompe el valor para eids >= 64:
        // el cliente 0.15.x recibia SetEntityData con un eid inexistente (p.ej. 100 -> -50) y descartaba
        // el paquete, de modo que el Shift/sneak (DATA_FLAG_SNEAKING bit 1, ActorMetadataList PROTOCOL_81)
        // nunca se visualizaba. Mismo patron ya aplicado en translateRemoveEntityOutbound: el AddPlayer/AddEntity
        // de cada familia expone el VALOR real del id (unsigned), y el eid debe matchear el del spawn para que
        // el cliente aplique la metadata a la entidad correcta.
        long eid = in.readUnsignedVarLong();
        EntitySpawnInfo spawnInfo = state != null ? state.getEntity(eid) : null;
        LegacyStream out = LegacyStream.writer(64);
        out.writeEntityRuntimeId(dst, eid);
        out.writeBytes(LegacyContentMapper.translateMetadataOutbound(in, dst, spawnInfo));
        return out.toByteArray();
    }

    private static byte[] translateSetEntityMotionOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        float[] motion = in.readVector3(LegacyProtocol.PROTOCOL_113);
        LegacyStream out = LegacyStream.writer(24);
        out.writeEntityRuntimeId(dst, eid);
        out.writeVector3(dst, motion[0], motion[1], motion[2]);
        return out.toByteArray();
    }

    private static OutboundResult translateSetEntityLinkOutbound(byte[] payload, LegacyProtocol dst) {
        // MySoft Boat.php: "if($player->getProtocol() >= PROTOCOL_90) // 0.15 crash fix"
        if (dst.isBefore90()) {
            return OutboundResult.drop(false);
        }
        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.SET_ENTITY_LINK_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        long from = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        long to = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        int type = in.readByte();
        LegacyStream out = LegacyStream.writer(24);
        out.writeByte(legacyId & 0xFF);
        out.writeEntityUniqueId(dst, from);
        out.writeEntityUniqueId(dst, to);
        out.writeByte(type);
        return OutboundResult.forward(out.toByteArray());
    }

    private static byte[] translateSetHealthOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(4);
        out.writeIntBE(in.readVarInt());
        return out.toByteArray();
    }

    private static byte[] translateSetSpawnPositionOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90() && !dst.isBefore92()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        int spawnType = in.readVarInt();
        int[] bp = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        boolean forced = in.remaining() > 0 && in.readBoolean();
        LegacyStream out = LegacyStream.writer(20);
        if (dst.isBefore90()) {
            out.writeIntBE(bp[0]);
            out.writeIntBE(bp[1]);
            out.writeIntBE(bp[2]);
        } else {
            out.writeVarInt(spawnType);
            out.writeBlockPosition(dst, bp[0], bp[1], bp[2]);
            out.writeBoolean(forced);
        }
        return out.toByteArray();
    }

    private static byte[] translateAnimateOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        int action = in.readVarInt();
        long eid = in.readEntityRuntimeId(LegacyProtocol.PROTOCOL_113);
        LegacyStream out = LegacyStream.writer(16);
        if (dst.isBefore90()) {
            out.writeByte(action);
        } else {
            out.writeVarInt(action);
        }
        out.writeEntityRuntimeId(dst, eid);
        if (!dst.isBefore90() && in.remaining() > 0) {
            out.writeFloatLE(in.readFloatLE());
        }
        return out.toByteArray();
    }

    private static byte[] translateRespawnOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        LegacyStream out = LegacyStream.writer(12);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        return out.toByteArray();
    }

    private static byte[] translateContainerOpenOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore110()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        int windowId = in.readByte();
        int type = in.readByte();
        int[] bp = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        long eid = in.remaining() > 0 ? in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113) : -1L;

        int legacySlots = resolveHardcodedWindowSlots((byte) type);
        LegacyStream out = LegacyStream.writer(24);
        out.writeByte(windowId);
        out.writeByte(type);
        if (dst.isBefore90()) {
            out.writeShortBE(legacySlots);
            out.writeIntBE(bp[0]);
            out.writeIntBE(bp[1]);
            out.writeIntBE(bp[2]);
            if (dst.isBefore80() && eid != -1L) {
                out.writeLongBE(eid);
            }
        } else {
            out.writeVarInt(legacySlots);
            out.writeBlockPosition(dst, bp[0], bp[1], bp[2]);
            out.writeEntityUniqueId(dst, eid);
        }
        return out.toByteArray();
    }

    private static int resolveHardcodedWindowSlots(int signedWindowType) {
        return switch (signedWindowType) {
            case -1 -> 36;
            case 1, 6, 7, 10 -> 9;
            case 2, 15 -> 3;
            case 3, 5, 12 -> 2;
            case 4, 8, 11 -> 5;
            case 9, 13 -> 1;
            case 14 -> 0;
            default -> 27;
        };
    }

    private static byte[] translateContainerCloseOutbound(byte[] payload, LegacyProtocol dst) {
        return payload;
    }

    private static OutboundResult translateContainerSetSlotOutboundResult(byte[] payload, LegacyProtocol dst) {
        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.CONTAINER_SET_SLOT_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        int windowId = in.readByte();
        if ((dst.isBefore110() && windowId == 119) || windowId == 124 || windowId >= 122) {
            return OutboundResult.drop(false);
        }
        int slot = in.readVarInt();
        int hotbar = in.readVarInt();
        LegacyStream.LegacySlot item = LegacyContentMapper.mapSlotOutbound(in.readSlot(LegacyProtocol.PROTOCOL_113), dst, false);
        int selectSlot = in.remaining() > 0 ? in.readByte() : 0;

        LegacyStream out = LegacyStream.writer(48);
        out.writeByte(legacyId & 0xFF);
        out.writeByte(windowId);
        if (dst.isBefore90()) {
            out.writeShortBE(slot);
            out.writeShortBE(hotbar);
        } else {
            out.writeVarInt(slot);
            out.writeVarInt(hotbar);
        }
        out.writeSlot(dst, item);
        if (dst.getProtocol() >= 92) {
            out.writeByte(selectSlot);
        }
        return OutboundResult.forward(out.toByteArray());
    }

    private static byte[] translateContainerSetDataOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(8);
        out.writeByte(in.readByte());
        out.writeShortBE(in.readVarInt());
        out.writeShortBE(in.readVarInt());
        return out.toByteArray();
    }

    private static OutboundResult translateContainerSetContentOutboundResult(byte[] payload, LegacyProtocol dst) {
        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        int windowId = in.readUnsignedVarInt();
        if ((dst.isBefore110() && windowId == 119) || windowId == 124 || windowId >= 122) {
            return OutboundResult.drop(false);
        }
        long eid = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        int count = in.readUnsignedVarInt();
        boolean isCreative = (windowId == SPECIAL_CREATIVE_WINDOW);

        List<LegacyStream.LegacySlot> mappedSlots = new ArrayList<>(count);
        for (int i = 0; i < count && in.remaining() > 0; i++) {
            try {
                LegacyStream.LegacySlot rawSlot = in.readSlot(LegacyProtocol.PROTOCOL_113);
                LegacyStream.LegacySlot mapped = LegacyContentMapper.mapSlotOutbound(rawSlot, dst, isCreative);
                if (isCreative) {
                    if (mapped != null && mapped.id() > 0) {
                        mappedSlots.add(mapped);
                    }
                } else {
                    mappedSlots.add(mapped == null ? LegacyStream.LegacySlot.AIR : mapped);
                }
            } catch (RuntimeException malformed) {
                if (isCreative) {
                    break;
                }
                throw malformed;
            }
        }
        if (isCreative && dst.isBefore80()) {
            mappedSlots = LegacyContentMapper.mergeCreativeUnionForPre80(mappedSlots);
        }
        // Nukkit-MOT envia 45 slots (36 + 9 vacios) en PlayerInventory, pero 0.15.x solo soporta 36 slots en windowId=0
        if (windowId == 0 && dst.isBefore90() && mappedSlots.size() > 36) {
            mappedSlots = new ArrayList<>(mappedSlots.subList(0, 36));
        }

        int hotbarCount = in.remaining() > 0 ? Math.min(36, in.readUnsignedVarInt()) : 0;
        int[] hotbar = new int[hotbarCount];
        for (int i = 0; i < hotbarCount && in.remaining() > 0; i++) {
            hotbar[i] = in.readVarInt();
        }

        LegacyStream out = LegacyStream.writer(64 + mappedSlots.size() * 12);
        out.writeByte(legacyId & 0xFF);
        if (!dst.isBefore110()) {
            out.writeUnsignedVarInt(windowId);
            out.writeEntityUniqueId(dst, eid);
        } else {
            out.writeByte(windowId);
        }
        if (dst.isBefore90()) {
            out.writeShortBE(mappedSlots.size());
        } else {
            out.writeUnsignedVarInt(mappedSlots.size());
        }
        for (LegacyStream.LegacySlot slot : mappedSlots) {
            out.writeSlot(dst, slot);
        }
        if (dst.isBefore90()) {
            out.writeShortBE(hotbarCount);
            for (int h : hotbar) {
                out.writeIntBE(h);
            }
        } else {
            out.writeUnsignedVarInt(hotbarCount);
            for (int h : hotbar) {
                out.writeVarInt(h);
            }
        }
        return OutboundResult.forward(out.toByteArray());
    }

    private static byte[] translateCraftingDataOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        int count = in.readUnsignedVarInt();

        record EncodedRecipe(int type, byte[] data) {
        }
        List<EncodedRecipe> validRecipes = new ArrayList<>(Math.min(count, 2048));

        for (int i = 0; i < count && in.remaining() > 0; i++) {
            int recipeType = in.readVarInt();
            if (recipeType < 0) {
                continue;
            }

            LegacyStream entry = LegacyStream.writer(64);
            boolean unsupported = false;
            switch (recipeType) {
                case 0 -> { // ENTRY_SHAPELESS
                    int ingCount = in.readUnsignedVarInt();
                    if (dst.isBefore90()) {
                        entry.writeIntBE(ingCount);
                    } else {
                        entry.writeUnsignedVarInt(ingCount);
                    }
                    for (int j = 0; j < ingCount && in.remaining() > 0; j++) {
                        LegacyStream.LegacySlot raw = in.readSlot(LegacyProtocol.PROTOCOL_113);
                        LegacyStream.LegacySlot mapped = LegacyContentMapper.mapSlotOutbound(raw, dst, false);
                        if (raw.id() != 0 && (mapped == null || mapped.id() <= 0)) {
                            unsupported = true;
                        }
                        entry.writeSlot(dst, mapped);
                    }
                    int resCount = in.remaining() > 0 ? in.readUnsignedVarInt() : 0;
                    if (dst.isBefore90()) {
                        entry.writeIntBE(resCount);
                    } else {
                        entry.writeUnsignedVarInt(resCount);
                    }
                    for (int j = 0; j < resCount && in.remaining() > 0; j++) {
                        LegacyStream.LegacySlot raw = in.readSlot(LegacyProtocol.PROTOCOL_113);
                        LegacyStream.LegacySlot mapped = LegacyContentMapper.mapSlotOutbound(raw, dst, false);
                        if (raw.id() != 0 && (mapped == null || mapped.id() <= 0)) {
                            unsupported = true;
                        }
                        entry.writeSlot(dst, mapped);
                    }
                    entry.writeUUID(dst, in.remaining() >= 16 ? in.readUUID() : new UUID(0L, 0L));
                }
                case 1 -> { // ENTRY_SHAPED
                    int width = in.readVarInt();
                    int height = in.readVarInt();
                    if (dst.isBefore90()) {
                        entry.writeIntBE(width);
                        entry.writeIntBE(height);
                    } else {
                        entry.writeVarInt(width);
                        entry.writeVarInt(height);
                    }
                    int gridSize = Math.max(0, width) * Math.max(0, height);
                    for (int j = 0; j < gridSize && in.remaining() > 0; j++) {
                        LegacyStream.LegacySlot raw = in.readSlot(LegacyProtocol.PROTOCOL_113);
                        LegacyStream.LegacySlot mapped = LegacyContentMapper.mapSlotOutbound(raw, dst, false);
                        if (raw.id() != 0 && (mapped == null || mapped.id() <= 0)) {
                            unsupported = true;
                        }
                        entry.writeSlot(dst, mapped);
                    }
                    int resCount = in.remaining() > 0 ? in.readUnsignedVarInt() : 0;
                    if (dst.isBefore90()) {
                        entry.writeIntBE(resCount);
                    } else {
                        entry.writeUnsignedVarInt(resCount);
                    }
                    for (int j = 0; j < resCount && in.remaining() > 0; j++) {
                        LegacyStream.LegacySlot raw = in.readSlot(LegacyProtocol.PROTOCOL_113);
                        LegacyStream.LegacySlot mapped = LegacyContentMapper.mapSlotOutbound(raw, dst, false);
                        if (raw.id() != 0 && (mapped == null || mapped.id() <= 0)) {
                            unsupported = true;
                        }
                        entry.writeSlot(dst, mapped);
                    }
                    entry.writeUUID(dst, in.remaining() >= 16 ? in.readUUID() : new UUID(0L, 0L));
                }
                case 2 -> { // ENTRY_FURNACE
                    int inputId = in.readVarInt();
                    LegacyStream.LegacySlot rawRes = in.readSlot(LegacyProtocol.PROTOCOL_113);
                    LegacyStream.LegacySlot mappedRes = LegacyContentMapper.mapSlotOutbound(rawRes, dst, false);
                    if (inputId <= 0 || inputId > 460 || (rawRes.id() != 0 && (mappedRes == null || mappedRes.id() <= 0))) {
                        unsupported = true;
                    }
                    if (dst.isBefore90()) {
                        entry.writeIntBE(inputId);
                    } else {
                        entry.writeVarInt(inputId);
                    }
                    entry.writeSlot(dst, mappedRes);
                }
                case 3 -> { // ENTRY_FURNACE_DATA
                    int inputId = in.readVarInt();
                    int inputDamage = in.readVarInt();
                    LegacyStream.LegacySlot rawRes = in.readSlot(LegacyProtocol.PROTOCOL_113);
                    LegacyStream.LegacySlot mappedRes = LegacyContentMapper.mapSlotOutbound(rawRes, dst, false);
                    if (inputId <= 0 || inputId > 460 || (rawRes.id() != 0 && (mappedRes == null || mappedRes.id() <= 0))) {
                        unsupported = true;
                    }
                    if (dst.isBefore90()) {
                        entry.writeIntBE(((inputId & 0xFFFF) << 16) | (inputDamage & 0xFFFF));
                    } else {
                        entry.writeVarInt(inputId);
                        entry.writeVarInt(inputDamage);
                    }
                    entry.writeSlot(dst, mappedRes);
                }
                case 4 -> entry.writeUUID(dst, in.remaining() >= 16 ? in.readUUID() : new UUID(0L, 0L));
                default -> {
                    in.position(in.position() + in.remaining());
                    unsupported = true;
                }
            }

            if (!unsupported) {
                validRecipes.add(new EncodedRecipe(recipeType, entry.toByteArray()));
            }
        }

        boolean cleanRecipes = in.remaining() > 0 && in.readBoolean();
        LegacyStream out = LegacyStream.writer(Math.max(64, payload.length));
        if (dst.isBefore90()) {
            out.writeIntBE(validRecipes.size());
        } else {
            out.writeUnsignedVarInt(validRecipes.size());
        }
        for (EncodedRecipe er : validRecipes) {
            if (dst.isBefore90()) {
                out.writeIntBE(er.type());
                out.writeIntBE(er.data().length);
            } else {
                out.writeVarInt(er.type());
            }
            out.writeBytes(er.data());
        }
        out.writeBoolean(cleanRecipes);
        return out.toByteArray();
    }

    private static byte[] translateAdventureSettingsOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        int flags = in.readUnsignedVarInt();
        int cmdPerm = in.readUnsignedVarInt();
        // MySoft AdventureSettingsPacket (< 90):
        // WORLD_IMMUTABLE=0x01, NO_PVP=0x02, PRE_V_0_15_0_AUTO_JUMP=0x40, PRE_V_0_15_0_ALLOW_FLIGHT=0x80, PRE_V_0_15_0_NO_CLIP=0x100
        int mappedFlags = 0;
        if ((flags & 0x01) != 0) {
            mappedFlags |= 0x01;
        }
        if ((flags & 0x02) != 0) {
            mappedFlags |= 0x02;
        }
        if ((flags & 0x20) != 0) {
            mappedFlags |= 0x40;
        }
        if ((flags & 0x40) != 0) {
            mappedFlags |= 0x80;
        }
        if ((flags & 0x80) != 0) {
            mappedFlags |= 0x100;
        }
        LegacyStream out = LegacyStream.writer(12);
        out.writeIntBE(mappedFlags);
        out.writeIntBE(Math.min(2, cmdPerm));
        out.writeIntBE(2); // PERMISSION_HOST
        return out.toByteArray();
    }

    private static OutboundResult translateBlockEntityDataOutbound(byte[] payload, LegacyProtocol dst) {
        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.BLOCK_ENTITY_DATA_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        LegacyStream in = LegacyStream.reader(payload);
        int[] bp = in.readBlockPosition(LegacyProtocol.PROTOCOL_113);
        byte[] rawNbt = in.readRemaining();
        byte[] convertedNbt = LegacyNbtConverter.convertSingleBlockEntity(rawNbt, dst);
        if (convertedNbt == null || convertedNbt.length == 0) {
            return OutboundResult.drop(false);
        }

        LegacyStream out = LegacyStream.writer(16 + convertedNbt.length);
        out.writeByte(legacyId & 0xFF);
        out.writeBlockPosition(dst, bp[0], bp[1], bp[2]);
        out.writeBytes(convertedNbt);
        return OutboundResult.forward(out.toByteArray());
    }

    private static byte[] translateSetDifficultyOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(4);
        out.writeIntBE(in.readUnsignedVarInt());
        return out.toByteArray();
    }

    private static byte[] translateChangeDimensionOutbound(byte[] payload, LegacyProtocol dst) {
        LegacyStream in = LegacyStream.reader(payload);
        int dimension = in.readVarInt();
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        boolean respawn = in.remaining() > 0 && in.readBoolean();
        int safeDim = clampDimension(dimension, dst);

        LegacyStream out = LegacyStream.writer(20);
        if (dst.isBefore90()) {
            // MySoft ChangeDimensionPacket (< 90): byte(dimension) + boolean(respawn), SIN Vector3
            out.writeByte(safeDim);
            out.writeBoolean(respawn);
            return out.toByteArray();
        }
        out.writeVarInt(safeDim);
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        out.writeBoolean(respawn);
        return out.toByteArray();
    }

    private static int clampDimension(int dimension, LegacyProtocol dst) {
        if (dst.getProtocol() < 100) {
            return dimension & 0x01; // MySoft getClientFriendlyDimension (< 100): solo Overworld (0) y Nether (1)
        }
        return (dimension < 0 || dimension > 2) ? 0 : dimension;
    }

    private static byte[] translateGameRulesChangedOutbound(byte[] payload, LegacyProtocol dst) {
        if (dst.isBefore90()) {
            return new byte[0];
        }
        LegacyStream in = LegacyStream.reader(payload);
        int count = in.remaining() > 0 ? in.readUnsignedVarInt() : 0;
        return filterEncodedGameRules(in, count, dst);
    }

    private static byte[] filterEncodedGameRules(LegacyStream in, int count, LegacyProtocol dst) {
        LegacyStream entries = LegacyStream.writer(128);
        int kept = 0;
        for (int i = 0; i < count && in.remaining() > 0; i++) {
            String name = in.readString(LegacyProtocol.PROTOCOL_113);
            int type = in.readUnsignedVarInt();
            boolean supported = isGameRuleSupported(name, dst);
            switch (type) {
                case 1 -> {
                    boolean val = in.readBoolean();
                    if (supported) {
                        kept++;
                        entries.writeString(dst, name);
                        entries.writeUnsignedVarInt(1);
                        entries.writeBoolean(val);
                    }
                }
                case 2 -> {
                    int val = in.readUnsignedVarInt();
                    if (supported) {
                        kept++;
                        entries.writeString(dst, name);
                        entries.writeUnsignedVarInt(2);
                        entries.writeUnsignedVarInt(val);
                    }
                }
                case 3 -> {
                    float val = in.readFloatLE();
                    if (supported) {
                        kept++;
                        entries.writeString(dst, name);
                        entries.writeUnsignedVarInt(3);
                        entries.writeFloatLE(val);
                    }
                }
                default -> {
                }
            }
        }
        LegacyStream out = LegacyStream.writer(entries.toByteArray().length + 4);
        out.writeUnsignedVarInt(kept);
        out.writeBytes(entries.toByteArray());
        return out.toByteArray();
    }

    private static boolean isGameRuleSupported(String name, LegacyProtocol dst) {
        if (name == null || name.isEmpty() || dst.isBefore90()) {
            return false;
        }
        String key = name.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "dodaylightcycle", "doentitydrops", "dofiretick", "domobloot", "domobspawning",
                    "dotiledrops", "doweathercycle", "drowningdamage", "falldamage", "firedamage",
                    "keepinventory", "mobgriefing", "naturalregeneration", "pvp",
                    "showdeathmessages", "tntexplodes" -> true;
            case "commandblockoutput", "sendcommandfeedback" -> dst.getProtocol() >= 105;
            case "maxcommandchainlength", "randomtickspeed" -> !dst.isBefore110();
            default -> false;
        };
    }

    private static byte[] translateSetPlayerGameTypeOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(4);
        out.writeIntBE(in.readVarInt());
        return out.toByteArray();
    }

    private static byte[] translatePlayerListOutbound(byte[] payload, LegacyProtocol dst, SessionProtocolRegistry registry) {
        if (!dst.isBefore110()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        int type = in.readByte();
        int count = in.readUnsignedVarInt();

        LegacyStream out = LegacyStream.writer(payload.length + 16);
        out.writeByte(type);
        if (dst.isBefore90()) {
            out.writeIntBE(count);
        } else {
            out.writeUnsignedVarInt(count);
        }
        for (int i = 0; i < count && in.remaining() >= 16; i++) {
            UUID uuid = in.readUUID();
            out.writeUUID(dst, uuid);
            if (type == 0) {
                long eid = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
                String name = in.readString(LegacyProtocol.PROTOCOL_113);
                String skinId = in.readString(LegacyProtocol.PROTOCOL_113);
                byte[] rawSkin = in.readByteArray(LegacyProtocol.PROTOCOL_113);
                if (in.remaining() > 0) {
                    in.readString(LegacyProtocol.PROTOCOL_113);
                }
                if (in.remaining() > 0) {
                    in.readString(LegacyProtocol.PROTOCOL_113);
                }
                if (in.remaining() > 0) {
                    in.readString(LegacyProtocol.PROTOCOL_113);
                }

                // Nukkit-MOT BinaryStream.putSkin convierte a Steve si rawSkin != 8192B en v113;
                // restaurar la skin real cacheada del jugador (64x64 / HD / slim) antes de normalizar.
                SkinSnapshot realSnap = resolveRealPlayerSkin(uuid, name, registry);
                boolean slim = skinId.toLowerCase(Locale.ROOT).contains("slim");
                if (realSnap != null && realSnap.skinData() != null && realSnap.skinData().length > 0) {
                    rawSkin = realSnap.skinData();
                    slim = realSnap.slim();
                }

                String mappedSkinId = slim ? "Standard_CustomSlim" : "Standard_Custom";
                byte[] safeSkin = LegacyContentMapper.normalizeSkinData(rawSkin, dst);

                out.writeEntityUniqueId(dst, eid);
                out.writeString(dst, name);
                out.writeString(dst, mappedSkinId);
                if (dst.isBefore90()) {
                    out.writeShortBE(safeSkin.length);
                    out.writeBytes(safeSkin);
                } else {
                    out.writeByteArray(dst, safeSkin);
                }
            }
        }
        return out.toByteArray();
    }

    private static SkinSnapshot resolveRealPlayerSkin(UUID uuid, String name, SessionProtocolRegistry registry) {
        if (registry != null) {
            SkinSnapshot cached = registry.getSkin(uuid, name);
            if (cached != null) {
                return cached;
            }
        }
        try {
            Server server = Server.getInstance();
            if (server != null) {
                Player target = null;
                if (uuid != null) {
                    target = server.getOnlinePlayers().get(uuid);
                }
                if (target == null && name != null && !name.isBlank()) {
                    target = server.getPlayerExact(name);
                }
                if (target != null && target.getSkin() != null) {
                    Skin skin = target.getSkin();
                    if (skin.getSkinData() != null && skin.getSkinData().data != null && skin.getSkinData().data.length > 0) {
                        boolean slim = skin.getSkinId() != null
                                && skin.getSkinId().toLowerCase(Locale.ROOT).contains("slim");
                        return new SkinSnapshot(skin.getSkinId(), skin.getSkinData().data, slim);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] translateSpawnExperienceOrbOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(16);
        float[] pos = in.readVector3(LegacyProtocol.PROTOCOL_113);
        int amount = in.readVarInt();
        out.writeVector3(dst, pos[0], pos[1], pos[2]);
        out.writeIntBE(amount);
        return out.toByteArray();
    }

    private static OutboundResult translateMapItemDataOutbound(byte[] payload, LegacyProtocol dst) {
        int legacyId = PacketIdMapper.toLegacy(dst, ProtocolInfo_v113.CLIENTBOUND_MAP_ITEM_DATA_PACKET & 0xFF);
        if (legacyId == PacketIdMapper.UNMAPPED) {
            return OutboundResult.drop(false);
        }
        if (!dst.isBefore90()) {
            LegacyStream in = LegacyStream.reader(payload);
            long mapId = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
            LegacyStream out = LegacyStream.writer(payload.length + 4);
            out.writeByte(legacyId & 0xFF);
            out.writeEntityUniqueId(dst, mapId);
            in.copyRemainingTo(out);
            return OutboundResult.forward(out.toByteArray());
        }
        // En 0.15.x ClientboundMapItemData usa ints BE y longBE; traducir cabecera segura
        LegacyStream in = LegacyStream.reader(payload);
        long mapId = in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113);
        int flags = in.readUnsignedVarInt();
        LegacyStream out = LegacyStream.writer(payload.length + 32);
        out.writeByte(legacyId & 0xFF);
        out.writeLongBE(mapId);
        out.writeIntBE(flags);
        if ((flags & 0x08) != 0) { // eids
            int eidCount = in.readUnsignedVarInt();
            out.writeIntBE(eidCount);
            for (int i = 0; i < eidCount && in.remaining() > 0; i++) {
                out.writeLongBE(in.readEntityUniqueId(LegacyProtocol.PROTOCOL_113));
            }
        }
        if ((flags & (0x02 | 0x04)) != 0) {
            out.writeByte(in.readByte()); // scale
        }
        if ((flags & 0x04) != 0) {
            int decCount = in.readUnsignedVarInt();
            out.writeIntBE(decCount);
            for (int i = 0; i < decCount && in.remaining() > 0; i++) {
                int weird = in.readVarInt();
                int x = in.readByte();
                int z = in.readByte();
                String label = in.readString(LegacyProtocol.PROTOCOL_113);
                int argb = in.readUnsignedVarInt();
                out.writeIntBE(weird);
                out.writeByte(x);
                out.writeByte(z);
                out.writeString(dst, label);
                out.writeIntLE(argb);
            }
        }
        if ((flags & 0x02) != 0 && in.remaining() >= 4) {
            int w = in.readVarInt();
            int h = in.readVarInt();
            int xOff = in.readVarInt();
            int yOff = in.readVarInt();
            out.writeIntBE(w);
            out.writeIntBE(h);
            out.writeIntBE(xOff);
            out.writeIntBE(yOff);
            int total = Math.max(0, w) * Math.max(0, h);
            for (int i = 0; i < total && in.remaining() > 0; i++) {
                out.writeIntLE(in.readUnsignedVarInt());
            }
        }
        return OutboundResult.forward(out.toByteArray());
    }

    private static byte[] translateChunkRadiusUpdatedOutbound(byte[] payload, LegacyProtocol dst) {
        if (!dst.isBefore90()) {
            return payload;
        }
        LegacyStream in = LegacyStream.reader(payload);
        LegacyStream out = LegacyStream.writer(4);
        out.writeIntBE(in.readVarInt());
        return out.toByteArray();
    }

    private static String extractDisplayName(byte[] connectionPayload) {
        try {
            if (connectionPayload == null || connectionPayload.length == 0) {
                return "Jugador";
            }
            String candidateJson;
            if (connectionPayload[0] == '{') {
                candidateJson = new String(connectionPayload, StandardCharsets.UTF_8);
            } else {
                LegacyStream stream = LegacyStream.reader(connectionPayload);
                int chainJsonLen = stream.readIntLE();
                if (chainJsonLen > 0 && chainJsonLen <= stream.remaining()) {
                    candidateJson = new String(stream.readBytes(chainJsonLen), StandardCharsets.UTF_8);
                } else {
                    candidateJson = new String(connectionPayload, StandardCharsets.UTF_8);
                }
            }

            String direct = extractDisplayNameFromJson(candidateJson);
            if (direct != null) {
                return direct;
            }

            int idx = 0;
            while (idx < candidateJson.length()) {
                int q1 = candidateJson.indexOf('"', idx);
                if (q1 < 0) {
                    break;
                }
                int q2 = candidateJson.indexOf('"', q1 + 1);
                if (q2 < 0) {
                    break;
                }
                String token = candidateJson.substring(q1 + 1, q2);
                int dot1 = token.indexOf('.');
                if (dot1 > 0) {
                    int dot2 = token.indexOf('.', dot1 + 1);
                    if (dot2 > dot1 + 1) {
                        String payloadPart = token.substring(dot1 + 1, dot2);
                        String decoded = decodeJwtSegment(payloadPart);
                        if (decoded != null) {
                            String found = extractDisplayNameFromJson(decoded);
                            if (found != null) {
                                return found;
                            }
                        }
                    }
                }
                idx = q2 + 1;
            }
        } catch (Exception ignored) {
        }
        return "Jugador";
    }

    private static String decodeJwtSegment(String b64) {
        try {
            byte[] raw = java.util.Base64.getUrlDecoder().decode(b64);
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            try {
                byte[] raw = java.util.Base64.getDecoder().decode(b64);
                return new String(raw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private static LoginSniffResult tryNormalizePre80Login(byte[] rawPacket, int minAllowed, int maxAllowed) {
        LegacyStream in = LegacyStream.reader(rawPacket, 1, rawPacket.length - 1);
        String username = in.readString(LegacyProtocol.PROTOCOL_41);
        if (in.remaining() < 4) {
            return null;
        }
        int proto = in.readIntBE();
        if (in.remaining() >= 4) {
            int proto2 = in.readIntBE();
            if (proto == 0 && proto2 != 0) {
                proto = proto2;
            }
        }
        if (proto >= 80 || proto < minAllowed || proto > maxAllowed) {
            return null;
        }
        LegacyProtocol source = LegacyProtocol.resolve(proto);
        long clientId = in.remaining() >= 8 ? in.readLongBE() : 0L;
        UUID clientUuid = in.remaining() >= 16
                ? in.readUUID(LegacyProtocol.PROTOCOL_41)
                : UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        String serverAddress = in.remaining() >= 2 ? in.readString(LegacyProtocol.PROTOCOL_41) : "127.0.0.1:19132";
        if (in.remaining() >= 2) {
            in.readString(LegacyProtocol.PROTOCOL_41); // clientSecret (sin uso en v113)
        }
        String skinName = in.remaining() >= 2 ? in.readString(LegacyProtocol.PROTOCOL_41) : "Standard_Custom";
        byte[] rawSkin = readPre80SkinBytes(in);
        byte[] safeSkin = LegacyContentMapper.normalizeSkinData(rawSkin, LegacyProtocol.PROTOCOL_113);
        String safeUsername = (username == null || username.isBlank()) ? "Jugador" : username;
        String safeSkinId = (skinName == null || skinName.isBlank()) ? "Standard_Custom" : skinName;

        StringBuilder chainPayload = new StringBuilder(192);
        chainPayload.append("{\"extraData\":{\"displayName\":");
        appendJsonStringLiteral(chainPayload, safeUsername);
        chainPayload.append(",\"identity\":");
        appendJsonStringLiteral(chainPayload, clientUuid.toString());
        // "identityPublicKey" en el nodo raiz es obligatorio para Nukkit-MOT:
        // ChainValidationResult.createLegacyClaims (ChainValidationResult.java:70) lo lee con
        // childAsType y lanza IllegalStateException("identityPublicKey node is missing") si falta;
        // ClientChainData lo envuelve en IllegalArgumentException("Invalid JWT") (ClientChainData.java:428-430),
        // LoginChainVerifier lo reporta como failure y Player expulsa con disconnectionScreen.invalidName
        // (Player.java:10008-10012) aunque LoginPacket.decodeChainData (leniente) ya extrajo el displayName
        // que se muestra al desconectar (Player.java:7766). Vacio es seguro: solo se parsea como clave EC en
        // el cifrado de red (protocolo >= v1_7_0 = 291, Player.isEnableNetworkEncryption) o en el binding
        // NetherNet, rutas que nunca corren en sesiones RakNet canonizadas a P113.
        chainPayload.append(",\"XUID\":\"\"},\"identityPublicKey\":\"\"}");
        String chainJwt = buildUnpaddedBase64Jwt(chainPayload.toString());
        byte[] chainWrapper = ("{\"chain\":[\"" + chainJwt + "\"]}").getBytes(StandardCharsets.UTF_8);

        String skinB64 = java.util.Base64.getEncoder().encodeToString(safeSkin);
        StringBuilder skinPayload = new StringBuilder(skinB64.length() + 192);
        skinPayload.append("{\"ClientRandomId\":").append(clientId)
                .append(",\"ServerAddress\":");
        appendJsonStringLiteral(skinPayload, serverAddress == null ? "127.0.0.1:19132" : serverAddress);
        skinPayload.append(",\"GameVersion\":");
        appendJsonStringLiteral(skinPayload, source.getMcVersion());
        skinPayload.append(",\"SkinId\":");
        appendJsonStringLiteral(skinPayload, safeSkinId);
        skinPayload.append(",\"SkinData\":\"").append(skinB64).append("\"}");
        byte[] skinJwt = buildUnpaddedBase64Jwt(skinPayload.toString()).getBytes(StandardCharsets.UTF_8);

        LegacyStream conn = LegacyStream.writer(chainWrapper.length + skinJwt.length + 12);
        conn.writeIntLE(chainWrapper.length);
        conn.writeBytes(chainWrapper);
        conn.writeIntLE(skinJwt.length);
        conn.writeBytes(skinJwt);
        byte[] connectionPayload = conn.toByteArray();

        LegacyStream out = LegacyStream.writer(connectionPayload.length + 16);
        out.writeByte(ProtocolInfo_v113.LOGIN_PACKET & 0xFF);
        out.writeIntBE(LegacyProtocol.CANONICAL_NUKKIT_PROTOCOL);
        out.writeByte(0); // gameEdition
        out.writeByteArray(LegacyProtocol.PROTOCOL_113, connectionPayload);
        return new LoginSniffResult(proto, safeUsername, out.toByteArray());
    }

    private static byte[] readPre80SkinBytes(LegacyStream in) {
        int rem = in.remaining();
        if (rem <= 0) {
            return new byte[0];
        }
        if (rem == 8192 || rem == 16384) {
            return in.readBytes(rem);
        }
        if (rem >= 2) {
            int len = in.readShortBE() & 0xFFFF;
            if (len <= in.remaining()) {
                return in.readBytes(len);
            }
            return in.readRemaining();
        }
        return in.readRemaining();
    }

    private static boolean canReadPre80SlotAfterInt(LegacyStream in) {
        int rem = in.remaining();
        if (rem < 6) {
            return false;
        }
        int pos = in.position();
        try {
            int candidateSlot = in.readIntBE();
            if (candidateSlot < -1 || candidateSlot > 44) {
                return false;
            }
            int idAfter = (short) in.readShortBE();
            if (idAfter <= 0) {
                return rem == 6;
            }
            return rem >= 11 && idAfter <= 512;
        } finally {
            in.position(pos);
        }
    }

    private static String buildUnpaddedBase64Jwt(String jsonPayload) {
        String header = "{\"alg\":\"none\"}"; // 14 bytes (14 % 3 == 2 -> añadir 1 espacio = 15B)
        byte[] paddedHeader = (header + " ").getBytes(StandardCharsets.UTF_8);
        StringBuilder paddedJson = new StringBuilder(jsonPayload);
        int mod = paddedJson.toString().getBytes(StandardCharsets.UTF_8).length % 3;
        if (mod != 0) {
            for (int i = 0; i < 3 - mod; i++) {
                paddedJson.append(' ');
            }
        }
        java.util.Base64.Encoder enc = java.util.Base64.getEncoder().withoutPadding();
        return enc.encodeToString(paddedHeader) + "."
                + enc.encodeToString(paddedJson.toString().getBytes(StandardCharsets.UTF_8)) + ".AA";
    }

    private static void appendJsonStringLiteral(StringBuilder sb, String value) {
        sb.append('"');
        if (value != null) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        // Escapar <, >, ?, ~ y >= 0x80 para evitar '+' '/' '=' al codificar Base64/Base64URL
                        if (c < 0x20 || c >= 0x7F || c == '<' || c == '>' || c == '?' || c == '~') {
                            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String extractDisplayNameFromJson(String json) {
        int marker = json.indexOf("\"displayName\"");
        if (marker < 0) {
            return null;
        }
        int colon = json.indexOf(':', marker);
        if (colon < 0) {
            return null;
        }
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 >= 0 && q2 > q1) {
            return json.substring(q1 + 1, q2);
        }
        return null;
    }
}
