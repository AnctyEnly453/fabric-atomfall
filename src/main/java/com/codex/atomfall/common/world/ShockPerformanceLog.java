package com.codex.atomfall.common.world;

import com.codex.atomfall.AtomfallMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public final class ShockPerformanceLog {
    private static final Object LOCK = new Object();
    private static boolean warned;

    private ShockPerformanceLog() {
    }

    public static Path path() {
        return FabricLoader.getInstance().getGameDir().resolve("logs").resolve("atomfall-shock-perf.log").toAbsolutePath();
    }

    public static void append(String message) {
        Path path = path();
        String line = Instant.now() + " " + message + System.lineSeparator();
        synchronized (LOCK) {
            try {
                Files.createDirectories(path.getParent());
                Files.writeString(path, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                if (!warned) {
                    warned = true;
                    AtomfallMod.LOGGER.error("Failed to write Atomfall shock perf log to {}", path, ex);
                }
            }
        }
    }
}
