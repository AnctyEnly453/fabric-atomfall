package com.codex.atomfall.common.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Captures the original surface once per column and never re-samples it for
 * terrain cutting. This prevents the classic repeated-topY bug where each
 * later pass digs deeper because it uses already-damaged terrain as the new
 * baseline.
 */
public final class SurfaceHeightCache {
    private final Long2IntOpenHashMap originalHeights = new Long2IntOpenHashMap();

    public record Entry(long packedColumn, int height) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.LONG.fieldOf("column").forGetter(Entry::packedColumn),
                        Codec.INT.fieldOf("height").forGetter(Entry::height)
                ).apply(instance, Entry::new)
        );
    }

    public SurfaceHeightCache() {
        this.originalHeights.defaultReturnValue(Integer.MIN_VALUE);
    }

    public int getOrCapture(ServerLevel level, int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        int cached = this.originalHeights.get(key);
        if (cached != Integer.MIN_VALUE) {
            return cached;
        }
        if (!level.hasChunk(x >> 4, z >> 4)) {
            return level.getSeaLevel();
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        this.originalHeights.put(key, top);
        return top;
    }

    public List<Entry> snapshotEntries() {
        List<Entry> entries = new ArrayList<>(this.originalHeights.size());
        for (Long2IntMap.Entry entry : this.originalHeights.long2IntEntrySet()) {
            entries.add(new Entry(entry.getLongKey(), entry.getIntValue()));
        }
        return entries;
    }

    public void restoreEntries(List<Entry> entries) {
        this.originalHeights.clear();
        this.originalHeights.defaultReturnValue(Integer.MIN_VALUE);
        for (Entry entry : entries) {
            this.originalHeights.put(entry.packedColumn(), entry.height());
        }
    }
}
