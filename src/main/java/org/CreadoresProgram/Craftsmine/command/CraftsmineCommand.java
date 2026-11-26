package org.CreadoresProgram.Craftsmine.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.utils.TextFormat;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.CreadoresProgram.Craftsmine.CraftsmineNKMPlugin;
import org.CreadoresProgram.Craftsmine.config.CraftsmineConfig;
import org.CreadoresProgram.Craftsmine.logging.CraftsmineLogger;
import org.CreadoresProgram.Craftsmine.logging.CraftsmineLogger.Category;
import org.CreadoresProgram.Craftsmine.metrics.PerformanceMetrics;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry.LegacySessionState;
import org.CreadoresProgram.Craftsmine.protocol.LegacyProtocol;

/**
 * Comando administrativo /craftsmine para monitoreo en tiempo real, toggle de debug
 * en config.yml, recarga y consulta de sesiones y protocolos.
 */
public final class CraftsmineCommand extends Command {

    private final CraftsmineNKMPlugin plugin;
    private final CraftsmineConfig config;
    private final CraftsmineLogger logger;
    private final SessionProtocolRegistry registry;
    private final PerformanceMetrics metrics;

    public CraftsmineCommand(
            CraftsmineNKMPlugin plugin,
            CraftsmineConfig config,
            CraftsmineLogger logger,
            SessionProtocolRegistry registry,
            PerformanceMetrics metrics
    ) {
        super(
                "craftsmine",
                "Administracion y monitoreo en tiempo real de CraftsmineNKM",
                "/craftsmine <status|debug <on|off>|reload|logs [n]|protocols|sessions>",
                new String[]{"cmnkm", "nkm"}
        );
        this.setPermission("craftsmine.admin");
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.registry = registry;
        this.metrics = metrics;
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) {
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                PerformanceMetrics.Snapshot snap = metrics.snapshot();
                sender.sendMessage(TextFormat.GOLD + "=== CraftsmineNKM v" + plugin.getDescription().getVersion() + " ===");
                sender.sendMessage(TextFormat.GRAY + "Rango puente: " + TextFormat.GREEN + config.getMinProtocol() + " - " + config.getMaxProtocol()
                        + TextFormat.GRAY + " | Debug: " + (config.isDebug() ? TextFormat.GREEN + "ON" : TextFormat.RED + "OFF")
                        + TextFormat.GRAY + " | RakNet v6: " + (config.isEnableRakNetV6() ? TextFormat.GREEN + "ON" : TextFormat.RED + "OFF")
                        + TextFormat.GRAY + " | RakNet v7: " + (config.isEnableRakNetV7() ? TextFormat.GREEN + "ON" : TextFormat.RED + "OFF"));
                sender.sendMessage(TextFormat.AQUA + snap.formatSummary());
            }
            case "debug" -> {
                boolean next = args.length > 1
                        ? ("on".equalsIgnoreCase(args[1]) || "true".equalsIgnoreCase(args[1]))
                        : !config.isDebug();
                config.setDebug(next);
                logger.info(Category.SYSTEM, "Modo debug actualizado via comando a: " + next);
                sender.sendMessage(TextFormat.GREEN + "[CraftsmineNKM] Debug en config.yml ahora esta "
                        + (next ? TextFormat.BOLD + "ACTIVADO" : TextFormat.RED + "DESACTIVADO"));
            }
            case "reload" -> {
                config.reload();
                logger.reopenFileWriter();
                plugin.getBridgeManager().scheduleMetricsTask();
                logger.info(Category.SYSTEM, "Configuracion y logger recargados desde config.yml (debug=" + config.isDebug() + ")");
                sender.sendMessage(TextFormat.GREEN + "[CraftsmineNKM] config.yml y sistema de logs recargados.");
            }
            case "logs" -> {
                int limit = 15;
                if (args.length > 1) {
                    try {
                        limit = Math.max(1, Math.min(100, Integer.parseInt(args[1])));
                    } catch (NumberFormatException ignored) {
                    }
                }
                List<String> lines = logger.getRecentLines(limit);
                sender.sendMessage(TextFormat.GOLD + "=== Ultimos " + lines.size() + " logs CraftsmineNKM ===");
                if (lines.isEmpty()) {
                    sender.sendMessage(TextFormat.GRAY + "(Sin eventos registrados aun)");
                } else {
                    for (String line : lines) {
                        sender.sendMessage(TextFormat.WHITE + line);
                    }
                }
            }
            case "protocols" -> {
                sender.sendMessage(TextFormat.GOLD + "=== Protocolos soportados por CraftsmineNKM (41 - 113) ===");
                for (LegacyProtocol lp : LegacyProtocol.all()) {
                    sender.sendMessage(TextFormat.GREEN + "p" + lp.getProtocol() + TextFormat.GRAY + " -> MCPE "
                            + lp.getMcVersion() + " | RakNet v" + lp.getRakNetVersion() + " | " + lp.getFamily().name());
                }
            }
            case "sessions" -> {
                Collection<LegacySessionState> sessions = registry.getActiveSessions();
                sender.sendMessage(TextFormat.GOLD + "=== Sesiones legadas activas (" + sessions.size() + ") ===");
                if (sessions.isEmpty()) {
                    sender.sendMessage(TextFormat.GRAY + "No hay clientes legados (41-112) conectados.");
                } else {
                    for (LegacySessionState s : sessions) {
                        sender.sendMessage(TextFormat.AQUA + s.getUsername() + TextFormat.GRAY + " | proto=" + s.getRawProtocol()
                                + " (" + s.getProtocol().getMcVersion() + ") | ip=" + s.getRemoteAddress());
                    }
                }
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(TextFormat.GOLD + "Comandos CraftsmineNKM:");
        sender.sendMessage(TextFormat.YELLOW + "/craftsmine status" + TextFormat.GRAY + " - Estado y metricas del puente");
        sender.sendMessage(TextFormat.YELLOW + "/craftsmine debug <on|off>" + TextFormat.GRAY + " - Activa/desactiva debug en config.yml");
        sender.sendMessage(TextFormat.YELLOW + "/craftsmine reload" + TextFormat.GRAY + " - Recarga config.yml y archivo de logs");
        sender.sendMessage(TextFormat.YELLOW + "/craftsmine logs [n]" + TextFormat.GRAY + " - Muestra ultimas N trazas en tiempo real");
        sender.sendMessage(TextFormat.YELLOW + "/craftsmine protocols" + TextFormat.GRAY + " - Lista completa 41..113");
        sender.sendMessage(TextFormat.YELLOW + "/craftsmine sessions" + TextFormat.GRAY + " - Clientes legados conectados");
    }
}
