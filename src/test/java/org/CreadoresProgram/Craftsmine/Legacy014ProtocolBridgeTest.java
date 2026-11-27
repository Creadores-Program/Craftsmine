package org.CreadoresProgram.Craftsmine;

import cn.nukkit.nbt.NBTIO;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.utils.BinaryStream;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cn.nukkit.network.protocol.TextPacket;
import cn.nukkit.network.protocol.v113.ProtocolInfo_v113;
import java.util.Base64;
import io.netty.channel.ChannelId;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry.LegacySessionState;
import org.CreadoresProgram.Craftsmine.protocol.BatchCodec;
import org.CreadoresProgram.Craftsmine.protocol.ChunkTranslator;
import org.CreadoresProgram.Craftsmine.protocol.LegacyContentMapper;
import org.CreadoresProgram.Craftsmine.protocol.LegacyProtocol;
import org.CreadoresProgram.Craftsmine.protocol.LegacyStream;
import org.CreadoresProgram.Craftsmine.protocol.PacketIdMapper;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class Legacy014ProtocolBridgeTest {

    private static final ChannelId DUMMY_CHANNEL = new ChannelId() {
        @Override public String asShortText() { return "014-test"; }
        @Override public String asLongText() { return "014-test-long"; }
        @Override public int compareTo(ChannelId o) { return asLongText().compareTo(o.asLongText()); }
    };

    @Test
    void testProtocolRegistrationAndIsolationFrom80Plus() {
        int[] p014 = {41, 42, 43, 44, 45, 46, 60, 70};
        for (int p : p014) {
            LegacyProtocol lp = LegacyProtocol.resolve(p);
            assertEquals(p, lp.getProtocol());
            assertEquals(6, lp.getRakNetVersion());
            assertEquals(LegacyProtocol.Family.V0_14, lp.getFamily());
            assertTrue(lp.isBefore80());
            assertTrue(lp.isBefore90());
            assertTrue(lp.needsTranslation());
        }
        assertFalse(LegacyProtocol.PROTOCOL_80.isBefore80());
        assertFalse(LegacyProtocol.PROTOCOL_84.isBefore80());
        assertFalse(LegacyProtocol.PROTOCOL_112.isBefore80());
        assertEquals(0x8F, PacketIdMapper.toLegacy(LegacyProtocol.PROTOCOL_70, ProtocolInfo_v113.LOGIN_PACKET & 0xFF));
        assertEquals(0x01, PacketIdMapper.toLegacy(LegacyProtocol.PROTOCOL_80, ProtocolInfo_v113.LOGIN_PACKET & 0xFF));
    }

    @Test
    void testBatchCodecPre80Framing() throws Exception {
        byte[] sampleSub = new byte[]{0x8F - 256, 0x01, 0x02, 0x03};
        List<byte[]> unbatchedPreLogin = BatchCodec.decodeRakNetBatchBody(sampleSub, 6, null, 4 * 1024 * 1024);
        assertEquals(1, unbatchedPreLogin.size());
        assertArrayEquals(sampleSub, unbatchedPreLogin.get(0));

        // Outbound single small packet (< 256B) omits 0x92 wrapper
        byte[] smallBody = BatchCodec.encodeRakNetBatchBody(LegacyProtocol.PROTOCOL_70, List.of(sampleSub), 7);
        assertArrayEquals(sampleSub, smallBody);

        // Outbound large packet (>= 256B) wraps in 0x92 + intBE len + zlib
        byte[] largeSub = new byte[320];
        largeSub[0] = (byte) 0x95;
        byte[] batchedBody = BatchCodec.encodeRakNetBatchBody(LegacyProtocol.PROTOCOL_41, List.of(largeSub), 7);
        assertEquals(0x92, batchedBody[0] & 0xFF);
        List<byte[]> decoded = BatchCodec.decodeRakNetBatchBody(batchedBody, 6, LegacyProtocol.PROTOCOL_41, 4 * 1024 * 1024);
        assertEquals(1, decoded.size());
        assertArrayEquals(largeSub, decoded.get(0));
    }

    @Test
    void testBinaryLogin014NormalizationAndMotDecode() throws Exception {
        UUID uuid = UUID.fromString("12345678-1234-4321-8765-123456789abc");
        byte[] rawSkin = new byte[8192];
        for (int i = 0; i < rawSkin.length; i += 4) {
            rawSkin[i] = 0x10;
            rawSkin[i + 3] = (byte) 0xFF;
        }

        for (int proto : new int[]{41, 70}) {
            LegacyStream w = LegacyStream.writer(9000);
            w.writeByte(0x8F);
            w.writeString(LegacyProtocol.PROTOCOL_41, "Steve<014>");
            w.writeIntBE(proto);
            w.writeIntBE(proto);
            w.writeLongBE(987654321L);
            w.writeUUID(LegacyProtocol.PROTOCOL_41, uuid);
            w.writeString(LegacyProtocol.PROTOCOL_41, "127.0.0.1:19132");
            w.writeString(LegacyProtocol.PROTOCOL_41, "secret");
            w.writeString(LegacyProtocol.PROTOCOL_41, "Standard_Custom");
            w.writeShortBE(rawSkin.length);
            w.writeBytes(rawSkin);

            PacketTranslator.LoginSniffResult sniff = PacketTranslator.tryNormalizeLogin(w.toByteArray(), 41, 112);
            assertNotNull(sniff);
            assertEquals(proto, sniff.rawProtocol());
            assertEquals("Steve<014>", sniff.username());

            byte[] v113Raw = sniff.canonicalV113LoginPacket();
            assertEquals(0x01, v113Raw[0] & 0xFF);

            BinaryStream nukkitStream = new BinaryStream(v113Raw, 1);
            assertEquals(113, nukkitStream.getInt());
            assertEquals(0, nukkitStream.getByte());
            byte[] connPayload = nukkitStream.getByteArray();
            BinaryStream connStream = new BinaryStream(connPayload, 0);
            byte[] chainBytes = connStream.get(connStream.getLInt());
            byte[] skinJwtBytes = connStream.get(connStream.getLInt());

            JsonObject chainRoot = JsonParser.parseString(new String(chainBytes, StandardCharsets.UTF_8)).getAsJsonObject();
            String chainJwt = chainRoot.getAsJsonArray("chain").get(0).getAsString();
            String[] chainParts = chainJwt.split("\\.");
            assertEquals(3, chainParts.length);
            assertFalse(chainJwt.contains("="));
            JsonObject chainClaims = JsonParser.parseString(new String(Base64.getDecoder().decode(chainParts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject extraData = chainClaims.getAsJsonObject("extraData");
            assertEquals("Steve<014>", extraData.get("displayName").getAsString());
            assertEquals(uuid.toString(), extraData.get("identity").getAsString());
            // Nukkit-MOT ChainValidationResult.createLegacyClaims exige "identityPublicKey" como String
            // en el nodo raiz (childAsType lanza IllegalStateException si falta -> kick invalidName).
            assertTrue(chainClaims.has("identityPublicKey"));
            assertTrue(chainClaims.get("identityPublicKey").isJsonPrimitive());
            assertEquals("", chainClaims.get("identityPublicKey").getAsString());

            String skinJwt = new String(skinJwtBytes, StandardCharsets.UTF_8);
            String[] skinParts = skinJwt.split("\\.");
            assertEquals(3, skinParts.length);
            assertFalse(skinJwt.contains("="));
            JsonObject skinClaims = JsonParser.parseString(new String(Base64.getDecoder().decode(skinParts[1]), StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(987654321L, skinClaims.get("ClientRandomId").getAsLong());
            assertEquals("127.0.0.1:19132", skinClaims.get("ServerAddress").getAsString());
            assertEquals("Standard_Custom", skinClaims.get("SkinId").getAsString());
        }
    }

    @Test
    void testAddPlayerRemovePlayerAndMovePackets() {
        LegacySessionState state = new LegacySessionState(DUMMY_CHANNEL, null, 70);
        UUID playerUuid = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

        // AddPlayer outbound -> 0x96 con UUID BE
        LegacyStream addIn = LegacyStream.writer(64);
        addIn.writeByte(ProtocolInfo_v113.ADD_PLAYER_PACKET & 0xFF);
        addIn.writeUUID(playerUuid);
        addIn.writeString(LegacyProtocol.PROTOCOL_113, "Alex014");
        addIn.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, 42L);
        addIn.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 42L);
        addIn.writeVector3(LegacyProtocol.PROTOCOL_113, 10f, 64f, -5f);
        addIn.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 0f, 0f);
        addIn.writeFloatLE(0f);
        addIn.writeFloatLE(90f);
        addIn.writeFloatLE(90f);
        addIn.writeSlot(LegacyProtocol.PROTOCOL_113, LegacyStream.LegacySlot.AIR);
        addIn.writeUnsignedVarInt(0);

        PacketTranslator.OutboundResult addOut = PacketTranslator.translateOutbound(addIn.toByteArray(), LegacyProtocol.PROTOCOL_70, true, null, state);
        assertFalse(addOut.suppress());
        assertEquals(0x96, addOut.legacyPacket()[0] & 0xFF);
        LegacyStream addRead = LegacyStream.reader(addOut.legacyPacket(), 1, addOut.legacyPacket().length - 1);
        assertEquals(playerUuid, addRead.readUUID(LegacyProtocol.PROTOCOL_70));

        // RemoveEntity sobre ese player -> 0x97 RemovePlayerPacket
        LegacyStream remIn = LegacyStream.writer(8);
        remIn.writeByte(ProtocolInfo_v113.REMOVE_ENTITY_PACKET & 0xFF);
        remIn.writeUnsignedVarLong(42L);
        PacketTranslator.OutboundResult remPlayerOut = PacketTranslator.translateOutbound(remIn.toByteArray(), LegacyProtocol.PROTOCOL_70, true, null, state);
        assertEquals(0x97, remPlayerOut.legacyPacket()[0] & 0xFF);
        LegacyStream remRead = LegacyStream.reader(remPlayerOut.legacyPacket(), 1, remPlayerOut.legacyPacket().length - 1);
        assertEquals(42L, remRead.readLongBE());
        assertEquals(playerUuid, remRead.readUUID(LegacyProtocol.PROTOCOL_70));

        // RemoveEntity sobre entidad no jugador -> 0x99 RemoveEntityPacket
        LegacyStream remMob = LegacyStream.writer(8);
        remMob.writeByte(ProtocolInfo_v113.REMOVE_ENTITY_PACKET & 0xFF);
        remMob.writeUnsignedVarLong(99L);
        PacketTranslator.OutboundResult remMobOut = PacketTranslator.translateOutbound(remMob.toByteArray(), LegacyProtocol.PROTOCOL_70, true, null, state);
        assertEquals(0x99, remMobOut.legacyPacket()[0] & 0xFF);

        // MoveEntity -> 0x9C con entityCount=1 + floatBE yaw/headYaw/pitch
        LegacyStream mvEnt = LegacyStream.writer(32);
        mvEnt.writeByte(ProtocolInfo_v113.MOVE_ENTITY_PACKET & 0xFF);
        mvEnt.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 99L);
        mvEnt.writeVector3(LegacyProtocol.PROTOCOL_113, 1f, 2f, 3f);
        mvEnt.writeByte(64);  // 90 deg pitch
        mvEnt.writeByte(128); // 180 deg yaw
        mvEnt.writeByte(128); // 180 deg headYaw
        mvEnt.writeByte(1);
        PacketTranslator.OutboundResult mvEntOut = PacketTranslator.translateOutbound(mvEnt.toByteArray(), LegacyProtocol.PROTOCOL_45, true);
        assertEquals(0x9C, mvEntOut.legacyPacket()[0] & 0xFF);
        LegacyStream mvRead = LegacyStream.reader(mvEntOut.legacyPacket(), 1, mvEntOut.legacyPacket().length - 1);
        assertEquals(1, mvRead.readIntBE());
        assertEquals(99L, mvRead.readLongBE());
        mvRead.readVector3(LegacyProtocol.PROTOCOL_45);
        assertEquals(180f, mvRead.readFloatBE(), 0.5f);
        assertEquals(180f, mvRead.readFloatBE(), 0.5f);
        assertEquals(90f, mvRead.readFloatBE(), 0.5f);

        // MovePlayer -> mode 2->1 (RESET) y UpdateBlock recordCount=1
        LegacyStream mvPly = LegacyStream.writer(48);
        mvPly.writeByte(ProtocolInfo_v113.MOVE_PLAYER_PACKET & 0xFF);
        mvPly.writeEntityRuntimeId(LegacyProtocol.PROTOCOL_113, 42L);
        mvPly.writeVector3(LegacyProtocol.PROTOCOL_113, 0f, 64f, 0f);
        mvPly.writeFloatLE(0f);
        mvPly.writeFloatLE(0f);
        mvPly.writeFloatLE(0f);
        mvPly.writeByte(2); // MODE_TELEPORT
        mvPly.writeBoolean(true);
        PacketTranslator.OutboundResult mvPlyOut = PacketTranslator.translateOutbound(mvPly.toByteArray(), LegacyProtocol.PROTOCOL_60, true);
        assertEquals(0x9D, mvPlyOut.legacyPacket()[0] & 0xFF);
        LegacyStream mvPlyRead = LegacyStream.reader(mvPlyOut.legacyPacket(), 1, mvPlyOut.legacyPacket().length - 1);
        mvPlyRead.readLongBE();
        mvPlyRead.readVector3(LegacyProtocol.PROTOCOL_60);
        mvPlyRead.readFloatBE(); mvPlyRead.readFloatBE(); mvPlyRead.readFloatBE();
        assertEquals(1, mvPlyRead.readByte());
    }

    @Test
    void testInbound014VariantsTextRemoveBlockUseItemPlayerInput() {
        // TextPopup (tipo 3 con 2 strings) y Tip (tipo 4 -> v113 TYPE_TIP=5)
        LegacyStream txtIn = LegacyStream.writer(32);
        txtIn.writeByte(0x93);
        txtIn.writeByte(4);
        txtIn.writeString(LegacyProtocol.PROTOCOL_41, "");
        txtIn.writeString(LegacyProtocol.PROTOCOL_41, "Hola 0.14");
        PacketTranslator.InboundResult txtRes = PacketTranslator.translateInbound(txtIn.toByteArray(), LegacyProtocol.PROTOCOL_41);
        assertFalse(txtRes.suppress());
        assertEquals(TextPacket.TYPE_TIP, txtRes.canonicalPacket()[1] & 0xFF);

        // RemoveBlock 12-byte Nukkit-Retro
        LegacyStream rbRetro = LegacyStream.writer(16);
        rbRetro.writeByte(0x9E);
        rbRetro.writeIntBE(12);
        rbRetro.writeIntBE(64);
        rbRetro.writeIntBE(-8);
        PacketTranslator.InboundResult rbRes = PacketTranslator.translateInbound(rbRetro.toByteArray(), LegacyProtocol.PROTOCOL_46);
        assertEquals(ProtocolInfo_v113.REMOVE_BLOCK_PACKET & 0xFF, rbRes.canonicalPacket()[0] & 0xFF);
        LegacyStream rbRead = LegacyStream.reader(rbRes.canonicalPacket(), 1, rbRes.canonicalPacket().length - 1);
        assertArrayEquals(new int[]{12, 64, -8}, rbRead.readBlockPosition(LegacyProtocol.PROTOCOL_113));

        // UseItem sin intBE slot (VeoZax p70)
        LegacyStream uiVeo = LegacyStream.writer(64);
        uiVeo.writeByte(0xAA);
        uiVeo.writeIntBE(1); uiVeo.writeIntBE(64); uiVeo.writeIntBE(2);
        uiVeo.writeByte(1);
        uiVeo.writeVector3(LegacyProtocol.PROTOCOL_70, 0.5f, 1.0f, 0.5f);
        uiVeo.writeVector3(LegacyProtocol.PROTOCOL_70, 1.5f, 66.0f, 2.5f);
        uiVeo.writeSlot(LegacyProtocol.PROTOCOL_70, new LegacyStream.LegacySlot(1, 0, 1, new byte[0], new String[0], new String[0]));
        PacketTranslator.InboundResult uiRes = PacketTranslator.translateInbound(uiVeo.toByteArray(), LegacyProtocol.PROTOCOL_70);
        assertFalse(uiRes.suppress());
        assertEquals(ProtocolInfo_v113.USE_ITEM_PACKET & 0xFF, uiRes.canonicalPacket()[0] & 0xFF);

        // PlayerInput 1-byte bitflags
        LegacyStream piIn = LegacyStream.writer(16);
        piIn.writeByte(0xBE);
        piIn.writeFloatBE(0.5f);
        piIn.writeFloatBE(-0.25f);
        piIn.writeByte(0x80 | 0x40);
        PacketTranslator.InboundResult piRes = PacketTranslator.translateInbound(piIn.toByteArray(), LegacyProtocol.PROTOCOL_70);
        LegacyStream piRead = LegacyStream.reader(piRes.canonicalPacket(), 1, piRes.canonicalPacket().length - 1);
        assertEquals(0.5f, piRead.readFloatLE(), 0.001f);
        assertEquals(-0.25f, piRead.readFloatLE(), 0.001f);
        assertTrue(piRead.readBoolean());
        assertTrue(piRead.readBoolean());
    }

    @Test
    void test015PlusFiltersDowngradesCreativeUnionAndChunks() throws IOException {
        // Bloques 0.15+ -> 4:0 / 0:0
        assertEquals((4 << 8), LegacyContentMapper.mapBlockAndMeta(29, 0, LegacyProtocol.PROTOCOL_70));
        assertEquals((4 << 8), LegacyContentMapper.mapBlockAndMeta(33, 2, LegacyProtocol.PROTOCOL_70));
        assertEquals(0, LegacyContentMapper.mapBlockAndMeta(34, 0, LegacyProtocol.PROTOCOL_41));
        assertEquals((4 << 8), LegacyContentMapper.mapBlockAndMeta(251, 0, LegacyProtocol.PROTOCOL_70));
        // En 0.15.x (80) deben seguir siendo validos!
        assertEquals((29 << 8), LegacyContentMapper.mapBlockAndMeta(29, 0, LegacyProtocol.PROTOCOL_80));

        // Items 0.15+ filtrados < 80
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(262, 6, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_70, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(420, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_70, false).id());
        assertEquals(0, LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(423, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_70, false).id());
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(262, 6, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_70, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(420, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_70, true));
        assertNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(423, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_70, true));
        assertNotNull(LegacyContentMapper.mapSlotOutbound(new LegacyStream.LegacySlot(420, 0, 1, new byte[0], new String[0], new String[0]), LegacyProtocol.PROTOCOL_80, false));

        // Entidades 0.15+ degradadas < 80
        assertEquals(11, LegacyContentMapper.mapEntityTypeOutbound(23, LegacyProtocol.PROTOCOL_70));
        assertEquals(34, LegacyContentMapper.mapEntityTypeOutbound(48, LegacyProtocol.PROTOCOL_70));
        assertEquals(32, LegacyContentMapper.mapEntityTypeOutbound(47, LegacyProtocol.PROTOCOL_70));
        assertNull(LegacyContentMapper.mapAttributeNameOutbound("minecraft:horse.jump_strength", LegacyProtocol.PROTOCOL_70));
        assertEquals("horse.jumpStrength", LegacyContentMapper.mapAttributeNameOutbound("minecraft:horse.jump_strength", LegacyProtocol.PROTOCOL_80));

        // Creative union en ContainerSetContent (0x79) -> 518 items sin pistones/etiquetas
        LegacyStream creativeIn = LegacyStream.writer(64);
        creativeIn.writeByte(ProtocolInfo_v113.CONTAINER_SET_CONTENT_PACKET & 0xFF);
        creativeIn.writeUnsignedVarInt(0x79);
        creativeIn.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, -1L);
        creativeIn.writeUnsignedVarInt(1);
        creativeIn.writeSlot(LegacyProtocol.PROTOCOL_113, new LegacyStream.LegacySlot(1, 0, 1, new byte[0], new String[0], new String[0]));
        creativeIn.writeUnsignedVarInt(0);

        PacketTranslator.OutboundResult creativeOut = PacketTranslator.translateOutbound(creativeIn.toByteArray(), LegacyProtocol.PROTOCOL_70, true);
        assertFalse(creativeOut.suppress());
        assertEquals(0xB9, creativeOut.legacyPacket()[0] & 0xFF);
        LegacyStream cRead = LegacyStream.reader(creativeOut.legacyPacket(), 1, creativeOut.legacyPacket().length - 1);
        assertEquals(0x79, cRead.readByte());
        int count = cRead.readShortBE();
        assertTrue(count >= 515, "Se esperaban >= 515 items creativos en la union 0.14.x, actual=" + count);
        Set<Integer> keys = new HashSet<>();
        for (int i = 0; i < count; i++) {
            LegacyStream.LegacySlot s = cRead.readSlot(LegacyProtocol.PROTOCOL_70);
            keys.add((s.id() << 16) | (s.meta() & 0xFFFF));
            assertNotEquals(33, s.id());
            assertNotEquals(420, s.id());
            assertNotEquals(421, s.id());
        }
        // Items rescatados VeoZax + variantes pez Nukkit-Retro
        assertTrue(keys.contains(404 << 16));
        assertTrue(keys.contains(389 << 16));
        assertTrue(keys.contains(165 << 16));
        assertTrue(keys.contains((460 << 16) | 1));
        assertTrue(keys.contains((373 << 16) | 5));
        assertTrue(keys.contains((383 << 16) | 45));
        assertTrue(keys.contains((349 << 16) | 1));
        assertTrue(keys.contains((350 << 16) | 1));

        // ChunkTranslator degrada Piston (33) -> Cobblestone (4) y elimina PistonArm tile entity
        LegacyStream subChunk = LegacyStream.writer(10300);
        subChunk.writeByte(1); // 1 subchunk
        subChunk.writeByte(0); // version 0
        byte[] ids = new byte[4096];
        ids[0] = 33; // Sticky/Piston block at (0,0,0)
        subChunk.writeBytes(ids);
        subChunk.writeBytes(new byte[2048]);
        subChunk.writeBytes(new byte[2048]);
        subChunk.writeBytes(new byte[2048]);
        subChunk.writeBytes(new byte[512]);
        subChunk.writeBytes(new byte[256]);
        subChunk.writeByte(0);
        subChunk.writeVarInt(0);
        byte[] pistonNbt = NBTIO.write(new CompoundTag().putString("id", "PistonArm").putInt("x", 0).putInt("y", 0).putInt("z", 0), ByteOrder.LITTLE_ENDIAN, true);
        subChunk.writeBytes(pistonNbt);

        LegacyStream chunkPkt = LegacyStream.writer(11000);
        chunkPkt.writeByte(ProtocolInfo_v113.FULL_CHUNK_DATA_PACKET & 0xFF);
        chunkPkt.writeVarInt(0);
        chunkPkt.writeVarInt(0);
        chunkPkt.writeByteArray(LegacyProtocol.PROTOCOL_113, subChunk.toByteArray());

        PacketTranslator.OutboundResult chunkOut = PacketTranslator.translateOutbound(chunkPkt.toByteArray(), LegacyProtocol.PROTOCOL_70, true);
        assertEquals(0xBF, chunkOut.legacyPacket()[0] & 0xFF);
        LegacyStream chRead = LegacyStream.reader(chunkOut.legacyPacket(), 1, chunkOut.legacyPacket().length - 1);
        assertEquals(0, chRead.readIntBE());
        assertEquals(0, chRead.readIntBE());
        assertEquals(0, chRead.readByte()); // ORDER_COLUMNS
        byte[] colData = chRead.readByteArray(LegacyProtocol.PROTOCOL_70);
        assertEquals(4, colData[0] & 0xFF); // Piston -> Cobblestone (4)
        assertEquals(83204, colData.length); // PistonArm eliminado -> sin bytes de tile entity al final
    }
}
