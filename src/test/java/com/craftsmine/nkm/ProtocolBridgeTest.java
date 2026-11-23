package com.craftsmine.nkm;

import cn.nukkit.GameVersion;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.item.Item;
import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.nbt.tag.ListTag;
import cn.nukkit.nbt.tag.LongTag;
import cn.nukkit.nbt.tag.ShortTag;
import cn.nukkit.network.Network;
import cn.nukkit.network.PacketPool;
import cn.nukkit.network.protocol.AdventureSettingsPacket;
import cn.nukkit.network.protocol.MobEffectPacket;
import cn.nukkit.network.protocol.MovePlayerPacket;
import cn.nukkit.network.protocol.PlayerActionPacket;
import cn.nukkit.network.protocol.PlayerListPacket;
import cn.nukkit.network.protocol.RequestChunkRadiusPacket;
import cn.nukkit.network.protocol.TextPacket;
import cn.nukkit.network.protocol.v113.CommandStepPacket_v113;
import cn.nukkit.network.protocol.v113.ProtocolInfo_v113;
import cn.nukkit.network.protocol.v113.RemoveBlockPacket_v113;
import cn.nukkit.network.protocol.v113.UseItemPacket_v113;
import cn.nukkit.utils.BinaryStream;
import com.craftsmine.nkm.network.SessionProtocolRegistry;
import com.craftsmine.nkm.protocol.BatchCodec;
import com.craftsmine.nkm.protocol.ChunkTranslator;
import com.craftsmine.nkm.protocol.LegacyContentMapper;
import com.craftsmine.nkm.protocol.LegacyNbtConverter;
import com.craftsmine.nkm.protocol.LegacyProtocol;
import com.craftsmine.nkm.protocol.LegacyStream;
import com.craftsmine.nkm.protocol.PacketIdMapper;
import com.craftsmine.nkm.protocol.PacketTranslator;
import io.netty.channel.ChannelId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolBridgeTest {

    @Test
    void resolvesProtocols80Through113Correctly() {
        LegacyProtocol p80 = LegacyProtocol.resolve(80);
        assertEquals(80, p80.getProtocol());
        assertEquals(7, p80.getRakNetVersion());
        assertTrue(p80.isBefore90());
        assertTrue(p80.needsTranslation());

        LegacyProtocol p84 = LegacyProtocol.resolve(84);
        assertEquals(84, p84.getProtocol());
        assertEquals(7, p84.getRakNetVersion());
        assertTrue(p84.isBefore90());

        LegacyProtocol p91 = LegacyProtocol.resolve(91);
        assertEquals(91, p91.getProtocol());
        assertEquals(8, p91.getRakNetVersion());
        assertFalse(p91.isBefore90());
        assertTrue(p91.isBefore92());

        LegacyProtocol p105 = LegacyProtocol.resolve(105);
        assertEquals(105, p105.getProtocol());
        assertFalse(p105.isBefore92());
        assertTrue(p105.isBefore110());

        LegacyProtocol p113 = LegacyProtocol.resolve(113);
        assertFalse(p113.needsTranslation());
    }

    @Test
    void mapsPacketIdsBidirectionallyAcrossFamilies() {
        // 0.15.x (80-84): StartGame is 0x09 -> v113 0x0b
        assertEquals(0x0b, PacketIdMapper.toV113(LegacyProtocol.PROTOCOL_80, 0x09));
        assertEquals(0x09, PacketIdMapper.toLegacy(LegacyProtocol.PROTOCOL_84, 0x0b));

        // 0.15.x: ChunkRadiusUpdated is 0x3e -> v113 0x46
        assertEquals(0x46, PacketIdMapper.toV113(LegacyProtocol.PROTOCOL_81, 0x3e));
        assertEquals(0x3e, PacketIdMapper.toLegacy(LegacyProtocol.PROTOCOL_81, 0x46));

        // 1.1.0.0 (110): TakeItemEntity is 0x11 -> v113 0x11
        assertEquals(0x11, PacketIdMapper.toV113(LegacyProtocol.PROTOCOL_110, 0x11));

        // 1.0.4 (102): ResourcePacksInfo is 0x07 -> v113 0x06
        assertEquals(0x06, PacketIdMapper.toV113(LegacyProtocol.PROTOCOL_102, 0x07));
    }

    @Test
    void normalizesLegacy01510LoginPacketInsideLiteral06BatchAndDecodesWithNukkitMot() throws DataFormatException {
        UUID playerUuid = UUID.randomUUID();
        String chainPayloadJson = "{\"extraData\":{\"displayName\":\"Craftsmine015\",\"identity\":\""
                + playerUuid + "\",\"XUID\":\"\"}}";
        String clientPayloadJson = "{\"ClientRandomId\":123456789,\"SkinId\":\"Standard_Custom\",\"SkinData\":\""
                + Base64.getEncoder().encodeToString(new byte[Skin.SINGLE_SKIN_SIZE]) + "\"}";

        String headerB64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String chainJwt = headerB64 + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(chainPayloadJson.getBytes(StandardCharsets.UTF_8))
                + ".sig";
        String clientJwt = headerB64 + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(clientPayloadJson.getBytes(StandardCharsets.UTF_8))
                + ".sig";

        byte[] chainJsonBytes = ("{\"chain\":[\"" + chainJwt + "\"]}").getBytes(StandardCharsets.UTF_8);
        byte[] clientJwtBytes = clientJwt.getBytes(StandardCharsets.UTF_8);

        LegacyStream payloadStream = LegacyStream.writer(chainJsonBytes.length + clientJwtBytes.length + 16);
        payloadStream.writeIntLE(chainJsonBytes.length);
        payloadStream.writeBytes(chainJsonBytes);
        payloadStream.writeIntLE(clientJwtBytes.length);
        payloadStream.writeBytes(clientJwtBytes);
        byte[] rawConnectionPayload = payloadStream.toByteArray();
        byte[] compressedPayload = BatchCodec.deflateZlib(rawConnectionPayload, 6);

        LegacyStream legacyLogin = LegacyStream.writer(compressedPayload.length + 16);
        legacyLogin.writeByte(0x01);
        legacyLogin.writeIntBE(84); // 0.15.10 protocol 84
        legacyLogin.writeByteArray(LegacyProtocol.PROTOCOL_84, compressedPayload);
        byte[] legacyLoginBytes = legacyLogin.toByteArray();

        // 0.15.10 puede enviar el LoginPacket dentro de un BatchPacket literal (0x06 + int32(compLen) + zlib)
        byte[] literalBatchBody = BatchCodec.encodeRakNetBatchBody(
                LegacyProtocol.PROTOCOL_84, List.of(legacyLoginBytes), 6
        );
        List<byte[]> decodedPreLogin = BatchCodec.decodeRakNetBatchBody(literalBatchBody, 7, null, 2 * 1024 * 1024);
        assertNotNull(decodedPreLogin);
        assertEquals(1, decodedPreLogin.size());
        assertArrayEquals(legacyLoginBytes, decodedPreLogin.get(0));

        PacketTranslator.LoginSniffResult sniff = PacketTranslator.tryNormalizeLogin(decodedPreLogin.get(0), 80, 113);
        assertNotNull(sniff);
        assertEquals(84, sniff.rawProtocol());
        assertEquals("Craftsmine015", sniff.username());

        // Verificar con BinaryStream real de Nukkit-MOT la estructura canonica v113 (sin requerir Server booted)
        BinaryStream nukkitStream = new BinaryStream(sniff.canonicalV113LoginPacket(), 1);
        assertEquals(113, nukkitStream.getInt());
        assertEquals(0, nukkitStream.getByte()); // gameEdition v1.1
        byte[] decodedConnectionPayload = nukkitStream.getByteArray();
        assertArrayEquals(rawConnectionPayload, decodedConnectionPayload);

        BinaryStream connStream = new BinaryStream(decodedConnectionPayload, 0);
        byte[] outChain = connStream.get(connStream.getLInt());
        byte[] outClientJwt = connStream.get(connStream.getLInt());
        assertArrayEquals(chainJsonBytes, outChain);
        assertArrayEquals(clientJwtBytes, outClientJwt);
    }

    @Test
    void bypassesModern113LoginPacket() throws DataFormatException {
        LegacyStream modernLogin = LegacyStream.writer(32);
        modernLogin.writeByte(0x01);
        modernLogin.writeIntBE(113);
        modernLogin.writeByte(0);
        modernLogin.writeByteArray(LegacyProtocol.PROTOCOL_113, "modern".getBytes(StandardCharsets.UTF_8));

        assertNull(PacketTranslator.tryNormalizeLogin(modernLogin.toByteArray(), 80, 113));
    }

    @Test
    void bypassesResourcePacksFor015ClientAndRequestsSyntheticComplete() {
        byte[] fakeResourcePacksInfo = new byte[]{ProtocolInfo_v113.RESOURCE_PACKS_INFO_PACKET, 0, 0, 0, 0, 0};
        PacketTranslator.OutboundResult res = PacketTranslator.translateOutbound(
                fakeResourcePacksInfo, LegacyProtocol.PROTOCOL_84, true
        );
        assertTrue(res.suppress());
        assertTrue(res.synthesizeResourceComplete());

        byte[] synthetic = PacketTranslator.buildSyntheticResourcePackCompleteV113();
        assertEquals(ProtocolInfo_v113.RESOURCE_PACK_CLIENT_RESPONSE_PACKET & 0xFF, synthetic[0] & 0xFF);
        assertEquals(4, synthetic[1]);
    }

    @Test
    void translates01510InboundPacketsAndDecodesWithRealNukkitMotCodecs() {
        GameVersion v110 = GameVersion.byProtocol(113, false);

        // 1) UseItemPacket (0.15.10 pid 0x1f -> v113 0x23)
        LegacyStream useItem015 = LegacyStream.writer(64);
        useItem015.writeByte(0x1f);
        useItem015.writeBlockPosition(LegacyProtocol.PROTOCOL_84, 12, 64, -8);
        useItem015.writeByte(1); // face
        useItem015.writeVector3(LegacyProtocol.PROTOCOL_84, 0.5f, 1.0f, 0.25f);
        useItem015.writeVector3(LegacyProtocol.PROTOCOL_84, 12.5f, 65.62f, -7.5f);
        useItem015.writeIntBE(3); // slot
        useItem015.writeSlot(LegacyProtocol.PROTOCOL_84, new LegacyStream.LegacySlot(1, 0, 16, new byte[0], new String[0], new String[0]));

        PacketTranslator.InboundResult useItemCanon = PacketTranslator.translateInbound(useItem015.toByteArray(), LegacyProtocol.PROTOCOL_84);
        assertFalse(useItemCanon.suppress());
        assertNotNull(useItemCanon.canonicalPacket());
        UseItemPacket_v113 nukkitUseItem = new UseItemPacket_v113();
        nukkitUseItem.protocol = 113;
        nukkitUseItem.gameVersion = v110;
        nukkitUseItem.setBuffer(useItemCanon.canonicalPacket(), 1);
        nukkitUseItem.decode();
        assertEquals(12, nukkitUseItem.x);
        assertEquals(64, nukkitUseItem.y);
        assertEquals(-8, nukkitUseItem.z);
        assertEquals(1, nukkitUseItem.face);
        assertEquals(3, nukkitUseItem.slot);
        assertEquals(1, nukkitUseItem.item.getId());
        assertEquals(16, nukkitUseItem.item.getCount());

        // 2) MovePlayerPacket MODE_TELEPORT (0.15.10 pid 0x10 -> v113 0x13)
        LegacyStream move015 = LegacyStream.writer(64);
        move015.writeByte(0x10);
        move015.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_84, 42L);
        move015.writeVector3(LegacyProtocol.PROTOCOL_84, 100.5f, 70.0f, -50.25f);
        move015.writeFloatBE(90.0f);  // yaw
        move015.writeFloatBE(90.0f);  // headYaw
        move015.writeFloatBE(15.0f);  // pitch
        move015.writeByte(MovePlayerPacket.MODE_TELEPORT);
        move015.writeBoolean(true);

        PacketTranslator.InboundResult moveCanon = PacketTranslator.translateInbound(move015.toByteArray(), LegacyProtocol.PROTOCOL_84);
        assertFalse(moveCanon.suppress());
        MovePlayerPacket nukkitMove = new MovePlayerPacket();
        nukkitMove.protocol = 113;
        nukkitMove.gameVersion = v110;
        nukkitMove.setBuffer(moveCanon.canonicalPacket(), 1);
        nukkitMove.decode();
        assertEquals(42L, nukkitMove.eid);
        assertEquals(MovePlayerPacket.MODE_TELEPORT, nukkitMove.mode);
        assertTrue(nukkitMove.onGround);

        // 3) RemoveBlockPacket (0.15.10 pid 0x12 -> v113 0x15)
        LegacyStream rm015 = LegacyStream.writer(32);
        rm015.writeByte(0x12);
        rm015.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_84, 42L);
        rm015.writeIntBE(5);
        rm015.writeIntBE(-9);
        rm015.writeByte(63);
        PacketTranslator.InboundResult rmCanon = PacketTranslator.translateInbound(rm015.toByteArray(), LegacyProtocol.PROTOCOL_84);
        RemoveBlockPacket_v113 nukkitRm = new RemoveBlockPacket_v113();
        nukkitRm.protocol = 113;
        nukkitRm.gameVersion = v110;
        nukkitRm.setBuffer(rmCanon.canonicalPacket(), 1);
        nukkitRm.decode();
        assertEquals(5, nukkitRm.x);
        assertEquals(63, nukkitRm.y);
        assertEquals(-9, nukkitRm.z);

        // 4) RequestChunkRadiusPacket (0.15.10 pid 0x3d -> v113 0x45)
        LegacyStream radius015 = LegacyStream.writer(8);
        radius015.writeByte(0x3d);
        radius015.writeIntBE(8);
        PacketTranslator.InboundResult radiusCanon = PacketTranslator.translateInbound(radius015.toByteArray(), LegacyProtocol.PROTOCOL_84);
        RequestChunkRadiusPacket nukkitRadius = new RequestChunkRadiusPacket();
        nukkitRadius.protocol = 113;
        nukkitRadius.gameVersion = v110;
        nukkitRadius.setBuffer(radiusCanon.canonicalPacket(), 1);
        nukkitRadius.decode();
        assertEquals(8, nukkitRadius.radius);

        // 5) AdventureSettingsPacket (0.15.10 pid 0x31 -> v113 0x37)
        LegacyStream adv015 = LegacyStream.writer(16);
        adv015.writeByte(0x31);
        adv015.writeIntBE(0x20);
        adv015.writeIntBE(1);
        adv015.writeIntBE(1);
        PacketTranslator.InboundResult advCanon = PacketTranslator.translateInbound(adv015.toByteArray(), LegacyProtocol.PROTOCOL_84);
        AdventureSettingsPacket nukkitAdv = new AdventureSettingsPacket();
        nukkitAdv.protocol = 113;
        nukkitAdv.gameVersion = v110;
        nukkitAdv.setBuffer(advCanon.canonicalPacket(), 1);
        nukkitAdv.decode();
        assertEquals(0x20L, nukkitAdv.flags);
        assertEquals(1L, nukkitAdv.commandPermission);
    }

    @Test
    void degradesOutboundTextAnnouncementAndWhisperToSystemForLegacyProtocols() {
        // 1) TYPE_ANNOUNCEMENT hacia 90..112 (< 113): degrada a TYPE_SYSTEM (alambre 5) y descarta el source
        LegacyStream annV113 = LegacyStream.writer(64);
        annV113.writeByte(ProtocolInfo_v113.TEXT_PACKET & 0xFF);
        annV113.writeByte(TextPacket.TYPE_ANNOUNCEMENT);
        annV113.writeString(LegacyProtocol.PROTOCOL_113, "Servidor");
        annV113.writeString(LegacyProtocol.PROTOCOL_113, "Mantenimiento a las 22:00");
        PacketTranslator.OutboundResult ann100 = PacketTranslator.translateOutbound(annV113.toByteArray(), LegacyProtocol.PROTOCOL_100, true);
        assertFalse(ann100.suppress());
        LegacyStream ann100r = LegacyStream.reader(ann100.legacyPacket());
        assertEquals(0x0A, ann100r.readByte()); // TEXT para la familia V1_0_0 (PacketIdMapper initV100Table)
        assertEquals(5, ann100r.readByte()); // TYPE_SYSTEM = 5 en el alambre 80..112 (tabla PROTOCOL_81 de MySoft)
        assertEquals("Mantenimiento a las 22:00", ann100r.readString(LegacyProtocol.PROTOCOL_100));
        assertEquals(0, ann100r.remaining());

        // 2) TYPE_WHISPER hacia 90..112: sin degradacion (la regla de susurros solo aplica < 90), passthrough intacto
        LegacyStream whisV113 = LegacyStream.writer(64);
        whisV113.writeByte(ProtocolInfo_v113.TEXT_PACKET & 0xFF);
        whisV113.writeByte(TextPacket.TYPE_WHISPER);
        whisV113.writeString(LegacyProtocol.PROTOCOL_113, "Ana");
        whisV113.writeString(LegacyProtocol.PROTOCOL_113, "hola en privado");
        PacketTranslator.OutboundResult whis100 = PacketTranslator.translateOutbound(whisV113.toByteArray(), LegacyProtocol.PROTOCOL_100, true);
        assertFalse(whis100.suppress());
        LegacyStream whis100r = LegacyStream.reader(whis100.legacyPacket());
        assertEquals(0x0A, whis100r.readByte());
        assertEquals(TextPacket.TYPE_WHISPER, whis100r.readByte());
        assertEquals("Ana", whis100r.readString(LegacyProtocol.PROTOCOL_100));
        assertEquals("hola en privado", whis100r.readString(LegacyProtocol.PROTOCOL_100));
        assertEquals(0, whis100r.remaining());

        // 3) TYPE_ANNOUNCEMENT hacia 0.15.x (< 90): degrada a TYPE_SYSTEM con string corto y sin source
        PacketTranslator.OutboundResult ann84 = PacketTranslator.translateOutbound(annV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        assertFalse(ann84.suppress());
        LegacyStream ann84r = LegacyStream.reader(ann84.legacyPacket());
        assertEquals(0x07, ann84r.readByte()); // TEXT para la familia V0_15
        assertEquals(5, ann84r.readByte());
        assertEquals("Mantenimiento a las 22:00", ann84r.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals(0, ann84r.remaining());

        // 4) TYPE_WHISPER hacia 0.15.x (< 90): degrada a TYPE_SYSTEM y descarta el source
        PacketTranslator.OutboundResult whis84 = PacketTranslator.translateOutbound(whisV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        assertFalse(whis84.suppress());
        LegacyStream whis84r = LegacyStream.reader(whis84.legacyPacket());
        assertEquals(0x07, whis84r.readByte());
        assertEquals(5, whis84r.readByte());
        assertEquals("hola en privado", whis84r.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals(0, whis84r.remaining());

        // 5) TYPE_CHAT hacia 0.15.x: sin degradacion, conserva source + message
        LegacyStream chatV113 = LegacyStream.writer(64);
        chatV113.writeByte(ProtocolInfo_v113.TEXT_PACKET & 0xFF);
        chatV113.writeByte(TextPacket.TYPE_CHAT);
        chatV113.writeString(LegacyProtocol.PROTOCOL_113, "Luis");
        chatV113.writeString(LegacyProtocol.PROTOCOL_113, "buenas");
        PacketTranslator.OutboundResult chat84 = PacketTranslator.translateOutbound(chatV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        assertFalse(chat84.suppress());
        LegacyStream chat84r = LegacyStream.reader(chat84.legacyPacket());
        assertEquals(0x07, chat84r.readByte());
        assertEquals(TextPacket.TYPE_CHAT, chat84r.readByte());
        assertEquals("Luis", chat84r.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals("buenas", chat84r.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals(0, chat84r.remaining());
    }

    @Test
    void translatesOutboundTextPopupAttributesPlayerListAndCraftingDataFor015() {
        // 1) TextPacket TYPE_POPUP (formato v113: type + message + UnsignedVarInt(paramCount) + params[])
        LegacyStream textV113 = LegacyStream.writer(64);
        textV113.writeByte(ProtocolInfo_v113.TEXT_PACKET & 0xFF);
        textV113.writeByte(TextPacket.TYPE_POPUP);
        textV113.writeString(LegacyProtocol.PROTOCOL_113, "Bienvenido");
        textV113.writeUnsignedVarInt(1);
        textV113.writeString(LegacyProtocol.PROTOCOL_113, "Titulo");
        PacketTranslator.OutboundResult textOut = PacketTranslator.translateOutbound(textV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        assertFalse(textOut.suppress());
        LegacyStream tr = LegacyStream.reader(textOut.legacyPacket());
        assertEquals(0x07, tr.readByte());
        assertEquals(TextPacket.TYPE_POPUP, tr.readByte());
        assertEquals("Titulo", tr.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals("Bienvenido", tr.readString(LegacyProtocol.PROTOCOL_84));

        // 2) UpdateAttributesPacket: EntityUniqueId (VarLong zigzag) + mapeo minecraft:health -> generic.health + filtro
        LegacyStream attrV113 = LegacyStream.writer(96);
        attrV113.writeByte(ProtocolInfo_v113.UPDATE_ATTRIBUTES_PACKET & 0xFF);
        attrV113.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 1L);
        attrV113.writeUnsignedVarInt(2);
        attrV113.writeFloatLE(0f);
        attrV113.writeFloatLE(20f);
        attrV113.writeFloatLE(14f);
        attrV113.writeFloatLE(20f);
        attrV113.writeString(LegacyProtocol.PROTOCOL_113, "minecraft:health");
        attrV113.writeFloatLE(0f);
        attrV113.writeFloatLE(1024f);
        attrV113.writeFloatLE(0f);
        attrV113.writeFloatLE(0f);
        attrV113.writeString(LegacyProtocol.PROTOCOL_113, "minecraft:luck");
        PacketTranslator.OutboundResult attrOut = PacketTranslator.translateOutbound(attrV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        LegacyStream ar = LegacyStream.reader(attrOut.legacyPacket());
        assertEquals(0x1a, ar.readByte());
        assertEquals(1L, ar.readEntityRuntimeId(LegacyProtocol.PROTOCOL_84));
        assertEquals(1, ar.readShortBE());
        assertEquals(0f, ar.readFloatBE());
        assertEquals(20f, ar.readFloatBE());
        assertEquals(14f, ar.readFloatBE());
        assertEquals("generic.health", ar.readString(LegacyProtocol.PROTOCOL_84));

        // 3) PlayerListPacket (2 entradas con geometryName + geometryData + xboxUserId de Nukkit-MOT V1_1_0)
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        byte[] skinBytes = new byte[Skin.SINGLE_SKIN_SIZE];
        LegacyStream plV113 = LegacyStream.writer(16384);
        plV113.writeByte(ProtocolInfo_v113.PLAYER_LIST_PACKET & 0xFF);
        plV113.writeByte(PlayerListPacket.TYPE_ADD);
        plV113.writeUnsignedVarInt(2);
        plV113.writeUUID(u1);
        plV113.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 10L);
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "JugadorUno");
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "Standard_Custom");
        plV113.writeByteArray(LegacyProtocol.PROTOCOL_113, skinBytes);
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "geometry.humanoid.custom");
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "{}");
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "111");
        plV113.writeUUID(u2);
        plV113.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 11L);
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "JugadorDos");
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "Standard_CustomSlim");
        plV113.writeByteArray(LegacyProtocol.PROTOCOL_113, skinBytes);
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "geometry.humanoid.customSlim");
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "{}");
        plV113.writeString(LegacyProtocol.PROTOCOL_113, "222");

        PacketTranslator.OutboundResult plOut = PacketTranslator.translateOutbound(plV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        LegacyStream plr = LegacyStream.reader(plOut.legacyPacket());
        assertEquals(0x38, plr.readByte());
        assertEquals(PlayerListPacket.TYPE_ADD, plr.readByte());
        assertEquals(2, plr.readIntBE());
        assertEquals(u1, plr.readUUID());
        assertEquals(10L, plr.readEntityUniqueId(LegacyProtocol.PROTOCOL_84));
        assertEquals("JugadorUno", plr.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals("Standard_Custom", plr.readString(LegacyProtocol.PROTOCOL_84));
        int s1Len = plr.readShortBE();
        assertEquals(Skin.SINGLE_SKIN_SIZE, s1Len);
        plr.readBytes(s1Len);
        assertEquals(u2, plr.readUUID());
        assertEquals(11L, plr.readEntityUniqueId(LegacyProtocol.PROTOCOL_84));
        assertEquals("JugadorDos", plr.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals("Standard_CustomSlim", plr.readString(LegacyProtocol.PROTOCOL_84));

        // 4) CraftingDataPacket (v113 -> 0.15.10)
        LegacyStream craftV113 = LegacyStream.writer(128);
        craftV113.writeByte(ProtocolInfo_v113.CRAFTING_DATA_PACKET & 0xFF);
        craftV113.writeUnsignedVarInt(1); // 1 recipe
        craftV113.writeVarInt(2); // ENTRY_FURNACE
        craftV113.writeVarInt(15); // iron ore
        craftV113.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(265, 0, 1, new byte[0], new String[0], new String[0]));
        craftV113.writeBoolean(true);
        PacketTranslator.OutboundResult craftOut = PacketTranslator.translateOutbound(craftV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        LegacyStream cr = LegacyStream.reader(craftOut.legacyPacket());
        assertEquals(0x2f, cr.readByte());
        assertEquals(1, cr.readIntBE());
        assertEquals(2, cr.readIntBE());
        int entryLen = cr.readIntBE();
        assertTrue(entryLen > 0);
        assertEquals(15, cr.readIntBE());
        LegacyStream.LegacySlot resSlot = cr.readSlot(LegacyProtocol.PROTOCOL_84);
        assertEquals(265, resSlot.id());
        assertEquals(1, resSlot.count());
        assertTrue(cr.readBoolean());

        // 5) ContainerOpenPacket (v113 -> 0.15.10 y 1.0.5)
        LegacyStream openV113 = LegacyStream.writer(32);
        openV113.writeByte(ProtocolInfo_v113.CONTAINER_OPEN_PACKET & 0xFF);
        openV113.writeByte(2); // windowId
        openV113.writeByte(0); // CONTAINER
        openV113.writeBlockPosition(LegacyProtocol.PROTOCOL_113, 10, 64, 20);
        openV113.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, -1L);
        PacketTranslator.OutboundResult open015 = PacketTranslator.translateOutbound(openV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        LegacyStream or015 = LegacyStream.reader(open015.legacyPacket());
        assertEquals(0x2a, or015.readByte());
        assertEquals(2, or015.readByte());
        assertEquals(0, or015.readByte());
        assertEquals(27, or015.readShortBE());
        assertEquals(10, or015.readIntBE());
        assertEquals(64, or015.readIntBE());
        assertEquals(20, or015.readIntBE());

        PacketTranslator.OutboundResult open105 = PacketTranslator.translateOutbound(openV113.toByteArray(), LegacyProtocol.PROTOCOL_105, false);
        LegacyStream or105 = LegacyStream.reader(open105.legacyPacket());
        assertEquals(0x31, or105.readByte());
        assertEquals(2, or105.readByte());
        assertEquals(0, or105.readByte());
        assertEquals(27, or105.readVarInt());
        assertArrayEquals(new int[]{10, 64, 20}, or105.readBlockPosition(LegacyProtocol.PROTOCOL_105));
        assertEquals(-1L, or105.readEntityUniqueId(LegacyProtocol.PROTOCOL_105));
    }

    @Test
    void convertsV113ChunkToPre92ColumnFormat() {
        LegacyStream nukkitStyle = LegacyStream.writer(16384);
        nukkitStyle.writeVarInt(2);
        nukkitStyle.writeVarInt(-3);
        byte[] payloadBytes = new byte[1 + 1 + 4096 + 2048 + 2048 + 2048 + 512 + 256 + 1 + 1];
        payloadBytes[0] = 1; // 1 subchunk
        payloadBytes[1] = 0; // version 0
        Arrays.fill(payloadBytes, 2, 2 + 4096, (byte) 7); // bedrock
        nukkitStyle.writeByteArray(LegacyProtocol.PROTOCOL_113, payloadBytes);

        byte[] p80Chunk = ChunkTranslator.translateOutbound(nukkitStyle.toByteArray(), LegacyProtocol.PROTOCOL_80);
        LegacyStream outReader = LegacyStream.reader(p80Chunk);
        assertEquals(2, outReader.readIntBE());
        assertEquals(-3, outReader.readIntBE());
        assertEquals(0, outReader.readByte()); // order = 0 (COLUMNS)
        byte[] legacyCol = outReader.readByteArray(LegacyProtocol.PROTOCOL_80);
        assertTrue(legacyCol.length >= 83204);
        assertEquals(7, legacyCol[0] & 0xFF);
    }

    @Test
    void packsAndUnpacksBatchesAcrossProtocols84And105And113() throws DataFormatException {
        List<byte[]> subs = List.of(new byte[]{0x09, 0x01, 0x02}, new byte[]{0x0a, 0x05});

        // 0.15.10 (protocolo 84) batch body: 0x06 + int32(compLen) + zlib(int32(len)+pkt)
        byte[] body84 = BatchCodec.encodeRakNetBatchBody(LegacyProtocol.PROTOCOL_84, subs, 6);
        assertEquals(0x06, body84[0] & 0xFF);
        List<byte[]> unpacked84 = BatchCodec.decodeRakNetBatchBody(body84, 7, LegacyProtocol.PROTOCOL_84, 1024 * 1024);
        assertEquals(2, unpacked84.size());
        assertArrayEquals(subs.get(0), unpacked84.get(0));
        assertArrayEquals(subs.get(1), unpacked84.get(1));

        // 1.0.5 (protocolo 105) batch body: 0x06 + UnsignedVarInt(compLen) + zlib(UnsignedVarInt(len)+pkt)
        byte[] body105 = BatchCodec.encodeRakNetBatchBody(LegacyProtocol.PROTOCOL_105, subs, 6);
        assertEquals(0x06, body105[0] & 0xFF);
        List<byte[]> unpacked105 = BatchCodec.decodeRakNetBatchBody(body105, 8, LegacyProtocol.PROTOCOL_105, 1024 * 1024);
        assertEquals(2, unpacked105.size());
        assertArrayEquals(subs.get(0), unpacked105.get(0));
        assertArrayEquals(subs.get(1), unpacked105.get(1));

        // 1.1.0+ canonical body: zlib(varint(len)+pk)
        byte[] body113 = BatchCodec.encodeCanonicalV113Body(subs, 6);
        List<byte[]> unpacked113 = BatchCodec.decodeRakNetBatchBody(body113, 8, LegacyProtocol.PROTOCOL_113, 1024 * 1024);
        assertEquals(2, unpacked113.size());
        assertArrayEquals(subs.get(1), unpacked113.get(1));
    }

    @Test
    void filtersCreativeContentAndHandlesNetwork0xFFFNSlotsFor01510() throws IOException {
        CompoundTag bookTag = new CompoundTag();
        ListTag<CompoundTag> enchList = new ListTag<>("ench");
        enchList.add(new CompoundTag().putShort("id", 0).putShort("lvl", 4));
        enchList.add(new CompoundTag().putShort("id", 70).putShort("lvl", 1)); // Mending (> 24 en 0.15)
        bookTag.putList(enchList);
        byte[] networkNbt = NBTIO.write(bookTag, ByteOrder.LITTLE_ENDIAN, true);

        LegacyStream pk = LegacyStream.writer(512);
        pk.writeByte(ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
        pk.writeUnsignedVarInt(0x79); // SPECIAL_CREATIVE
        pk.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 1L);
        pk.writeUnsignedVarInt(7); // 7 items

        // 1) Stone (valido)
        pk.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(1, 0, 1, new byte[0], new String[0], new String[0]));
        // 2) Fireworks 401 (no existe en 0.15 -> filtrar)
        pk.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(401, 0, 1, new byte[0], new String[0], new String[0]));
        // 3) Firework Star 402 (filtrar)
        pk.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(402, 0, 1, new byte[0], new String[0], new String[0]));
        // 4) Banner 446 (filtrar en creativo)
        pk.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(446, 0, 1, new byte[0], new String[0], new String[0]));
        // 5) Shulker spawn egg 383:54 (filtrar)
        pk.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(383, 54, 1, new byte[0], new String[0], new String[0]));
        // 6) Sea Lantern 169 (no existe en 0.15 creativo -> filtrar)
        pk.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(169, 0, 1, new byte[0], new String[0], new String[0]));
        // 7) Enchanted Book 403 con formato 0xFFFF NetworkLittleEndianNBT
        pk.writeVarInt(403);
        pk.writeVarInt(1); // meta=0, count=1
        pk.writeShortLE(0xFFFF);
        pk.writeByte(1); // nbt version / tag count
        pk.writeBytes(networkNbt);
        pk.writeVarInt(0); // canPlaceOn
        pk.writeVarInt(0); // canDestroy

        pk.writeUnsignedVarInt(0); // hotbar

        PacketTranslator.OutboundResult res = PacketTranslator.translateOutbound(pk.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        assertFalse(res.suppress());
        LegacyStream out = LegacyStream.reader(res.legacyPacket());
        assertEquals(0x2e, out.readByte());
        assertEquals(0x79, out.readByte());
        // En 0.15.10 permanecen Stone y el Enchanted Book (403): existe desde 0.12/0.14
        // (aunque MySoft creative_items/82 no lo liste en el creativo vanilla).
        assertEquals(2, out.readShortBE());

        LegacyStream.LegacySlot s1 = out.readSlot(LegacyProtocol.PROTOCOL_84);
        assertEquals(1, s1.id());

        LegacyStream.LegacySlot sBook84 = out.readSlot(LegacyProtocol.PROTOCOL_84);
        assertEquals(403, sBook84.id());
        assertTrue(sBook84.nbt().length > 0);

        // El mismo Enchanted Book SI se reenvia a 0.16+ (P91+) con su NBT convertido
        LegacyStream pk91 = LegacyStream.writer(512);
        pk91.writeByte(ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
        pk91.writeUnsignedVarInt(0x79);
        pk91.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 1L);
        pk91.writeUnsignedVarInt(1);
        pk91.writeVarInt(403);
        pk91.writeVarInt(1); // meta=0, count=1
        pk91.writeShortLE(0xFFFF);
        pk91.writeByte(1); // nbt version / tag count
        pk91.writeBytes(networkNbt);
        pk91.writeVarInt(0); // canPlaceOn
        pk91.writeVarInt(0); // canDestroy
        pk91.writeUnsignedVarInt(0); // hotbar
        PacketTranslator.OutboundResult res91 = PacketTranslator.translateOutbound(pk91.toByteArray(), LegacyProtocol.PROTOCOL_91, true);
        assertFalse(res91.suppress());
        LegacyStream out91 = LegacyStream.reader(res91.legacyPacket());
        assertEquals(0x33, out91.readByte()); // CONTAINER_SET_CONTENT legacy id para 91 (familia V0_16)
        assertEquals(0x79, out91.readByte()); // windowid (byte en < 110)
        assertEquals(1, out91.readUnsignedVarInt());
        LegacyStream.LegacySlot s2 = out91.readSlot(LegacyProtocol.PROTOCOL_91);
        assertEquals(403, s2.id());
        assertTrue(s2.nbt().length > 0);
        CompoundTag parsedOut = (CompoundTag) NBTIO.readTag(new ByteArrayInputStream(s2.nbt()), ByteOrder.LITTLE_ENDIAN, false);
        assertEquals(1, parsedOut.getList("ench", CompoundTag.class).size());
        assertEquals(0, parsedOut.getList("ench", CompoundTag.class).get(0).getShort("id"));

        // Inventario normal (no creativo): items sin soporte vuelven a AIR sin traduccion a items parecidos
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(450, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(444, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(446, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 54, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(399, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, false).id());

        // Creativo: items/bloques no existentes en la version se eliminan (null), ni reemplazo ni AIR
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(450, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_105, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(444, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(84, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(160, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
    }

    @Test
    void filtersBugItemsByProtocolAndSerializesWildcardMetaCorrectly() {
        // ===== Semillas: existen desde 0.15.0 en todos los protocolos (MySoft creative_items/81) =====
        for (LegacyProtocol dst : new LegacyProtocol[]{LegacyProtocol.PROTOCOL_81, LegacyProtocol.PROTOCOL_84,
                LegacyProtocol.PROTOCOL_91, LegacyProtocol.PROTOCOL_100, LegacyProtocol.PROTOCOL_110, LegacyProtocol.PROTOCOL_112}) {
            assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(295, 0, 1, new byte[0], new String[0], new String[0]), dst, true));
            assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(361, 0, 1, new byte[0], new String[0], new String[0]), dst, true));
            assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(362, 0, 1, new byte[0], new String[0], new String[0]), dst, true));
            assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(458, 0, 1, new byte[0], new String[0], new String[0]), dst, true));
        }

        // ===== Alimentos / spider eye / minerales: 0.15.0 hereda TODO el inventario de 0.14 (brewing) =====
        // apple 260, bread 297, beef 363, wheat 296: en vanilla 81
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(260, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(363, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        // rotten flesh 367, spider eye 375/376: items de 0.14 (brewing) -> presentes tambien en 0.15.0
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(367, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(375, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(376, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(375, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        // Adiciones de 0.15.0 (wiki): saddle 329, name tag 421, lead 420, item frame 389 (0.14),
        // mushroom stew 282, doors 427..431, rabbit 411..414 -> pasan en 80/81
        // (MySoft creative_items/81 es incompleta y NO refleja el inventario real de 0.15.0)
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(282, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(329, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(389, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(411, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(420, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(421, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(427, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        // Adiciones 0.15.0 confirmadas por wiki: fire charge 385, horse armor 416, mutton 423, carrot on a stick 398
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(385, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(416, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(423, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(398, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        // Beetroot 457/seeds 458/soup 459: exclusivos de PE desde 0.8.0 (wiki) -> nunca se filtran
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(457, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(459, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        // Fish types (salmon 460, pufferfish 462): pesca desde 0.11 -> nunca se filtran
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(460, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(462, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        // minerales: coal 263, diamond 264, iron 265, gold 266, quartz 406, emerald 388: vanilla 81
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(263, 1, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(264, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(406, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(388, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));

        // ===== Tintes (351): 0..15 en todos; 16..19 (1.14+) nunca =====
        for (LegacyProtocol dst : new LegacyProtocol[]{LegacyProtocol.PROTOCOL_81, LegacyProtocol.PROTOCOL_91, LegacyProtocol.PROTOCOL_112}) {
            assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(351, 15, 1, new byte[0], new String[0], new String[0]), dst, true));
            assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(351, 16, 1, new byte[0], new String[0], new String[0]), dst, true));
        }

        // ===== Pociones (373) y splash (438): brewing desde 0.14 -> 0.15.0 las tiene (0..35);
        // 373:36 / 438:36 (potion of decay, wither) desde 0.16 (P91) =====
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(373, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(373, 35, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(373, 36, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_90, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(373, 36, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_91, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(438, 5, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(438, 5, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        // splash base 0..4: brewable desde 0.14 (botella de agua + polvora) -> pasa tambien en 0.15/0.16
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(438, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_90, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(438, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_91, true));
        // Lingering (441): solo 100+ (MySoft creative_items/100)
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(441, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_91, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(441, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_100, true));

        // ===== Flechas con efecto (262): metas 6..36 desde 0.15.0 (version exclusive, calderos de pocion);
        // 37 desde 0.16 (P91); 38+ solo v113 =====
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(262, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(262, 6, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(262, 36, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(262, 37, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_90, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(262, 37, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_91, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(262, 38, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_112, true));

        // ===== Libro encantado (403): existe incluso en 0.14 (encantamientos); nunca se filtra por protocolo =====
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(403, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(403, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(403, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_91, true));

        // ===== Kelp (335) y boats 333:6..10: solo v113, nunca en 80..112 =====
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(335, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_112, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(333, 6, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_112, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(333, 5, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));

        // ===== Spawn eggs (383): el gating es SOLO por entidad nativa en dst (mapEntityTypeOutbound),
        // NO por lista vanilla creativa: 0.15.10 (84) renderiza sin crash eggs de mobs que existen
        // en su tabla de entidades aunque no esten en su inventario creativo vanilla. =====
        // Eggs de mobs nativos en 0.15.x pasan incluso en P80/81 (no estan en vanilla 81 pero no crashean)
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 10, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 15, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 10, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        // Eggs anadidos en 0.15.0 (wiki): caballo 23, caballo zombi 27, stray 46, husk 47,
        // wither skeleton 48 -> todos nativos en 0.15.x, pasan en 80/81 tambien
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 23, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 27, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 46, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 47, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 48, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_81, true));
        // Egg 49 (guardian, mob de 0.16): en < 90 la entidad se reemplaza (49 -> 17) -> filtered; en 90+ nativo -> pasa
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 49, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 49, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_90, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 49, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_91, true));
        // Egg 54 (shulker, 1.0+): en < 92 se reemplaza (54 -> 43) -> filtered; en 100+ nativo -> pasa
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 54, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_91, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 54, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_100, true));
        // Egg 57 (vindicator, 1.1): en < 110 se reemplaza (57 -> 45) -> filtered; en 110 nativo -> pasa
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 57, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_105, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 57, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_110, true));
        // Eggs de mobs 1.2+ (meta > 105): sin entidad nativa en ningun cliente legacy -> filtered
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(383, 108, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_112, true));

        // ===== Wildcard meta -1 (Nukkit-MOT putSlotV113 con hasMeta=false) =====
        // En 90..109 NO debe emitirse 0x7FFF crudo (MySoft solo lo interpreta como wildcard en >= 110):
        // se serializa como damage 0.
        for (LegacyProtocol dst : new LegacyProtocol[]{LegacyProtocol.PROTOCOL_90, LegacyProtocol.PROTOCOL_91,
                LegacyProtocol.PROTOCOL_100, LegacyProtocol.PROTOCOL_107}) {
            LegacyStream w = LegacyStream.writer(16);
            w.writeSlot(dst, new LegacyStream.LegacySlot(351, -1, 1, new byte[0], new String[0], new String[0]));
            LegacyStream r = LegacyStream.reader(w.toByteArray());
            assertEquals(351, r.readVarInt());
            int aux = r.readVarInt();
            assertEquals(0, aux >> 8, "meta wildcard debe serializarse como 0 en " + dst);
            assertEquals(1, aux & 0xFF);
        }
        // En 110..112 se conserva el wildcard nativo 0x7FFF
        for (LegacyProtocol dst : new LegacyProtocol[]{LegacyProtocol.PROTOCOL_110, LegacyProtocol.PROTOCOL_112}) {
            LegacyStream w = LegacyStream.writer(16);
            w.writeSlot(dst, new LegacyStream.LegacySlot(351, -1, 1, new byte[0], new String[0], new String[0]));
            LegacyStream r = LegacyStream.reader(w.toByteArray());
            assertEquals(351, r.readVarInt());
            int aux = r.readVarInt();
            assertEquals(0x7FFF, aux >> 8, "meta wildcard debe conservarse 0x7FFF en " + dst);
            assertEquals(1, aux & 0xFF);
        }
        // En < 90 el meta negativo ya se escribia como 0 (short con signo)
        LegacyStream w84 = LegacyStream.writer(16);
        w84.writeSlot(LegacyProtocol.PROTOCOL_84, new LegacyStream.LegacySlot(351, -1, 1, new byte[0], new String[0], new String[0]));
        LegacyStream r84 = LegacyStream.reader(w84.toByteArray());
        assertEquals(351, r84.readShortBE());
        assertEquals(1, r84.readByte());
        assertEquals(0, r84.readShortBE());
    }

    @Test
    void patchesAddEntityModifiersMetadataAndEntityReplacementsFor01510() {
        SessionProtocolRegistry registry = new SessionProtocolRegistry();
        SessionProtocolRegistry.LegacySessionState state = registry.registerOrUpdate(
                new DummyChannelId("entity-test"), null, 84, "JugadorTest"
        );

        // 1) Shulker (54) se reemplaza por Blaze (43) en < 92 y filtra DATA_VARIANT en metadata/eventos
        LegacyStream shulker = LegacyStream.writer(96);
        shulker.writeByte(ProtocolInfo_v113.ADD_ENTITY_PACKET & 0xFF);
        shulker.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 100L);
        shulker.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 100L);
        shulker.writeUnsignedVarInt(54);
        shulker.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 64f, 0f);
        shulker.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 0f, 0f);
        shulker.writeFloatLE(0f);
        shulker.writeFloatLE(0f);
        shulker.writeUnsignedVarInt(0);
        shulker.writeUnsignedVarInt(1); // 1 meta entry: key 2 variant (debe filtrarse por reemplazo)
        shulker.writeUnsignedVarInt(2);
        shulker.writeUnsignedVarInt(LegacyContentMapper.DATA_TYPE_INT);
        shulker.writeVarInt(16);
        PacketTranslator.OutboundResult shulkerOut = PacketTranslator.translateOutbound(
                shulker.toByteArray(), LegacyProtocol.PROTOCOL_84, true, registry, state
        );
        assertFalse(shulkerOut.suppress());
        LegacyStream sr = LegacyStream.reader(shulkerOut.legacyPacket());
        assertEquals(0x0b, sr.readByte());
        assertEquals(100L, sr.readEntityRuntimeId(LegacyProtocol.PROTOCOL_84));
        assertEquals(43, sr.readIntBE()); // Blaze fallback

        // Otros reemplazos clave para 0.15.x: Llama->Donkey(24), Evoker->Witch(45), Vex->Bat(19), Guardian->Squid(17), Wither->WitherSkeleton(48)
        assertEquals(24, LegacyContentMapper.mapEntityTypeOutbound(29, LegacyProtocol.PROTOCOL_84));
        assertEquals(45, LegacyContentMapper.mapEntityTypeOutbound(104, LegacyProtocol.PROTOCOL_84));
        assertEquals(19, LegacyContentMapper.mapEntityTypeOutbound(105, LegacyProtocol.PROTOCOL_84));
        assertEquals(17, LegacyContentMapper.mapEntityTypeOutbound(49, LegacyProtocol.PROTOCOL_84));
        assertEquals(48, LegacyContentMapper.mapEntityTypeOutbound(52, LegacyProtocol.PROTOCOL_84));
        assertEquals(14, LegacyContentMapper.mapEntityTypeOutbound(28, LegacyProtocol.PROTOCOL_84));
        assertEquals(41, LegacyContentMapper.mapEntityTypeOutbound(53, LegacyProtocol.PROTOCOL_84));

        // Entidades > 1.1 ya no se traducen en el plugin (Nukkit-MOT las gestiona automaticamente)
        assertEquals(-1, LegacyContentMapper.mapEntityTypeOutbound(110, LegacyProtocol.PROTOCOL_84));
        assertEquals(-1, LegacyContentMapper.mapEntityTypeOutbound(122, LegacyProtocol.PROTOCOL_84));
        assertEquals(-1, LegacyContentMapper.mapEntityTypeOutbound(123, LegacyProtocol.PROTOCOL_84));
        assertEquals(-1, LegacyContentMapper.mapEntityTypeOutbound(75, LegacyProtocol.PROTOCOL_84));

        // Evento especifico de entidad reemplazada (ej: 34) se filtra, mientras HURT (2) permanece
        LegacyStream hurtEv = LegacyStream.writer(16);
        hurtEv.writeByte(ProtocolInfo_v113.ENTITY_EVENT_PACKET & 0xFF);
        hurtEv.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 100L);
        hurtEv.writeByte(2);
        assertFalse(PacketTranslator.translateOutbound(hurtEv.toByteArray(), LegacyProtocol.PROTOCOL_84, true, registry, state).suppress());

        // Nube AreaEffectCloud (95) sigue suprimiendose
        LegacyStream aec = LegacyStream.writer(64);
        aec.writeByte(ProtocolInfo_v113.ADD_ENTITY_PACKET & 0xFF);
        aec.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 101L);
        aec.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 101L);
        aec.writeUnsignedVarInt(95);
        aec.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 64f, 0f);
        aec.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 0f, 0f);
        aec.writeFloatLE(0f);
        aec.writeFloatLE(0f);
        aec.writeUnsignedVarInt(0);
        aec.writeUnsignedVarInt(0);
        assertTrue(PacketTranslator.translateOutbound(aec.toByteArray(), LegacyProtocol.PROTOCOL_84, true, registry, state).suppress());

        // 2) Zombie (32) en 0.15.10 incluye modifiers(intBE 0) y metadata 0.15 + terminador 0x7F
        LegacyStream zombie = LegacyStream.writer(96);
        zombie.writeByte(ProtocolInfo_v113.ADD_ENTITY_PACKET & 0xFF);
        zombie.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 77L);
        zombie.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 77L);
        zombie.writeUnsignedVarInt(32);
        zombie.writeVector3(LegacyProtocol.PROTOCOL_113, 1f, 65f, 2f);
        zombie.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 0f, 0f);
        zombie.writeFloatLE(10f);
        zombie.writeFloatLE(20f);
        zombie.writeUnsignedVarInt(0); // attributes
        // Metadata v113: count=2 -> key 0 flags (LONG), key 4 nametag (STRING)
        zombie.writeUnsignedVarInt(2);
        zombie.writeUnsignedVarInt(0);
        zombie.writeUnsignedVarInt(LegacyContentMapper.DATA_TYPE_LONG);
        zombie.writeVarLong(1L << 14); // CAN_SHOW_NAME
        zombie.writeUnsignedVarInt(4);
        zombie.writeUnsignedVarInt(LegacyContentMapper.DATA_TYPE_STRING);
        zombie.writeString(LegacyProtocol.PROTOCOL_113, "Zombi");

        PacketTranslator.OutboundResult outRes = PacketTranslator.translateOutbound(zombie.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        assertFalse(outRes.suppress());
        LegacyStream r = LegacyStream.reader(outRes.legacyPacket());
        assertEquals(0x0b, r.readByte());
        assertEquals(77L, r.readEntityRuntimeId(LegacyProtocol.PROTOCOL_84));
        assertEquals(32, r.readIntBE());
        r.readVector3(LegacyProtocol.PROTOCOL_84);
        r.readVector3(LegacyProtocol.PROTOCOL_84);
        assertEquals(20f, r.readFloatBE());
        assertEquals(10f, r.readFloatBE());
        assertEquals(0, r.readIntBE()); // modifiers intBE
        // Debe terminar en 0x7F seguido por shortBE(0) links
        byte[] tail = r.readRemaining();
        assertTrue(tail.length >= 3);
        assertEquals(0x7F, tail[tail.length - 3] & 0xFF);
        assertEquals(0, tail[tail.length - 2] & 0xFF);
        assertEquals(0, tail[tail.length - 1] & 0xFF);
    }

    @Test
    void convertsChunkBlocksAndTileEntitiesAndBlockEntityDataSafely() throws IOException {
        // Chunk subchunk v113 con bloque 218 (Shulker Box) -> debe bajar a Chest (54) en 0.15
        // y trailing tile entities: Sign (conservado como LittleEndianNBT) + ShulkerBox (filtrado)
        CompoundTag signTile = new CompoundTag()
                .putString("id", "Sign")
                .putInt("x", 1)
                .putInt("y", 64)
                .putInt("z", 2)
                .putString("Text1", "Hola");
        CompoundTag shulkerTile = new CompoundTag()
                .putString("id", "ShulkerBox")
                .putInt("x", 3)
                .putInt("y", 64)
                .putInt("z", 4);
        byte[] signNet = NBTIO.write(signTile, ByteOrder.LITTLE_ENDIAN, true);
        byte[] shulkerNet = NBTIO.write(shulkerTile, ByteOrder.LITTLE_ENDIAN, true);

        LegacyStream chunkPayload = LegacyStream.writer(16384);
        chunkPayload.writeByte(1); // 1 subchunk
        chunkPayload.writeByte(0); // version 0
        byte[] blockIds = new byte[4096];
        blockIds[0] = (byte) 218; // Shulker Box -> 54 Chest en 0.15
        blockIds[1] = (byte) 169; // Sea Lantern -> 89 Glowstone en 0.15
        chunkPayload.writeBytes(blockIds);
        chunkPayload.writeBytes(new byte[2048]); // data
        chunkPayload.writeBytes(new byte[2048]); // sky light
        chunkPayload.writeBytes(new byte[2048]); // block light
        chunkPayload.writeBytes(new byte[256]);  // heightmap (256B)
        chunkPayload.writeBytes(new byte[256]);  // PAD_256 (256B)
        chunkPayload.writeBytes(new byte[256]);  // biomes
        chunkPayload.writeByte(0);               // border blocks
        chunkPayload.writeVarInt(0);             // extraData count
        chunkPayload.writeBytes(signNet);
        chunkPayload.writeBytes(shulkerNet);

        LegacyStream fullChunk = LegacyStream.writer(16384);
        fullChunk.writeVarInt(0);
        fullChunk.writeVarInt(0);
        fullChunk.writeByteArray(LegacyProtocol.PROTOCOL_113, chunkPayload.toByteArray());

        byte[] p84Chunk = ChunkTranslator.translateOutbound(fullChunk.toByteArray(), LegacyProtocol.PROTOCOL_84);
        LegacyStream outReader = LegacyStream.reader(p84Chunk);
        assertEquals(0, outReader.readIntBE());
        assertEquals(0, outReader.readIntBE());
        assertEquals(0, outReader.readByte());
        byte[] legacyCol = outReader.readByteArray(LegacyProtocol.PROTOCOL_84);
        assertEquals(54, legacyCol[0] & 0xFF);
        assertEquals(89, legacyCol[1] & 0xFF);

        // Verificar que la entidad de bloque Sign al final esta en LittleEndianNBT (no network VarInt!)
        int tileStart = 83204;
        assertTrue(legacyCol.length > tileStart);
        ByteArrayInputStream bais = new ByteArrayInputStream(legacyCol, tileStart, legacyCol.length - tileStart);
        CompoundTag decodedTile = (CompoundTag) NBTIO.readTag(bais, ByteOrder.LITTLE_ENDIAN, false);
        assertEquals("Sign", decodedTile.getString("id"));
        assertEquals("Hola", decodedTile.getString("Text1"));
        assertEquals(0, bais.available()); // ShulkerBox fue eliminado limpiamente

        // BlockEntityDataPacket: Bed debe suprimirse en < 110; ItemFrame map_uuid LongTag -> StringTag
        CompoundTag frameTile = new CompoundTag()
                .putString("id", "ItemFrame")
                .putInt("x", 5).putInt("y", 65).putInt("z", 5)
                .putCompound("Item", new CompoundTag().putShort("id", 358).putShort("Damage", 0).putByte("Count", 1)
                        .put("map_uuid", new LongTag("map_uuid", 9999L)));
        byte[] convertedFrame = LegacyNbtConverter.convertSingleBlockEntity(
                NBTIO.write(frameTile, ByteOrder.LITTLE_ENDIAN, true), LegacyProtocol.PROTOCOL_84
        );
        assertNotNull(convertedFrame);
        CompoundTag frameOut = (CompoundTag) NBTIO.readTag(new ByteArrayInputStream(convertedFrame), ByteOrder.LITTLE_ENDIAN, false);
        assertEquals("9999", frameOut.getCompound("Item").getString("map_uuid"));

        CompoundTag bedTile = new CompoundTag().putString("id", "Bed").putInt("x", 0).putInt("y", 64).putInt("z", 0);
        assertNull(LegacyNbtConverter.convertSingleBlockEntity(
                NBTIO.write(bedTile, ByteOrder.LITTLE_ENDIAN, true), LegacyProtocol.PROTOCOL_105
        ));
    }

    @Test
    void bridgesSoundsMoveRotationSkinsEntityLinksAndUseItemFace() {
        // 1) LevelSoundEvent SHOOT (20) en 0.15.10 -> LevelEvent (0x16) id 1002
        LegacyStream sndV113 = LegacyStream.writer(32);
        sndV113.writeByte(ProtocolInfo_v113.LEVEL_SOUND_EVENT_PACKET & 0xFF);
        sndV113.writeByte(20);
        sndV113.writeVector3(LegacyProtocol.PROTOCOL_113, 1f, 64f, 1f);
        sndV113.writeVarInt(-1);
        sndV113.writeVarInt(1);
        sndV113.writeBoolean(false);
        sndV113.writeBoolean(false);
        PacketTranslator.OutboundResult snd015 = PacketTranslator.translateOutbound(sndV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        assertFalse(snd015.suppress());
        LegacyStream s015r = LegacyStream.reader(snd015.legacyPacket());
        assertEquals(0x16, s015r.readByte());
        assertEquals(1002, s015r.readShortBE());

        // 2) LevelSoundEvent POP (70) en 1.0.5 (90..109) -> sound 68 (-2 shift)
        LegacyStream popV113 = LegacyStream.writer(32);
        popV113.writeByte(ProtocolInfo_v113.LEVEL_SOUND_EVENT_PACKET & 0xFF);
        popV113.writeByte(70);
        popV113.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 64f, 0f);
        popV113.writeVarInt(-1);
        popV113.writeVarInt(1);
        popV113.writeBoolean(false);
        popV113.writeBoolean(false);
        PacketTranslator.OutboundResult pop105 = PacketTranslator.translateOutbound(popV113.toByteArray(), LegacyProtocol.PROTOCOL_105, false);
        assertFalse(pop105.suppress());
        LegacyStream p105r = LegacyStream.reader(pop105.legacyPacket());
        assertEquals(0x1a, p105r.readByte());
        assertEquals(68, p105r.readByte());

        // 3) SetEntityLink suprimido en 0.15.x
        LegacyStream linkV113 = LegacyStream.writer(16);
        linkV113.writeByte(ProtocolInfo_v113.SET_ENTITY_LINK_PACKET & 0xFF);
        linkV113.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 1L);
        linkV113.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 2L);
        linkV113.writeByte(1);
        assertTrue(PacketTranslator.translateOutbound(linkV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true).suppress());

        // 4) Downsample skin HD 128x128 (65536B) -> 64x64 (16384B) para 0.15.10 (< 100)
        byte[] hdSkin = new byte[128 * 128 * 4];
        byte[] safeSkin = LegacyContentMapper.normalizeSkinData(hdSkin, LegacyProtocol.PROTOCOL_84);
        assertEquals(Skin.DOUBLE_SKIN_SIZE, safeSkin.length);

        // 5) MoveEntityAbsolutePacket rotacion 110..112 vs 0.15.x
        LegacyStream moveEntV113 = LegacyStream.writer(24);
        moveEntV113.writeByte(ProtocolInfo_v113.MOVE_ENTITY_PACKET & 0xFF);
        moveEntV113.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 9L);
        moveEntV113.writeVector3(LegacyProtocol.PROTOCOL_113, 1f, 2f, 3f);
        moveEntV113.writeByte(10); // pitch
        moveEntV113.writeByte(20); // yaw
        moveEntV113.writeByte(30); // headYaw
        moveEntV113.writeByte(0x03); // onGround + teleport
        LegacyStream m110 = LegacyStream.reader(PacketTranslator.translateOutbound(moveEntV113.toByteArray(), LegacyProtocol.PROTOCOL_110, false).legacyPacket());
        assertEquals(0x12, m110.readByte());
        assertEquals(9L, m110.readEntityRuntimeId(LegacyProtocol.PROTOCOL_110));
        m110.readVector3(LegacyProtocol.PROTOCOL_110);
        assertEquals(10, m110.readByte()); // pitch
        assertEquals(30, m110.readByte()); // headYaw
        assertEquals(20, m110.readByte()); // yaw
        assertTrue(m110.readBoolean());
        assertTrue(m110.readBoolean());

        // 6) UseItemPacket inbound 0.15.10 con face=0xFF (-1 lanzar/usar en el aire)
        LegacyStream useAir = LegacyStream.writer(64);
        useAir.writeByte(0x1f);
        useAir.writeBlockPosition(LegacyProtocol.PROTOCOL_84, 0, 0, 0);
        useAir.writeByte(0xFF);
        useAir.writeVector3(LegacyProtocol.PROTOCOL_84, 0f, 0f, 0f);
        useAir.writeVector3(LegacyProtocol.PROTOCOL_84, 0f, 64f, 0f);
        useAir.writeIntBE(0);
        useAir.writeSlot(LegacyProtocol.PROTOCOL_84, LegacyStream.LegacySlot.AIR);
        PacketTranslator.InboundResult useAirCanon = PacketTranslator.translateInbound(useAir.toByteArray(), LegacyProtocol.PROTOCOL_84);
        UseItemPacket_v113 decodedUse = new UseItemPacket_v113();
        decodedUse.protocol = 113;
        decodedUse.gameVersion = GameVersion.byProtocol(113, false);
        decodedUse.setBuffer(useAirCanon.canonicalPacket(), 1);
        decodedUse.decode();
        assertEquals(-1, decodedUse.face);
    }

    @Test
    void translates015SlashCommandsAndRespawnToCanonicalV113Packets() {
        GameVersion v110 = GameVersion.byProtocol(113, false);

        // 1) Chat "/gamemode 1" -> CommandStepPacket_v113 (0x4f)
        LegacyStream chatCmd = LegacyStream.writer(48);
        chatCmd.writeByte(0x07); // 0.15 TextPacket
        chatCmd.writeByte(TextPacket.TYPE_CHAT);
        chatCmd.writeString(LegacyProtocol.PROTOCOL_84, "Trollhunters501C");
        chatCmd.writeString(LegacyProtocol.PROTOCOL_84, "/gamemode 1");

        PacketTranslator.InboundResult cmdRes = PacketTranslator.translateInbound(chatCmd.toByteArray(), LegacyProtocol.PROTOCOL_84);
        assertFalse(cmdRes.suppress());
        assertEquals(ProtocolInfo_v113.COMMAND_STEP_PACKET & 0xFF, cmdRes.canonicalPacket()[0] & 0xFF);
        CommandStepPacket_v113 nukkitCmd = new CommandStepPacket_v113();
        nukkitCmd.protocol = 113;
        nukkitCmd.gameVersion = v110;
        nukkitCmd.setBuffer(cmdRes.canonicalPacket(), 1);
        nukkitCmd.decode();
        assertEquals("gamemode 1", nukkitCmd.command);
        assertTrue(nukkitCmd.args == null || nukkitCmd.args.isEmpty());

        // 2) Chat "./help" -> CommandStepPacket_v113 ("help")
        LegacyStream dotCmd = LegacyStream.writer(32);
        dotCmd.writeByte(0x07);
        dotCmd.writeByte(TextPacket.TYPE_CHAT);
        dotCmd.writeString(LegacyProtocol.PROTOCOL_84, "Jugador");
        dotCmd.writeString(LegacyProtocol.PROTOCOL_84, "./help");
        PacketTranslator.InboundResult dotRes = PacketTranslator.translateInbound(dotCmd.toByteArray(), LegacyProtocol.PROTOCOL_84);
        assertEquals(ProtocolInfo_v113.COMMAND_STEP_PACKET & 0xFF, dotRes.canonicalPacket()[0] & 0xFF);

        // 3) Chat normal sigue siendo TextPacket (0x09)
        LegacyStream chatNormal = LegacyStream.writer(32);
        chatNormal.writeByte(0x07);
        chatNormal.writeByte(TextPacket.TYPE_CHAT);
        chatNormal.writeString(LegacyProtocol.PROTOCOL_84, "Jugador");
        chatNormal.writeString(LegacyProtocol.PROTOCOL_84, "Hola mundo");
        PacketTranslator.InboundResult normalRes = PacketTranslator.translateInbound(chatNormal.toByteArray(), LegacyProtocol.PROTOCOL_84);
        assertEquals(ProtocolInfo_v113.TEXT_PACKET & 0xFF, normalRes.canonicalPacket()[0] & 0xFF);

        // 4) RespawnPacket (0.15 pid 0x28) -> PlayerActionPacket ACTION_RESPAWN (7) con el EID de la sesion
        SessionProtocolRegistry registry = new SessionProtocolRegistry();
        SessionProtocolRegistry.LegacySessionState state = registry.registerOrUpdate(
                new DummyChannelId("respawn-test"), null, 84, "Jugador"
        );
        state.setPlayerEntityId(55L);
        LegacyStream respawn015 = LegacyStream.writer(16);
        respawn015.writeByte(0x28);
        respawn015.writeVector3(LegacyProtocol.PROTOCOL_84, 0f, 64f, 0f);
        PacketTranslator.InboundResult respawnRes = PacketTranslator.translateInbound(
                respawn015.toByteArray(), LegacyProtocol.PROTOCOL_84, state
        );
        assertFalse(respawnRes.suppress());
        assertEquals(ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF, respawnRes.canonicalPacket()[0] & 0xFF);
        PlayerActionPacket nukkitAction = new PlayerActionPacket();
        nukkitAction.protocol = 113;
        nukkitAction.gameVersion = v110;
        nukkitAction.setBuffer(respawnRes.canonicalPacket(), 1);
        nukkitAction.decode();
        assertEquals(55L, nukkitAction.entityId);
        assertEquals(7, nukkitAction.action);
    }

    @Test
    void handlesNegativeBlockItemIdsTrimsInventoryAndDropsOffhandUiFor015() throws IOException {
        // 1) Slot con ID negativo (-206) seguido de Enchanted Book (403) en SPECIAL_CREATIVE (sin desincronizar NBT)
        CompoundTag bookTag = new CompoundTag().putList(new ListTag<CompoundTag>("ench")
                .add(new CompoundTag().putShort("id", 0).putShort("lvl", 1)));
        byte[] bookNbt = NBTIO.write(bookTag, ByteOrder.LITTLE_ENDIAN, true);

        LegacyStream creative = LegacyStream.writer(256);
        creative.writeByte(ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
        creative.writeUnsignedVarInt(0x79);
        creative.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 1L);
        creative.writeUnsignedVarInt(2);
        // Slot 1: negative block ID (-206) serialized in full v113 format
        creative.writeVarInt(-206);
        creative.writeVarInt(1); // meta=0, count=1
        creative.writeShortLE(0); // nbtLen=0
        creative.writeVarInt(0); // canPlaceOn
        creative.writeVarInt(0); // canDestroy
        // Slot 2: Enchanted Book (403)
        creative.writeVarInt(403);
        creative.writeVarInt(1);
        creative.writeShortLE(0xFFFF);
        creative.writeByte(1);
        creative.writeBytes(bookNbt);
        creative.writeVarInt(0);
        creative.writeVarInt(0);
        creative.writeUnsignedVarInt(0);

        PacketTranslator.OutboundResult creativeOut = PacketTranslator.translateOutbound(
                creative.toByteArray(), LegacyProtocol.PROTOCOL_84, true
        );
        assertFalse(creativeOut.suppress());
        LegacyStream cr = LegacyStream.reader(creativeOut.legacyPacket());
        assertEquals(0x2e, cr.readByte());
        assertEquals(0x79, cr.readByte());
        // En 0.15.10 solo se filtra el slot negativo -206; el Enchanted Book (403) permanece
        // (existe desde 0.12/0.14) con su NBT ench convertido.
        assertEquals(1, cr.readShortBE());
        LegacyStream.LegacySlot book84 = cr.readSlot(LegacyProtocol.PROTOCOL_84);
        assertEquals(403, book84.id());
        assertTrue(book84.nbt().length > 0);

        // 2) PlayerInventory (windowId=0) con 45 slots se recorta a 36 para 0.15.x
        LegacyStream inv = LegacyStream.writer(256);
        inv.writeByte(ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
        inv.writeUnsignedVarInt(0);
        inv.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 1L);
        inv.writeUnsignedVarInt(45);
        for (int i = 0; i < 45; i++) {
            inv.writeSlot(LegacyProtocol.PROTOCOL_113, LegacyStream.LegacySlot.AIR);
        }
        inv.writeUnsignedVarInt(9);
        for (int i = 0; i < 9; i++) {
            inv.writeVarInt(i + 9);
        }
        PacketTranslator.OutboundResult invOut = PacketTranslator.translateOutbound(inv.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        LegacyStream invReader = LegacyStream.reader(invOut.legacyPacket());
        assertEquals(0x2e, invReader.readByte());
        assertEquals(0, invReader.readByte());
        assertEquals(36, invReader.readShortBE());

        // 3) Offhand (119) y UI (124) se suprimen en 0.15.x tanto en SetContent como SetSlot
        LegacyStream offhand = LegacyStream.writer(32);
        offhand.writeByte(ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
        offhand.writeUnsignedVarInt(119);
        offhand.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 1L);
        offhand.writeUnsignedVarInt(1);
        offhand.writeSlot(LegacyProtocol.PROTOCOL_113, LegacyStream.LegacySlot.AIR);
        offhand.writeUnsignedVarInt(0);
        assertTrue(PacketTranslator.translateOutbound(offhand.toByteArray(), LegacyProtocol.PROTOCOL_84, true).suppress());

        LegacyStream uiSlot = LegacyStream.writer(16);
        uiSlot.writeByte(ProtocolInfo_v113.CONTAINER_SET_SLOT_PACKET & 0xFF);
        uiSlot.writeByte(124);
        uiSlot.writeVarInt(0);
        uiSlot.writeVarInt(0);
        uiSlot.writeSlot(LegacyProtocol.PROTOCOL_113, LegacyStream.LegacySlot.AIR);
        assertTrue(PacketTranslator.translateOutbound(uiSlot.toByteArray(), LegacyProtocol.PROTOCOL_84, true).suppress());

        // ContainerClosePacket corrupto (> 1 byte) suprimido
        assertTrue(PacketTranslator.translateOutbound(new byte[]{0x31, 0x00, 0x01, 0x02}, LegacyProtocol.PROTOCOL_84, true).suppress());
    }

    @Test
    void omitsUnsupportedRecipesFiltersEffectsAndRemapsParticles() {
        // 1) CraftingDataPacket con 1 receta vacia (-1) y 1 horno valido -> count real = 1 (sin entradas -1)
        LegacyStream craft = LegacyStream.writer(64);
        craft.writeByte(ProtocolInfo_v113.CRAFTING_DATA_PACKET & 0xFF);
        craft.writeUnsignedVarInt(2);
        craft.writeVarInt(-1);
        craft.writeVarInt(2);
        craft.writeVarInt(15);
        craft.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(265, 0, 1, new byte[0], new String[0], new String[0]));
        craft.writeBoolean(true);
        LegacyStream cr = LegacyStream.reader(PacketTranslator.translateOutbound(craft.toByteArray(), LegacyProtocol.PROTOCOL_84, true).legacyPacket());
        assertEquals(0x2f, cr.readByte());
        assertEquals(1, cr.readIntBE());
        assertEquals(2, cr.readIntBE());

        // 2) MobEffect ID 25 (Levitation) filtrado en 0.15.x; ID 1 (Speed) reenviado
        LegacyStream lev = LegacyStream.writer(24);
        lev.writeByte(ProtocolInfo_v113.MOB_EFFECT_PACKET & 0xFF);
        lev.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 1L);
        lev.writeByte(1);
        lev.writeVarInt(25);
        lev.writeVarInt(0);
        lev.writeBoolean(true);
        lev.writeVarInt(200);
        assertTrue(PacketTranslator.translateOutbound(lev.toByteArray(), LegacyProtocol.PROTOCOL_84, true).suppress());

        // 3) Particula V1_1_0 MOB_SPELL (30) -> 0.15 MOB_SPELL (26) y END_ROD (44) -> suprimido en 0.15 / 39 en 1.0.5
        LegacyStream spell = LegacyStream.writer(24);
        spell.writeByte(ProtocolInfo_v113.LEVEL_EVENT_PACKET & 0xFF);
        spell.writeVarInt(0x4000 | 30);
        spell.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 64f, 0f);
        spell.writeVarInt(0xFF0000);
        LegacyStream spReader = LegacyStream.reader(PacketTranslator.translateOutbound(spell.toByteArray(), LegacyProtocol.PROTOCOL_84, true).legacyPacket());
        assertEquals(0x16, spReader.readByte());
        assertEquals(0x4000 | 26, spReader.readShortBE());

        LegacyStream rod = LegacyStream.writer(24);
        rod.writeByte(ProtocolInfo_v113.LEVEL_EVENT_PACKET & 0xFF);
        rod.writeVarInt(0x4000 | 44);
        rod.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 64f, 0f);
        rod.writeVarInt(0);
        assertTrue(PacketTranslator.translateOutbound(rod.toByteArray(), LegacyProtocol.PROTOCOL_84, true).suppress());
        LegacyStream rod105 = LegacyStream.reader(PacketTranslator.translateOutbound(rod.toByteArray(), LegacyProtocol.PROTOCOL_105, false).legacyPacket());
        assertEquals(0x1b, rod105.readByte());
        assertEquals(0x4000 | 39, rod105.readVarInt());
    }

    @Test
    void sanitizesWarpedForestNetherChunksAndInjectsRealPlayerSkins() {
        // 1) Chunk del Nether (Warped Forest biome 180 + bloque 250 wrapped de 506 y 223 de 479)
        LegacyStream chunkPayload = LegacyStream.writer(16384);
        chunkPayload.writeByte(1);
        chunkPayload.writeByte(0);
        byte[] ids = new byte[4096];
        ids[0] = (byte) 250; // wrapped 506 WARPED_FUNGUS -> 1 (Stone)
        ids[1] = (byte) 223; // wrapped 479 WARPED_NYLIUM -> 159 (Stained Clay)
        chunkPayload.writeBytes(ids);
        chunkPayload.writeBytes(new byte[2048]);
        chunkPayload.writeBytes(new byte[2048]);
        chunkPayload.writeBytes(new byte[2048]);
        chunkPayload.writeBytes(new byte[512]);
        byte[] biomes = new byte[256];
        Arrays.fill(biomes, (byte) 180); // Warped Forest
        chunkPayload.writeBytes(biomes);
        chunkPayload.writeByte(0);
        chunkPayload.writeVarInt(0);

        LegacyStream full = LegacyStream.writer(16384);
        full.writeVarInt(0);
        full.writeVarInt(0);
        full.writeByteArray(LegacyProtocol.PROTOCOL_113, chunkPayload.toByteArray());

        byte[] p84 = ChunkTranslator.translateOutbound(full.toByteArray(), LegacyProtocol.PROTOCOL_84);
        LegacyStream r84 = LegacyStream.reader(p84);
        r84.readIntBE();
        r84.readIntBE();
        r84.readByte();
        byte[] col84 = r84.readByteArray(LegacyProtocol.PROTOCOL_84);
        assertEquals(1, col84[0] & 0xFF);
        assertEquals(159, col84[1] & 0xFF);

        // En p105 (subchunk) el bioma 180 debe haberse mapeado a 8 (Hell)
        byte[] p105 = ChunkTranslator.translateOutbound(full.toByteArray(), LegacyProtocol.PROTOCOL_105);
        LegacyStream r105 = LegacyStream.reader(p105);
        r105.readVarInt();
        r105.readVarInt();
        byte[] raw105 = r105.readByteArray(LegacyProtocol.PROTOCOL_105);
        int biomeOffset105 = 1 + 1 + 4096 + 2048 + 2048 + 2048 + 512;
        assertEquals(8, raw105[biomeOffset105] & 0xFF);

        // 2) Skin real cacheada (64x64 Slim = 16384B) restaura el skin de Steve de 8192B en PlayerListPacket
        SessionProtocolRegistry registry = new SessionProtocolRegistry();
        UUID targetUuid = UUID.randomUUID();
        byte[] customPixels = new byte[Skin.DOUBLE_SKIN_SIZE];
        Arrays.fill(customPixels, (byte) 0x42);
        registry.cacheSkin(targetUuid, "OtroJugador", "Standard_CustomSlim", customPixels, true);

        LegacyStream pl = LegacyStream.writer(10000);
        pl.writeByte(ProtocolInfo_v113.PLAYER_LIST_PACKET & 0xFF);
        pl.writeByte(PlayerListPacket.TYPE_ADD);
        pl.writeUnsignedVarInt(1);
        pl.writeUUID(targetUuid);
        pl.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 77L);
        pl.writeString(LegacyProtocol.PROTOCOL_113, "OtroJugador");
        pl.writeString(LegacyProtocol.PROTOCOL_113, "Standard_Custom");
        pl.writeByteArray(LegacyProtocol.PROTOCOL_113, new byte[Skin.SINGLE_SKIN_SIZE]); // Steve degradado por v113
        pl.writeString(LegacyProtocol.PROTOCOL_113, "");
        pl.writeString(LegacyProtocol.PROTOCOL_113, "");
        pl.writeString(LegacyProtocol.PROTOCOL_113, "");

        PacketTranslator.OutboundResult plOut = PacketTranslator.translateOutbound(
                pl.toByteArray(), LegacyProtocol.PROTOCOL_84, true, registry, null
        );
        LegacyStream plr = LegacyStream.reader(plOut.legacyPacket());
        assertEquals(0x38, plr.readByte());
        assertEquals(PlayerListPacket.TYPE_ADD, plr.readByte());
        assertEquals(1, plr.readIntBE());
        assertEquals(targetUuid, plr.readUUID());
        assertEquals(77L, plr.readEntityUniqueId(LegacyProtocol.PROTOCOL_84));
        assertEquals("OtroJugador", plr.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals("Standard_CustomSlim", plr.readString(LegacyProtocol.PROTOCOL_84));
        byte[] outSkin = plr.readBytes(plr.readShortBE());
        assertEquals(Skin.DOUBLE_SKIN_SIZE, outSkin.length);
        assertEquals((byte) 0x42, outSkin[0]);
    }

    @Test
    void sanitizesAllRealV110CreativeItemsEnforcesItemAirRuleAndPatchesGamemodeMetadata() throws IOException {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            java.lang.reflect.Field fUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            fUnsafe.setAccessible(true);
            Object unsafe = fUnsafe.get(null);
            java.lang.reflect.Method allocateInstance = unsafeClass.getMethod("allocateInstance", Class.class);
            java.lang.reflect.Field fInst = cn.nukkit.Server.class.getDeclaredField("instance");
            fInst.setAccessible(true);
            Object previousServer = fInst.get(null);
            if (previousServer == null) {
                fInst.set(null, allocateInstance.invoke(unsafe, cn.nukkit.Server.class));
            }
            try {
                cn.nukkit.item.RuntimeItems.init();
                cn.nukkit.block.Block.init();
                cn.nukkit.item.enchantment.Enchantment.init();
                cn.nukkit.level.GlobalBlockPalette.init();
                cn.nukkit.item.Item.init();
                if (Item.getCreativeItems(GameVersion.V1_1_0).isEmpty()) {
                    Item.initCreativeItems();
                }
            } finally {
                fInst.set(null, previousServer);
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        List<Item> v110Creative = Item.getCreativeItems(GameVersion.V1_1_0);
        assertTrue(v110Creative.size() > 900);

        LegacyStream creativePkt = LegacyStream.writer(v110Creative.size() * 24 + 32);
        creativePkt.writeByte(ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
        creativePkt.writeUnsignedVarInt(0x79);
        creativePkt.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 1L);
        creativePkt.writeUnsignedVarInt(v110Creative.size());
        for (Item item : v110Creative) {
            byte[] nbt = new byte[0];
            if (item.hasCompoundTag()) {
                nbt = NBTIO.write(item.getNamedTag(), ByteOrder.LITTLE_ENDIAN, false);
            }
            creativePkt.writeSlot(
                    LegacyProtocol.PROTOCOL_113,
                    new LegacyStream.LegacySlot(item.getId(), item.getDamage(), item.getCount(), nbt, new String[0], new String[0])
            );
        }
        creativePkt.writeUnsignedVarInt(0);
        byte[] rawV113Creative = creativePkt.toByteArray();

        for (LegacyProtocol proto : List.of(
                LegacyProtocol.PROTOCOL_84,
                LegacyProtocol.PROTOCOL_91,
                LegacyProtocol.PROTOCOL_105,
                LegacyProtocol.PROTOCOL_110
        )) {
            PacketTranslator.OutboundResult res = PacketTranslator.translateOutbound(rawV113Creative, proto, true);
            assertFalse(res.suppress());
            LegacyStream r = LegacyStream.reader(res.legacyPacket());
            int expectedPid = PacketIdMapper.toLegacy(proto, ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
            assertEquals(expectedPid, r.readByte());
            assertEquals(0x79, proto.isBefore110() ? r.readByte() : r.readUnsignedVarInt());
            if (!proto.isBefore110()) {
                assertEquals(1L, r.readEntityUniqueId(proto));
            }
            int outCount = proto.isBefore90() ? r.readShortBE() : r.readUnsignedVarInt();
            assertTrue(outCount > 250 && outCount < v110Creative.size(), "proto=" + proto + " count=" + outCount);

            int maxEnch = proto.isBefore110() ? 24 : 26;
            for (int i = 0; i < outCount; i++) {
                LegacyStream.LegacySlot slot = r.readSlot(proto);
                assertTrue(slot.id() > 0, "Creative slot must never be AIR on proto=" + proto);
                assertNotEquals(386, slot.id(), "Book & Quill does not exist <= 1.1");
                assertNotEquals(401, slot.id(), "Fireworks do not exist <= 1.1");
                assertNotEquals(402, slot.id(), "Firework Star does not exist <= 1.1");
                assertTrue(slot.id() <= 466 && slot.id() != 464 && slot.id() != 465, "Invalid item id=" + slot.id());
                if (slot.id() == 325) {
                    assertTrue(slot.meta() == 0 || slot.meta() == 1 || slot.meta() == 8 || slot.meta() == 10);
                }
                if (slot.id() == 351) {
                    assertTrue(slot.meta() <= 15);
                }
                if (slot.id() == 139) {
                    assertTrue(slot.meta() <= 1);
                }
                if (slot.id() == 182) {
                    assertTrue(slot.meta() <= (proto.isBefore92() ? 0 : 1));
                }
                if (slot.id() == 373 || slot.id() == 438 || slot.id() == 441) {
                    assertTrue(slot.meta() <= 36);
                }
                if (slot.id() == 262 && slot.meta() > 0) {
                    // 262:6..36 existen desde 0.15.0 (calderos de pocion, version exclusive);
                    // 262:37 (undecay) desde 0.16 (P91)
                    assertTrue(slot.meta() >= 6 && slot.meta() <= 36
                                    || (slot.meta() == 37 && proto.getProtocol() >= 91),
                            "Unexpected tipped arrow meta=" + slot.meta() + " for proto=" + proto);
                }
                if (slot.id() == 403) {
                    assertTrue(slot.nbt().length > 0, "Empty enchanted book leaked on proto=" + proto);
                    CompoundTag tag = (CompoundTag) NBTIO.readTag(new ByteArrayInputStream(slot.nbt()), ByteOrder.LITTLE_ENDIAN, false);
                    ListTag<CompoundTag> ench = tag.getList("ench", CompoundTag.class);
                    assertTrue(ench.size() > 0);
                    for (int j = 0; j < ench.size(); j++) {
                        int eid = ench.get(j).getShort("id");
                        assertTrue(eid >= 0 && eid <= maxEnch, "Unsupported enchant id=" + eid + " for proto=" + proto);
                    }
                }
            }
            assertEquals(0, proto.isBefore90() ? r.readShortBE() : r.readUnsignedVarInt());
            assertEquals(0, r.remaining(), "Trailing unparsed bytes on creative packet proto=" + proto);
        }

        // Regla de items no-creativos: sin reemplazo a items parecidos -> AIR (0)
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(218, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_105, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(166, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_110, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(169, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_84, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(355, 14, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_105, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(386, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_110, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(325, 2, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_110, false).id());

        // SetEntityData DATA_FLAG_HAS_COLLISION (bit 48) + bit shifts en 110 y 105
        // NOTA: el eid se serializa como Nukkit-MOT v113 real (DataPacket.putEntityUniqueId ->
        // putUnsignedVarInt para protocol < v1_2_0, DataPacket.java:151-158), NO como VarLong zigzag.
        LegacyStream metaV113 = LegacyStream.writer(32);
        metaV113.writeByte(ProtocolInfo_v113.SET_ENTITY_DATA_PACKET & 0xFF);
        metaV113.writeUnsignedVarLong(1L);
        metaV113.writeUnsignedVarInt(1);
        metaV113.writeUnsignedVarInt(0); // key DATA_FLAGS
        metaV113.writeUnsignedVarInt(LegacyContentMapper.DATA_TYPE_LONG);
        // bits: CAN_FLY(21) | WALKER(22) | TAMED(28) | LEASHED(30) | HAS_COLLISION(48)
        long v113Flags = (1L << 21) | (1L << 22) | (1L << 28) | (1L << 30) | (1L << 48);
        metaV113.writeVarLong(v113Flags);

        PacketTranslator.OutboundResult meta110 = PacketTranslator.translateOutbound(metaV113.toByteArray(), LegacyProtocol.PROTOCOL_110, false);
        LegacyStream mr110 = LegacyStream.reader(meta110.legacyPacket());
        assertEquals(0x27, mr110.readByte());
        assertEquals(1L, mr110.readEntityRuntimeId(LegacyProtocol.PROTOCOL_110));
        assertEquals(1, mr110.readUnsignedVarInt());
        assertEquals(0, mr110.readUnsignedVarInt());
        assertEquals(LegacyContentMapper.DATA_TYPE_LONG, mr110.readUnsignedVarInt());
        long expected110 = (1L << 21) | (1L << 27) | (1L << 28); // sin WALKER(22) ni HAS_COLLISION(48)
        assertEquals(expected110, mr110.readVarLong());

        PacketTranslator.OutboundResult meta105 = PacketTranslator.translateOutbound(metaV113.toByteArray(), LegacyProtocol.PROTOCOL_105, false);
        LegacyStream mr105 = LegacyStream.reader(meta105.legacyPacket());
        mr105.readByte();
        mr105.readEntityRuntimeId(LegacyProtocol.PROTOCOL_105);
        mr105.readUnsignedVarInt();
        mr105.readUnsignedVarInt();
        mr105.readUnsignedVarInt();
        long expected105 = (1L << 24) | (1L << 25); // TAMED(28)->24, LEASHED(30)->25
        assertEquals(expected105, mr105.readVarLong());

        // AdventureSettings 0.15.x outbound flags (AUTO_JUMP 0x20->0x40, ALLOW_FLIGHT 0x40->0x80, WORLD_BUILDER/FLYING filtrados)
        LegacyStream advV113 = LegacyStream.writer(16);
        advV113.writeByte(ProtocolInfo_v113.ADVENTURE_SETTINGS_PACKET & 0xFF);
        advV113.writeUnsignedVarInt(0x20 | 0x40 | 0x100 | 0x200);
        advV113.writeUnsignedVarInt(1);
        LegacyStream ar = LegacyStream.reader(PacketTranslator.translateOutbound(advV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true).legacyPacket());
        assertEquals(0x31, ar.readByte());
        assertEquals(0x40 | 0x80, ar.readIntBE());
        assertEquals(1, ar.readIntBE());
        assertEquals(2, ar.readIntBE());
    }

    @Test
    void handlesDimensionTeleportPacketsAcrossAllLegacyProtocols() throws IOException {
        // 1) ChangeDimensionPacket: 0.15.x (< 90) omite Vector3 y clampea End (2 -> 0); 0.16 (91) clampea 2 -> 0; 1.0+ conserva 2
        LegacyStream chDimV113 = LegacyStream.writer(32);
        chDimV113.writeByte(ProtocolInfo_v113.CHANGE_DIMENSION_PACKET & 0xFF);
        chDimV113.writeVarInt(2); // The End
        chDimV113.writeVector3(LegacyProtocol.PROTOCOL_113, 100f, 64f, 100f);
        chDimV113.writeBoolean(false);

        PacketTranslator.OutboundResult dim84 = PacketTranslator.translateOutbound(chDimV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true);
        LegacyStream dr84 = LegacyStream.reader(dim84.legacyPacket());
        assertEquals(0x36, dr84.readByte());
        assertEquals(0, dr84.readByte()); // End clamped a Overworld (0)
        assertFalse(dr84.readBoolean());
        assertEquals(0, dr84.remaining(), "0.15 ChangeDimension must NOT contain Vector3");

        PacketTranslator.OutboundResult dim91 = PacketTranslator.translateOutbound(chDimV113.toByteArray(), LegacyProtocol.PROTOCOL_91, false);
        LegacyStream dr91 = LegacyStream.reader(dim91.legacyPacket());
        assertEquals(0x3c, dr91.readByte());
        assertEquals(0, dr91.readVarInt()); // End clamped a Overworld en < 100

        PacketTranslator.OutboundResult dim110 = PacketTranslator.translateOutbound(chDimV113.toByteArray(), LegacyProtocol.PROTOCOL_110, false);
        LegacyStream dr110 = LegacyStream.reader(dim110.legacyPacket());
        assertEquals(0x3d, dr110.readByte());
        assertEquals(2, dr110.readVarInt());
        dr110.readVector3(LegacyProtocol.PROTOCOL_110);
        assertFalse(dr110.readBoolean());

        // 2) MovePlayerPacket MODE_TELEPORT (2) para 1.1.0 (110) conserva teleportCause y teleportItem (8 bytes)
        LegacyStream tpV113 = LegacyStream.writer(64);
        tpV113.writeByte(ProtocolInfo_v113.MOVE_PLAYER_PACKET & 0xFF);
        tpV113.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 7L);
        tpV113.writeVector3(LegacyProtocol.PROTOCOL_113, 10f, 70f, -20f);
        tpV113.writeFloatLE(0f);
        tpV113.writeFloatLE(180f);
        tpV113.writeFloatLE(180f);
        tpV113.writeByte(MovePlayerPacket.MODE_TELEPORT);
        tpV113.writeBoolean(true);
        tpV113.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 0L);
        tpV113.writeIntLE(3); // teleportCause
        tpV113.writeIntLE(368); // teleportItem

        PacketTranslator.OutboundResult tp110 = PacketTranslator.translateOutbound(tpV113.toByteArray(), LegacyProtocol.PROTOCOL_110, false);
        MovePlayerPacket decodedTp = new MovePlayerPacket();
        decodedTp.protocol = 113;
        decodedTp.gameVersion = GameVersion.byProtocol(113, false);
        decodedTp.setBuffer(tp110.legacyPacket(), 1);
        decodedTp.decode();
        assertEquals(MovePlayerPacket.MODE_TELEPORT, decodedTp.mode);
        assertEquals(3, decodedTp.teleportCause);
        assertEquals(368, decodedTp.teleportItem);

        // 3) GameRulesChangedPacket (0x49) filtra showCoordinates y maxCommandChainLength segun version
        LegacyStream grV113 = LegacyStream.writer(128);
        grV113.writeByte(ProtocolInfo_v113.GAME_RULES_CHANGED_PACKET & 0xFF);
        grV113.writeUnsignedVarInt(3);
        grV113.writeString(LegacyProtocol.PROTOCOL_113, "pvp");
        grV113.writeUnsignedVarInt(1);
        grV113.writeBoolean(true);
        grV113.writeString(LegacyProtocol.PROTOCOL_113, "showCoordinates");
        grV113.writeUnsignedVarInt(1);
        grV113.writeBoolean(true);
        grV113.writeString(LegacyProtocol.PROTOCOL_113, "maxCommandChainLength");
        grV113.writeUnsignedVarInt(2);
        grV113.writeUnsignedVarInt(65536);

        assertTrue(PacketTranslator.translateOutbound(grV113.toByteArray(), LegacyProtocol.PROTOCOL_84, true).suppress());

        PacketTranslator.OutboundResult gr91 = PacketTranslator.translateOutbound(grV113.toByteArray(), LegacyProtocol.PROTOCOL_91, false);
        LegacyStream grr91 = LegacyStream.reader(gr91.legacyPacket());
        assertEquals(0x47, grr91.readByte());
        assertEquals(1, grr91.readUnsignedVarInt());
        assertEquals("pvp", grr91.readString(LegacyProtocol.PROTOCOL_91));

        PacketTranslator.OutboundResult gr110 = PacketTranslator.translateOutbound(grV113.toByteArray(), LegacyProtocol.PROTOCOL_110, false);
        LegacyStream grr110 = LegacyStream.reader(gr110.legacyPacket());
        assertEquals(0x49, grr110.readByte());
        assertEquals(2, grr110.readUnsignedVarInt()); // pvp + maxCommandChainLength (sin showCoordinates)

        // 4) BlockEntity EnderChest filtrado en 0.16 (91) pero permitido en 1.0 (105)
        CompoundTag enderTile = new CompoundTag().putString("id", "EnderChest").putInt("x", 0).putInt("y", 64).putInt("z", 0);
        byte[] enderNet = NBTIO.write(enderTile, ByteOrder.LITTLE_ENDIAN, true);
        assertNull(LegacyNbtConverter.convertSingleBlockEntity(enderNet, LegacyProtocol.PROTOCOL_91));
        assertNotNull(LegacyNbtConverter.convertSingleBlockEntity(enderNet, LegacyProtocol.PROTOCOL_105));
    }

    @Test
    void translatesPlayerActionShiftSneakAcrossLegacyProtocols() {
        // MySoft PacketPool.php:429-442: el protocolo 90 no tiene tabla propia y resuelve hacia
        // abajo hasta PacketsIds81, donde PLAYER_ACTION_PACKET = 0x20 (PacketsIds81.php:57).
        // El bug: la familia V0_16 (tabla v91) mapeaba 0x20 -> MOB_EQUIPMENT y 0x24 -> PLAYER_ACTION,
        // de modo que el Shift (action=11 START_SNEAK / 12 STOP_SNEAK, mismos valores en la tabla
        // PROTOCOL_81 de PlayerActionIds.php:372-373 y en v113) se perdia en 0.15.90.

        // 1) P90 inbound: cliente 0.15.90 envia pid 0x20 con action=11 (START_SNEAK)
        //    Serializacion P90: VarLong eid + VarInt action + pos (Y byte) + VarInt face
        LegacyStream sneak90 = LegacyStream.writer(16);
        sneak90.writeByte(0x20);
        sneak90.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_90, 77L);
        sneak90.writeVarInt(11);
        sneak90.writeBlockPosition(LegacyProtocol.PROTOCOL_90, 10, 64, -20);
        sneak90.writeVarInt(0);
        PacketTranslator.InboundResult res90 = PacketTranslator.translateInbound(
                sneak90.toByteArray(), LegacyProtocol.PROTOCOL_90
        );
        assertFalse(res90.suppress());
        assertEquals(ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF, res90.canonicalPacket()[0] & 0xFF);
        PlayerActionPacket nukkitSneak = new PlayerActionPacket();
        nukkitSneak.protocol = 113;
        nukkitSneak.gameVersion = GameVersion.byProtocol(113, false);
        nukkitSneak.setBuffer(res90.canonicalPacket(), 1);
        nukkitSneak.decode();
        assertEquals(77L, nukkitSneak.entityId);
        assertEquals(11, nukkitSneak.action);
        assertEquals(10, nukkitSneak.x);
        assertEquals(64, nukkitSneak.y);
        assertEquals(-20, nukkitSneak.z);

        // 2) P90 outbound: PLAYER_ACTION v113 debe salir como pid 0x20 con Y serializada como byte
        LegacyStream actionV113 = LegacyStream.writer(16);
        actionV113.writeByte(ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF);
        actionV113.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 77L);
        actionV113.writeVarInt(12); // STOP_SNEAK
        actionV113.writeBlockPosition(LegacyProtocol.PROTOCOL_113, 10, 64, -20);
        actionV113.writeVarInt(0);
        PacketTranslator.OutboundResult out90 = PacketTranslator.translateOutbound(
                actionV113.toByteArray(), LegacyProtocol.PROTOCOL_90, false
        );
        assertFalse(out90.suppress());
        LegacyStream legacy90 = LegacyStream.reader(out90.legacyPacket());
        assertEquals(0x20, legacy90.readByte());
        assertEquals(77L, legacy90.readEntityRuntimeId(LegacyProtocol.PROTOCOL_90));
        assertEquals(12, legacy90.readVarInt());
        int[] bp90 = legacy90.readBlockPosition(LegacyProtocol.PROTOCOL_90);
        assertEquals(10, bp90[0]);
        assertEquals(64, bp90[1]);
        assertEquals(-20, bp90[2]);
        assertEquals(0, legacy90.readVarInt());

        // 3) P91 sin regresion: PLAYER_ACTION sigue siendo 0x24 en la tabla v91
        assertEquals(ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF,
                PacketIdMapper.toV113(LegacyProtocol.PROTOCOL_91, 0x24));
        assertEquals(0x24, PacketIdMapper.toLegacy(LegacyProtocol.PROTOCOL_91, ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF));

        // 4) P84 (< 90): pid 0x20, layout Long + IntBE x5 (Nukkit-Retro PlayerActionPacket.java:49-56)
        LegacyStream sneak84 = LegacyStream.writer(32);
        sneak84.writeByte(0x20);
        sneak84.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_84, 77L);
        sneak84.writeIntBE(11); // START_SNEAK
        sneak84.writeBlockPosition(LegacyProtocol.PROTOCOL_84, 10, 64, -20);
        sneak84.writeIntBE(0);
        PacketTranslator.InboundResult res84 = PacketTranslator.translateInbound(
                sneak84.toByteArray(), LegacyProtocol.PROTOCOL_84
        );
        assertFalse(res84.suppress());
        PlayerActionPacket nukkitSneak84 = new PlayerActionPacket();
        nukkitSneak84.protocol = 113;
        nukkitSneak84.gameVersion = GameVersion.byProtocol(113, false);
        nukkitSneak84.setBuffer(res84.canonicalPacket(), 1);
        nukkitSneak84.decode();
        assertEquals(11, nukkitSneak84.action);
        assertEquals(64, nukkitSneak84.y);

        // 5) P84 outbound: PLAYER_ACTION v113 -> pid 0x20 con IntBE x5
        PacketTranslator.OutboundResult out84 = PacketTranslator.translateOutbound(
                actionV113.toByteArray(), LegacyProtocol.PROTOCOL_84, false
        );
        assertFalse(out84.suppress());
        LegacyStream legacy84 = LegacyStream.reader(out84.legacyPacket());
        assertEquals(0x20, legacy84.readByte());
        assertEquals(77L, legacy84.readEntityRuntimeId(LegacyProtocol.PROTOCOL_84));
        assertEquals(12, legacy84.readIntBE());
        int[] bp84 = legacy84.readBlockPosition(LegacyProtocol.PROTOCOL_84);
        assertEquals(10, bp84[0]);
        assertEquals(64, bp84[1]);
        assertEquals(-20, bp84[2]);
        assertEquals(0, legacy84.readIntBE());

        // 6) P112 sin regresion: payload v113 pasa intacto (layout identico >= 92)
        PacketTranslator.OutboundResult out112 = PacketTranslator.translateOutbound(
                actionV113.toByteArray(), LegacyProtocol.PROTOCOL_112, false
        );
        assertFalse(out112.suppress());
        assertEquals(ProtocolInfo_v113.PLAYER_ACTION_PACKET & 0xFF, out112.legacyPacket()[0] & 0xFF);
        assertArrayEquals(java.util.Arrays.copyOfRange(actionV113.toByteArray(), 1, actionV113.toByteArray().length),
                java.util.Arrays.copyOfRange(out112.legacyPacket(), 1, out112.legacyPacket().length));
    }

    @Test
    void translatesMobEffectPacketIdAndLayoutAcrossLegacyProtocols() {
        // MySoft PacketPool.php:429-441: el protocolo 90 no tiene tabla propia y resuelve hacia
        // abajo hasta PacketsIds81, donde MOB_EFFECT_PACKET = 0x19 (PacketsIds81.php:51) y
        // 0x1e = INTERACT_PACKET (PacketsIds81.php:55). El bug: la familia V0_16 (tabla v91,
        // MOB_EFFECT = 0x1e en PacketsIds91.php:56) hacia que el cliente 0.15.90 leyera el
        // efecto como InteractPacket. La serializacion P90 si usa las ramas >= 90
        // (MobEffectPacket.php:54-87: VarInt/UnsignedVarLong desde PROTOCOL_90).

        // MobEffect v113 real (Nukkit-MOT MobEffectPacket.java:44-51 con putEntityRuntimeId ->
        // putUnsignedVarInt en protocol < v1_2_0, DataPacket.java:175-181): ADD de Speed (1)
        LegacyStream effectV113 = LegacyStream.writer(24);
        effectV113.writeByte(ProtocolInfo_v113.MOB_EFFECT_PACKET & 0xFF);
        effectV113.writeUnsignedVarInt(77); // eid: Nukkit-MOT lo escribe como UnsignedVarInt
        effectV113.writeByte(1);            // EVENT_ADD
        effectV113.writeVarInt(1);          // effectId Speed
        effectV113.writeVarInt(0);          // amplifier
        effectV113.writeBoolean(true);      // particles
        effectV113.writeVarInt(200);        // duration

        // 1) P90 outbound: pid 0x19 (tabla 81) con layout >= 90 (UVarLong + VarInt)
        PacketTranslator.OutboundResult out90 = PacketTranslator.translateOutbound(
                effectV113.toByteArray(), LegacyProtocol.PROTOCOL_90, false
        );
        assertFalse(out90.suppress());
        LegacyStream legacy90 = LegacyStream.reader(out90.legacyPacket());
        assertEquals(0x19, legacy90.readByte());
        assertEquals(77L, legacy90.readEntityRuntimeId(LegacyProtocol.PROTOCOL_90));
        assertEquals(1, legacy90.readByte());
        assertEquals(1, legacy90.readVarInt());
        assertEquals(0, legacy90.readVarInt());
        assertTrue(legacy90.readBoolean());
        assertEquals(200, legacy90.readVarInt());

        // 2) P91 sin regresion: MOB_EFFECT sigue siendo 0x1e en la tabla v91
        PacketTranslator.OutboundResult out91 = PacketTranslator.translateOutbound(
                effectV113.toByteArray(), LegacyProtocol.PROTOCOL_91, false
        );
        assertFalse(out91.suppress());
        assertEquals(0x1e, out91.legacyPacket()[0] & 0xFF);

        // 3) P110 sin regresion: pid 0x1d identico a v113 (tabla v110, PacketsIds110.php:55)
        PacketTranslator.OutboundResult out110 = PacketTranslator.translateOutbound(
                effectV113.toByteArray(), LegacyProtocol.PROTOCOL_110, false
        );
        assertFalse(out110.suppress());
        assertEquals(0x1d, out110.legacyPacket()[0] & 0xFF);

        // 4) P84 (< 90) sin regresion: pid 0x19 con layout LongBE + bytes + IntBE
        PacketTranslator.OutboundResult out84 = PacketTranslator.translateOutbound(
                effectV113.toByteArray(), LegacyProtocol.PROTOCOL_84, false
        );
        assertFalse(out84.suppress());
        LegacyStream legacy84 = LegacyStream.reader(out84.legacyPacket());
        assertEquals(0x19, legacy84.readByte());
        assertEquals(77L, legacy84.readLongBE());
        assertEquals(1, legacy84.readByte());
        assertEquals(1, legacy84.readByte());
        assertEquals(0, legacy84.readByte());
        assertTrue(legacy84.readBoolean());
        assertEquals(200, legacy84.readIntBE());
    }

    @Test
    void registersMobEffectInV113PoolOfNukkitMotNetwork() {
        // Nukkit-MOT Network#registerPackets (Network.java:579-664) NO registra MobEffectPacket en
        // packetPool113 (solo MOB_ARMOR_EQUIPMENT/MOB_EQUIPMENT): en protocolo 113 (< v1_2_0=137)
        // DataPacket#reset() resuelve el pid del sub-paquete con PacketPool#getPacketId, que lanza
        // IllegalArgumentException (PacketPool.java:58-65) y cae al fallback 0x6a (DataPacket.java:58-67,
        // id inexistente en 1.1). El sub-paquete salia del servidor con pid 0x6a, PacketIdMapper lo
        // consideraba UNMAPPED y los efectos se descartaban para TODOS los clientes 80-113.
        Network network = new Network(null);
        PacketPool pool113 = network.getPacketPool(GameVersion.V1_1_0);
        assertThrows(IllegalArgumentException.class, () -> pool113.getPacketId(MobEffectPacket.class));

        // Registro oficial (Network.java:223-226) usado por
        // MultiversionBridgeManager#registerV113MobEffectPacket: tras el registro, reset()
        // escribe el pid real 0x1d (ProtocolInfo_v113.java:37, MySoft PacketsIds110.php:55).
        network.registerPacket(GameVersion.V1_1_0, ProtocolInfo_v113.MOB_EFFECT_PACKET, MobEffectPacket.class);
        assertEquals(ProtocolInfo_v113.MOB_EFFECT_PACKET & 0xFF,
                network.getPacketPool(GameVersion.V1_1_0).getPacketId(MobEffectPacket.class));
    }

    @Test
    void dropsEffectIdsThatDoNotExistInEachLegacyClientVersion() {
        // Nukkit-MOT conoce efectos hasta RAID_OMEN=36 (Effect.java:26-68) y su gate
        // canReceiveEffectPacket (Player.java:2674-2682) solo excluye los 4 efectos de 1.21.0
        // (WIND_CHARGED/WEAVING/OOZING/INFESTED) en protocolos < v1_21_0: en la sesion puente P113
        // los IDs 24-31 y 36 SI llegan al traductor. Validos por version (LegacyProtocol.java:13-30 +
        // MySoft ProtocolInfo.php:198-215): 1-23 clasicos en todos los legacy; LEVITATION(24) solo
        // desde 1.0.0 (P92, Family V1_0_0; P90/P91 son 0.15.90/0.16.x Family V0_16); 25+ no existen
        // en ninguna version <= 1.1.13.

        // 1) 25 (FATAL_POISON, Effect.java:57) no existe en ninguna version legacy: descarte total
        for (LegacyProtocol dst : new LegacyProtocol[]{
                LegacyProtocol.PROTOCOL_84, LegacyProtocol.PROTOCOL_90, LegacyProtocol.PROTOCOL_110}) {
            assertTrue(PacketTranslator.translateOutbound(mobEffectV113(25), dst, false).suppress(),
                    "FATAL_POISON debe descartarse en " + dst);
        }

        // 2) 24 (LEVITATION) no existe en 0.15.x ni en 0.15.90/0.16.x (P80-P91): se descarta
        assertTrue(PacketTranslator.translateOutbound(mobEffectV113(24), LegacyProtocol.PROTOCOL_84, false).suppress());
        assertTrue(PacketTranslator.translateOutbound(mobEffectV113(24), LegacyProtocol.PROTOCOL_90, false).suppress());
        assertTrue(PacketTranslator.translateOutbound(mobEffectV113(24), LegacyProtocol.PROTOCOL_91, false).suppress());
        // ...y pasa desde 1.0.0 (P92, Family V1_0_0, pid 0x1e de la tabla v100, PacketIdMapper.java:234)
        PacketTranslator.OutboundResult lev92 = PacketTranslator.translateOutbound(
                mobEffectV113(24), LegacyProtocol.PROTOCOL_92, false);
        assertFalse(lev92.suppress());
        assertEquals(0x1e, lev92.legacyPacket()[0] & 0xFF);
        // ...y en 1.1.x sin regresion (pid identidad 0x1d, initV110Table, PacketIdMapper.java:400-405)
        PacketTranslator.OutboundResult lev110 = PacketTranslator.translateOutbound(
                mobEffectV113(24), LegacyProtocol.PROTOCOL_110, false);
        assertFalse(lev110.suppress());
        assertEquals(0x1d, lev110.legacyPacket()[0] & 0xFF);

        // 3) 23 (SATURATION, el maximo clasico) pasa en todas las versiones legacy
        for (LegacyProtocol dst : new LegacyProtocol[]{
                LegacyProtocol.PROTOCOL_84, LegacyProtocol.PROTOCOL_90, LegacyProtocol.PROTOCOL_110}) {
            assertFalse(PacketTranslator.translateOutbound(mobEffectV113(23), dst, false).suppress(),
                    "SATURATION debe pasar en " + dst);
        }

        // 4) IDs no positivos se descartan
        assertTrue(PacketTranslator.translateOutbound(mobEffectV113(0), LegacyProtocol.PROTOCOL_110, false).suppress());
    }

    private static byte[] mobEffectV113(int effectId) {
        // MobEffect v113 real (Nukkit-MOT MobEffectPacket.java:44-51, protocolo 113 < v1_20_70:
        // sin tick): pid + eid UVarInt + eventId byte + effectId/amplifier VarInt + particles bool
        // + duration VarInt
        LegacyStream effectV113 = LegacyStream.writer(24);
        effectV113.writeByte(ProtocolInfo_v113.MOB_EFFECT_PACKET & 0xFF);
        effectV113.writeUnsignedVarInt(77);
        effectV113.writeByte(1);            // EVENT_ADD
        effectV113.writeVarInt(effectId);
        effectV113.writeVarInt(0);
        effectV113.writeBoolean(true);
        effectV113.writeVarInt(200);
        return effectV113.toByteArray();
    }

    @Test
    void translatesSneakEchoSetEntityDataWithHighEntityIdsFor015() {
        // Bug del Shift en 80-84: Nukkit-MOT v113 escribe el eid de SetEntityData con
        // putEntityUniqueId -> putUnsignedVarInt (DataPacket.java:151-158, protocol < v1_2_0=137;
        // SetEntityDataPacket.java:38-39). translateSetEntityDataOutbound lo leia con VarLong
        // zigzag, que solo coincide con unsigned para eids < 64: con eids >= 64 (habitual en
        // partidas reales, el eid crece con cada drop/mob) el cliente 0.15.x recibia un eid
        // inexistente y descartaba el paquete, por lo que el sneak nunca se visualizaba.
        // Mismo patron ya documentado en translateRemoveEntityOutbound para RemoveEntity.
        long playerEid = 100L; // >= 64: zigzag(100)=200 != 100

        // 1) SetEntityDataPacket v113 real: pid 0x27 + UnsignedVarInt(eid) + metadata
        //    (Binary.writeMetadata: UnsignedVarInt(count) + key/type VarInt + VarLong flags)
        cn.nukkit.utils.BinaryStream v113 = new cn.nukkit.utils.BinaryStream();
        v113.putByte((byte) (ProtocolInfo_v113.SET_ENTITY_DATA_PACKET & 0xFF));
        v113.putUnsignedVarInt(playerEid);
        v113.putUnsignedVarInt(1);                       // 1 entrada
        v113.putUnsignedVarInt(0);                       // key DATA_FLAGS
        v113.putUnsignedVarInt(LegacyContentMapper.DATA_TYPE_LONG);             // type LONG (Entity.DATA_TYPE_LONG=7)
        v113.putVarLong(0b10L);                          // bit 1 = DATA_FLAG_SNEAKING

        // 2) Traducir a 0.15.x (protocolo 84) y verificar el eid y el bit de sneak
        PacketTranslator.OutboundResult out84 = PacketTranslator.translateOutbound(
                v113.getBuffer(), LegacyProtocol.PROTOCOL_84, false
        );
        assertFalse(out84.suppress());
        LegacyStream r84 = LegacyStream.reader(out84.legacyPacket());
        assertEquals(0x22, r84.readByte());              // SET_ENTITY_DATA_PACKET 0.15.x (PacketsIds81)
        assertEquals(playerEid, r84.readLongBE());       // LongBE con el VALOR real del eid
        int header = r84.readByte();                     // (type BYTE(0) << 5) | key 0
        assertEquals(0, header);
        assertEquals(0x02, r84.readByte());              // bit 1 = SNEAKING (ActorMetadataList PROTOCOL_81)
        // A continuacion el traductor deriva SHOW_NAMETAG(key 3), SILENT(key 4) y NO_AI(key 15)
        // de los flags (LegacyContentMapper.translateMetadataToPre90) y termina con 0x7F:
        // recorrer las entradas derivadas hasta el terminador sin EOF.
        int entries = 1;
        int terminator = -1;
        while (!r84.eof()) {
            int h = r84.readByte();
            if (h == 0x7F) {
                terminator = h;
                break;
            }
            int t = (h >> 5) & 0x07;
            switch (t) {
                case 0 -> r84.readByte();
                case 1 -> r84.readShortLE();
                case 2, 3 -> r84.readIntLE();
                case 4 -> r84.readBytes(r84.readShortBE());
                case 6 -> { r84.readIntLE(); r84.readIntLE(); r84.readIntLE(); }
                case 7 -> r84.readLongLE();
                default -> r84.readByte();
            }
            entries++;
        }
        assertEquals(0x7F, terminator);                  // terminador metadata 0.15.x
        assertTrue(entries >= 1 && entries <= 5);        // DATA_FLAGS + derivadas (3,4,15)

        // 3) Sin regresion en 90..112 (layout UnsignedVarLong/long flags intacto)
        for (int proto : new int[]{90, 91, 92, 105, 110, 112}) {
            PacketTranslator.OutboundResult out = PacketTranslator.translateOutbound(
                    v113.getBuffer(), LegacyProtocol.resolve(proto), false
            );
            assertFalse(out.suppress());
            LegacyStream r = LegacyStream.reader(out.legacyPacket());
            r.readByte();                                 // pid (varia por familia)
            assertEquals(playerEid, r.readUnsignedVarLong());
        }

        // 4) El mismo eid debe matchear el registro de entidades del estado de sesion
        //    (getEntity alimentado por AddPlayer/AddEntity con el id unsigned real)
        SessionProtocolRegistry registry = new SessionProtocolRegistry();
        SessionProtocolRegistry.LegacySessionState state = registry.registerOrUpdate(
                new DummyChannelId("sneak-echo-test"), null, 84, "Jugador"
        );
        state.setPlayerEntityId(playerEid);
        state.trackEntity(playerEid, 0, 0);
        PacketTranslator.OutboundResult outTracked = PacketTranslator.translateOutbound(
                v113.getBuffer(), LegacyProtocol.PROTOCOL_84, false, registry, state
        );
        assertFalse(outTracked.suppress());
        // La traduccion con estado debe producir el mismo eid (sin corromper por lookup fallido)
        LegacyStream rt = LegacyStream.reader(outTracked.legacyPacket());
        rt.readByte();
        assertEquals(playerEid, rt.readLongBE());
    }

    @Test
    void preservesEndStoneAndEndPortalBlocksForLegacyProtocols() {
        // End Stone (121), End Portal Frame (120, incluido bit del ojo 0x04) y End Portal (119)
        // existen desde 0.15.x (MySoft creative_items/81-82 incluye 120 y 121; Nukkit-Retro
        // Block.java:475-477 registra 119/120/121): no deben reemplazarse ni filtrarse.
        for (LegacyProtocol dst : new LegacyProtocol[]{LegacyProtocol.PROTOCOL_81, LegacyProtocol.PROTOCOL_84, LegacyProtocol.PROTOCOL_91}) {
            // mapBlockAndMeta: el ID debe preservarse tal cual (ni cobblestone/sandstone/portal)
            assertEquals((121 << 8), LegacyContentMapper.mapBlockAndMeta(121, 0, dst));            // END_STONE
            assertEquals((120 << 8) | 0x04, LegacyContentMapper.mapBlockAndMeta(120, 0x04, dst)); // FRAME con ojo
            assertEquals((120 << 8) | 0x03, LegacyContentMapper.mapBlockAndMeta(120, 0x03, dst)); // FRAME orientacion
        }
        // En 1.0.x+ (92..113) siguen intactos (nunca hubo remapeo alli)
        assertEquals((121 << 8), LegacyContentMapper.mapBlockAndMeta(121, 0, LegacyProtocol.PROTOCOL_92));
        assertEquals((119 << 8), LegacyContentMapper.mapBlockAndMeta(119, 0, LegacyProtocol.PROTOCOL_105));
        assertEquals((120 << 8) | 0x04, LegacyContentMapper.mapBlockAndMeta(120, 0x04, LegacyProtocol.PROTOCOL_113));

        // mapSlotOutbound inventario normal: End Stone y Frame se conservan (no AIR) en 0.15.x
        LegacyStream.LegacySlot endStoneItem = LegacyContentMapper.mapSlotOutbound(
                new LegacyStream.LegacySlot(121, 0, 1, new byte[0], new String[0], new String[0]),
                LegacyProtocol.PROTOCOL_84, false);
        assertEquals(121, endStoneItem.id());
        LegacyStream.LegacySlot frameItem = LegacyContentMapper.mapSlotOutbound(
                new LegacyStream.LegacySlot(120, 0x04, 1, new byte[0], new String[0], new String[0]),
                LegacyProtocol.PROTOCOL_84, false);
        assertEquals(120, frameItem.id());
        assertEquals(0x04, frameItem.meta());

        // Creativo: End Stone, Frame y End Portal (119) se conservan (no null, no filtrados);
        // 120/121 estan en el creativo vanilla de 0.15.x (MySoft creative_items/81) y 119 es un
        // bloque valido del cliente 0.15.x (Nukkit-Retro Item.java:212 / Block.java:475)
        assertNotNull(LegacyContentMapper.mapSlotOutbound(
                new LegacyStream.LegacySlot(121, 0, 1, new byte[0], new String[0], new String[0]),
                LegacyProtocol.PROTOCOL_81, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(
                new LegacyStream.LegacySlot(120, 0, 1, new byte[0], new String[0], new String[0]),
                LegacyProtocol.PROTOCOL_81, true));

        // Control negativo: Dragon Egg (122, bloque 1.0.x) SIGUE degradandose a obsidiana en < 92
        assertEquals((49 << 8), LegacyContentMapper.mapBlockAndMeta(122, 0, LegacyProtocol.PROTOCOL_84));
        assertNull(LegacyContentMapper.mapSlotOutbound(
                new LegacyStream.LegacySlot(122, 0, 1, new byte[0], new String[0], new String[0]),
                LegacyProtocol.PROTOCOL_84, true));
    }

    private record DummyChannelId(String id) implements ChannelId {
        @Override
        public String asShortText() {
            return id;
        }

        @Override
        public String asLongText() {
            return id;
        }

        @Override
        public int compareTo(ChannelId o) {
            return asLongText().compareTo(o.asLongText());
        }
    }
}

