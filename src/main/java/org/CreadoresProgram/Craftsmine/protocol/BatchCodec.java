package org.CreadoresProgram.Craftsmine.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Codificador/decodificador de BatchPackets optimizado con pooling por hilo (ThreadLocal)
 * para minimizar asignaciones en el EventLoop de Netty.
 */
public final class BatchCodec {

    private static final int MAX_SUB_PACKETS = 1024;
    private static final ThreadLocal<byte[]> SCRATCH = ThreadLocal.withInitial(() -> new byte[16384]);

    private BatchCodec() {
    }

    public static byte[] inflateZlib(byte[] compressed, int maxBytes) throws DataFormatException {
        return inflateZlib(compressed, 0, compressed.length, maxBytes);
    }

    public static byte[] inflateZlib(byte[] compressed, int offset, int length, int maxBytes) throws DataFormatException {
        return inflateInternal(compressed, offset, length, maxBytes, false);
    }

    public static byte[] inflateZlibOrRaw(byte[] compressed, int offset, int length, int maxBytes) throws DataFormatException {
        if (length <= 0) {
            throw new DataFormatException("Empty compressed payload");
        }
        boolean looksZlib = (compressed[offset] & 0x0F) == 0x08;
        if (looksZlib) {
            try {
                return inflateInternal(compressed, offset, length, maxBytes, false);
            } catch (DataFormatException primaryEx) {
                try {
                    return inflateInternal(compressed, offset, length, maxBytes, true);
                } catch (DataFormatException ignored) {
                    throw primaryEx;
                }
            }
        }
        return inflateInternal(compressed, offset, length, maxBytes, true);
    }

