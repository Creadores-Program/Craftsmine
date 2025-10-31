package org.CreadoresProgram.CraftsMine.utils;

import com.nukkitx.nbt.CompoundTag;
import com.nukkitx.nbt.ListTag;
import com.nukkitx.nbt.stream.NBTInputStream;
import com.nukkitx.nbt.tag.CompoundTagBuilder;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.extern.log4j.Log4j2;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
public class RuntimePaletteManager {

    private static final Map<Integer, Integer> legacyToRuntimeId_v389 = new HashMap<>();
    private static final Map<Integer, Integer> runtimeIdToLegacy_v389 = new HashMap<>();
    private static final AtomicInteger runtimeIdAllocator_v389 = new AtomicInteger(0);
    private static ListTag<CompoundTag> blockPalette_v389;

    private static final Map<Integer, Integer> legacyToRuntimeId_v388 = new HashMap<>();
    private static final Map<Integer, Integer> runtimeIdToLegacy_v388 = new HashMap<>();
    private static final AtomicInteger runtimeIdAllocator_v388 = new AtomicInteger(0);
    private static ListTag<CompoundTag> blockPalette_v388;

    private static final Map<Integer, Integer> legacyToRuntimeId_v361 = new HashMap<>();
    private static final Map<Integer, Integer> runtimeIdToLegacy_v361 = new HashMap<>();
    private static final AtomicInteger runtimeIdAllocator_v361 = new AtomicInteger(0);
    private static ListTag<CompoundTag> blockPalette_v361;

    private static final Map<Integer, Integer> legacyToRuntimeId_v354 = new HashMap<>();
    private static final Map<Integer, Integer> runtimeIdToLegacy_v354 = new HashMap<>();
    private static final AtomicInteger runtimeIdAllocator_v354 = new AtomicInteger(0);
    private static ListTag<CompoundTag> blockPalette_v354;

    private static final Map<Integer, Integer> legacyToRuntimeId_v340 = new HashMap<>();
    private static final Map<Integer, Integer> runtimeIdToLegacy_v340 = new HashMap<>();
    private static final AtomicInteger runtimeIdAllocator_v340 = new AtomicInteger(0);
    private static ListTag<CompoundTag> blockPalette_v340;

    private static final Map<Integer, Integer> legacyToRuntimeId_v332 = new HashMap<>();
    private static final Map<Integer, Integer> runtimeIdToLegacy_v332 = new HashMap<>();
    private static final AtomicInteger runtimeIdAllocator_v332 = new AtomicInteger(0);
    private static ListTag<CompoundTag> blockPalette_v332;

    private static final Map<Integer, Integer> legacyToRuntimeId_v313 = new HashMap<>();
    private static final Map<Integer, Integer> runtimeIdToLegacy_v313 = new HashMap<>();
    private static final AtomicInteger runtimeIdAllocator_v313 = new AtomicInteger(0);
    private static ListTag<CompoundTag> blockPalette_v313;

    private static final Map<Integer, Integer> legacyToRuntimeId_v291 = new HashMap<>();
    private static final Map<Integer, Integer> runtimeIdToLegacy_v291 = new HashMap<>();
    private static final AtomicInteger runtimeIdAllocator_v291 = new AtomicInteger(0);
    private static ListTag<CompoundTag> blockPalette_v291;

    private static final List<org.cloudburstmc.protocol.bedrock.packet.StartGamePacket.ItemEntry> itemPalette_v389 = new ArrayList<>();
    private static final List<org.cloudburstmc.protocol.bedrock.packet.StartGamePacket.ItemEntry> itemPalette_v388 = new ArrayList<>();
    private static final List<org.cloudburstmc.protocol.bedrock.packet.StartGamePacket.ItemEntry> itemPalette_v361 = new ArrayList<>();

