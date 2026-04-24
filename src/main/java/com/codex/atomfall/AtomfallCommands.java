package com.codex.atomfall;

import com.codex.atomfall.common.temperature.TemperatureSavedData;
import com.codex.atomfall.common.temperature.TemperatureZone;
import com.codex.atomfall.common.world.BlastPhysicsConstants;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class AtomfallCommands {
    private AtomfallCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("atomfall")
                        .then(literal("performance")
                                .executes(AtomfallCommands::showPerformance)
                                .then(literal("set")
                                        .then(argument("key", StringArgumentType.word())
                                                .suggests(AtomfallCommands::suggestShockKeys)
                                                .then(argument("value", IntegerArgumentType.integer(0, 100000))
                                                        .executes(AtomfallCommands::setShockParam))))
                                .then(literal("reset")
                                        .executes(AtomfallCommands::resetShockOverrides))
                                .then(literal("log")
                                        .executes(AtomfallCommands::showShockLog)
                                        .then(literal("on")
                                                .executes(AtomfallCommands::enableShockLog))
                                        .then(literal("off")
                                                .executes(AtomfallCommands::disableShockLog))
                                        .then(literal("interval")
                                                .then(argument("ticks", IntegerArgumentType.integer(1, 1200))
                                                        .executes(AtomfallCommands::setShockLogInterval))))
                                .then(argument("profile", StringArgumentType.word())
                                        .suggests(AtomfallCommands::suggestProfiles)
                                        .executes(AtomfallCommands::setPerformance)))
                        .then(literal("thermal")
                                .executes(AtomfallCommands::showThermal)
                                .then(literal("set")
                                        .then(argument("key", StringArgumentType.word())
                                                .suggests(AtomfallCommands::suggestThermalKeys)
                                                .then(argument("value", IntegerArgumentType.integer(0, 100000))
                                                        .executes(AtomfallCommands::setThermalParam))))
                                .then(literal("reset")
                                        .executes(AtomfallCommands::resetThermalOverrides))
                                .then(argument("profile", StringArgumentType.word())
                                        .suggests(AtomfallCommands::suggestThermalProfiles)
                                        .executes(AtomfallCommands::setThermal)))
                        .then(literal("heatwave")
                                .executes(AtomfallCommands::spawnHeatwaveDefault)
                                .then(argument("radius", DoubleArgumentType.doubleArg(10.0D, 5000.0D))
                                        .executes(AtomfallCommands::spawnHeatwaveRadius)
                                        .then(argument("temperature", DoubleArgumentType.doubleArg(100.0D, 10000.0D))
                                                .executes(AtomfallCommands::spawnHeatwaveRadiusTemp)
                                                .then(argument("duration", IntegerArgumentType.integer(5, 3600))
                                                        .executes(AtomfallCommands::spawnHeatwaveFull)))))
        ));
    }

    private static int showPerformance(CommandContext<CommandSourceStack> context) {
        BlastPhysicsConstants.PerformanceProfile profile = BlastPhysicsConstants.activeProfile();
        BlastPhysicsConstants.ThermalProfile thermal = BlastPhysicsConstants.activeThermalProfile();
        context.getSource().sendSuccess(
                () -> Component.literal("Atomfall performance: " + profile.id()
                        + " | sectors=" + BlastPhysicsConstants.shockSectors()
                        + " | craterBudget=" + BlastPhysicsConstants.craterBlockBudget()
                        + " | shockBudget=" + BlastPhysicsConstants.shockBlockBudget()
                        + " | shockLog=" + (BlastPhysicsConstants.shockPerfLogEnabled() ? "on" : "off")
                        + "/" + BlastPhysicsConstants.shockPerfLogIntervalTicks() + "t"
                        + " | thermal=" + thermal.id()
                        + " | thermalBudget=" + BlastPhysicsConstants.thermalBlockBudget()
                        + " | waterBudget=" + BlastPhysicsConstants.waterBfsBudget()
                        + " | thermalInterval=" + BlastPhysicsConstants.thermalEnvironmentIntervalTicks()),
                false
        );
        return 1;
    }

    private static int setPerformance(CommandContext<CommandSourceStack> context) {
        String requested = StringArgumentType.getString(context, "profile").toLowerCase(Locale.ROOT);
        BlastPhysicsConstants.PerformanceProfile profile = BlastPhysicsConstants.PerformanceProfile.byId(requested);
        if (profile == null) {
            context.getSource().sendFailure(Component.literal("Unknown profile: " + requested + ". Use high_fidelity, balanced, or performance."));
            return 0;
        }

        BlastPhysicsConstants.setActiveProfile(profile);
        context.getSource().sendSuccess(
                () -> Component.literal("Atomfall performance set to " + profile.id()
                        + ". New blasts use the new sector count immediately; active blasts finish with mixed settings."),
                true
        );
        return 1;
    }

    private static int showShockLog(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(
                () -> Component.literal("Atomfall shock perf log: "
                        + (BlastPhysicsConstants.shockPerfLogEnabled() ? "on" : "off")
                        + " | interval=" + BlastPhysicsConstants.shockPerfLogIntervalTicks()
                        + " ticks | use /atomfall performance log on, off, or interval <ticks>"),
                false
        );
        return 1;
    }

    private static int enableShockLog(CommandContext<CommandSourceStack> context) {
        BlastPhysicsConstants.setShockPerfLogEnabled(true);
        context.getSource().sendSuccess(
                () -> Component.literal("Atomfall shock perf log enabled. Active blasts will write Atomfall shock perf lines every "
                        + BlastPhysicsConstants.shockPerfLogIntervalTicks() + " ticks."),
                true
        );
        return 1;
    }

    private static int disableShockLog(CommandContext<CommandSourceStack> context) {
        BlastPhysicsConstants.setShockPerfLogEnabled(false);
        context.getSource().sendSuccess(
                () -> Component.literal("Atomfall shock perf log disabled."),
                true
        );
        return 1;
    }

    private static int setShockLogInterval(CommandContext<CommandSourceStack> context) {
        int ticks = IntegerArgumentType.getInteger(context, "ticks");
        BlastPhysicsConstants.setShockPerfLogIntervalTicks(ticks);
        context.getSource().sendSuccess(
                () -> Component.literal("Atomfall shock perf log interval set to "
                        + BlastPhysicsConstants.shockPerfLogIntervalTicks() + " ticks."),
                true
        );
        return 1;
    }

    private static int spawnHeatwaveDefault(CommandContext<CommandSourceStack> context) {
        return spawnHeatwave(context, 300.0D, 2400.0D, 60);
    }

    private static int spawnHeatwaveRadius(CommandContext<CommandSourceStack> context) {
        double radius = DoubleArgumentType.getDouble(context, "radius");
        return spawnHeatwave(context, radius * 0.35D, radius, 60);
    }

    private static int spawnHeatwaveRadiusTemp(CommandContext<CommandSourceStack> context) {
        double radius = DoubleArgumentType.getDouble(context, "radius");
        double temperature = DoubleArgumentType.getDouble(context, "temperature");
        return spawnHeatwave(context, radius * 0.35D, radius, 60, temperature);
    }

    private static int spawnHeatwaveFull(CommandContext<CommandSourceStack> context) {
        double radius = DoubleArgumentType.getDouble(context, "radius");
        double temperature = DoubleArgumentType.getDouble(context, "temperature");
        int duration = IntegerArgumentType.getInteger(context, "duration");
        return spawnHeatwave(context, radius * 0.35D, radius, duration, temperature);
    }

    private static int spawnHeatwave(CommandContext<CommandSourceStack> context, double coreRadius, double maxRadius, int durationSeconds) {
        return spawnHeatwave(context, coreRadius, maxRadius, durationSeconds, 2400.0D);
    }

    private static int spawnHeatwave(CommandContext<CommandSourceStack> context, double coreRadius, double maxRadius, int durationSeconds, double peakTemperature) {
        if (!(context.getSource().getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            context.getSource().sendFailure(Component.literal("This command can only be run on the server."));
            return 0;
        }

        BlockPos center = BlockPos.containing(context.getSource().getPosition());
        double hotZoneTemp = Math.max(500.0D, peakTemperature * 0.35D);
        double waterFlashRadius = maxRadius * 0.28D;

        TemperatureZone zone = new TemperatureZone(
                center,
                coreRadius,
                maxRadius,
                peakTemperature,
                hotZoneTemp,
                waterFlashRadius,
                durationSeconds * 20
        );

        TemperatureSavedData.get(level).addZone(zone);
        context.getSource().sendSuccess(
                () -> Component.literal("Heatwave spawned at " + center.getX() + ", " + center.getY() + ", " + center.getZ()
                        + " | radius=" + String.format("%.0f", maxRadius)
                        + " | temp=" + String.format("%.0f", peakTemperature) + "C"
                        + " | duration=" + durationSeconds + "s"),
                true
        );
        return 1;
    }

    private static int showThermal(CommandContext<CommandSourceStack> context) {
        BlastPhysicsConstants.ThermalProfile profile = BlastPhysicsConstants.activeThermalProfile();
        context.getSource().sendSuccess(
                () -> Component.literal("Atomfall thermal profile: " + profile.id()
                        + " | thermalBudget=" + BlastPhysicsConstants.thermalBlockBudget()
                        + " | waterBudget=" + BlastPhysicsConstants.waterBfsBudget()
                        + " | interval=" + BlastPhysicsConstants.thermalEnvironmentIntervalTicks()),
                false
        );
        return 1;
    }

    private static int setThermal(CommandContext<CommandSourceStack> context) {
        String requested = StringArgumentType.getString(context, "profile").toLowerCase(Locale.ROOT);
        BlastPhysicsConstants.ThermalProfile profile = BlastPhysicsConstants.ThermalProfile.byId(requested);
        if (profile == null) {
            context.getSource().sendFailure(Component.literal("Unknown thermal profile: " + requested + ". Use high, balanced, or performance."));
            return 0;
        }

        BlastPhysicsConstants.setActiveThermalProfile(profile);
        context.getSource().sendSuccess(
                () -> Component.literal("Atomfall thermal profile set to " + profile.id()
                        + ". Active temperature zones pick up the new budget immediately."),
                true
        );
        return 1;
    }

    private static int setShockParam(CommandContext<CommandSourceStack> context) {
        String key = StringArgumentType.getString(context, "key").toLowerCase(Locale.ROOT);
        int value = IntegerArgumentType.getInteger(context, "value");
        if (BlastPhysicsConstants.setShockParam(key, value)) {
            context.getSource().sendSuccess(
                    () -> Component.literal("Shock parameter '" + key + "' set to " + value + ". Active immediately."),
                    true
            );
            return 1;
        }
        context.getSource().sendFailure(Component.literal("Unknown shock parameter: " + key
                + ". Valid keys: sectors, strideNear, strideMid, strideFar, lateralNear, lateralMid, lateralFar, craterBudget, shockBudget"));
        return 0;
    }

    private static int resetShockOverrides(CommandContext<CommandSourceStack> context) {
        BlastPhysicsConstants.resetShockOverrides();
        context.getSource().sendSuccess(
                () -> Component.literal("All shock parameter overrides cleared. Reverted to profile defaults."),
                true
        );
        return 1;
    }

    private static int setThermalParam(CommandContext<CommandSourceStack> context) {
        String key = StringArgumentType.getString(context, "key").toLowerCase(Locale.ROOT);
        int value = IntegerArgumentType.getInteger(context, "value");
        if (BlastPhysicsConstants.setThermalParam(key, value)) {
            context.getSource().sendSuccess(
                    () -> Component.literal("Thermal parameter '" + key + "' set to " + value + ". Active immediately."),
                    true
            );
            return 1;
        }
        context.getSource().sendFailure(Component.literal("Unknown thermal parameter: " + key
                + ". Valid keys: thermalBudget, waterBudget, interval"));
        return 0;
    }

    private static int resetThermalOverrides(CommandContext<CommandSourceStack> context) {
        BlastPhysicsConstants.resetThermalOverrides();
        context.getSource().sendSuccess(
                () -> Component.literal("All thermal parameter overrides cleared. Reverted to profile defaults."),
                true
        );
        return 1;
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestProfiles(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (BlastPhysicsConstants.PerformanceProfile profile : BlastPhysicsConstants.PerformanceProfile.values()) {
            builder.suggest(profile.id());
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestThermalProfiles(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (BlastPhysicsConstants.ThermalProfile profile : BlastPhysicsConstants.ThermalProfile.values()) {
            builder.suggest(profile.id());
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestShockKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (String key : new String[]{"sectors", "strideNear", "strideMid", "strideFar",
                "lateralNear", "lateralMid", "lateralFar", "craterBudget", "shockBudget"}) {
            builder.suggest(key);
        }
        return builder.buildFuture();
    }

    private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestThermalKeys(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        for (String key : new String[]{"thermalBudget", "waterBudget", "interval"}) {
            builder.suggest(key);
        }
        return builder.buildFuture();
    }
}
