package org.CreadoresProgram.Craftsmine;

import org.CreadoresProgram.Craftsmine.protocol.BatchCodec;
import org.CreadoresProgram.Craftsmine.protocol.LegacyProtocol;
import org.CreadoresProgram.Craftsmine.protocol.LegacyStream;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator.OutboundResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regresion del flujo Outbound post-login para MCPE 0.14.x (protocolos 41-70).
 *
 * Evidencia usada para fijar los formatos:
 * - Cliente real 0.14.3 (libminecraftpe.so, NetworkProtocolVersion=70):
 *   DisconnectPacket::read = un unico readString (0x4561f0, tail-call 0x456194);
 *   StartGamePacket::read = 4+1+4+4+8+12+12+3 bytes + readString (0x439834);
 *   wireId = 0x8e + tipo interno (getId() - 0x72 & 0xFF, LoginPacket::write 0x36da7c).
 * - Nukkit-Retro (MemoriesOfTime/Nukkit-Retro): DisconnectPacket.encode isBefore0160 =
 *   putString(message) SIN booleano; StartGamePacket isBefore0160 = int32 seed, byte dim,
 *   int32 generator, int32 gamemode, long eid, int32 spawn x3, float x3, 3 bytes, string.
 * - VeoZax Multiversion-014 (p70/p84): DisconnectPacket = putString sin booleano;
 *   batch = 0x92 + int32BE(compLen) + zlib(int32BE(subLen) + sub...), paquete suelto < 256B.
 */
class Legacy014PostLoginOutboundTest {

    private static byte[] v113Packet(int pid, byte[] payload) {
        byte[] pk = new byte[1 + payload.length];
        pk[0] = (byte) pid;
        System.arraycopy(payload, 0, pk, 1, payload.length);
        return pk;
    }

    private static byte[] wrapV113Batch(List<byte[]> subs) {
        // Replica RakNetPlayerSession: 0xfe + zlib(uvarint(len)+pkt...)
        byte[] raw = BatchCodec.packSubPackets(subs, false);
        byte[] compressed = BatchCodec.deflateZlib(raw, 6);
        byte[] frame = new byte[1 + compressed.length];
        frame[0] = (byte) 0xfe;
        System.arraycopy(compressed, 0, frame, 1, compressed.length);
        return frame;
    }

    private static List<byte[]> translateFrame(byte[] frame, LegacyProtocol target) throws DataFormatException {
        // Replica LegacyPipelineHandler.write() paso a paso
        assertEquals(0xfe, frame[0] & 0xFF);
        byte[] compressedV113 = java.util.Arrays.copyOfRange(frame, 1, frame.length);
        byte[] rawV113 = BatchCodec.inflateZlibOrRaw(compressedV113, 0, compressedV113.length, 4 * 1024 * 1024);
        List<byte[]> v113Subs = BatchCodec.unpackSubPackets(rawV113, false);
        List<byte[]> legacySubs = new ArrayList<>();
        for (byte[] sub : v113Subs) {
            if (sub.length == 0) {
                continue;
            }
            OutboundResult r = PacketTranslator.translateOutbound(sub, target, true, null, null);
            if (!r.suppress() && r.legacyPacket() != null) {
                legacySubs.add(r.legacyPacket());
            }
        }
        return legacySubs;
    }

    @Test
    void testDisconnect014IsStringOnlyWithoutHideBoolean() {
        // v113: [bool hide][uvarint len][msg] (Nukkit-MOT DisconnectPacket.encode protocol 113)
        LegacyStream v113 = LegacyStream.writer(48);
        v113.writeBoolean(false);
        v113.writeString(LegacyProtocol.PROTOCOL_113, "disconnectionScreen.notAuthenticated");

        for (LegacyProtocol proto : new LegacyProtocol[]{LegacyProtocol.PROTOCOL_41, LegacyProtocol.PROTOCOL_70}) {
            OutboundResult out = PacketTranslator.translateOutbound(
                    v113Packet(0x05, v113.toByteArray()), proto, true, null, null);
            assertFalse(out.suppress());
            byte[] legacy = out.legacyPacket();
            assertEquals(0x91, legacy[0] & 0xFF, "Disconnect 0.14 = 0x91");
            // Layout real 0.14 (Nukkit-Retro isBefore0160 / VeoZax p70 / cliente 0.14.3):
            // [short BE len][msg] SIN booleano inicial.
            LegacyStream r = LegacyStream.reader(legacy, 1, legacy.length - 1);
            String msg = r.readString(proto);
            assertEquals("disconnectionScreen.notAuthenticated", msg);
            int msgLen = "disconnectionScreen.notAuthenticated".getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertEquals(1 + 2 + msgLen, legacy.length, "sin byte extra: 1 (pid) + 2 (short) + msg");
            assertTrue(r.eof(), "todo el payload consumido, sin bytes residuales");
        }
    }