    public static void init() {
        try {
            // Try multiple resource names. Prefer JSON lists when available.
            loadJsonPalette("blocks_v389.json", runtimeIdAllocator_v389, legacyToRuntimeId_v389, runtimeIdToLegacy_v389, blockPalette_v389, 389);
            loadJsonPalette("blocks_v388.json", runtimeIdAllocator_v388, legacyToRuntimeId_v388, runtimeIdToLegacy_v388, blockPalette_v388, 388);
            loadJsonPalette("blocks_v361.json", runtimeIdAllocator_v361, legacyToRuntimeId_v361, runtimeIdToLegacy_v361, blockPalette_v361, 361);
            loadJsonPalette("blocks_v354.json", runtimeIdAllocator_v354, legacyToRuntimeId_v354, runtimeIdToLegacy_v354, blockPalette_v354, 354);
            loadJsonPalette("blocks_v340.json", runtimeIdAllocator_v340, legacyToRuntimeId_v340, runtimeIdToLegacy_v340, blockPalette_v340, 340);
            loadJsonPalette("blocks_v332.json", runtimeIdAllocator_v332, legacyToRuntimeId_v332, runtimeIdToLegacy_v332, blockPalette_v332, 332);
            loadJsonPalette("blocks_v313.json", runtimeIdAllocator_v313, legacyToRuntimeId_v313, runtimeIdToLegacy_v313, blockPalette_v313, 313);
            loadJsonPalette("blocks_v291.json", runtimeIdAllocator_v291, legacyToRuntimeId_v291, runtimeIdToLegacy_v291, blockPalette_v291, 291);
            // Items
            loadItemPaletteJson("items_v389.json", itemPalette_v389);
            loadItemPaletteJson("items_v388.json", itemPalette_v388);
            loadItemPaletteJson("items_v361.json", itemPalette_v361);
        } catch (Throwable t) {
            log.warn("RuntimePaletteManager init encountered problem: {}", t.toString());
        }
    }

