package org.CreadoresProgram.Craftsmine;

import cn.nukkit.network.protocol.v113.ProtocolInfo_v113;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import org.CreadoresProgram.Craftsmine.protocol.LegacyProtocol;
import org.CreadoresProgram.Craftsmine.protocol.LegacyStream;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator;

/**
 * Verificacion independiente de translateUpdateAttributesOutbound contra el
 * formato binario de MySoft (NetworkBinaryStream::putAttributeList) para P80-112.
 */
class UpdateAttributesOutboundTest {

    /**
     * Simula exactamente lo que emite Nukkit-MOT P113 (UpdateAttributesPacket.encode,
     * rama protocol < v1_2_0): putEntityUniqueId(VarLong zigzag) + putUnsignedVarInt(count)
     * + por atributo: putLFloat(min), putLFloat(max), putLFloat(value), putLFloat(default), putString(name).
     */
    private byte[] buildV113Packet(long entityId, Object[][] attrs) {
        LegacyStream w = LegacyStream.writer(128);
        w.writeByte(ProtocolInfo_v113.UPDATE_ATTRIBUTES_PACKET & 0xFF);
        w.writeEntityUniqueId(LegacyProtocol.PROTOCOL_113, entityId); // VarLong zigzag (putEntityUniqueId)
        w.writeUnsignedVarInt(attrs.length);
        for (Object[] a : attrs) {
            w.writeFloatLE((float) a[0]); // min (LFloat)
            w.writeFloatLE((float) a[1]); // max (LFloat)
            w.writeFloatLE((float) a[2]); // current (LFloat)
            w.writeFloatLE((float) a[3]); // default (LFloat)
            w.writeString(LegacyProtocol.PROTOCOL_113, (String) a[4]);
        }
        return w.toByteArray();
    }

    /**
     * P81-89 (MySoft getAttributeList < 90): getShort(count) + getFloat BE min/max/current
     * + getShortString(name). Entity id: getLong BE (putEntityUniqueId < 90).
     */
    @Test
    void testPre90FormatMatchesMySoft() {
        byte[] v113 = buildV113Packet(5L, new Object[][]{
                {0f, 20f, 14.5f, 20f, "minecraft:health"},
                {0f, 20f, 20f, 20f, "minecraft:player.hunger"}
        });

        PacketTranslator.OutboundResult res =
                PacketTranslator.translateOutbound(v113, LegacyProtocol.PROTOCOL_84, true);
        assertFalse(res.suppress());
        assertNotNull(res.legacyPacket());

        LegacyStream r = LegacyStream.reader(res.legacyPacket());
        // ID legado 0x1A (PacketsIds81 UPDATE_ATTRIBUTES_PACKET)
        assertEquals(0x1A, r.readByte());
        // EntityUniqueId <90 = Long BE
        assertEquals(5L, r.readEntityUniqueId(LegacyProtocol.PROTOCOL_84));
        // count = Short BE
        assertEquals(2, r.readShortBE());
        // atributo 1: floats BE sin default, nombre corto
        assertEquals(0f, r.readFloatBE());
        assertEquals(20f, r.readFloatBE());
        assertEquals(14.5f, r.readFloatBE());
        assertEquals("generic.health", r.readString(LegacyProtocol.PROTOCOL_84));
        // atributo 2 (filtrado: luck no existe en <90 -> aqui hunger si existe)
        assertEquals(0f, r.readFloatBE());
        assertEquals(20f, r.readFloatBE());
        assertEquals(20f, r.readFloatBE());
        assertEquals("player.hunger", r.readString(LegacyProtocol.PROTOCOL_84));
        assertEquals(0, r.remaining());
    }

