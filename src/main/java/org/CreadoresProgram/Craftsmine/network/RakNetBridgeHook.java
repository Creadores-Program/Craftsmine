package org.CreadoresProgram.Craftsmine.network;

import cn.nukkit.Server;
import cn.nukkit.network.RakNetInterface;
import cn.nukkit.network.SourceInterface;
import cn.nukkit.network.session.RakNetPlayerSession;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;

import org.CreadoresProgram.Craftsmine.config.CraftsmineConfig;
import org.CreadoresProgram.Craftsmine.logging.CraftsmineLogger;
import org.CreadoresProgram.Craftsmine.logging.CraftsmineLogger.Category;
import org.CreadoresProgram.Craftsmine.metrics.PerformanceMetrics;
import org.cloudburstmc.netty.channel.raknet.config.RakChannelOption;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Engancha RakNetInterface de Nukkit-MOT para:
 * 1) Habilitar RakNet v7 (clientes 0.15.0 - 0.15.10, protocolos 80 - 84).
 * 2) Inyectar LegacyPipelineHandler en cada nuevo RakChildChannel antes de "nukkit-handler".
 */
public final class RakNetBridgeHook {

    private static final String ACCEPTOR_HOOK_NAME = "craftsmine-raknet-acceptor";
    private static final String PRE_INIT_HOOK_NAME = "craftsmine-pre-init";
    private static final int[] LEGACY_SUPPORTED_RAK_VERSIONS_V6_V7 = new int[]{6, 7, 8, 9, 10, 11};
    private static final int[] LEGACY_SUPPORTED_RAK_VERSIONS = new int[]{7, 8, 9, 10, 11};
    private static final int[] DEFAULT_RAK_VERSIONS = new int[]{8, 9, 10, 11};

    private final CraftsmineConfig config;
    private final CraftsmineLogger logger;
    private final SessionProtocolRegistry registry;
    private final PerformanceMetrics metrics;
    private final List<Channel> hookedServerChannels = new ArrayList<>();
    private final Map<Channel, RakServerCookieMode> originalCookieModes = new java.util.IdentityHashMap<>();

    public RakNetBridgeHook(
            CraftsmineConfig config,
            CraftsmineLogger logger,
            SessionProtocolRegistry registry,
            PerformanceMetrics metrics
    ) {
        this.config = config;
        this.logger = logger;
        this.registry = registry;
        this.metrics = metrics;
    }

    public int install(Server server) {
        int hookedCount = 0;
        for (SourceInterface iface : server.getNetwork().getInterfaces()) {
            if (iface instanceof RakNetInterface rakIface) {
                hookedCount += hookRakNetInterface(rakIface);
            }
        }
        return hookedCount;
    }

    private int hookRakNetInterface(RakNetInterface rakIface) {
        int count = 0;
        Channel v4 = readPrivateField(rakIface, "channel", Channel.class);
        Channel v6 = readPrivateField(rakIface, "ipv6Channel", Channel.class);

        if (v4 != null && attachToServerChannel(v4, "IPv4")) {
            count++;
        }
        if (v6 != null && attachToServerChannel(v6, "IPv6")) {
            count++;
        }

        // Proteger sesiones ya abiertas antes del enable/reload
        attachToExistingSessions(rakIface);
        return count;
    }