    private static void loadItemPaletteJson(String resourceName, List<org.cloudburstmc.protocol.bedrock.packet.StartGamePacket.ItemEntry> out) {
        try (InputStream is = RuntimePaletteManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) return;
            List<Map<String, Object>> list = JSON.parseObject(new InputStreamReader(is, StandardCharsets.UTF_8), new TypeReference<List<Map<String, Object>>>(){});
            for (Map<String, Object> entry : list) {
                String name = String.valueOf(entry.get("name"));
                int id = ((Number) entry.get("id")).intValue();
                out.add(new org.cloudburstmc.protocol.bedrock.packet.StartGamePacket.ItemEntry(name, id));
            }
        } catch (Exception ex) {
            log.warn("Failed to load item palette {}: {}", resourceName, ex.toString());
        }
    }

    private static void loadJsonPalette(String resourceName, AtomicInteger allocator, Map<Integer,Integer> legacyToRuntime, Map<Integer,Integer> runtimeToLegacy, ListTag<CompoundTag> store, int ver) {
        try (InputStream is = RuntimePaletteManager.class.getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                log.info("RuntimePaletteManager: resource {} not found, skipping.", resourceName);
                return;
            }
            List<Map<String, Object>> list = JSON.parseObject(new InputStreamReader(is, StandardCharsets.UTF_8), new TypeReference<List<Map<String, Object>>>(){});
            for (Map<String, Object> entry : list) {
                int id = ((Number) entry.get("id")).intValue();
                int meta = ((Number) entry.getOrDefault("data", 0)).intValue();
                registerMapping(allocator, legacyToRuntime, runtimeToLegacy, id, meta);
            }
            log.info("Loaded {} entries for {}", list.size(), resourceName);
        } catch (Exception ex) {
            log.warn("Failed to load palette {}: {}", resourceName, ex.toString());
        }
    }

    private static int registerMapping(AtomicInteger allocator, Map<Integer,Integer> legacyToRuntime, Map<Integer,Integer> runtimeToLegacy, int blockId, int blockMeta) {
        int legacyId = getLegacyId(blockId, blockMeta);
        int runtimeId = allocator.getAndIncrement();
        runtimeToLegacy.put(runtimeId, legacyId);
        legacyToRuntime.put(legacyId, runtimeId);
        return runtimeId;
    }

    public static int getRuntimeId_v389(int legacyId) {
        return legacyToRuntimeId_v389.get(legacyId);
    }
    public static int getRuntimeId_v388(int legacyId) { return legacyToRuntimeId_v388.get(legacyId); }
    public static int getRuntimeId_v361(int legacyId) { return legacyToRuntimeId_v361.get(legacyId); }
    public static int getRuntimeId_v354(int legacyId) { return legacyToRuntimeId_v354.get(legacyId); }
    public static int getRuntimeId_v340(int legacyId) { return legacyToRuntimeId_v340.get(legacyId); }
    public static int getRuntimeId_v332(int legacyId) { return legacyToRuntimeId_v332.get(legacyId); }
    public static int getRuntimeId_v313(int legacyId) { return legacyToRuntimeId_v313.get(legacyId); }
    public static int getRuntimeId_v291(int legacyId) { return legacyToRuntimeId_v291.get(legacyId); }

    public static int getLegacyId_v389(int runtimeId) { return runtimeIdToLegacy_v389.get(runtimeId); }
    public static int getLegacyId_v388(int runtimeId) { return runtimeIdToLegacy_v388.get(runtimeId); }
    public static int getLegacyId_v361(int runtimeId) { return runtimeIdToLegacy_v361.get(runtimeId); }
    public static int getLegacyId_v354(int runtimeId) { return runtimeIdToLegacy_v354.get(runtimeId); }
    public static int getLegacyId_v340(int runtimeId) { return runtimeIdToLegacy_v340.get(runtimeId); }
    public static int getLegacyId_v332(int runtimeId) { return runtimeIdToLegacy_v332.get(runtimeId); }
    public static int getLegacyId_v313(int runtimeId) { return runtimeIdToLegacy_v313.get(runtimeId); }
    public static int getLegacyId_v291(int runtimeId) { return runtimeIdToLegacy_v291.get(runtimeId); }

    public static int getRuntimeId_v389(int blockId, int blockMeta) { return getRuntimeId_v389(getLegacyIdExpanded(blockId, blockMeta)); }
    public static int getRuntimeId_v388(int blockId, int blockMeta) { return getRuntimeId_v388(getLegacyIdExpanded(blockId, blockMeta)); }
    public static int getRuntimeId_v361(int blockId, int blockMeta) { return getRuntimeId_v361(getLegacyId(blockId, blockMeta)); }
    public static int getRuntimeId_v354(int blockId, int blockMeta) { return getRuntimeId_v354(getLegacyId(blockId, blockMeta)); }
    public static int getRuntimeId_v340(int blockId, int blockMeta) { return getRuntimeId_v340(getLegacyId(blockId, blockMeta)); }
    public static int getRuntimeId_v332(int blockId, int blockMeta) { return getRuntimeId_v332(getLegacyId(blockId, blockMeta)); }
    public static int getRuntimeId_v313(int blockId, int blockMeta) { return getRuntimeId_v313(getLegacyId(blockId, blockMeta)); }
    public static int getRuntimeId_v291(int blockId, int blockMeta) { return getRuntimeId_v291(getLegacyId(blockId, blockMeta)); }

    public static int getLegacyId(int blockId, int blockMeta) { return (blockId << 4) | (blockMeta & 0xF); }
    public static int getLegacyIdExpanded(int blockId, int blockMeta) { return (blockId << 6) | (blockMeta & 0x3F); }
    public static int convertToLegacy(int expanded) {
        int meta = getBlockMetaExpanded(expanded);
        return getLegacyId(getBlockIdExpanded(expanded), meta > 0xf ? 0 : meta);
    }
    public static int getBlockId(int legacyId) { return legacyId >> 4; }
    public static int getBlockIdExpanded(int legacyId) { return legacyId >> 6; }
    public static int getBlockMeta(int legacyId) { return legacyId & 0xf; }
    public static int getBlockMetaExpanded(int legacyId) { return legacyId & 0x3f; }

    public static List<org.cloudburstmc.protocol.bedrock.packet.StartGamePacket.ItemEntry> getItemPalette_v388() { return itemPalette_v388; }
    public static List<org.cloudburstmc.protocol.bedrock.packet.StartGamePacket.ItemEntry> getItemPalette_v361() { return itemPalette_v361; }

    public static void initNoop() {
        // NOOP placeholder if needed
    }
}
