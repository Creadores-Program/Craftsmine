package org.CreadoresProgram.Craftsmine.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Lector y escritor binario multiversion optimizado para protocolos 80-113.
 * Soporta tanto la codificacion fija de 0.15.x (< 90) como VarInt/UnsignedVarInt (90-113).
 */
public final class LegacyStream {

    private final byte[] inBuf;
    private int inPos;
    private final int inLimit;

    private final ByteArrayOutputStream out;

    private LegacyStream(byte[] inBuf, int offset, int length, int initialOutCapacity) {
        this.inBuf = inBuf;
        this.inPos = offset;
        this.inLimit = offset + length;
        this.out = new ByteArrayOutputStream(initialOutCapacity);
    }

    public static LegacyStream reader(byte[] buf) {
        return new LegacyStream(buf, 0, buf.length, 0);
    }

    public static LegacyStream reader(byte[] buf, int offset, int length) {
        return new LegacyStream(buf, offset, length, 0);
    }

    public static LegacyStream writer(int initialCapacity) {
        return new LegacyStream(new byte[0], 0, 0, initialCapacity);
    }

    public int remaining() {
        return Math.max(0, inLimit - inPos);
    }

    public boolean eof() {
        return inPos >= inLimit;
    }

    public int position() {
        return inPos;
    }

    public void position(int pos) {
        this.inPos = Math.max(0, Math.min(inLimit, pos));
    }

    public byte[] readRemaining() {
        int rem = remaining();
        if (rem <= 0) {
            return new byte[0];
        }
        byte[] copy = Arrays.copyOfRange(inBuf, inPos, inLimit);
        inPos = inLimit;
        return copy;
    }

    public void copyRemainingTo(LegacyStream dest) {
        int rem = remaining();
        if (rem > 0) {
            dest.out.write(inBuf, inPos, rem);
            inPos = inLimit;
        }
    }

    public byte[] toByteArray() {
        return out.toByteArray();
    }

    // ---- Readers ----

