package org.CreadoresProgram.Craftsmine.network;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import org.CreadoresProgram.Craftsmine.config.CraftsmineConfig;
import org.CreadoresProgram.Craftsmine.logging.CraftsmineLogger;
import org.CreadoresProgram.Craftsmine.logging.CraftsmineLogger.Category;
import org.CreadoresProgram.Craftsmine.metrics.PerformanceMetrics;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry.LegacySessionState;
import org.CreadoresProgram.Craftsmine.protocol.BatchCodec;
import org.CreadoresProgram.Craftsmine.protocol.LegacyProtocol;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator.InboundResult;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator.LoginSniffResult;
import org.CreadoresProgram.Craftsmine.protocol.PacketTranslator.OutboundResult;
import org.cloudburstmc.netty.channel.raknet.RakChildChannel;
import org.cloudburstmc.netty.channel.raknet.packet.RakMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handler Netty bidireccional inyectado antes de "nukkit-handler" en RakChildChannel.
 * Traduce en tiempo real paquetes legados (protocolos 80-112) <-> protocolo canonico 113 de Nukkit-MOT
 * y se auto-desacopla para clientes modernos (>= 113) con sobrecarga cero.
 */
public final class LegacyPipelineHandler extends ChannelDuplexHandler {

    public static final String HANDLER_NAME = "craftsmine-bridge";

    private final SessionProtocolRegistry registry;
    private final CraftsmineConfig config;
    private final CraftsmineLogger logger;
    private final PerformanceMetrics metrics;
    private final int rakNetVersion;

