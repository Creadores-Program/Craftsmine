package org.CreadoresProgram.Craftsmine.logging;

import cn.nukkit.plugin.PluginLogger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.CreadoresProgram.Craftsmine.config.CraftsmineConfig;

public final class CraftsmineLogger {

    public enum Category {
        SYSTEM,
        CONNECTION,
        NEGOTIATION,
        PACKET,
        CHUNK,
        DROP,
        METRICS
    }

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final PluginLogger pluginLogger;
    private final CraftsmineConfig config;
    private final File dataFolder;
    private final Deque<String> ringBuffer = new ConcurrentLinkedDeque<>();
    private final Object fileLock = new Object();

    private BufferedWriter fileWriter;
    private String activeLogFilePath;

    public CraftsmineLogger(PluginLogger pluginLogger, CraftsmineConfig config, File dataFolder) {
        this.pluginLogger = pluginLogger;
        this.config = config;
        this.dataFolder = dataFolder;
        reopenFileWriter();
    }

    public void reopenFileWriter() {
        synchronized (fileLock) {
            closeFileWriter();
            if (!config.isLoggingEnabled() || !config.isFileLogging()) {
                return;
            }
            String relPath = config.getLogFile();
            if (relPath == null || relPath.isBlank()) {
                relPath = "logs/craftsmine-realtime.log";
            }
            File target = new File(dataFolder, relPath);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                pluginLogger.warning("[CraftsmineNKM] No se pudo crear directorio de logs: " + parent.getAbsolutePath());
                return;
            }
            try {
                this.fileWriter = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(target, true), StandardCharsets.UTF_8)
                );
                this.activeLogFilePath = target.getAbsolutePath();
            } catch (IOException e) {
                pluginLogger.warning("[CraftsmineNKM] No se pudo abrir archivo de logs: " + e.getMessage());
            }
        }
    }

    public void info(Category category, String message) {
        if (!isCategoryEnabled(category)) {
            return;
        }
        String formatted = formatLine("INFO", category, message);
        appendBufferAndFile(formatted);
        pluginLogger.info("[" + category.name() + "] " + message);
    }

    public void warn(Category category, String message) {
        if (!config.isLoggingEnabled()) {
            return;
        }
        String formatted = formatLine("WARN", category, message);
        appendBufferAndFile(formatted);
        pluginLogger.warning("[" + category.name() + "] " + message);
    }

    public void error(Category category, String message, Throwable error) {
        String formatted = formatLine("ERROR", category, message + (error != null ? " -> " + error.getMessage() : ""));
        appendBufferAndFile(formatted);
        if (error != null) {
            pluginLogger.error("[" + category.name() + "] " + message, error);
        } else {
            pluginLogger.error("[" + category.name() + "] " + message);
        }
    }

    public void debug(Category category, String message) {
        if (!config.isDebug() || !isCategoryEnabled(category)) {
            return;
        }
        String formatted = formatLine("DEBUG", category, message);
        appendBufferAndFile(formatted);
        pluginLogger.info("[DEBUG/" + category.name() + "] " + message);
    }

    public boolean isCategoryEnabled(Category category) {
        if (!config.isLoggingEnabled()) {
            return false;
        }
        return switch (category) {
            case SYSTEM -> true;
            case CONNECTION -> config.isLogConnections();
            case NEGOTIATION -> config.isLogProtocolNegotiation();
            case PACKET -> config.isLogPacketTranslation();
            case CHUNK -> config.isLogChunkConversion();
            case DROP -> config.isLogDroppedPackets();
            case METRICS -> config.isLogPerformanceMetrics();
        };
    }

    private String formatLine(String level, Category category, String message) {
        return "[" + TIME_FORMAT.format(Instant.now()) + "] [" + level + "] [" + category.name() + "] " + message;
    }

    private void appendBufferAndFile(String line) {
        ringBuffer.addLast(line);
        int maxLines = config.getMaxBufferLines();
        while (ringBuffer.size() > maxLines) {
            ringBuffer.pollFirst();
        }
        if (config.isFileLogging()) {
            synchronized (fileLock) {
                if (fileWriter != null) {
                    try {
                        fileWriter.write(line);
                        fileWriter.newLine();
                        fileWriter.flush();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    public List<String> getRecentLines(int limit) {
        List<String> snapshot = new ArrayList<>(ringBuffer);
        int size = snapshot.size();
        if (limit <= 0 || limit >= size) {
            return snapshot;
        }
        return snapshot.subList(size - limit, size);
    }

    public String getActiveLogFilePath() {
        return activeLogFilePath;
    }

    public void close() {
        synchronized (fileLock) {
            closeFileWriter();
        }
    }

    private void closeFileWriter() {
        if (fileWriter != null) {
            try {
                fileWriter.flush();
                fileWriter.close();
            } catch (IOException ignored) {
            }
            fileWriter = null;
        }
    }
}
