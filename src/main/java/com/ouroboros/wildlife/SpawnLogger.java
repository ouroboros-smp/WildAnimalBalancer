package com.ouroboros.wildlife;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Append-only JSONL audit log of top-ups, one JSON object per line, written to
 * the plugin data folder for later analysis (jq, a spreadsheet, whatever).
 *
 * Writes are handed to a single daemon IO thread so a Folia region thread
 * never blocks on disk. Spawn events are rare by design (throttled per cycle
 * and per hourly cell budget), so the queue stays tiny. A write failure is
 * logged once, not per event, so a full disk cannot flood the console.
 */
final class SpawnLogger implements AutoCloseable {

    private final Path file;
    private final Logger log;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wildlife-spawn-log");
        t.setDaemon(true);
        return t;
    });

    SpawnLogger(Path file, Logger log) {
        this.file = file;
        this.log = log;
    }

    /** Queue one JSON line for append. Safe to call from any region thread. */
    void write(String jsonLine) {
        try {
            io.execute(() -> {
                try {
                    Path parent = file.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.writeString(file, jsonLine + "\n", StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    if (warned.compareAndSet(false, true)) {
                        log.log(Level.WARNING, "Could not write spawn log " + file
                                + "; further write failures will not be reported.", ex);
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            // Closed mid-reload while an in-flight region task still held the old
            // sink. Drop the entry: the balancer that produced it is already
            // stopped, and logging must never throw on a region thread.
        }
    }

    @Override
    public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warning("Spawn log writer did not drain in time; some entries may be lost.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Build the JSON line for one top-up. Takes the timestamp as a parameter so
     * it stays a pure function and can be unit tested byte for byte.
     */
    static String json(long epochMillis, String world, String cell, String biome, String species,
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
    static String escape(String v) {
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