    public LegacyPipelineHandler(
            SessionProtocolRegistry registry,
            CraftsmineConfig config,
            CraftsmineLogger logger,
            PerformanceMetrics metrics,
            int rakNetVersion
    ) {
        this.registry = registry;
        this.config = config;
        this.logger = logger;
        this.metrics = metrics;
        this.rakNetVersion = rakNetVersion;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (!(msg instanceof RakMessage rakMsg)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf content = rakMsg.content();
        if (content == null || !content.isReadable()) {
            ctx.fireChannelRead(msg);
            return;
        }

        LegacySessionState state = registry.getByChannel(ctx.channel().id());
        if (state != null && state.isModernDetached()) {
            ctx.fireChannelRead(msg);
            return;
        }

        int readerIdx = content.readerIndex();
        int firstByte = content.getUnsignedByte(readerIdx);
        if (firstByte != 0xfe && firstByte != 0x8e) {
            ctx.fireChannelRead(msg);
            return;
        }

        byte[] body = new byte[content.readableBytes() - 1];
        content.getBytes(readerIdx + 1, body);
        if (body.length == 0) {
            if (state != null || rakNetVersion <= 7) {
                ReferenceCountUtil.release(rakMsg);
                return;
            }
            ctx.fireChannelRead(msg);
            return;
        }

        long startNanos = System.nanoTime();
        try {
            if (state == null) {
                List<byte[]> subPackets = BatchCodec.decodeRakNetBatchBody(
                        body, rakNetVersion, null, config.getMaxBatchBytes()
                );
                if (subPackets == null || subPackets.isEmpty()) {
                    if (rakNetVersion <= 7) {
                        logger.warn(
                                Category.PACKET,
                                "Paquete pre-login RakNet v" + rakNetVersion + " no reconocido de "
                                        + ctx.channel().remoteAddress() + " hex=" + hexPreview(body, 24)
                        );
                        ReferenceCountUtil.release(rakMsg);
                        return;
                    }
                    ctx.fireChannelRead(msg);
                    return;
                }

                boolean foundLegacyLogin = false;
                boolean foundModernLogin = false;
                List<byte[]> normalizedSubs = new ArrayList<>(subPackets.size());

                for (byte[] sub : subPackets) {
                    if (sub.length > 0 && ((sub[0] & 0xFF) == 0x01 || (sub[0] & 0xFF) == 0x8f)) {
                        LoginSniffResult sniff = PacketTranslator.tryNormalizeLogin(
                                sub, config.getMinProtocol(), config.getMaxProtocol()
                        );
                        if (sniff != null) {
                            foundLegacyLogin = true;
                            state = registry.registerOrUpdate(
                                    ctx.channel().id(),
                                    ctx.channel().remoteAddress(),
                                    sniff.rawProtocol(),
                                    sniff.username()
                            );
                            metrics.onLegacySessionOpened();
                            LegacyProtocol lp = state.getProtocol();
                            logger.info(
                                    Category.NEGOTIATION,
                                    String.format(
                                            "Handshake legado detectado: jugador=%s ip=%s proto=%d (%s, RakNet v%d) -> normalizado a v113",
                                            state.getUsername(),
                                            state.getRemoteAddress(),
                                            sniff.rawProtocol(),
                                            lp.getMcVersion(),
                                            lp.getRakNetVersion()
                                    )
                            );
                            normalizedSubs.add(sniff.canonicalV113LoginPacket());
                            continue;
                        } else {
                            foundModernLogin = true;
                        }
                    }
                    normalizedSubs.add(sub);
                }

                if (!foundLegacyLogin) {
                    if (foundModernLogin) {
                        metrics.onModernBypassed();
                        logger.debug(
                                Category.NEGOTIATION,
                                "Cliente moderno (>= 113) detectado en " + ctx.channel().remoteAddress()
                                        + "; detach=" + config.isDetachModernClients()
                        );
                        if (config.isDetachModernClients() && ctx.pipeline().get(HANDLER_NAME) != null) {
                            ctx.pipeline().remove(this);
                        }
                    } else if (rakNetVersion <= 7) {
                        logger.warn(
                                Category.NEGOTIATION,
                                "Lote pre-login sin LoginPacket valido en RakNet v" + rakNetVersion
                                        + " de " + ctx.channel().remoteAddress() + " hex=" + hexPreview(body, 24)
                        );
                        ReferenceCountUtil.release(rakMsg);
                        return;
                    }
                    ctx.fireChannelRead(msg);
                    return;
                }

                // Traducir cualquier sub-paquete adicional en el mismo batch del login
                List<byte[]> canonicalSubs = new ArrayList<>(normalizedSubs.size());
                for (byte[] sub : normalizedSubs) {
                    if (sub.length > 0 && (sub[0] & 0xFF) == 0x01) {
                        canonicalSubs.add(sub);
                    } else if (sub.length > 0) {
                        try {
                            InboundResult res = PacketTranslator.translateInbound(sub, state.getProtocol(), state);
                            if (!res.suppress() && res.canonicalPacket() != null) {
                                canonicalSubs.add(res.canonicalPacket());
                            }
                        } catch (RuntimeException subEx) {
                            metrics.recordDroppedPacket();
                            logger.warn(
                                    Category.PACKET,
                                    String.format("Error traduciendo sub-paquete en lote de login (%s, pid=0x%02x, hex=%s): %s",
                                            state.getUsername(), sub[0] & 0xFF, hexPreview(sub, 24), subEx.getMessage())
                            );
                        }
                    }
                }

                if (canonicalSubs.isEmpty()) {
                    ReferenceCountUtil.release(rakMsg);
                    return;
                }
                forwardCanonicalInbound(ctx, rakMsg, canonicalSubs, System.nanoTime() - startNanos);
                return;
            }

            // Sesion legada ya identificada
            LegacyProtocol sourceProto = state.getProtocol();
            List<byte[]> rawSubs = BatchCodec.decodeRakNetBatchBody(
                    body, rakNetVersion, sourceProto, config.getMaxBatchBytes()
            );
            if (rawSubs == null || rawSubs.isEmpty()) {
                ReferenceCountUtil.release(rakMsg);
                return;
            }
            List<byte[]> canonicalSubs = new ArrayList<>(rawSubs.size());

            for (byte[] rawSub : rawSubs) {
                if (rawSub == null || rawSub.length == 0) {
                    continue;
                }
                int legacyPid = rawSub[0] & 0xFF;
                try {
                    InboundResult translated = PacketTranslator.translateInbound(rawSub, sourceProto, state);
                    if (translated.suppress() || translated.canonicalPacket() == null) {
                        metrics.recordDroppedPacket();
                        if (!config.isSuppressUnknownPackets()) {
                            logger.warn(Category.DROP, "Paquete entrante sin mapeo (" + state.getUsername() + "): proto="
                                    + sourceProto.getProtocol() + " pid=0x" + Integer.toHexString(legacyPid));
                        } else {
                            logger.debug(Category.DROP, "Sub-paquete entrante omitido (" + state.getUsername() + "): proto="
                                    + sourceProto.getProtocol() + " pid=0x" + Integer.toHexString(legacyPid));
                        }
                    } else {
                        int v113Pid = translated.canonicalPacket()[0] & 0xFF;
                        logger.debug(
                                Category.PACKET,
                                String.format("IN [%s | p%d] 0x%02x -> v113 0x%02x (%d bytes)",
                                        state.getUsername(), sourceProto.getProtocol(), legacyPid, v113Pid,
                                        translated.canonicalPacket().length)
                        );
                        canonicalSubs.add(translated.canonicalPacket());
                    }
                } catch (RuntimeException subEx) {
                    metrics.recordDroppedPacket();
                    logger.warn(
                            Category.PACKET,
                            String.format("Error traduciendo sub-paquete entrante [%s | p%d] pid=0x%02x hex=%s: %s",
                                    state.getUsername(), sourceProto.getProtocol(), legacyPid,
                                    hexPreview(rawSub, 24), subEx.getMessage())
                    );
                }
            }

            if (canonicalSubs.isEmpty()) {
                ReferenceCountUtil.release(rakMsg);
                return;
            }

            forwardCanonicalInbound(ctx, rakMsg, canonicalSubs, System.nanoTime() - startNanos);
        } catch (Throwable ex) {
            logger.warn(
                    Category.PACKET,
                    "Error traduciendo lote entrante ("
                            + (state != null ? state.getUsername() : ctx.channel().remoteAddress())
                            + ", rak=" + rakNetVersion
                            + ", hex=" + hexPreview(body, 24)
                            + "): " + ex.getClass().getSimpleName() + ": " + ex.getMessage()
            );
            if (state != null || rakNetVersion <= 7) {
                ReferenceCountUtil.release(rakMsg);
                return;
            }
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf buf)) {
            ctx.write(msg, promise);
            return;
        }

        LegacySessionState state = registry.getByChannel(ctx.channel().id());
        if (state == null || state.isModernDetached() || !buf.isReadable()) {
            ctx.write(msg, promise);
            return;
        }

        int readerIdx = buf.readerIndex();
        if (buf.getUnsignedByte(readerIdx) != 0xfe || buf.readableBytes() <= 1) {
            ctx.write(msg, promise);
            return;
        }

        long startNanos = System.nanoTime();
        byte[] compressedV113 = new byte[buf.readableBytes() - 1];
        buf.getBytes(readerIdx + 1, compressedV113);

        try {
            byte[] rawV113 = BatchCodec.inflateZlibOrRaw(
                    compressedV113, 0, compressedV113.length, config.getMaxBatchBytes()
            );
            List<byte[]> v113Subs = BatchCodec.unpackSubPackets(rawV113, false);
            List<byte[]> legacySubs = new ArrayList<>(v113Subs.size());
            LegacyProtocol targetProto = state.getProtocol();
            boolean needSyntheticRpComplete = false;

            for (byte[] v113Sub : v113Subs) {
                if (v113Sub.length == 0) {
                    continue;
                }
                int v113Pid = v113Sub[0] & 0xFF;
                try {
                    OutboundResult translated = PacketTranslator.translateOutbound(
                            v113Sub, targetProto, config.isAutoBypassResourcePacks015(), registry, state
                    );

                    if (translated.synthesizeResourceComplete() && !state.isBypassedResourcePacks()) {
                        state.setBypassedResourcePacks(true);
                        needSyntheticRpComplete = true;
                        logger.info(
                                Category.NEGOTIATION,
                                "Bypass de ResourcePacks aplicado automaticamente para 0.15.x (" + state.getUsername() + ")"
                        );
                    }

                    if (translated.suppress() || translated.legacyPacket() == null) {
                        metrics.recordDroppedPacket();
                        logger.debug(
                                Category.DROP,
                                String.format("OUT omitido [%s | p%d] v113 pid=0x%02x",
                                        state.getUsername(), targetProto.getProtocol(), v113Pid)
                        );
                    } else {
                        if (v113Pid == 0x3a) {
                            metrics.recordChunkConverted();
                            logger.debug(
                                    Category.CHUNK,
                                    String.format("Chunk convertido [%s | p%d] v113=%dB -> legacy=%dB",
                                            state.getUsername(), targetProto.getProtocol(),
                                            v113Sub.length, translated.legacyPacket().length)
                            );
                        } else {
                            int legacyPid = translated.legacyPacket()[0] & 0xFF;
                            logger.debug(
                                    Category.PACKET,
                                    String.format("OUT [%s | p%d] v113 0x%02x -> 0x%02x (%d bytes)",
                                            state.getUsername(), targetProto.getProtocol(), v113Pid, legacyPid,
                                            translated.legacyPacket().length)
                            );
                        }
                        legacySubs.add(translated.legacyPacket());
                    }
                } catch (RuntimeException subEx) {
                    metrics.recordDroppedPacket();
                    logger.warn(
                            Category.PACKET,
                            String.format("Error traduciendo sub-paquete saliente [%s | p%d] v113 pid=0x%02x: %s",
                                    state.getUsername(), targetProto.getProtocol(), v113Pid, subEx.getMessage())
                    );
                }
            }

            if (needSyntheticRpComplete) {
                scheduleSyntheticResourcePackComplete(ctx);
            }

            if (legacySubs.isEmpty()) {
                ReferenceCountUtil.release(buf);
                promise.trySuccess();
                return;
            }

            byte[] legacyBatchBody = BatchCodec.encodeRakNetBatchBody(
                    targetProto, legacySubs, config.getCompressionLevel()
            );
            ByteBuf outBuf = ctx.alloc().buffer(1 + legacyBatchBody.length);
            if (targetProto.isBefore80()) {
                if (targetProto.getProtocol() >= 45) {
                    outBuf.writeByte(0x8e);
                }
            }else{
                outBuf.writeByte(0xfe);
            }
            outBuf.writeBytes(legacyBatchBody);

            metrics.recordOutboundTranslated(System.nanoTime() - startNanos);
            ReferenceCountUtil.release(buf);
            ctx.write(outBuf, promise);
        } catch (Throwable ex) {
            logger.warn(
                    Category.PACKET,
                    "Error traduciendo lote saliente (" + state.getUsername() + ", hex="
                            + hexPreview(compressedV113, 24) + "): "
                            + ex.getClass().getSimpleName() + ": " + ex.getMessage()
            );
            ReferenceCountUtil.release(buf);
            promise.tryFailure(ex);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        LegacySessionState removed = registry.removeByChannel(ctx.channel().id());
        if (removed != null) {
            metrics.onLegacySessionClosed();
            logger.info(
                    Category.CONNECTION,
                    String.format("Sesion legada cerrada: %s (%s, proto=%d)",
                            removed.getUsername(), removed.getRemoteAddress(), removed.getRawProtocol())
            );
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        LegacySessionState state = registry.getByChannel(ctx.channel().id());
        if (state != null) {
            logger.warn(
                    Category.CONNECTION,
                    String.format("Excepcion en pipeline (%s, proto=%d): %s: %s",
                            state.getUsername(), state.getRawProtocol(),
                            cause.getClass().getSimpleName(), cause.getMessage())
            );
        }
        ctx.fireExceptionCaught(cause);
    }

    private void forwardCanonicalInbound(
            ChannelHandlerContext ctx,
            RakMessage originalMsg,
            List<byte[]> canonicalSubs,
            long elapsedNanos
    ) {
        byte[] canonicalBody = BatchCodec.encodeCanonicalV113Body(canonicalSubs, config.getCompressionLevel());
        ByteBuf canonicalBuf = ctx.alloc().buffer(1 + canonicalBody.length);
        canonicalBuf.writeByte(0xfe);
        canonicalBuf.writeBytes(canonicalBody);
        RakMessage forwardMsg = originalMsg.replace(canonicalBuf);
        metrics.recordInboundTranslated(elapsedNanos);
        ReferenceCountUtil.release(originalMsg);
        ctx.fireChannelRead(forwardMsg);
    }

    private void scheduleSyntheticResourcePackComplete(ChannelHandlerContext ctx) {
        ctx.channel().eventLoop().execute(() -> {
            ChannelHandlerContext bridgeCtx = ctx.pipeline().context(HANDLER_NAME);
            if (bridgeCtx == null || !ctx.channel().isActive()) {
                return;
            }
            byte[] syntheticPk = PacketTranslator.buildSyntheticResourcePackCompleteV113();
            byte[] body = BatchCodec.encodeCanonicalV113Body(
                    Collections.singletonList(syntheticPk), config.getCompressionLevel()
            );
            ByteBuf buf = ctx.alloc().buffer(1 + body.length);
            buf.writeByte(0xfe);
            buf.writeBytes(body);
            bridgeCtx.fireChannelRead(new RakMessage(buf));
        });
    }

    private static String hexPreview(byte[] data, int maxLen) {
        if (data == null || data.length == 0) {
            return "<empty>";
        }
        int n = Math.min(data.length, maxLen);
        StringBuilder sb = new StringBuilder(n * 2 + 8);
        for (int i = 0; i < n; i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        if (data.length > n) {
            sb.append("...(").append(data.length).append("B)");
        }
        return sb.toString();
    }

    public static int resolveRakVersion(io.netty.channel.Channel channel) {
        if (channel instanceof RakChildChannel rakChild) {
            return rakChild.config().getProtocolVersion();
        }
        return 8;
    }
}
