package com.craftsmine.nkm;

import cn.nukkit.Player;
import cn.nukkit.entity.data.Skin;
import cn.nukkit.plugin.PluginBase;
import com.craftsmine.nkm.api.CraftsmineAPI;
import com.craftsmine.nkm.command.CraftsmineCommand;
import com.craftsmine.nkm.config.CraftsmineConfig;
import com.craftsmine.nkm.listener.CraftsmineListener;
import com.craftsmine.nkm.logging.CraftsmineLogger;
import com.craftsmine.nkm.logging.CraftsmineLogger.Category;
import com.craftsmine.nkm.metrics.PerformanceMetrics;
import com.craftsmine.nkm.network.MultiversionBridgeManager;
import com.craftsmine.nkm.network.SessionProtocolRegistry;

import java.util.Locale;

/**
 * Plugin principal CraftsmineNKM para Nukkit-MOT.
 * Extiende la API Multiversion hacia atras hasta el protocolo 80 (MCPE 0.15.0)
 * cubriendo todos los protocolos 80 - 113 con monitoreo en tiempo real por config.yml.
 */
public final class CraftsmineNKMPlugin extends PluginBase {

    private CraftsmineConfig craftsmineConfig;
    private CraftsmineLogger craftsmineLogger;
    private SessionProtocolRegistry sessionRegistry;
    private PerformanceMetrics metrics;
    private MultiversionBridgeManager bridgeManager;

    @Override
    public void onLoad() {
        saveDefaultConfig();
        this.craftsmineConfig = new CraftsmineConfig(this);
        this.craftsmineLogger = new CraftsmineLogger(getLogger(), craftsmineConfig, getDataFolder());
        this.sessionRegistry = new SessionProtocolRegistry();
        this.metrics = new PerformanceMetrics();
        new CraftsmineAPI(sessionRegistry, metrics);
        craftsmineLogger.info(Category.SYSTEM, "CraftsmineNKM cargado (debug=" + craftsmineConfig.isDebug() + ")");
    }

    @Override
    public void onEnable() {
        this.bridgeManager = new MultiversionBridgeManager(
                this, craftsmineConfig, craftsmineLogger, sessionRegistry, metrics
        );
        this.bridgeManager.initialize();

        getServer().getPluginManager().registerEvents(
                new CraftsmineListener(sessionRegistry, craftsmineLogger), this
        );
        for (Player online : getServer().getOnlinePlayers().values()) {
            Skin skin = online.getSkin();
            if (skin != null && skin.getSkinData() != null && skin.getSkinData().data != null) {
                String skinId = skin.getSkinId();
                boolean slim = skinId != null && skinId.toLowerCase(Locale.ROOT).contains("slim");
                sessionRegistry.cacheSkin(online.getUniqueId(), online.getName(), skinId, skin.getSkinData().data, slim);
            }
        }
        getServer().getCommandMap().register(
                "craftsmine",
                new CraftsmineCommand(this, craftsmineConfig, craftsmineLogger, sessionRegistry, metrics)
        );

        craftsmineLogger.info(
                Category.SYSTEM,
                "CraftsmineNKM habilitado correctamente. Usa /craftsmine status para ver metricas en vivo."
        );
    }

    @Override
    public void onDisable() {
        if (bridgeManager != null) {
            bridgeManager.shutdown();
        }
        CraftsmineAPI.clearInstance();
        if (craftsmineLogger != null) {
            craftsmineLogger.info(Category.SYSTEM, "CraftsmineNKM deshabilitado limpiamente.");
            craftsmineLogger.close();
        }
    }

    public CraftsmineConfig getCraftsmineConfig() {
        return craftsmineConfig;
    }

    public CraftsmineLogger getCraftsmineLogger() {
        return craftsmineLogger;
    }

    public SessionProtocolRegistry getSessionRegistry() {
        return sessionRegistry;
    }

    public PerformanceMetrics getMetrics() {
        return metrics;
    }

    public MultiversionBridgeManager getBridgeManager() {
        return bridgeManager;
    }
}
