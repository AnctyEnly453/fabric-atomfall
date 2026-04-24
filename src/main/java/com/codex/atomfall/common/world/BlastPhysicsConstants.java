package com.codex.atomfall.common.world;

import net.minecraft.util.Mth;

/**
 * Real-world inspired anchors used by the simplified simulation.
 *
 * Runtime-tunable performance values live here as well so commands can switch
 * profiles in-game. New blasts pick up the new sector count immediately; active
 * blasts keep their already-allocated sector buffers but use the latest budget
 * and stride values on subsequent ticks.
 */
public final class BlastPhysicsConstants {
    private static final double[] OVERPRESSURE_RADII_1KT = {55.0D, 120.0D, 220.0D, 420.0D, 620.0D, 1200.0D, 2400.0D};
    private static final double[] OVERPRESSURE_PSI = {60.0D, 20.0D, 10.0D, 5.0D, 3.0D, 1.0D, 0.3D};

    public enum PerformanceProfile {

        HIGH_FIDELITY("high_fidelity", 72, 5, 10, 14, 1, 0, 0, 1200, 1000, 104, false),
        BALANCED("balanced", 32, 7, 14, 18, 0, 0, 0, 900, 700, 64, false),
        PERFORMANCE("performance", 24, 7, 12, 18, 1, 0, 0, 800, 600, 48, false);
        private final String id;
        private final int shockSectors;
        private final int shockStrideNear;
        private final int shockStrideMid;
        private final int shockStrideFar;
        private final int lateralNear;
        private final int lateralMid;
        private final int lateralFar;
        private final int craterBudget;
        private final int shockBudget;
        private final int activeRadiusPadding;
        private final boolean blockLineOfSight;

        PerformanceProfile(String id, int shockSectors, int shockStrideNear, int shockStrideMid, int shockStrideFar,
                           int lateralNear, int lateralMid, int lateralFar,
                           int craterBudget, int shockBudget,
                           int activeRadiusPadding, boolean blockLineOfSight) {
            this.id = id;
            this.shockSectors = shockSectors;
            this.shockStrideNear = shockStrideNear;
            this.shockStrideMid = shockStrideMid;
            this.shockStrideFar = shockStrideFar;
            this.lateralNear = lateralNear;
            this.lateralMid = lateralMid;
            this.lateralFar = lateralFar;
            this.craterBudget = craterBudget;
            this.shockBudget = shockBudget;
            this.activeRadiusPadding = activeRadiusPadding;
            this.blockLineOfSight = blockLineOfSight;
        }

        public String id() {
            return this.id;
        }

        public static PerformanceProfile byId(String id) {
            for (PerformanceProfile value : values()) {
                if (value.id.equalsIgnoreCase(id)) {
                    return value;
                }
            }
            return null;
        }
    }

    public enum ThermalProfile {
        HIGH("high", 200, 320, 8),
        BALANCED("balanced", 100, 160, 10),
        PERFORMANCE("performance", 60, 100, 12);
        private final String id;
        private final int thermalBudget;
        private final int waterBudget;
        private final int thermalEnvironmentInterval;

        ThermalProfile(String id, int thermalBudget, int waterBudget, int thermalEnvironmentInterval) {
            this.id = id;
            this.thermalBudget = thermalBudget;
            this.waterBudget = waterBudget;
            this.thermalEnvironmentInterval = thermalEnvironmentInterval;
        }

        public String id() {
            return this.id;
        }

        public static ThermalProfile byId(String id) {
            for (ThermalProfile value : values()) {
                if (value.id.equalsIgnoreCase(id)) {
                    return value;
                }
            }
            return null;
        }
    }

    private static volatile PerformanceProfile activeProfile = PerformanceProfile.PERFORMANCE;
    private static volatile ThermalProfile activeThermalProfile = ThermalProfile.PERFORMANCE;

    private static volatile int ovShockSectors = -1;
    private static volatile int ovStrideNear = -1;
    private static volatile int ovStrideMid = -1;
    private static volatile int ovStrideFar = -1;
    private static volatile int ovLateralNear = -1;
    private static volatile int ovLateralMid = -1;
    private static volatile int ovLateralFar = -1;
    private static volatile int ovCraterBudget = -1;
    private static volatile int ovShockBudget = -1;
    private static volatile int ovThermalBudget = -1;
    private static volatile int ovWaterBudget = -1;
    private static volatile int ovThermalInterval = -1;

    private BlastPhysicsConstants() {
    }

