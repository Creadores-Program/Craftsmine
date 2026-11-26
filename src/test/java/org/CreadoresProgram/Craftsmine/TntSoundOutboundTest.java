package org.CreadoresProgram.Craftsmine;

import cn.nukkit.network.protocol.v113.ProtocolInfo_v113;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import org.CreadoresProgram.Craftsmine.protocol.LegacyProtocol;
import org.CreadoresProgram.Craftsmine.protocol.LegacyStream;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator;

/**
 * Regresion del sonido de encender TNT frente al de explotar en 80-112.
 *
 * Nukkit-MOT (P113) emite al encender un LevelEventPacket 1005 (TNTPrimeSound) y al explotar un
 * PlaySoundPacket "random.explode" (Explosion) o un LevelSoundEvent EXPLODE (45). En 0.15.x los
 * tres caian en el mismo LevelEvent 1005, asi que mecha y explosion eran el mismo evento.
 *
 * Referencias: Nukkit-Retro TNTPrimeSound.java:16 (1005) / ExplodeSound.java:13 (3501),
 * MySoft PrimedTNT.php:69 (EVENT_SOUND_IGNITE 1005).
 */
class TntSoundOutboundTest {

    /** LevelEvent legado ya traducido: pid + evid + posicion + data. */
    private record LegacyLevelEvent(int pid, int evid, float x, float y, float z, int data) {
    }

    /** LevelEventPacket v113 tal como lo codifica LevelEventSound.encode(): VarInt evid + Vector3f + VarInt data. */
    private byte[] levelEventV113(int evid, float x, float y, float z, int data) {
        LegacyStream w = LegacyStream.writer(24);
        w.writeByte(ProtocolInfo_v113.LEVEL_EVENT_PACKET & 0xFF);
        w.writeVarInt(evid);
        w.writeVector3(LegacyProtocol.PROTOCOL_113, x, y, z);
        w.writeVarInt(data);
        return w.toByteArray();
    }

    /** PlaySoundPacket v113 (< v1_26_20_26): String name + BlockVector3(x<<3) + LFloat volume + LFloat pitch. */
    private byte[] playSoundV113(String name, int x, int y, int z) {
        LegacyStream w = LegacyStream.writer(48);
        w.writeByte(ProtocolInfo_v113.PLAY_SOUND_PACKET & 0xFF);
        w.writeString(LegacyProtocol.PROTOCOL_113, name);
        w.writeBlockPosition(LegacyProtocol.PROTOCOL_113, x << 3, y << 3, z << 3);
        w.writeFloatLE(1f); // volume
        w.writeFloatLE(1f); // pitch
        return w.toByteArray();
    }

    /** LevelSoundEventPacketV1 v113: byte sound + Vector3f + VarInt extraData + VarInt pitch + bool baby + bool global. */
    private byte[] levelSoundEventV113(int sound, float x, float y, float z) {
        LegacyStream w = LegacyStream.writer(32);
        w.writeByte(ProtocolInfo_v113.LEVEL_SOUND_EVENT_PACKET & 0xFF);
        w.writeByte(sound);
        w.writeVector3(LegacyProtocol.PROTOCOL_113, x, y, z);
        w.writeVarInt(-1);
        w.writeVarInt(1);
        w.writeBoolean(false);
        w.writeBoolean(false);
        return w.toByteArray();
    }

    /** < 90 = ShortBE evid + 3 FloatBE + IntBE data; >= 90 = VarInt evid + 3 FloatLE + VarInt data. */
    private LegacyLevelEvent toLegacyLevelEvent(byte[] v113, LegacyProtocol dst) {
        PacketTranslator.OutboundResult res = PacketTranslator.translateOutbound(v113, dst, true);
        assertFalse(res.suppress(), dst.name());
        LegacyStream r = LegacyStream.reader(res.legacyPacket());
        int pid = r.readByte();
        if (dst.isBefore90()) {
            return new LegacyLevelEvent(pid, r.readShortBE(), r.readFloatBE(), r.readFloatBE(), r.readFloatBE(), r.readIntBE());
        }
        return new LegacyLevelEvent(pid, r.readVarInt(), r.readFloatLE(), r.readFloatLE(), r.readFloatLE(), r.readVarInt());
    }

    private static final LegacyProtocol[] PRE90 = {
            LegacyProtocol.PROTOCOL_81, LegacyProtocol.PROTOCOL_82, LegacyProtocol.PROTOCOL_84
    };

