package com.codex.atomfall.common.world;

import com.mojang.serialization.Codec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class ActiveBlastSavedData extends SavedData {
    private static final String DATA_ID = "atomfall_active_blasts";

    private final List<ActiveNuclearBlast> blasts = new ArrayList<>();

    private ActiveBlastSavedData() {
    }

    public static SavedDataType<ActiveBlastSavedData> type() {
        return new SavedDataType<>(DATA_ID, ActiveBlastSavedData::new, codec(), null);
    }

    public static ActiveBlastSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(type());
    }

    public void addBlast(ActiveNuclearBlast blast) {
        this.blasts.add(blast);
        setDirty();
    }

    public void serverTick(ServerLevel level) {
        if (this.blasts.isEmpty()) {
            return;
        }

        Iterator<ActiveNuclearBlast> iterator = this.blasts.iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            if (iterator.next().tick(level)) {
                iterator.remove();
                removed = true;
            }
        }

        if (removed || !this.blasts.isEmpty()) {
            setDirty();
        }
    }

    private static Codec<ActiveBlastSavedData> codec() {
        return ActiveNuclearBlast.PersistenceState.CODEC.listOf().xmap(
                states -> {
                    ActiveBlastSavedData data = new ActiveBlastSavedData();
                    for (ActiveNuclearBlast.PersistenceState state : states) {
                        data.blasts.add(ActiveNuclearBlast.fromPersistenceState(state));
                    }
                    return data;
                },
                data -> {
                    List<ActiveNuclearBlast.PersistenceState> states = new ArrayList<>(data.blasts.size());
                    for (ActiveNuclearBlast blast : data.blasts) {
                        states.add(blast.toPersistenceState());
                    }
                    return states;
                }
        );
    }
}