    private static int resolve(int override, int fallback) {
        return override >= 0 ? override : fallback;
    }

    public static final double MIN_YIELD_KT = 0.1D;
    public static final double MAX_YIELD_KT = 50.0D;
    public static final double DEFAULT_YIELD_KT = 15.0D;

    public static final double SOUND_SPEED_MPS = 343.0D;
    public static final double BLOCKS_PER_METER = 1.0D;
    public static final double SOUND_SPEED_BLOCKS_PER_TICK = SOUND_SPEED_MPS * BLOCKS_PER_METER / 20.0D;

    public static final double FIREBALL_RADIUS_1KT = 55.0D;
    public static final double CRATER_RADIUS_1KT = 51.0D;
    public static final double CRATER_DEPTH_1KT = 15.0D;

    public static final double SHOCK_CORE_RADIUS_1KT = 120.0D;
    public static final double SHOCK_SEVERE_RADIUS_1KT = 420.0D;
    public static final double SHOCK_SURFACE_RADIUS_1KT = 1200.0D;

    public static final double THERMAL_VITRIFICATION_RADIUS_1KT = 150.0D;
    public static final double THERMAL_CORE_RADIUS_1KT = 240.0D;
    public static final double THERMAL_SCORCH_RADIUS_1KT = 720.0D;
    public static final double THERMAL_RADIUS_1KT = 980.0D;
    public static final double WATER_FLASH_RADIUS_1KT = 260.0D;

    public static final double PROMPT_RADIATION_RADIUS_1KT = 420.0D;
    public static final double FALLOUT_RADIUS_1KT = 1500.0D;

    public static final double FIREBALL_TEMPERATURE_C = 4800.0D;
    public static final double CORE_TEMPERATURE_C = 1800.0D;
    public static final double HOT_ZONE_TEMPERATURE_C = 700.0D;

    public static final double PSI_BREAK_GLASS = 0.6D;
    public static final double PSI_BREAK_LIGHT_ROOF = 1.5D;
    public static final double PSI_BREAK_WOOD = 3.0D;
    public static final double PSI_BREAK_BRICK = 6.0D;
    public static final double PSI_BREAK_REINFORCED = 10.0D;
    public static final double PSI_BREAK_BUNKER = 18.0D;

    public static PerformanceProfile activeProfile() {
        return activeProfile;
    }

    public static void setActiveProfile(PerformanceProfile profile) {
        activeProfile = profile;
    }

    public static ThermalProfile activeThermalProfile() {
        return activeThermalProfile;
    }

    public static void setActiveThermalProfile(ThermalProfile profile) {
        activeThermalProfile = profile;
    }

    public static int shockSectors() {
        return resolve(ovShockSectors, activeProfile.shockSectors);
    }

    public static int shockSampleStrideNear() {
        return resolve(ovStrideNear, activeProfile.shockStrideNear);
    }

    public static int shockSampleStrideMid() {
        return resolve(ovStrideMid, activeProfile.shockStrideMid);
    }

    public static int shockSampleStrideFar() {
        return resolve(ovStrideFar, activeProfile.shockStrideFar);
    }

    public static int craterBlockBudget() {
        return resolve(ovCraterBudget, activeProfile.craterBudget);
    }

    public static int shockBlockBudget() {
        return resolve(ovShockBudget, activeProfile.shockBudget);
    }

    public static int thermalBlockBudget() {
        return resolve(ovThermalBudget, activeThermalProfile.thermalBudget);
    }

    public static int waterBfsBudget() {
        return resolve(ovWaterBudget, activeThermalProfile.waterBudget);
    }

    public static int playerActiveRadiusPadding() {
        return activeProfile.activeRadiusPadding;
    }

    public static int thermalEnvironmentIntervalTicks() {
        return resolve(ovThermalInterval, activeThermalProfile.thermalEnvironmentInterval);
    }

    public static boolean useBlockLineOfSight() {
        return activeProfile.blockLineOfSight;
    }

    public static int lateralShellSamples(double radius) {
        if (radius <= 220.0D) {
            return resolve(ovLateralNear, activeProfile.lateralNear);
        }
        if (radius <= 700.0D) {
            return resolve(ovLateralMid, activeProfile.lateralMid);
        }
        return resolve(ovLateralFar, activeProfile.lateralFar);
    }

