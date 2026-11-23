package com.craftsmine.nkm.listener;

import cn.nukkit.Player;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerChangeSkinEvent;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.event.server.DataPacketReceiveEvent;
import cn.nukkit.network.protocol.DataPacket;
import com.craftsmine.nkm.logging.CraftsmineLogger;
import com.craftsmine.nkm.logging.CraftsmineLogger.Category;
import com.craftsmine.nkm.network.SessionProtocolRegistry;
import com.craftsmine.nkm.network.SessionProtocolRegistry.LegacySessionState;

import java.util.Locale;

/**
 * Escucha eventos de Nukkit-MOT para auditar sesiones legadas, cachear skins reales
 * y registrar trazas en el logger en tiempo real sin interferir en el flujo normal.
 */
public final class CraftsmineListener implements Listener {

    private final SessionProtocolRegistry registry;
    private final CraftsmineLogger logger;

    public CraftsmineListener(SessionProtocolRegistry registry, CraftsmineLogger logger) {
        this.registry = registry;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        cachePlayerSkin(player, player.getSkin());

        LegacySessionState state = registry.getByPlayer(player);
        if (state != null) {
            logger.info(
                    Category.CONNECTION,
                    String.format(
                            "Jugador legado conectado al mundo: %s | protocolo=%d (%s) | puente canonico=%d",
                            player.getName(),
                            state.getRawProtocol(),
                            state.getProtocol().getMcVersion(),
                            player.protocol
                    )
            );
        } else {
            logger.debug(
                    Category.CONNECTION,
                    "Jugador moderno unido: " + player.getName() + " (proto=" + player.protocol + ")"
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChangeSkin(PlayerChangeSkinEvent event) {
        Player player = event.getPlayer();
        Skin skin = event.getSkin() != null ? event.getSkin() : player.getSkin();
        cachePlayerSkin(player, skin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        registry.removeCachedSkin(player.getUniqueId(), player.getName());
        LegacySessionState state = registry.getByPlayer(player);
        if (state != null) {
            long secs = Math.max(0L, (System.currentTimeMillis() - state.getConnectedAtMillis()) / 1000L);
            logger.info(
                    Category.CONNECTION,
                    String.format(
                            "Jugador legado desconectado: %s (proto=%d, sesion=%ds)",
                            player.getName(), state.getRawProtocol(), secs
                    )
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPacketReceive(DataPacketReceiveEvent event) {
        Player player = event.getPlayer();
        LegacySessionState state = registry.getByPlayer(player);
        if (state == null) {
            return;
        }
        DataPacket pk = event.getPacket();
        if (pk != null) {
            logger.debug(
                    Category.PACKET,
                    String.format(
                            "Nukkit dispatch [%s | orig=%d]: %s (pid=%d)",
                            player.getName(),
                            state.getRawProtocol(),
                            pk.getClass().getSimpleName(),
                            pk.pid()
                    )
            );
        }
    }

    private void cachePlayerSkin(Player player, Skin skin) {
        if (player == null || skin == null || skin.getSkinData() == null || skin.getSkinData().data == null) {
            return;
        }
        String skinId = skin.getSkinId();
        boolean slim = skinId != null && skinId.toLowerCase(Locale.ROOT).contains("slim");
        registry.cacheSkin(player.getUniqueId(), player.getName(), skinId, skin.getSkinData().data, slim);
    }
}