    @Test
    void testDisconnect014HideScreenStillEmitsValidString() {
        // v113 hideScreen=true: para 0.14 (sin concepto de hide) se envia string vacio valido
        LegacyStream v113 = LegacyStream.writer(8);
        v113.writeBoolean(true);
        OutboundResult out = PacketTranslator.translateOutbound(
                v113Packet(0x05, v113.toByteArray()), LegacyProtocol.PROTOCOL_70, true, null, null);
        byte[] legacy = out.legacyPacket();
        assertEquals(0x91, legacy[0] & 0xFF);
        assertEquals(3, legacy.length, "pid + short(0) para string vacio");
        assertEquals(0, legacy[1]);
        assertEquals(0, legacy[2]);
    }

    @Test
    void testDisconnect015KeepsExistingBooleanFormat() {
        // 0.15.x (80-112): se conserva intacto el formato actual [bool][string]
        // (V0_15 usa la numeracion 0x01..0x5b: Disconnect = 0x05)
        LegacyStream v113 = LegacyStream.writer(48);
        v113.writeBoolean(false);
        v113.writeString(LegacyProtocol.PROTOCOL_113, "kicked");
        OutboundResult out = PacketTranslator.translateOutbound(
                v113Packet(0x05, v113.toByteArray()), LegacyProtocol.PROTOCOL_80, true, null, null);
        byte[] legacy = out.legacyPacket();
        assertEquals(0x05, legacy[0] & 0xFF);
        assertEquals(0, legacy[1], "0.15 conserva el booleano");
        LegacyStream r = LegacyStream.reader(legacy, 2, legacy.length - 2);
        assertEquals("kicked", r.readString(LegacyProtocol.PROTOCOL_80));
    }

    @Test
    void testLoginPhaseStreamP70() throws Exception {
        // Frame 1: PlayStatus(LOGIN_SUCCESS) — como sendPlayStatus (Player.java:4316)
        List<byte[]> frame1 = translateFrame(
                wrapV113Batch(List.of(v113Packet(0x02, new byte[]{0, 0, 0, 0}))),
                LegacyProtocol.PROTOCOL_70);
        assertEquals(1, frame1.size(), "PlayStatus debe reenviarse");
        byte[] ps = frame1.get(0);
        assertEquals(0x90, ps[0] & 0xFF);
        assertEquals(0, java.nio.ByteBuffer.wrap(ps, 1, 4).getInt());

        // Frame 2: ResourcePacksInfo suprimido + sintetico (bypass)
        LegacyStream rpi = LegacyStream.writer(8);
        rpi.writeBoolean(false);
        rpi.writeBoolean(false);
        rpi.writeUnsignedVarInt(0);
        rpi.writeUnsignedVarInt(0);
        OutboundResult rpiOut = PacketTranslator.translateOutbound(
                v113Packet(0x06, rpi.toByteArray()), LegacyProtocol.PROTOCOL_70, true, null, null);
        assertTrue(rpiOut.suppress());
        assertTrue(rpiOut.synthesizeResourceComplete());

        // Frame 3: StartGame — fiel a Nukkit-MOT StartGamePacket.encodeLegacyStartGame
        LegacyStream sg = LegacyStream.writer(128);
        sg.writeVarLong(12345L);
        sg.writeUnsignedVarLong(12345L);
        sg.writeVarInt(1);
        sg.writeFloatLE(128.5f);
        sg.writeFloatLE(70f);
        sg.writeFloatLE(-64.25f);
        sg.writeFloatLE(10f);
        sg.writeFloatLE(90f);
        sg.writeVarInt(999);
        sg.writeVarInt(0);
        sg.writeVarInt(1);
        sg.writeVarInt(1);
        sg.writeVarInt(1);
        sg.writeVarInt(8);
        sg.writeUnsignedVarInt(64);
        sg.writeVarInt(8);
        sg.writeBoolean(true);
        sg.writeVarInt(-1);
        sg.writeBoolean(false);
        sg.writeFloatLE(0f);
        sg.writeFloatLE(0f);
        sg.writeBoolean(true);
        sg.writeBoolean(false);
        sg.writeUnsignedVarInt(0);
        sg.writeString(LegacyProtocol.PROTOCOL_113, "levelid123");
        sg.writeString(LegacyProtocol.PROTOCOL_113, "World");
        sg.writeString(LegacyProtocol.PROTOCOL_113, "");
        List<byte[]> frame3 = translateFrame(wrapV113Batch(List.of(v113Packet(0x0b, sg.toByteArray()))), LegacyProtocol.PROTOCOL_70);
        assertEquals(1, frame3.size());
        byte[] out = frame3.get(0);
        assertEquals(0x95, out[0] & 0xFF, "StartGame 0.14 = 0x95");
        // Layout exacto del cliente 0.14.3 (StartGamePacket::read):
        // int32 seed, byte dim, int32 gen, int32 gamemode, long eid, int32 spawn x3,
        // float x3 (BE), 3 bytes, string
        LegacyStream r = LegacyStream.reader(out, 1, out.length - 1);
        assertEquals(999, r.readIntBE());
        assertEquals(0, r.readByte());
        assertEquals(1, r.readIntBE());
        assertEquals(1, r.readIntBE());
        assertEquals(12345L, r.readLongBE());
        assertEquals(8, r.readIntBE());
        assertEquals(64, r.readIntBE());
        assertEquals(8, r.readIntBE());
        assertEquals(128.5f, r.readFloatBE(), 0.001f);
        assertEquals(70f, r.readFloatBE(), 0.001f);
        assertEquals(-64.25f, r.readFloatBE(), 0.001f);
        assertTrue(r.readBoolean());
        assertTrue(r.readBoolean());
        assertFalse(r.readBoolean());
        assertNotNull(r.readString(LegacyProtocol.PROTOCOL_70));
        assertTrue(r.eof());
    }