    public static boolean setShockParam(String key, int value) {
        return switch (key) {
            case "sectors" -> { ovShockSectors = value; yield true; }
            case "stridenear" -> { ovStrideNear = value; yield true; }
            case "stridemid" -> { ovStrideMid = value; yield true; }
            case "stridefar" -> { ovStrideFar = value; yield true; }
            case "lateralnear" -> { ovLateralNear = value; yield true; }
            case "lateralmid" -> { ovLateralMid = value; yield true; }
            case "lateralfar" -> { ovLateralFar = value; yield true; }
            case "craterbudget" -> { ovCraterBudget = value; yield true; }
            case "shockbudget" -> { ovShockBudget = value; yield true; }
            default -> false;
        };
    }

    public static boolean setThermalParam(String key, int value) {
        return switch (key) {
            case "thermalbudget" -> { ovThermalBudget = value; yield true; }
            case "waterbudget" -> { ovWaterBudget = value; yield true; }
            case "interval" -> { ovThermalInterval = value; yield true; }
            default -> false;
        };
    }

    public static void resetShockOverrides() {
        ovShockSectors = -1;
        ovStrideNear = -1;
        ovStrideMid = -1;
        ovStrideFar = -1;
        ovLateralNear = -1;
        ovLateralMid = -1;
        ovLateralFar = -1;
        ovCraterBudget = -1;
        ovShockBudget = -1;
    }

    public static void resetThermalOverrides() {
        ovThermalBudget = -1;
        ovWaterBudget = -1;
        ovThermalInterval = -1;
    }

    public static double clampYield(double yieldKt) {
        return Mth.clamp(yieldKt, MIN_YIELD_KT, MAX_YIELD_KT);
    }

    public static double cubeRootScale(double yieldKt) {
        return Math.cbrt(clampYield(yieldKt));
    }

    public static double scaledRadius(double at1Kt, double yieldKt) {
        return at1Kt * cubeRootScale(yieldKt);
    }

    public static double fireballRadius(double yieldKt) {
        return scaledRadius(FIREBALL_RADIUS_1KT, yieldKt);
    }

    public static double craterRadius(double yieldKt) {
        return scaledRadius(CRATER_RADIUS_1KT, yieldKt);
    }

    public static double craterDepth(double yieldKt) {
        return scaledRadius(CRATER_DEPTH_1KT, yieldKt);
    }

    public static double shockFrontSpeed(double yieldKt, double radius) {
        double scale = cubeRootScale(yieldKt);
        double decayLength = 165.0D * scale;
        double machExcess = 2.6D * Math.exp(-radius / Math.max(24.0D, decayLength));
        return SOUND_SPEED_BLOCKS_PER_TICK * (1.0D + machExcess);
    }

    public static double positivePhaseDurationTicks(double yieldKt, double distance) {
        double scale = cubeRootScale(yieldKt);
        double scaledDistance = distance / Math.max(1.0D, 220.0D * scale);
        return 10.0D + 14.0D * scale + scaledDistance * 8.0D;
    }

    /**
     * Overpressure is evaluated with log-log interpolation between public,
     * rounded radius anchors for a 1 kt surface burst, then scaled by W^(1/3).
     */
    public static double peakOverpressurePsi(double yieldKt, double distance) {
        double scaledDistance = distance / cubeRootScale(yieldKt);
        if (scaledDistance <= OVERPRESSURE_RADII_1KT[0]) {
            double ratio = OVERPRESSURE_RADII_1KT[0] / Math.max(8.0D, scaledDistance);
            return OVERPRESSURE_PSI[0] * Math.pow(ratio, 1.2D);
        }

        for (int i = 1; i < OVERPRESSURE_RADII_1KT.length; i++) {
            if (scaledDistance <= OVERPRESSURE_RADII_1KT[i]) {
                return logInterpolate(
                        OVERPRESSURE_RADII_1KT[i - 1],
                        OVERPRESSURE_RADII_1KT[i],
                        OVERPRESSURE_PSI[i - 1],
                        OVERPRESSURE_PSI[i],
                        scaledDistance
                );
            }
        }

        double tailRatio = OVERPRESSURE_RADII_1KT[OVERPRESSURE_RADII_1KT.length - 1] / scaledDistance;
        return OVERPRESSURE_PSI[OVERPRESSURE_PSI.length - 1] * Math.pow(tailRatio, 1.15D);
    }

    private static double logInterpolate(double x0, double x1, double y0, double y1, double x) {
        double tx = (Math.log(x) - Math.log(x0)) / (Math.log(x1) - Math.log(x0));
        return Math.exp(Mth.lerp(tx, Math.log(y0), Math.log(y1)));
    }
}