    @Test
    void tntPrimeKeepsItsOwnLevelEventOn015() {
        for (LegacyProtocol dst : PRE90) {
            // BlockTNT.prime -> TNTPrimeSound: evid 1005, posicion del bloque, data 0
            LegacyLevelEvent ev = toLegacyLevelEvent(levelEventV113(1005, 10f, 64f, 10f, 0), dst);
            assertEquals(0x16, ev.pid(), dst.name());
            assertEquals(1005, ev.evid(), dst.name());
            assertEquals(10f, ev.x(), dst.name());
            assertEquals(64f, ev.y(), dst.name());
            assertEquals(10f, ev.z(), dst.name());
            assertEquals(0, ev.data(), dst.name());
        }
    }

    @Test
    void playSoundExplosionUsesTheExplodeEventOn015() {
        for (LegacyProtocol dst : PRE90) {
            // Explosion -> addSound(Sound.RANDOM_EXPLODE) -> PlaySound "random.explode"
            LegacyLevelEvent ev = toLegacyLevelEvent(playSoundV113("random.explode", 10, 64, 10), dst);
            assertEquals(0x16, ev.pid(), dst.name());
            assertEquals(3501, ev.evid(), "la explosion no debe reusar el evento de encender TNT");
            assertEquals(10f, ev.x(), dst.name());
            assertEquals(64f, ev.y(), dst.name());
            assertEquals(10f, ev.z(), dst.name());
            assertEquals(0, ev.data(), dst.name());
        }
    }

    @Test
    void levelSoundEventExplosionUsesTheExplodeEventOn015() {
        for (LegacyProtocol dst : PRE90) {
            // LevelSoundEvent EXPLODE = 45 en la tabla PROTOCOL_110 / PROTOCOL_81 de MySoft
            LegacyLevelEvent ev = toLegacyLevelEvent(levelSoundEventV113(45, 10f, 64f, 10f), dst);
            assertEquals(0x16, ev.pid(), dst.name());
            assertEquals(3501, ev.evid(), dst.name());
            assertEquals(10f, ev.x(), dst.name());
            assertEquals(64f, ev.y(), dst.name());
            assertEquals(10f, ev.z(), dst.name());
        }
    }

    @Test
    void fizzStillUsesTheFizzEventOn015() {
        for (LegacyProtocol dst : PRE90) {
            assertEquals(1004, toLegacyLevelEvent(levelSoundEventV113(26, 0f, 64f, 0f), dst).evid(), dst.name());
            assertEquals(1004, toLegacyLevelEvent(playSoundV113("random.fizz", 0, 64, 0), dst).evid(), dst.name());
        }
    }

    @Test
    void protocols90To104KeepTheirBehaviour() {
        // PlaySound sigue sin traducirse (drop), LevelSoundEvent 45 sigue siendo 45 y LevelEvent 1005 pasa intacto
        for (LegacyProtocol dst : new LegacyProtocol[]{
                LegacyProtocol.PROTOCOL_90, LegacyProtocol.PROTOCOL_100, LegacyProtocol.PROTOCOL_102}) {
            assertTrue(PacketTranslator.translateOutbound(
                    playSoundV113("random.explode", 10, 64, 10), dst, true).suppress(), dst.name());

            PacketTranslator.OutboundResult lse = PacketTranslator.translateOutbound(
                    levelSoundEventV113(45, 10f, 64f, 10f), dst, true);
            assertFalse(lse.suppress(), dst.name());
            LegacyStream lseR = LegacyStream.reader(lse.legacyPacket());
            assertEquals(0x1A, lseR.readByte(), dst.name());
            assertEquals(45, lseR.readByte(), dst.name());

            assertEquals(1005, toLegacyLevelEvent(levelEventV113(1005, 10f, 64f, 10f, 0), dst).evid(), dst.name());
        }
    }

    @Test
    void protocols105To112KeepTheirBehaviour() {
        // PlaySound pasa intacto con su pid legado (0x56 en 105, 0x57 en 110) y LevelEvent 1005 pasa intacto
        byte[] play = playSoundV113("random.explode", 10, 64, 10);
        byte[] expected = java.util.Arrays.copyOfRange(play, 1, play.length);
        int[][] cases = {{105, 0x56}, {110, 0x57}};
        for (int[] c : cases) {
            LegacyProtocol dst = LegacyProtocol.resolve(c[0]);
            PacketTranslator.OutboundResult res = PacketTranslator.translateOutbound(play, dst, true);
            assertFalse(res.suppress(), dst.name());
            LegacyStream r = LegacyStream.reader(res.legacyPacket());
            assertEquals(c[1], r.readByte(), dst.name());
            assertArrayEquals(expected, r.readRemaining(), dst.name());
            assertEquals(1005, toLegacyLevelEvent(levelEventV113(1005, 10f, 64f, 10f, 0), dst).evid(), dst.name());
        }
    }
}
