package org.CreadoresProgram.Craftsmine.network;

import cn.nukkit.Player;
import io.netty.channel.ChannelId;

import java.net.SocketAddress;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.CreadoresProgram.Craftsmine.protocol.LegacyProtocol;

/**
 * Registro concurrente de sesiones legadas activas (Protocolo 80 - 112),
 * estado de entidades por sesion y cache de skins reales de jugadores.
 */
public final class SessionProtocolRegistry {

    public record SkinSnapshot(String skinId, byte[] skinData, boolean slim) {
    }

    public record EntitySpawnInfo(int rawType, int mappedType) {
        public boolean isReplaced() {
            return rawType != mappedType;
        }
    }

    public static final class LegacySessionState {
        private final ChannelId channelId;
        private final String remoteAddress;
        private volatile int rawProtocol;
        private volatile LegacyProtocol protocol;
        private volatile String username = "Desconocido";
        private volatile boolean bypassedResourcePacks;
        private volatile boolean modernDetached;
        private volatile long playerEntityId = -1L;
        private final long connectedAtMillis;
        private final Map<Long, EntitySpawnInfo> spawnedEntities = new ConcurrentHashMap<>();
        private final Map<Long, UUID> trackedPlayerUuids = new ConcurrentHashMap<>();

        public LegacySessionState(ChannelId channelId, SocketAddress remoteAddress, int rawProtocol) {
            this.channelId = channelId;
            this.remoteAddress = remoteAddress == null ? "unknown" : remoteAddress.toString();
            this.rawProtocol = rawProtocol;
            this.protocol = LegacyProtocol.resolve(rawProtocol);
            this.connectedAtMillis = System.currentTimeMillis();
        }

        public ChannelId getChannelId() {
            return channelId;
        }

        public String getRemoteAddress() {
            return remoteAddress;
        }

        public int getRawProtocol() {
            return rawProtocol;
        }

        public LegacyProtocol getProtocol() {
            return protocol;
        }

        public void updateProtocol(int rawProtocol) {
            this.rawProtocol = rawProtocol;
            this.protocol = LegacyProtocol.resolve(rawProtocol);
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            if (username != null && !username.isBlank()) {
                this.username = username;
            }
        }

        public boolean isBypassedResourcePacks() {
            return bypassedResourcePacks;
        }

        public void setBypassedResourcePacks(boolean bypassedResourcePacks) {
            this.bypassedResourcePacks = bypassedResourcePacks;
        }

        public boolean isModernDetached() {
            return modernDetached;
        }

        public void setModernDetached(boolean modernDetached) {
            this.modernDetached = modernDetached;
        }

        public long getPlayerEntityId() {
            return playerEntityId;
        }

        public void setPlayerEntityId(long playerEntityId) {
            this.playerEntityId = playerEntityId;
        }

        public long getConnectedAtMillis() {
            return connectedAtMillis;
        }

        public void trackEntity(long eid, int rawType, int mappedType) {
            if (spawnedEntities.size() > 8192) {
                spawnedEntities.clear();
            }
            spawnedEntities.put(eid, new EntitySpawnInfo(rawType, mappedType));
        }

        public EntitySpawnInfo getEntity(long eid) {
            return spawnedEntities.get(eid);
        }

        public void removeEntity(long eid) {
            spawnedEntities.remove(eid);
        }

        public void trackPlayerUuid(long eid, UUID uuid) {
            if (uuid == null) {
                return;
            }
            if (trackedPlayerUuids.size() > 4096) {
                trackedPlayerUuids.clear();
            }
            trackedPlayerUuids.put(eid, uuid);
        }

        public UUID getPlayerUuid(long eid) {
            return trackedPlayerUuids.get(eid);
        }

        public UUID removePlayerUuid(long eid) {
            return trackedPlayerUuids.remove(eid);
        }

        public void clearEntities() {
            spawnedEntities.clear();
            trackedPlayerUuids.clear();
        }
    }

    private final Map<ChannelId, LegacySessionState> byChannel = new ConcurrentHashMap<>();
    private final Map<String, LegacySessionState> byUsername = new ConcurrentHashMap<>();
    private final Map<UUID, SkinSnapshot> playerSkins = new ConcurrentHashMap<>();
    private final Map<String, SkinSnapshot> playerSkinsByName = new ConcurrentHashMap<>();

    public LegacySessionState registerOrUpdate(ChannelId channelId, SocketAddress remote, int rawProtocol, String username) {
        LegacySessionState state = byChannel.compute(channelId, (id, existing) -> {
            if (existing == null) {
                return new LegacySessionState(id, remote, rawProtocol);
            }
            existing.updateProtocol(rawProtocol);
            return existing;
        });
        if (username != null && !username.isBlank()) {
            state.setUsername(username);
            byUsername.put(username.toLowerCase(Locale.ROOT), state);
        }
        return state;
    }

    public LegacySessionState getByChannel(ChannelId channelId) {
        return byChannel.get(channelId);
    }

    public LegacySessionState getByPlayer(Player player) {
        if (player == null || player.getName() == null) {
            return null;
        }
        return byUsername.get(player.getName().toLowerCase(Locale.ROOT));
    }

    public LegacySessionState removeByChannel(ChannelId channelId) {
        LegacySessionState removed = byChannel.remove(channelId);
        if (removed != null) {
            removed.clearEntities();
            if (removed.getUsername() != null) {
                byUsername.remove(removed.getUsername().toLowerCase(Locale.ROOT), removed);
            }
        }
        return removed;
    }

    public void cacheSkin(UUID uuid, String username, String skinId, byte[] skinData, boolean slim) {
        if (skinData == null || skinData.length == 0) {
            return;
        }
        SkinSnapshot snap = new SkinSnapshot(
                skinId == null || skinId.isBlank() ? (slim ? "Standard_CustomSlim" : "Standard_Custom") : skinId,
                Arrays.copyOf(skinData, skinData.length),
                slim
        );
        if (uuid != null) {
            playerSkins.put(uuid, snap);
        }
        if (username != null && !username.isBlank()) {
            playerSkinsByName.put(username.toLowerCase(Locale.ROOT), snap);
        }
    }

    public SkinSnapshot getSkin(UUID uuid, String username) {
        if (uuid != null) {
            SkinSnapshot byUuid = playerSkins.get(uuid);
            if (byUuid != null) {
                return byUuid;
            }
        }
        if (username != null && !username.isBlank()) {
            return playerSkinsByName.get(username.toLowerCase(Locale.ROOT));
        }
        return null;
    }

    public void removeCachedSkin(UUID uuid, String username) {
        if (uuid != null) {
            playerSkins.remove(uuid);
        }
        if (username != null && !username.isBlank()) {
            playerSkinsByName.remove(username.toLowerCase(Locale.ROOT));
        }
    }

    public Collection<LegacySessionState> getActiveSessions() {
        return Collections.unmodifiableCollection(byChannel.values());
    }

    public void clear() {
        byChannel.clear();
        byUsername.clear();
        playerSkins.clear();
        playerSkinsByName.clear();
    }
}