    private boolean attachToServerChannel(Channel serverChannel, String label) {
        if (config.isEnableRakNetV6() || config.isEnableRakNetV7()) {
            try {
                int[] versions = config.isEnableRakNetV6()
                        ? LEGACY_SUPPORTED_RAK_VERSIONS_V6_V7
                        : LEGACY_SUPPORTED_RAK_VERSIONS;
                serverChannel.config().setOption(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, versions);
                logger.info(
                        Category.SYSTEM,
                        "RakNet " + label + " habilitado para versiones "
                                + (config.isEnableRakNetV6() ? "{6, 7, 8, 9, 10, 11} (soporte 0.14.0+)" : "{7, 8, 9, 10, 11} (soporte 0.15.0+)")
                );
            } catch (Throwable t) {
                logger.warn(Category.SYSTEM, "No se pudo actualizar RAK_SUPPORTED_PROTOCOLS en " + label + ": " + t.getMessage());
            }
            disableCookieModeForLegacyClients(serverChannel, label);
        }

        ChannelPipeline pipeline = serverChannel.pipeline();
        if (pipeline.get(ACCEPTOR_HOOK_NAME) != null) {
            return true;
        }

        pipeline.addFirst(ACCEPTOR_HOOK_NAME, new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                Channel child = (msg instanceof Channel ch) ? ch : null;
                ctx.fireChannelRead(msg);
                if (child != null) {
                    attachChildPipelineWatcher(child);
                }
            }
        });
        hookedServerChannels.add(serverChannel);
        return true;
    }

    /**
     * Con RakNet cookie mode activo (default de Nukkit-MOT: rak-cookie-mode "active"),
     * netty-transport-raknet inserta 4 bytes de cookie en OPEN_CONNECTION_REPLY_1 tras el GUID
     * (RakServerOfflineHandler.onOpenConnectionRequest1: writeLong(guid)+writeBoolean+writeInt(cookie)+
     * writeShort(mtu) = 32B) y descarta todo OPEN_CONNECTION_REQUEST_2 sin cookie exacta
     * ("Received ID_OPEN_CONNECTION_REQUEST_2 with invalid cookie"). Los clientes MCPE 0.14.x-1.1.x
     * (RakNet 6-10, protocolos 41-112) NO implementan cookies: envian REQUEST_2 sin cookie, el servidor
     * lo descarta silenciosamente y el cliente nunca recibe REPLY_2 -> se queda en "Localizando servidor"
     * aunque la traduccion de juego sea correcta. Con NONE, REPLY_1 vuelve al formato legado de 28B
     * ([id][magic][guid][security byte][mtu short]) y REQUEST_2 sin cookie es aceptado (verificado contra
     * Nukkit-Retro OPEN_CONNECTION_REPLY_1.java:35-45 / OPEN_CONNECTION_REQUEST_2.java:33-41).
     */
    private void disableCookieModeForLegacyClients(Channel serverChannel, String label) {
        try {
            RakServerCookieMode current = serverChannel.config().getOption(RakChannelOption.RAK_SERVER_COOKIE_MODE);
            if (shouldForceCookieModeNone(current)) {
                originalCookieModes.put(serverChannel, current);
                serverChannel.config().setOption(RakChannelOption.RAK_SERVER_COOKIE_MODE, RakServerCookieMode.NONE);
                logger.info(
                        Category.SYSTEM,
                        "RakNet cookie mode " + current + " -> NONE en " + label
                                + ": requerido para completar el handshake offline de clientes legado ("
                                + "REQUEST_2 sin cookie se descarta con mode=" + current + ")"
                );
            }
        } catch (Throwable t) {
            logger.warn(Category.SYSTEM, "No se pudo ajustar RAK_SERVER_COOKIE_MODE en " + label + ": " + t.getMessage());
        }
    }

    /**
     * Solo se fuerza NONE cuando hay un modo con cookies activo; NONE y null (opcion
     * no soportada por la build) se respetan.
     */
    public static boolean shouldForceCookieModeNone(RakServerCookieMode current) {
        return current != null && current != RakServerCookieMode.NONE;
    }

    private void attachChildPipelineWatcher(Channel child) {
        if (child == null || !child.isOpen()) {
            return;
        }
        if (tryInjectChildNow(child)) {
            return;
        }
        ChannelPipeline pipeline = child.pipeline();
        if (pipeline.get(PRE_INIT_HOOK_NAME) != null) {
            return;
        }
        pipeline.addFirst(PRE_INIT_HOOK_NAME, new ChannelInboundHandlerAdapter() {
            private void ensureInjected(ChannelHandlerContext ctx) {
                if (tryInjectChildNow(ctx.channel()) && ctx.pipeline().get(PRE_INIT_HOOK_NAME) != null) {
                    try {
                        ctx.pipeline().remove(this);
                    } catch (java.util.NoSuchElementException ignored) {
                    }
                }
            }

            @Override
            public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
                ensureInjected(ctx);
                super.handlerAdded(ctx);
            }

            @Override
            public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
                ensureInjected(ctx);
                ctx.fireChannelRegistered();
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ensureInjected(ctx);
                ctx.fireChannelActive();
            }

            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                ensureInjected(ctx);
                ctx.fireChannelRead(msg);
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                registry.removeByChannel(ctx.channel().id());
                ctx.fireChannelInactive();
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void attachToExistingSessions(RakNetInterface rakIface) {
        try {
            Set<RakNetPlayerSession> pending = readPrivateField(rakIface, "pendingSessions", Set.class);
            if (pending != null) {
                for (RakNetPlayerSession session : pending) {
                    injectSessionChannel(session);
                }
            }
            Map<InetSocketAddress, RakNetPlayerSession> sessions = readPrivateField(rakIface, "sessions", Map.class);
            if (sessions != null) {
                for (RakNetPlayerSession session : sessions.values()) {
                    injectSessionChannel(session);
                }
            }
        } catch (Throwable t) {
            logger.debug(Category.SYSTEM, "Inspeccion de sesiones previas omitida: " + t.getMessage());
        }
    }

    private void injectSessionChannel(RakNetPlayerSession session) {
        if (session == null) {
            return;
        }
        Channel child = readPrivateField(session, "channel", Channel.class);
        if (child != null && child.isActive()) {
            child.eventLoop().execute(() -> injectChildChannel(child));
        }
    }

    public void injectChildChannel(Channel child) {
        tryInjectChildNow(child);
    }

    private boolean tryInjectChildNow(Channel child) {
        if (child == null || !child.isOpen() || !child.isRegistered()) {
            return false;
        }
        ChannelPipeline pipeline = child.pipeline();
        if (pipeline.get(LegacyPipelineHandler.HANDLER_NAME) != null) {
            return true;
        }
        if (pipeline.get("nukkit-handler") == null) {
            return false;
        }
        int rakVersion = LegacyPipelineHandler.resolveRakVersion(child);
        if (rakVersion <= 7 || config.getSessionTimeoutMs() > 10000L) {
            try {
                child.config().setOption(RakChannelOption.RAK_SESSION_TIMEOUT, config.getSessionTimeoutMs());
                if (rakVersion <= 7) {
                    child.config().setOption(RakChannelOption.RAK_ORDERING_CHANNELS, config.getOrderingChannels());
                }
            } catch (Throwable ignored) {
            }
        }
        LegacyPipelineHandler handler = new LegacyPipelineHandler(registry, config, logger, metrics, rakVersion);
        try {
            pipeline.addBefore("nukkit-handler", LegacyPipelineHandler.HANDLER_NAME, handler);
        } catch (IllegalArgumentException alreadyAdded) {
            return pipeline.get(LegacyPipelineHandler.HANDLER_NAME) != null;
        }
        logger.debug(
                Category.CONNECTION,
                "Pipeline CraftsmineNKM inyectado en " + child.remoteAddress()
                        + " (RakNet v" + rakVersion + ", timeout=" + config.getSessionTimeoutMs() + "ms)"
        );
        return true;
    }

    public void uninstall() {
        for (Channel serverChannel : hookedServerChannels) {
            if (serverChannel == null) {
                continue;
            }
            try {
                if (serverChannel.isOpen() && serverChannel.pipeline().get(ACCEPTOR_HOOK_NAME) != null) {
                    serverChannel.pipeline().remove(ACCEPTOR_HOOK_NAME);
                }
                RakServerCookieMode originalCookieMode = originalCookieModes.remove(serverChannel);
                if (serverChannel.isOpen() && originalCookieMode != null) {
                    serverChannel.config().setOption(RakChannelOption.RAK_SERVER_COOKIE_MODE, originalCookieMode);
                }
                if (serverChannel.isOpen()) {
                    serverChannel.config().setOption(RakChannelOption.RAK_SUPPORTED_PROTOCOLS, DEFAULT_RAK_VERSIONS);
                }
            } catch (Throwable ignored) {
            }
        }
        hookedServerChannels.clear();
    }

    private static <T> T readPrivateField(Object target, String fieldName, Class<T> type) {
        Class<?> current = target.getClass();
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object val = f.get(target);
                return type.isInstance(val) ? type.cast(val) : null;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }
}