    private static byte[] inflateInternal(byte[] compressed, int offset, int length, int maxBytes, boolean nowrap) throws DataFormatException {
        Inflater inflater = new Inflater(nowrap);
        try {
            inflater.setInput(compressed, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, length * 3 + 256));
            byte[] buf = SCRATCH.get();
            int total = 0;
            while (!inflater.finished()) {
                int count = inflater.inflate(buf);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                } else {
                    total += count;
                    if (total > maxBytes) {
                        throw new DataFormatException("Decompressed batch exceeds maxBytes=" + maxBytes);
                    }
                    out.write(buf, 0, count);
                }
            }
            byte[] result = out.toByteArray();
            if (result.length == 0 && length > 0) {
                throw new DataFormatException("Inflated payload produced 0 bytes (nowrap=" + nowrap + ")");
            }
            return result;
        } finally {
            inflater.end();
        }
    }

    public static byte[] deflateZlib(byte[] raw, int level) {
        Deflater deflater = new Deflater(level, false);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            byte[] buf = SCRATCH.get();
            while (!deflater.finished()) {
                int count = deflater.deflate(buf);
                if (count > 0) {
                    out.write(buf, 0, count);
                }
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * Extrae los sub-paquetes de un batch ya descomprimido.
     * En < 90 los sub-paquetes usan longitud int32 BE; en >= 90 usan UnsignedVarInt.
     */
    public static List<byte[]> unpackSubPackets(byte[] decompressed, boolean pre90Framing) {
        List<byte[]> packets = new ArrayList<>(8);
        LegacyStream reader = LegacyStream.reader(decompressed);
        int count = 0;
        while (!reader.eof()) {
            if (++count > MAX_SUB_PACKETS) {
                throw new IllegalStateException("Batch exceeds max sub-packet count: " + MAX_SUB_PACKETS);
            }
            int len = pre90Framing ? reader.readIntBE() : reader.readUnsignedVarInt();
            if (len <= 0 || len > reader.remaining()) {
                throw new IllegalStateException("Invalid sub-packet length " + len + " (remaining=" + reader.remaining() + ", pre90=" + pre90Framing + ")");
            }
            packets.add(reader.readBytes(len));
        }
        return packets;
    }

    /**
     * Empaqueta multiples sub-paquetes en un bloque sin comprimir para un protocolo destino.
     */
    public static byte[] packSubPackets(List<byte[]> subPackets, boolean pre90Framing) {
        LegacyStream writer = LegacyStream.writer(subPackets.size() * 64 + 64);
        for (byte[] pk : subPackets) {
            if (pk == null || pk.length == 0) {
                continue;
            }
            if (pre90Framing) {
                writer.writeIntBE(pk.length);
            } else {
                writer.writeUnsignedVarInt(pk.length);
            }
            writer.writeBytes(pk);
        }
        return writer.toByteArray();
    }

    /**
     * Decodifica el cuerpo entrante despues de 0xfe de RakNet segun el protocolo conocido (o heuristica pre-login).
     *
     * Framing verificado contra MySoft (OldBatchPacket / BatchPacket / RakLibInterface):
     * - 80..89 (0.15.x): paquete suelto o 0x06 + int32BE(compLen) + zlib(int32BE(subLen)+sub ...)
     * - 90..109 (0.16..1.0.9): 0x06 + UnsignedVarInt(compLen) + zlib(UnsignedVarInt(subLen)+sub ...)
     * - 110..113 (1.1.0+): zlib(UnsignedVarInt(subLen)+sub ...)
     */
    public static List<byte[]> decodeRakNetBatchBody(
            byte[] body,
            int rakNetVersion,
            LegacyProtocol knownProto,
            int maxBytes
    ) throws DataFormatException {
        if (body == null || body.length == 0) {
            return List.of();
        }
        int pid = body[0] & 0xFF;

        if (knownProto != null) {
            if (knownProto.isBefore90()) {
                if (pid == 0x06) {
                    return decodeExplicitBatch06(body, true, maxBytes);
                }
                return List.of(body);
            }
            if (knownProto.isBefore110()) {
                if (pid == 0x06) {
                    return decodeExplicitBatch06(body, false, maxBytes);
                }
                if (isLikelyZlib(body, 0)) {
                    byte[] raw = inflateZlibOrRaw(body, 0, body.length, maxBytes);
                    return unpackSubPackets(raw, false);
                }
                return List.of(body);
            }
            byte[] raw = inflateZlibOrRaw(body, 0, body.length, maxBytes);
            return unpackSubPackets(raw, false);
        }

        // Pre-login (knownProto == null)
        if (pid == 0x01) {
            return List.of(body);
        }
        if (pid == 0x06) {
            boolean preferPre90 = rakNetVersion <= 7;
            return decodeExplicitBatch06(body, preferPre90, maxBytes);
        }
        if (isLikelyZlib(body, 0)) {
            byte[] raw = inflateZlibOrRaw(body, 0, body.length, maxBytes);
            return unpackSubPackets(raw, false);
        }
        if (rakNetVersion <= 7) {
            return List.of(body);
        }
        return null;
    }

    private static List<byte[]> decodeExplicitBatch06(byte[] body, boolean preferPre90, int maxBytes) throws DataFormatException {
        if (body.length < 2) {
            throw new DataFormatException("Truncated 0x06 batch packet (len=" + body.length + ")");
        }

        if (preferPre90) {
            List<byte[]> pre90 = tryDecodePre90Batch06(body, maxBytes);
            if (pre90 != null) {
                return pre90;
            }
            List<byte[]> v90 = tryDecodeV90Batch06(body, maxBytes);
            if (v90 != null) {
                return v90;
            }
        } else {
            List<byte[]> v90 = tryDecodeV90Batch06(body, maxBytes);
            if (v90 != null) {
                return v90;
            }
            List<byte[]> pre90 = tryDecodePre90Batch06(body, maxBytes);
            if (pre90 != null) {
                return pre90;
            }
        }

        // Respaldo: 0x06 seguido directamente por stream zlib sin cabecera de longitud
        byte[] raw = inflateZlibOrRaw(body, 1, body.length - 1, maxBytes);
        try {
            return unpackSubPackets(raw, preferPre90);
        } catch (RuntimeException primaryEx) {
            try {
                return unpackSubPackets(raw, !preferPre90);
            } catch (RuntimeException ignored) {
                throw primaryEx;
            }
        }
    }

    private static List<byte[]> tryDecodePre90Batch06(byte[] body, int maxBytes) {
        if (body.length < 6) {
            return null;
        }
        try {
            LegacyStream r = LegacyStream.reader(body, 1, body.length - 1);
            int compLen = r.readIntBE();
            if (compLen <= 0 || compLen > r.remaining()) {
                return null;
            }
            byte[] raw = inflateZlibOrRaw(body, r.position(), compLen, maxBytes);
            return unpackSubPackets(raw, true);
        } catch (DataFormatException | RuntimeException ignored) {
            return null;
        }
    }

    private static List<byte[]> tryDecodeV90Batch06(byte[] body, int maxBytes) {
        if (body.length < 3) {
            return null;
        }
        try {
            LegacyStream r = LegacyStream.reader(body, 1, body.length - 1);
            int compLen = r.readUnsignedVarInt();
            if (compLen <= 0 || compLen > r.remaining()) {
                return null;
            }
            byte[] raw = inflateZlibOrRaw(body, r.position(), compLen, maxBytes);
            return unpackSubPackets(raw, false);
        } catch (DataFormatException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean isLikelyZlib(byte[] data, int offset) {
        return data.length - offset >= 2 && (data[offset] & 0x0F) == 0x08;
    }

    /**
     * Construye el payload completo (despues del byte 0xfe de RakNet) para un cliente destino.
     */
    public static byte[] encodeRakNetBatchBody(LegacyProtocol target, List<byte[]> translatedSubPackets, int compressionLevel) {
        if (target.isBefore90()) {
            // 0.15.x (< 90): 0x06 + int32BE(compressedLen) + zlib(int32BE(len)+pkt ...)
            byte[] raw = packSubPackets(translatedSubPackets, true);
            byte[] compressed = deflateZlib(raw, compressionLevel);
            LegacyStream out = LegacyStream.writer(compressed.length + 8);
            out.writeByte(0x06);
            out.writeIntBE(compressed.length);
            out.writeBytes(compressed);
            return out.toByteArray();
        }
        byte[] raw = packSubPackets(translatedSubPackets, false);
        byte[] compressed = deflateZlib(raw, compressionLevel);
        if (target.isBefore110()) {
            // 0.16.0 - 1.0.9 (90..109): 0x06 + UnsignedVarInt(compressedLen) + zlib(UnsignedVarInt(len)+pkt ...)
            LegacyStream out = LegacyStream.writer(compressed.length + 8);
            out.writeByte(0x06);
            out.writeUnsignedVarInt(compressed.length);
            out.writeBytes(compressed);
            return out.toByteArray();
        }
        // 1.1.0+ (110..113): zlib(UnsignedVarInt(len)+pkt ...)
        return compressed;
    }

    /**
     * Construye un BatchPacket canonico v113 listo para RakNetPlayerSession de Nukkit-MOT.
     */
    public static byte[] encodeCanonicalV113Body(List<byte[]> v113SubPackets, int compressionLevel) {
        byte[] raw = packSubPackets(v113SubPackets, false);
        return deflateZlib(raw, compressionLevel);
    }
}
