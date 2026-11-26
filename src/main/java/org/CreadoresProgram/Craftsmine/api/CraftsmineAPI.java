package org.CreadoresProgram.Craftsmine.api;

import cn.nukkit.Player;

import java.util.Collection;

import org.CreadoresProgram.Craftsmine.metrics.PerformanceMetrics;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry;
import org.CreadoresProgram.Craftsmine.network.SessionProtocolRegistry.LegacySessionState;
import org.CreadoresProgram.Craftsmine.protocol.LegacyProtocol;

/**
 * API publica de CraftsmineNKM para que otros plugins de Nukkit-MOT consulten
 * el protocolo original real (80-113) de cada jugador conectado.
 */
public final class CraftsmineAPI {

    private static volatile CraftsmineAPI instance;

    private final SessionProtocolRegistry registry;
    private final PerformanceMetrics metrics;

    public CraftsmineAPI(SessionProtocolRegistry registry, PerformanceMetrics metrics) {
        this.registry = registry;
        this.metrics = metrics;
        instance = this;
    }

    public static CraftsmineAPI getInstance() {
        return instance;
    }

    public boolean isLegacyClient(Player player) {
        return registry.getByPlayer(player) != null;
    }

    public int getEffectiveProtocol(Player player) {
        LegacySessionState state = registry.getByPlayer(player);
        if (state != null) {
            return state.getRawProtocol();
        }
        return player != null ? player.protocol : LegacyProtocol.CANONICAL_NUKKIT_PROTOCOL;
    }

    public String getClientVersionLabel(Player player) {
        LegacySessionState state = registry.getByPlayer(player);
        if (state != null) {
            return state.getProtocol().getMcVersion();
        }
        return player != null && player.getGameVersion() != null ? player.getGameVersion().name() : "Unknown";
    }

    public Collection<LegacySessionState> getLegacySessions() {
        return registry.getActiveSessions();
    }

    public PerformanceMetrics.Snapshot getMetricsSnapshot() {
        return metrics.snapshot();
    }

    public static void clearInstance() {
        instance = null;
    }
}
