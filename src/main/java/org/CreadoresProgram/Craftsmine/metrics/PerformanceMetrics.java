package org.CreadoresProgram.Craftsmine.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class PerformanceMetrics {

    private final long startedAtMillis = System.currentTimeMillis();
    private final AtomicInteger activeLegacySessions = new AtomicInteger(0);
    private final LongAdder totalLegacyConnections = new LongAdder();
    private final LongAdder inboundPacketsTranslated = new LongAdder();
    private final LongAdder outboundPacketsTranslated = new LongAdder();
    private final LongAdder chunksConverted = new LongAdder();
    private final LongAdder droppedPackets = new LongAdder();
    private final LongAdder translationTimeNanos = new LongAdder();
    private final LongAdder modernBypassedSessions = new LongAdder();

    public void onLegacySessionOpened() {
        activeLegacySessions.incrementAndGet();
        totalLegacyConnections.increment();
    }

    public void onLegacySessionClosed() {
        activeLegacySessions.updateAndGet(cur -> Math.max(0, cur - 1));
    }

    public void onModernBypassed() {
        modernBypassedSessions.increment();
    }

    public void recordInboundTranslated(long elapsedNanos) {
        inboundPacketsTranslated.increment();
        translationTimeNanos.add(Math.max(0L, elapsedNanos));
    }

    public void recordOutboundTranslated(long elapsedNanos) {
        outboundPacketsTranslated.increment();
        translationTimeNanos.add(Math.max(0L, elapsedNanos));
    }

    public void recordChunkConverted() {
        chunksConverted.increment();
    }

    public void recordDroppedPacket() {
        droppedPackets.increment();
    }

    public Snapshot snapshot() {
        long in = inboundPacketsTranslated.sum();
        long out = outboundPacketsTranslated.sum();
        long totalPackets = in + out;
        long totalNanos = translationTimeNanos.sum();
        double avgMicros = totalPackets == 0 ? 0.0 : (totalNanos / 1000.0) / totalPackets;
        return new Snapshot(
                System.currentTimeMillis() - startedAtMillis,
                activeLegacySessions.get(),
                totalLegacyConnections.sum(),
                in,
                out,
                chunksConverted.sum(),
                droppedPackets.sum(),
                modernBypassedSessions.sum(),
                avgMicros
        );
    }

    public record Snapshot(
            long uptimeMillis,
            int activeLegacySessions,
            long totalLegacyConnections,
            long inboundPacketsTranslated,
            long outboundPacketsTranslated,
            long chunksConverted,
            long droppedPackets,
            long modernBypassedSessions,
            double averageTranslationMicros
    ) {
        public String formatSummary() {
            return String.format(
                    "Sesiones legadas activas=%d | Total conexiones=%d | Bypass modernos=%d | In=%d | Out=%d | Chunks=%d | Omitidos=%d | Latencia promedio=%.2fus",
                    activeLegacySessions,
                    totalLegacyConnections,
                    modernBypassedSessions,
                    inboundPacketsTranslated,
                    outboundPacketsTranslated,
                    chunksConverted,
                    droppedPackets,
                    averageTranslationMicros
            );
        }
    }
}
