package org.CreadoresProgram.Craftsmine.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Enumeracion completa de protocolos legados desde 0.15.0 (80) hasta 1.1.x (113).
 * Basada en la matriz de protocolos de MySoft-0.15.x-1.21.x y Nukkit-MOT.
 */
public enum LegacyProtocol {

    PROTOCOL_80(80, "0.15.0 alpha", 7, Family.V0_15),
    PROTOCOL_81(81, "0.15.0 - 0.15.3", 7, Family.V0_15),
    PROTOCOL_82(82, "0.15.4 - 0.15.8", 7, Family.V0_15),
    PROTOCOL_83(83, "0.15.9", 7, Family.V0_15),
    PROTOCOL_84(84, "0.15.10", 7, Family.V0_15),
    PROTOCOL_90(90, "0.15.90 / 0.16.0", 8, Family.V0_16),
    PROTOCOL_91(91, "0.16.0 - 0.17.0", 8, Family.V0_16),
    PROTOCOL_92(92, "1.0.0.0 - 1.0.0.1", 8, Family.V1_0_0),
    PROTOCOL_100(100, "1.0.0 - 1.0.2", 8, Family.V1_0_0),
    PROTOCOL_101(101, "1.0.3 - 1.0.4.0", 8, Family.V1_0_0),
    PROTOCOL_102(102, "1.0.4", 8, Family.V1_0_4),
    PROTOCOL_105(105, "1.0.5 - 1.0.6.0", 8, Family.V1_0_5),
    PROTOCOL_106(106, "1.0.6", 8, Family.V1_0_5),
    PROTOCOL_107(107, "1.0.7 - 1.0.9", 8, Family.V1_0_5),
    PROTOCOL_110(110, "1.1.0.0 - 1.1.0.5", 8, Family.V1_1_0_EARLY),
    PROTOCOL_111(111, "1.1.0.8", 8, Family.V1_1_0_EARLY),
    PROTOCOL_112(112, "1.1.0.9", 8, Family.V1_1_0_EARLY),
    PROTOCOL_113(113, "1.1.0 - 1.1.7", 8, Family.V1_1_0);

    public enum Family {
        /** 0.15.0 - 0.15.10 (RakNet 7, sin VarInts, sin ResourcePacks, chunks 128-col) */
        V0_15,
        /** 0.15.90 - 0.17.0 (RakNet 8, VarInts iniciales, IDs v91, chunks order + ARGB) */
        V0_16,
        /** 1.0.0 - 1.0.4.0 (SubChunks XZY, IDs v100, Batch prefijo 0x06) */
        V1_0_0,
        /** 1.0.4 (IDs v102) */
        V1_0_4,
        /** 1.0.5 - 1.0.9 (IDs v105) */
        V1_0_5,
        /** 1.1.0.0 - 1.1.0.9 (IDs v110, Login sin compresion, Batch sin 0x06) */
        V1_1_0_EARLY,
        /** 1.1.0 - 1.1.7 (Canonico v113 en Nukkit-MOT) */
        V1_1_0
    }

    public static final int MIN_LEGACY_PROTOCOL = 80;
    public static final int CANONICAL_NUKKIT_PROTOCOL = 113;

    private static final List<LegacyProtocol> ALL = Collections.unmodifiableList(Arrays.asList(values()));

    private final int protocol;
    private final String mcVersion;
    private final int rakNetVersion;
    private final Family family;

    LegacyProtocol(int protocol, String mcVersion, int rakNetVersion, Family family) {
        this.protocol = protocol;
        this.mcVersion = mcVersion;
        this.rakNetVersion = rakNetVersion;
        this.family = family;
    }

    public int getProtocol() {
        return protocol;
    }

    public String getMcVersion() {
        return mcVersion;
    }

    public int getRakNetVersion() {
        return rakNetVersion;
    }

    public Family getFamily() {
        return family;
    }

    public boolean isBefore90() {
        return protocol < 90;
    }

    public boolean isBefore92() {
        return protocol < 92;
    }

    public boolean isBefore110() {
        return protocol < 110;
    }

    public boolean needsTranslation() {
        return protocol >= MIN_LEGACY_PROTOCOL && protocol < CANONICAL_NUKKIT_PROTOCOL;
    }

    public static List<LegacyProtocol> all() {
        return ALL;
    }

    public static boolean isLegacyBridgeRange(int protocol, int minAllowed, int maxAllowed) {
        return protocol >= minAllowed && protocol <= maxAllowed && protocol < CANONICAL_NUKKIT_PROTOCOL;
    }

    public static LegacyProtocol resolve(int protocol) {
        LegacyProtocol best = null;
        for (LegacyProtocol candidate : values()) {
            if (candidate.protocol == protocol) {
                return candidate;
            }
            if (candidate.protocol < protocol) {
                best = candidate;
            }
        }
        return best != null ? best : PROTOCOL_80;
    }
}
