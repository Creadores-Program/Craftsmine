package org.CreadoresProgram.Craftsmine;

import org.CreadoresProgram.Craftsmine.network.RakNetBridgeHook;
import org.cloudburstmc.netty.channel.raknet.config.RakServerCookieMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regresion del ajuste de RakServerCookieMode en RakNetBridgeHook.
 *
 * Causa raiz verificada en vivo contra Nukkit-MOT (netty-transport-raknet
 * RakServerOfflineHandler, modo por defecto rak-cookie-mode "active"):
 * - REPLY_1 = 32B con 4 bytes de cookie entre el GUID y el MTU; un cliente
 *   RakNet 6-10 lee security=cookie[0] y MTU=cookie[1..3] (basura).
 * - Todo OPEN_CONNECTION_REQUEST_2 SIN cookie se descarta silenciosamente
 *   ("Received ID_OPEN_CONNECTION_REQUEST_2 with invalid cookie"): los clientes
 *   MCPE 0.14.x-1.1.x (protocolos 41-112) nunca implementan cookies, por lo que
 *   nunca reciben REPLY_2 y quedan en "Localizando servidor" para siempre.
 * Con NONE: REPLY_1 = 28B legado ([id][magic][guid][security byte][mtu]) y el
 * REQUEST_2 sin cookie se acepta (validado empiricamente contra el servidor real
 * y contra Nukkit-Retro OPEN_CONNECTION_REPLY_1.java:35-45 /
 * OPEN_CONNECTION_REQUEST_2.java:33-41).
 */
class RakNetCookieModeLegacyTest {

    @Test
    void testCookieModesWithCookiesMustBeForcedToNone() {
        // Todos los modos con cookies activas rompen el handshake offline legado.
        assertTrue(RakNetBridgeHook.shouldForceCookieModeNone(RakServerCookieMode.ACTIVE));
        assertTrue(RakNetBridgeHook.shouldForceCookieModeNone(RakServerCookieMode.STATELESS));
        assertTrue(RakNetBridgeHook.shouldForceCookieModeNone(RakServerCookieMode.OFF));
        assertTrue(RakNetBridgeHook.shouldForceCookieModeNone(RakServerCookieMode.OFFLOADED));
        assertTrue(RakNetBridgeHook.shouldForceCookieModeNone(RakServerCookieMode.OFFLOADED_PSK));
    }

    @Test
    void testNoneAndNullAreRespected() {
        // NONE ya es compatible; null = la build de la libreria no soporta la opcion.
        assertFalse(RakNetBridgeHook.shouldForceCookieModeNone(RakServerCookieMode.NONE));
        assertFalse(RakNetBridgeHook.shouldForceCookieModeNone(null));
    }
}