    /**
     * P90-109 (MySoft >= 90): getUnsignedVarInt(count) + getLFloat min/max/current/default
     * + getString(name). Entity id: getEntityRuntimeId >= 90 = UnsignedVarLong.
     */
    @Test
    void test90Through109FormatMatchesMySoft() {
        byte[] v113 = buildV113Packet(300L, new Object[][]{
                {0f, 20f, 14.5f, 20f, "minecraft:health"},
                {0f, 1024f, 0f, 0f, "minecraft:luck"} // luck existe en P90+ (AttributeNameIds PROTOCOL_90)
        });

        for (LegacyProtocol dst : new LegacyProtocol[]{
                LegacyProtocol.PROTOCOL_90, LegacyProtocol.PROTOCOL_91, LegacyProtocol.PROTOCOL_100,
                LegacyProtocol.PROTOCOL_105, LegacyProtocol.PROTOCOL_107}) {
            PacketTranslator.OutboundResult res = PacketTranslator.translateOutbound(v113, dst, true);
            assertFalse(res.suppress(), "suprimido para " + dst);
            assertNotNull(res.legacyPacket(), "null para " + dst);

            LegacyStream r = LegacyStream.reader(res.legacyPacket());
            // ID legado: 0x1F para V0_16/V1_0_x (PacketsIds91/100/105)
            assertEquals(0x1F, r.readByte(), "pid para " + dst);
            // EntityRuntimeId >= 90 = UnsignedVarLong
            assertEquals(300L, r.readEntityRuntimeId(dst));
            assertEquals(2, r.readUnsignedVarInt());
            // atributo 1: floats LE con default
            assertEquals(0f, r.readFloatLE());
            assertEquals(20f, r.readFloatLE());
            assertEquals(14.5f, r.readFloatLE());
            assertEquals(20f, r.readFloatLE());
            assertEquals("minecraft:health", r.readString(dst));
            // atributo 2 (pass-through minecraft:luck)
            assertEquals(0f, r.readFloatLE());
            assertEquals(1024f, r.readFloatLE());
            assertEquals(0f, r.readFloatLE());
            assertEquals(0f, r.readFloatLE());
            assertEquals("minecraft:luck", r.readString(dst));
            assertEquals(0, r.remaining(), "bytes restantes para " + dst);
        }
    }

    /**
     * P110-112 (V1_1_0_EARLY, IDs v110): UPDATE_ATTRIBUTES = 0x1E, mismo formato >= 90.
     */
    @Test
    void test110Through112FormatMatchesMySoft() {
        byte[] v113 = buildV113Packet(1L, new Object[][]{
                {0f, 20f, 20f, 20f, "minecraft:health"}
        });

        for (LegacyProtocol dst : new LegacyProtocol[]{
                LegacyProtocol.PROTOCOL_110, LegacyProtocol.PROTOCOL_111, LegacyProtocol.PROTOCOL_112}) {
            PacketTranslator.OutboundResult res = PacketTranslator.translateOutbound(v113, dst, true);
            assertFalse(res.suppress());
            assertNotNull(res.legacyPacket());

            LegacyStream r = LegacyStream.reader(res.legacyPacket());
            assertEquals(0x1E, r.readByte(), "pid para " + dst); // PacketsIds110
            assertEquals(1L, r.readEntityRuntimeId(dst));
            assertEquals(1, r.readUnsignedVarInt());
            assertEquals(0f, r.readFloatLE());
            assertEquals(20f, r.readFloatLE());
            assertEquals(20f, r.readFloatLE());
            assertEquals(20f, r.readFloatLE());
            assertEquals("minecraft:health", r.readString(dst));
            assertEquals(0, r.remaining());
        }
    }

    /**
     * Atributos desconocidos para <90 (p.ej. minecraft:luck, minecraft:fall_damage)
     * deben filtrarse y NO romper el stream para los atributos restantes.
     */
    @Test
    void testUnknownAttributesFilteredPre90() {
        byte[] v113 = buildV113Packet(9L, new Object[][]{
                {0f, 1024f, 3f, 0f, "minecraft:luck"},           // no existe en P81
                {0f, 20f, 15f, 20f, "minecraft:health"},          // existe
                {0f, 340282346638528859811704183484516925440f, 1f, 1f, "minecraft:fall_damage"} // no existe en P81
        });

        PacketTranslator.OutboundResult res =
                PacketTranslator.translateOutbound(v113, LegacyProtocol.PROTOCOL_81, true);
        assertFalse(res.suppress());
        LegacyStream r = LegacyStream.reader(res.legacyPacket());
        assertEquals(0x1A, r.readByte());
        assertEquals(9L, r.readEntityUniqueId(LegacyProtocol.PROTOCOL_81));
        assertEquals(1, r.readShortBE()); // solo health sobrevive
        assertEquals(0f, r.readFloatBE());
        assertEquals(20f, r.readFloatBE());
        assertEquals(15f, r.readFloatBE());
        assertEquals("generic.health", r.readString(LegacyProtocol.PROTOCOL_81));
        assertEquals(0, r.remaining());
    }
}
