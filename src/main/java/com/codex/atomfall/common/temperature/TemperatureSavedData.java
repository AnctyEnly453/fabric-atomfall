package com.codex.atomfall.common.temperature;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class TemperatureSavedData extends SavedData {
    private static final String DATA_ID = "atomfall_temperature";
    private static final int CELL_SHIFT = 7;

    private final List<TemperatureZone> zones = new ArrayList<>();
    private final Long2ObjectOpenHashMap<List<TemperatureZone>> zoneIndex = new Long2ObjectOpenHashMap<>();
    private boolean indexDirty = true;

    private TemperatureSavedData() {
    }

    public static SavedDataType<TemperatureSavedData> type() {
        return new SavedDataType<>(DATA_ID, TemperatureSavedData::new, TemperatureSavedData.codec(), null);
    }

    public static TemperatureSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(type());
    }

    public void addZone(TemperatureZone zone) {
        this.zones.add(zone);
        this.indexDirty = true;
        setDirty();
    }

    public List<TemperatureZone> getZones() {
        return this.zones;
    }

    public void serverTick(ServerLevel level) {
        Iterator<TemperatureZone> iterator = this.zones.iterator();
        boolean any = false;
        while (iterator.hasNext()) {
            TemperatureZone zone = iterator.next();
            any = true;
            if (zone.tick(level)) {
                iterator.remove();
            }
        }
        if (any) {
            this.indexDirty = true;
            setDirty();
        }
    }

    public double getTemperatureAt(ServerLevel level, Vec3 position) {
        ensureIndex();
        List<TemperatureZone> candidates = this.zoneIndex.get(cellKey(Mth.floor(position.x), Mth.floor(position.z)));
        if (candidates == null || candidates.isEmpty()) {
            return TemperatureMaterialRules.AMBIENT_C;
        }
        double temperature = TemperatureMaterialRules.AMBIENT_C;
        for (TemperatureZone zone : candidates) {
            temperature = Math.max(temperature, zone.sampleAbsoluteTemperature(level, position));
        }
        return temperature;
    }

    public boolean hasZones() {
        return !this.zones.isEmpty();
    }

    private void ensureIndex() {
        if (!this.indexDirty) {
            return;
        }

        this.zoneIndex.clear();
        for (TemperatureZone zone : this.zones) {
            int minCellX = cellCoord(zone.center().getX() - zone.radius());
            int maxCellX = cellCoord(zone.center().getX() + zone.radius());
            int minCellZ = cellCoord(zone.center().getZ() - zone.radius());
            int maxCellZ = cellCoord(zone.center().getZ() + zone.radius());

            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    this.zoneIndex.computeIfAbsent(packCell(cellX, cellZ), ignored -> new ArrayList<>()).add(zone);
                }
            }
        }
        this.indexDirty = false;
    }

    private static long cellKey(int blockX, int blockZ) {
        return packCell(blockX >> CELL_SHIFT, blockZ >> CELL_SHIFT);
    }

    private static int cellCoord(double blockCoord) {
        return Mth.floor(blockCoord) >> CELL_SHIFT;
    }

    private static long packCell(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xffffffffL);
    }

    private static Codec<TemperatureSavedData> codec() {
        return TemperatureZone.CODEC.listOf().xmap(
                zones -> {
                    TemperatureSavedData data = new TemperatureSavedData();
                    data.zones.addAll(zones);
                    data.indexDirty = true;
                    return data;
                },
                data -> List.copyOf(data.zones)
        );
    }
}
