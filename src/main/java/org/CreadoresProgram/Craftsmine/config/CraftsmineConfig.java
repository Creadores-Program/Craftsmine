package org.CreadoresProgram.Craftsmine.config;

import cn.nukkit.plugin.PluginBase;
import cn.nukkit.utils.Config;

public final class CraftsmineConfig {

    private final PluginBase plugin;

    private volatile boolean debug;
    private volatile int minProtocol;
    private volatile int maxProtocol;
    private volatile boolean enableRakNetV7;
    private volatile boolean autoBypassResourcePacks015;
    private volatile boolean suppressUnknownPackets;

    private volatile boolean loggingEnabled;
    private volatile boolean logConnections;
    private volatile boolean logProtocolNegotiation;
    private volatile boolean logPacketTranslation;
    private volatile boolean logChunkConversion;
    private volatile boolean logDroppedPackets;
    private volatile boolean logPerformanceMetrics;
    private volatile boolean fileLogging;
    private volatile String logFile;
    private volatile int maxBufferLines;
    private volatile int metricsIntervalSeconds;

    private volatile int compressionLevel;
    private volatile int maxBatchBytes;
    private volatile boolean detachModernClients;
    private volatile int initialBufferBytes;
    private volatile long sessionTimeoutMs;
    private volatile int orderingChannels;

    public CraftsmineConfig(PluginBase plugin) {
        this.plugin = plugin;
        this.plugin.saveDefaultConfig();
        reload();
    }

    public synchronized void reload() {
        this.plugin.reloadConfig();
        Config cfg = this.plugin.getConfig();

        this.debug = cfg.getBoolean("debug", false);

        this.minProtocol = Math.max(80, cfg.getInt("protocols.min-protocol", 80));
        this.maxProtocol = Math.min(113, cfg.getInt("protocols.max-protocol", 113));
        if (this.maxProtocol < this.minProtocol) {
            this.maxProtocol = this.minProtocol;
        }
        this.enableRakNetV7 = cfg.getBoolean("protocols.enable-raknet-v7", true);
        this.autoBypassResourcePacks015 = cfg.getBoolean("protocols.auto-bypass-resource-packs-015", true);
        this.suppressUnknownPackets = cfg.getBoolean("protocols.suppress-unknown-packets", true);

        this.loggingEnabled = cfg.getBoolean("logging.enabled", true);
        this.logConnections = cfg.getBoolean("logging.log-connections", true);
        this.logProtocolNegotiation = cfg.getBoolean("logging.log-protocol-negotiation", true);
        this.logPacketTranslation = cfg.getBoolean("logging.log-packet-translation", true);
        this.logChunkConversion = cfg.getBoolean("logging.log-chunk-conversion", true);
        this.logDroppedPackets = cfg.getBoolean("logging.log-dropped-packets", true);
        this.logPerformanceMetrics = cfg.getBoolean("logging.log-performance-metrics", true);
        this.fileLogging = cfg.getBoolean("logging.file-logging", true);
        this.logFile = cfg.getString("logging.log-file", "logs/craftsmine-realtime.log");
        this.maxBufferLines = Math.max(50, Math.min(5000, cfg.getInt("logging.max-buffer-lines", 500)));
        this.metricsIntervalSeconds = Math.max(0, cfg.getInt("logging.metrics-interval-seconds", 60));

        this.compressionLevel = Math.max(1, Math.min(9, cfg.getInt("performance.compression-level", 6)));
        this.maxBatchBytes = Math.max(65536, cfg.getInt("performance.max-batch-bytes", 4 * 1024 * 1024));
        this.detachModernClients = cfg.getBoolean("performance.detach-modern-clients", true);
        this.initialBufferBytes = Math.max(1024, cfg.getInt("performance.initial-buffer-bytes", 8192));
        this.sessionTimeoutMs = Math.max(10000L, Math.min(120000L, cfg.getLong("performance.session-timeout-ms", 30000L)));
        this.orderingChannels = Math.max(1, Math.min(32, cfg.getInt("performance.ordering-channels", 32)));
    }

    public synchronized void setDebug(boolean enabled) {
        this.debug = enabled;
        Config cfg = this.plugin.getConfig();
        cfg.set("debug", enabled);
        this.plugin.saveConfig();
    }

    public boolean isDebug() {
        return debug;
    }

    public int getMinProtocol() {
        return minProtocol;
    }

    public int getMaxProtocol() {
        return maxProtocol;
    }

    public boolean isEnableRakNetV7() {
        return enableRakNetV7;
    }

    public boolean isAutoBypassResourcePacks015() {
        return autoBypassResourcePacks015;
    }

    public boolean isSuppressUnknownPackets() {
        return suppressUnknownPackets;
    }

    public boolean isLoggingEnabled() {
        return loggingEnabled;
    }

    public boolean isLogConnections() {
        return logConnections;
    }

    public boolean isLogProtocolNegotiation() {
        return logProtocolNegotiation;
    }

    public boolean isLogPacketTranslation() {
        return logPacketTranslation;
    }

    public boolean isLogChunkConversion() {
        return logChunkConversion;
    }

    public boolean isLogDroppedPackets() {
        return logDroppedPackets;
    }

    public boolean isLogPerformanceMetrics() {
        return logPerformanceMetrics;
    }

    public boolean isFileLogging() {
        return fileLogging;
    }

    public String getLogFile() {
        return logFile;
    }

    public int getMaxBufferLines() {
        return maxBufferLines;
    }

    public int getMetricsIntervalSeconds() {
        return metricsIntervalSeconds;
    }

    public int getCompressionLevel() {
        return compressionLevel;
    }

    public int getMaxBatchBytes() {
        return maxBatchBytes;
    }

    public boolean isDetachModernClients() {
        return detachModernClients;
    }

    public int getInitialBufferBytes() {
        return initialBufferBytes;
    }

    public long getSessionTimeoutMs() {
        return sessionTimeoutMs;
    }

    public int getOrderingChannels() {
        return orderingChannels;
    }
}
