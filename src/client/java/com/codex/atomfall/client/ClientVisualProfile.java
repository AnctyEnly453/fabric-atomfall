package com.codex.atomfall.client;

import net.fabricmc.loader.api.FabricLoader;

public final class ClientVisualProfile {
    public static final boolean HAS_SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
    public static final boolean HAS_IMMEDIATELY_FAST = FabricLoader.getInstance().isModLoaded("immediatelyfast");
    public static final boolean HAS_IRIS = FabricLoader.getInstance().isModLoaded("iris");

    private ClientVisualProfile() {
    }

    public static int cloudStemSlices() {
        int base = 5;
        if (HAS_SODIUM) {
            base += 2;
        }
        if (HAS_IMMEDIATELY_FAST) {
            base += 2;
        }
        if (HAS_IRIS) {
            base += 3;
        }
        return base;
    }

    public static int cloudCapSlices() {
        int base = 6;
        if (HAS_SODIUM) {
            base += 2;
        }
        if (HAS_IMMEDIATELY_FAST) {
            base += 2;
        }
        if (HAS_IRIS) {
            base += 4;
        }
        return base;
    }

    public static int cloudCrownLayers() {
        return HAS_IRIS ? 5 : HAS_SODIUM ? 4 : 3;
    }

    public static float rendererSegmentScale() {
        float scale = 1.0F;
        if (HAS_SODIUM) {
            scale += 0.18F;
        }
        if (HAS_IMMEDIATELY_FAST) {
            scale += 0.12F;
        }
        if (HAS_IRIS) {
            scale += 0.22F;
        }
        return scale;
    }

    public static float emissiveBoost() {
        return HAS_IRIS ? 1.18F : HAS_SODIUM ? 1.05F : 1.0F;
    }
}