    @Test
    void testStartGamePre014TailP41() throws Exception {
        // Nukkit-Retro StartGamePacket.encode isBefore0140 (protocolos < 45):
        // int32 seed, byte dim, int32 gen, int32 mode, long eid, int32 spawn x3,
        // float x3, putByte(0) y RETURN — sin flags ni string.
        // MPMPESCore MultiProtocol.php: "StartGamePacket 0.14: ...[1][1][0][string] -> 0.13: ...[0] (47 bytes)".
        LegacyStream sg = LegacyStream.writer(128);
        sg.writeVarLong(12345L);
        sg.writeUnsignedVarLong(12345L);
        sg.writeVarInt(1);
        sg.writeFloatLE(128.5f);
        sg.writeFloatLE(70f);
        sg.writeFloatLE(-64.25f);
        sg.writeFloatLE(10f);
        sg.writeFloatLE(90f);
        sg.writeVarInt(999);
        sg.writeVarInt(0);
        sg.writeVarInt(1);
        sg.writeVarInt(1);
        sg.writeVarInt(1);
        sg.writeVarInt(8);
        sg.writeUnsignedVarInt(64);
        sg.writeVarInt(8);
        sg.writeBoolean(true);
        sg.writeVarInt(-1);
        sg.writeBoolean(false);
        sg.writeFloatLE(0f);
        sg.writeFloatLE(0f);
        sg.writeBoolean(true);
        sg.writeBoolean(false);
        sg.writeUnsignedVarInt(0);
        sg.writeString(LegacyProtocol.PROTOCOL_113, "levelid123");
        sg.writeString(LegacyProtocol.PROTOCOL_113, "World");
        sg.writeString(LegacyProtocol.PROTOCOL_113, "");
        List<byte[]> frames = translateFrame(wrapV113Batch(List.of(v113Packet(0x0b, sg.toByteArray()))), LegacyProtocol.PROTOCOL_41);
        assertEquals(1, frames.size());
        byte[] out = frames.get(0);
        assertEquals(0x95, out[0] & 0xFF, "StartGame 0.14.0-beta = 0x95");
        LegacyStream r = LegacyStream.reader(out, 1, out.length - 1);
        assertEquals(999, r.readIntBE());
        assertEquals(0, r.readByte());
        assertEquals(1, r.readIntBE());
        assertEquals(1, r.readIntBE());
        assertEquals(12345L, r.readLongBE());
        assertEquals(8, r.readIntBE());
        assertEquals(64, r.readIntBE());
        assertEquals(8, r.readIntBE());
        assertEquals(128.5f, r.readFloatBE(), 0.001f);
        assertEquals(70f, r.readFloatBE(), 0.001f);
        assertEquals(-64.25f, r.readFloatBE(), 0.001f);
        assertEquals(0, r.readByte(), "cierre pre-0.14: un unico byte 0 tras las floats");
        assertTrue(r.eof(), "isBefore0140 no escribe flags ni string: 46 bytes de campos + 1");
        assertEquals(46, out.length - 1, "pid(1)+45 campos+1 = 47 bytes totales como MPMPESCore");
    }

    @Test
    void testBatch92RoundtripP70() throws Exception {
        // Batch multi-sub (>= 256B) → 0x92 + int32BE(compLen) + zlib(int32BE(len)+sub...)
        // El cliente 0.14 lo decodifica exactamente asi (VeoZax RakLibInterface / Nukkit-Retro).
        List<byte[]> subs = new ArrayList<>();
        subs.add(new byte[]{(byte) 0x95, 1, 2, 3});
        byte[] big = new byte[400];
        big[0] = (byte) 0xbf;
        subs.add(big);
        byte[] body = BatchCodec.encodeRakNetBatchBody(LegacyProtocol.PROTOCOL_70, subs, 6);
        assertEquals(0x92, body[0] & 0xFF);

        // Wire 0.14: 0x8e + body (LegacyPipelineHandler.write: isBefore80 -> 0x8e)
        byte[] wire = new byte[1 + body.length];
        wire[0] = (byte) 0x8e;
        System.arraycopy(body, 0, wire, 1, body.length);
        assertEquals(0x92, wire[1] & 0xFF);
        int compLen = LegacyStream.reader(wire, 2, 4).readIntBE();
        assertTrue(compLen > 0);
        byte[] raw = BatchCodec.inflateZlibOrRaw(wire, 6, compLen, 4 * 1024 * 1024);
        List<byte[]> decoded = BatchCodec.unpackSubPackets(raw, true);
        assertEquals(2, decoded.size());
        assertArrayEquals(subs.get(0), decoded.get(0));
        assertArrayEquals(big, decoded.get(1));
    }
}