    public int readByte() {
        if (inPos >= inLimit) {
            throw new IndexOutOfBoundsException("Unexpected EOF reading byte");
        }
        return inBuf[inPos++] & 0xFF;
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public byte[] readBytes(int len) {
        if (len < 0 || inPos + len > inLimit) {
            throw new IndexOutOfBoundsException("Unexpected EOF reading " + len + " bytes (remaining=" + remaining() + ")");
        }
        byte[] res = Arrays.copyOfRange(inBuf, inPos, inPos + len);
        inPos += len;
        return res;
    }

    public int readShortBE() {
        return (readByte() << 8) | readByte();
    }

    public int readShortLE() {
        return readByte() | (readByte() << 8);
    }

    public int readSignedShortLE() {
        return (short) readShortLE();
    }

    public int readIntBE() {
        return (readByte() << 24) | (readByte() << 16) | (readByte() << 8) | readByte();
    }

    public int readIntLE() {
        return readByte() | (readByte() << 8) | (readByte() << 16) | (readByte() << 24);
    }

    public long readLongBE() {
        return ((long) readByte() << 56)
                | ((long) readByte() << 48)
                | ((long) readByte() << 40)
                | ((long) readByte() << 32)
                | ((long) readByte() << 24)
                | ((long) readByte() << 16)
                | ((long) readByte() << 8)
                | (long) readByte();
    }

    public long readLongLE() {
        return (long) readByte()
                | ((long) readByte() << 8)
                | ((long) readByte() << 16)
                | ((long) readByte() << 24)
                | ((long) readByte() << 32)
                | ((long) readByte() << 40)
                | ((long) readByte() << 48)
                | ((long) readByte() << 56);
    }

    public float readFloatBE() {
        return Float.intBitsToFloat(readIntBE());
    }

    public float readFloatLE() {
        return Float.intBitsToFloat(readIntLE());
    }

    public int readUnsignedVarInt() {
        int value = 0;
        int i = 0;
        int b;
        do {
            if (i > 35) {
                throw new IllegalArgumentException("VarInt too big");
            }
            b = readByte();
            value |= (b & 0x7F) << i;
            i += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    public int readVarInt() {
        int raw = readUnsignedVarInt();
        return (raw >>> 1) ^ -(raw & 1);
    }

    public long readUnsignedVarLong() {
        long value = 0;
        int i = 0;
        long b;
        do {
            if (i > 70) {
                throw new IllegalArgumentException("VarLong too big");
            }
            b = readByte();
            value |= (b & 0x7FL) << i;
            i += 7;
        } while ((b & 0x80L) != 0);
        return value;
    }

    public long readVarLong() {
        long raw = readUnsignedVarLong();
        return (raw >>> 1) ^ -(raw & 1);
    }

    public String readString(LegacyProtocol protocol) {
        int len = protocol.isBefore90() ? readShortBE() : readUnsignedVarInt();
        return new String(readBytes(len), StandardCharsets.UTF_8);
    }

    public byte[] readByteArray(LegacyProtocol protocol) {
        int len = protocol.isBefore90() ? readIntBE() : readUnsignedVarInt();
        return readBytes(len);
    }

    public UUID readUUID() {
        long msb = readLongLE();
        long lsb = readLongLE();
        return new UUID(msb, lsb);
    }

    public UUID readUUID(LegacyProtocol protocol) {
        if (protocol != null && protocol.isBefore80()) {
            long msb = readLongBE();
            long lsb = readLongBE();
            return new UUID(msb, lsb);
        }
        return readUUID();
    }

    public long readEntityUniqueId(LegacyProtocol protocol) {
        if (protocol.isBefore90()) {
            return readLongBE();
        }
        return readVarLong();
    }

    public long readEntityRuntimeId(LegacyProtocol protocol) {
        if (protocol.isBefore90()) {
            return readLongBE();
        }
        return readUnsignedVarLong();
    }

    public int[] readBlockPosition(LegacyProtocol protocol) {
        if (protocol.isBefore90()) {
            return new int[]{readIntBE(), readIntBE(), readIntBE()};
        }
        int x = readVarInt();
        int y = protocol.isBefore92() ? readByte() : readUnsignedVarInt();
        int z = readVarInt();
        return new int[]{x, y, z};
    }

    public int[] readSignedBlockPosition(LegacyProtocol protocol) {
        if (protocol.isBefore90()) {
            return new int[]{readIntLE(), readIntLE(), readIntLE()};
        }
        return new int[]{readVarInt(), readVarInt(), readVarInt()};
    }

    public float[] readVector3(LegacyProtocol protocol) {
        if (protocol.isBefore90()) {
            return new float[]{readFloatBE(), readFloatBE(), readFloatBE()};
        }
        return new float[]{readFloatLE(), readFloatLE(), readFloatLE()};
    }

    public LegacySlot readSlot(LegacyProtocol protocol) {
        if (eof()) {
            return LegacySlot.AIR;
        }
        int slotStartPos = inPos;
        int id = protocol.isBefore90() ? (short) readShortBE() : readVarInt();
        if (protocol.isBefore90() ? id <= 0 : id == 0) {
            return LegacySlot.AIR;
        }
        int count;
        int meta;
        if (protocol.isBefore90()) {
            count = readByte();
            meta = (short) readShortBE();
        } else {
            int aux = readVarInt();
            meta = aux >> 8;
            count = aux & 0xFF;
            if (!protocol.isBefore110() && meta == 0x7FFF) {
                meta = -1;
            }
        }
        int nbtLen = readShortLE();
        byte[] nbt = new byte[0];
        if (nbtLen == 0xFFFF) {
            // Formato network (0xFFFF + byte version + NetworkLittleEndianNBT): convertir a LittleEndianNBT
            nbt = LegacyNbtConverter.convertNetworkNbtStreamSlice(this, true);
        } else if (nbtLen > 0) {
            if (nbtLen > remaining()) {
                throw new IndexOutOfBoundsException(
                        "Invalid slot NBT len=" + nbtLen + " (itemId=" + id + ", meta=" + meta
                                + ", slotOffset=" + slotStartPos + ", remaining=" + remaining()
                                + ", preview=" + previewAround(slotStartPos, 24) + ")"
                );
            }
            nbt = readBytes(nbtLen);
        }
        String[] canPlaceOn = new String[0];
        String[] canDestroy = new String[0];
        if (!protocol.isBefore110()) {
            int placeCount = remaining() > 0 ? Math.min(128, Math.max(0, readVarInt())) : 0;
            canPlaceOn = new String[placeCount];
            for (int i = 0; i < placeCount && remaining() > 0; i++) {
                canPlaceOn[i] = readString(LegacyProtocol.PROTOCOL_113);
            }
            int destroyCount = remaining() > 0 ? Math.min(128, Math.max(0, readVarInt())) : 0;
            canDestroy = new String[destroyCount];
            for (int i = 0; i < destroyCount && remaining() > 0; i++) {
                canDestroy[i] = readString(LegacyProtocol.PROTOCOL_113);
            }
        }
        return new LegacySlot(id, meta, count, nbt, canPlaceOn, canDestroy);
    }

    private String previewAround(int startPos, int maxBytes) {
        int from = Math.max(0, Math.min(inLimit, startPos));
        int to = Math.min(inLimit, from + maxBytes);
        StringBuilder sb = new StringBuilder((to - from) * 2);
        for (int i = from; i < to; i++) {
            sb.append(String.format("%02x", inBuf[i] & 0xFF));
        }
        return sb.toString();
    }

    // ---- Writers ----

    public void writeByte(int b) {
        out.write(b & 0xFF);
    }

    public void writeBoolean(boolean b) {
        writeByte(b ? 1 : 0);
    }

    public void writeBytes(byte[] bytes) {
        if (bytes != null && bytes.length > 0) {
            out.write(bytes, 0, bytes.length);
        }
    }

    public void writeShortBE(int v) {
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public void writeShortLE(int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    public void writeIntBE(int v) {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    public void writeIntLE(int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }

    public void writeLongBE(long v) {
        out.write((int) (v >>> 56) & 0xFF);
        out.write((int) (v >>> 48) & 0xFF);
        out.write((int) (v >>> 40) & 0xFF);
        out.write((int) (v >>> 32) & 0xFF);
        out.write((int) (v >>> 24) & 0xFF);
        out.write((int) (v >>> 16) & 0xFF);
        out.write((int) (v >>> 8) & 0xFF);
        out.write((int) v & 0xFF);
    }

    public void writeLongLE(long v) {
        out.write((int) v & 0xFF);
        out.write((int) (v >>> 8) & 0xFF);
        out.write((int) (v >>> 16) & 0xFF);
        out.write((int) (v >>> 24) & 0xFF);
        out.write((int) (v >>> 32) & 0xFF);
        out.write((int) (v >>> 40) & 0xFF);
        out.write((int) (v >>> 48) & 0xFF);
        out.write((int) (v >>> 56) & 0xFF);
    }

    public void writeFloatBE(float v) {
        writeIntBE(Float.floatToRawIntBits(v));
    }

    public void writeFloatLE(float v) {
        writeIntLE(Float.floatToRawIntBits(v));
    }

    public void writeUnsignedVarInt(int v) {
        int val = v;
        while ((val & ~0x7F) != 0) {
            out.write((val & 0x7F) | 0x80);
            val >>>= 7;
        }
        out.write(val & 0x7F);
    }

    public void writeVarInt(int v) {
        writeUnsignedVarInt((v << 1) ^ (v >> 31));
    }

    public void writeUnsignedVarLong(long v) {
        long val = v;
        while ((val & ~0x7FL) != 0L) {
            out.write((int) ((val & 0x7FL) | 0x80L));
            val >>>= 7;
        }
        out.write((int) (val & 0x7FL));
    }

    public void writeVarLong(long v) {
        writeUnsignedVarLong((v << 1) ^ (v >> 63));
    }

    public void writeString(LegacyProtocol protocol, String s) {
        byte[] bytes = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (protocol.isBefore90()) {
            writeShortBE(bytes.length);
        } else {
            writeUnsignedVarInt(bytes.length);
        }
        writeBytes(bytes);
    }

    public void writeByteArray(LegacyProtocol protocol, byte[] bytes) {
        byte[] data = bytes == null ? new byte[0] : bytes;
        if (protocol.isBefore90()) {
            writeIntBE(data.length);
        } else {
            writeUnsignedVarInt(data.length);
        }
        writeBytes(data);
    }

    public void writeUUID(UUID uuid) {
        UUID u = uuid == null ? new UUID(0L, 0L) : uuid;
        writeLongLE(u.getMostSignificantBits());
        writeLongLE(u.getLeastSignificantBits());
    }

    public void writeUUID(LegacyProtocol protocol, UUID uuid) {
        if (protocol != null && protocol.isBefore80()) {
            UUID u = uuid == null ? new UUID(0L, 0L) : uuid;
            writeLongBE(u.getMostSignificantBits());
            writeLongBE(u.getLeastSignificantBits());
            return;
        }
        writeUUID(uuid);
    }

    public void writeEntityUniqueId(LegacyProtocol protocol, long eid) {
        if (protocol.isBefore90()) {
            writeLongBE(eid);
        } else {
            writeVarLong(eid);
        }
    }

    public void writeEntityRuntimeId(LegacyProtocol protocol, long eid) {
        if (protocol.isBefore90()) {
            writeLongBE(eid);
        } else {
            writeUnsignedVarLong(eid);
        }
    }

    public void writeBlockPosition(LegacyProtocol protocol, int x, int y, int z) {
        if (protocol.isBefore90()) {
            writeIntBE(x);
            writeIntBE(y);
            writeIntBE(z);
            return;
        }
        writeVarInt(x);
        if (protocol.isBefore92()) {
            writeByte(y);
        } else {
            writeUnsignedVarInt(y);
        }
        writeVarInt(z);
    }

    public void writeSignedBlockPosition(LegacyProtocol protocol, int x, int y, int z) {
        if (protocol.isBefore90()) {
            writeIntLE(x);
            writeIntLE(y);
            writeIntLE(z);
            return;
        }
        writeVarInt(x);
        writeVarInt(y);
        writeVarInt(z);
    }

    public void writeVector3(LegacyProtocol protocol, float x, float y, float z) {
        if (protocol.isBefore90()) {
            writeFloatBE(x);
            writeFloatBE(y);
            writeFloatBE(z);
            return;
        }
        writeFloatLE(x);
        writeFloatLE(y);
        writeFloatLE(z);
    }

    public void writeSlot(LegacyProtocol protocol, LegacySlot slot) {
        if (slot == null || slot.id() <= 0) {
            if (protocol.isBefore90()) {
                writeShortBE(0);
            } else {
                writeVarInt(0);
            }
            return;
        }
        if (protocol.isBefore90()) {
            writeShortBE(slot.id());
            writeByte(slot.count());
            writeShortBE(slot.meta() < 0 ? 0 : slot.meta());
        } else {
            writeVarInt(slot.id());
            // Nukkit-MOT v113 envia meta 0x7FFF como wildcard "sin meta" (BinaryStream#putSlotV113,
            // Nukkit-MOT utils/BinaryStream.java:1598-1612). MySoft solo interpreta 0x7fff como wildcard
            // en >= 110 (NetworkBinaryStream.php:302-304); en 90..109 un cliente lo lee como damage
            // 32767 y crashea al renderizar tintes/pociones/flechas (icon lookup fuera de rango).
            // Normalizar a 0 en 90..109; en 110..112 se conserva el wildcard nativo.
            int encodedMeta;
            if (slot.meta() == -1) {
                encodedMeta = protocol.isBefore110() ? 0 : 0x7FFF;
            } else {
                encodedMeta = slot.meta() & 0x7FFF;
            }
            int aux = (encodedMeta << 8) | (slot.count() & 0xFF);
            writeVarInt(aux);
        }
        byte[] nbt = slot.nbt();
        if (nbt != null && nbt.length > 0) {
            writeShortLE(nbt.length);
            writeBytes(nbt);
        } else {
            writeShortLE(0);
        }
        if (!protocol.isBefore110()) {
            String[] place = slot.canPlaceOn();
            writeVarInt(place.length);
            for (String s : place) {
                writeString(LegacyProtocol.PROTOCOL_113, s);
            }
            String[] destroy = slot.canDestroy();
            writeVarInt(destroy.length);
            for (String s : destroy) {
                writeString(LegacyProtocol.PROTOCOL_113, s);
            }
        }
    }

    public record LegacySlot(int id, int meta, int count, byte[] nbt, String[] canPlaceOn, String[] canDestroy) {
        public static final LegacySlot AIR = new LegacySlot(0, 0, 0, new byte[0], new String[0], new String[0]);
    }
}
