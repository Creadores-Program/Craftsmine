package com.craftsmine.nkm.network;

import cn.nukkit.GameVersion;
import cn.nukkit.Server;
import cn.nukkit.network.Network;
import cn.nukkit.network.PacketPool;
import cn.nukkit.network.protocol.MobEffectPacket;
import cn.nukkit.network.protocol.ProtocolInfo;
import cn.nukkit.network.protocol.v113.ProtocolInfo_v113;
import cn.nukkit.scheduler.TaskHandler;
import com.craftsmine.nkm.config.CraftsmineConfig;
import com.craftsmine.nkm.logging.CraftsmineLogger;
import com.craftsmine.nkm.logging.CraftsmineLogger.Category;
import com.craftsmine.nkm.metrics.PerformanceMetrics;
import com.craftsmine.nkm.protocol.LegacyProtocol;
import cn.nukkit.plugin.PluginBase;

/**
 * Orquesta la integracion con la API Multiversion de Nukkit-MOT (Network, GameVersion y PacketPool)
 * y coordina el ciclo de vida del hook RakNet y las metricas periodicas.
 */
public final class MultiversionBridgeManager {

    private final PluginBase plugin;
    private final CraftsmineConfig config;
    private final CraftsmineLogger logger;
    private final SessionProtocolRegistry registry;
    private final PerformanceMetrics metrics;
    private final RakNetBridgeHook rakNetHook;

    private TaskHandler metricsTask;
    private int hookedInterfaces;

    public MultiversionBridgeManager(
            PluginBase plugin,
            CraftsmineConfig config,
            CraftsmineLogger logger,
            SessionProtocolRegistry registry,
            PerformanceMetrics metrics
    ) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.registry = registry;
        this.metrics = metrics;
        this.rakNetHook = new RakNetBridgeHook(config, logger, registry, metrics);
    }

    public void initialize() {
        Server server = plugin.getServer();
        verifyMultiversionApi(server.getNetwork());
        registerV113MobEffectPacket(server.getNetwork());
        this.hookedInterfaces = rakNetHook.install(server);
        logger.info(
                Category.SYSTEM,
                String.format(
                        "Puente Multiversion activo: rango configurado=%d..%d | interfaces RakNet enganchadas=%d | base canonica=%d (%s)",
                        config.getMinProtocol(),
                        config.getMaxProtocol(),
                        hookedInterfaces,
                        LegacyProtocol.CANONICAL_NUKKIT_PROTOCOL,
                        GameVersion.V1_1_0.name()
                )
        );
        scheduleMetricsTask();
    }

    public void verifyMultiversionApi(Network network) {
        if (network == null) {
            return;
        }
        // Usa la API Multiversion oficial de Nukkit-MOT (Network#getPacketPool(GameVersion))
        PacketPool basePool = network.getPacketPool(GameVersion.V1_1_0);
        int supportedCount = ProtocolInfo.SUPPORTED_PROTOCOLS.size();
        logger.debug(
                Category.SYSTEM,
                "Nukkit-MOT Multiversion API verificada: PacketPool v1_1_0="
                        + (basePool != null ? "OK" : "NULL")
                        + " | Protocolos nativos en Nukkit-MOT=" + supportedCount
                        + " (desde " + ProtocolInfo.v1_1_0 + " hasta " + ProtocolInfo.CURRENT_PROTOCOL + ")"
        );
    }

    private void registerV113MobEffectPacket(Network network) {
        if (network == null) {
            return;
        }
        // Nukkit-MOT NO registra MobEffectPacket en packetPool113 (Network#registerPackets, lineas
        // 579-664: solo estan MOB_ARMOR_EQUIPMENT y MOB_EQUIPMENT). En protocolo 113 (< v1_2_0=137)
        // DataPacket#reset() resuelve el pid del sub-paquete con PacketPool#getPacketId(MobEffectPacket.class),
        // que lanza IllegalArgumentException (PacketPool.java:58-65) y cae al fallback 0x6a (DataPacket.java:
        // 58-67, "id inexistente en 1.1 para paquetes no soportados"). El sub-paquete llega entonces al
        // pipeline con pid 0x6a, PacketIdMapper no lo mapea y los efectos se descartan para TODOS los
        // clientes 80-113 (tambien los 1.1.0 vanilla). Registro oficial via Network#registerPacket(GameVersion,
        // byte, Class) (Network.java:223-226) para que Nukkit-MOT codifique el pid real 0x1d
        // (ProtocolInfo_v113.java:37, igual a MySoft PacketsIds110.php:55) y el case 0x1d de
        // translateMobEffectOutboundResult se ejecute.
        network.registerPacket(GameVersion.V1_1_0, ProtocolInfo_v113.MOB_EFFECT_PACKET, MobEffectPacket.class);
        logger.info(
                Category.SYSTEM,
                "MobEffectPacket registrado en PacketPool v1_1_0 (pid 0x1d): efectos visibles para clientes 80-113"
        );
    }

    public synchronized void scheduleMetricsTask() {
        if (metricsTask != null) {
            metricsTask.cancel();
            metricsTask = null;
        }
        int interval = config.getMetricsIntervalSeconds();
        if (interval <= 0) {
            return;
        }
        int ticks = interval * 20;
        this.metricsTask = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, () -> {
            PerformanceMetrics.Snapshot snap = metrics.snapshot();
            if (snap.activeLegacySessions() > 0 || snap.inboundPacketsTranslated() > 0 || config.isDebug()) {
                logger.info(Category.METRICS, snap.formatSummary());
            }
        }, ticks, true);
    }

    public int getHookedInterfaces() {
        return hookedInterfaces;
    }

    public void shutdown() {
        if (metricsTask != null) {
            metricsTask.cancel();
            metricsTask = null;
        }
        rakNetHook.uninstall();
        registry.clear();
    }
}
