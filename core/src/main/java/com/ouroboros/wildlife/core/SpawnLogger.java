package com.ouroboros.wildlife.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Append-only JSONL audit log of top-ups, one JSON object per line, written to
 * the platform data folder for later analysis (jq, a spreadsheet, whatever).
 *
 * Writes are handed to a single daemon IO thread so a simulation thread never
 * blocks on disk. Spawn events are rare by design (throttled per cycle
 * and per hourly cell budget), so the queue stays tiny. A write failure is
 * logged once, not per event, so a full disk cannot flood the console.
 */
public final class SpawnLogger implements AutoCloseable {

    private final Path file;
    private final BiConsumer<String, Throwable> warn;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wildlife-spawn-log");
        t.setDaemon(true);
        return t;
    });

    public SpawnLogger(Path file, BiConsumer<String, Throwable> warn) {
        this.file = file;
        this.warn = warn;
    }

    /** Queue one JSON line for append. Safe to call from any platform thread. */
    public void write(String jsonLine) {
        try {
            io.execute(() -> {
                try {
                    Path parent = file.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.writeString(file, jsonLine + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    if (warned.compareAndSet(false, true)) {
                        warn.accept("Could not write spawn log " + file
                                + "; further write failures will not be reported.", ex);
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            // Closed mid-reload while in-flight work still held the old sink.
            // Drop the entry because logging must never throw on a server thread.
        }
    }

    @Override
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                warn.accept("Spawn log writer did not drain in time; some entries may be lost.", null);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Build the JSON line for one top-up. Takes the timestamp as a parameter so
     * it stays a pure function and can be unit tested byte for byte.
     */
    public static String json(long epochMillis, String world, String cell, String biome, String species,
                              int spawned, int wild, int target, int players, int streak, int budgetLeft,
                              int x, int y, int z) {
        return "{\"time\":\"" + Instant.ofEpochMilli(epochMillis) + '"'
                + ",\"world\":\"" + escape(world) + '"'
                + ",\"cell\":\"" + escape(cell) + '"'
                + ",\"biome\":\"" + escape(biome) + '"'
                + ",\"species\":\"" + escape(species) + '"'
                + ",\"spawned\":" + spawned
                + ",\"wild\":" + wild
                + ",\"target\":" + target
                + ",\"players\":" + players
                + ",\"streak\":" + streak
                + ",\"budget_left\":" + budgetLeft
                + ",\"x\":" + x
                + ",\"y\":" + y
                + ",\"z\":" + z
                + '}';
    }

    /** Minimal JSON string escape for the fields above (names, biome keys). */
    public static String escape(String v) {
        StringBuilder b = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
                }
            }
        }
        return b.toString();
    }
}
