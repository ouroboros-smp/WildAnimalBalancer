package com.ouroboros.wildlife;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the JSONL spawn audit log: the pure line builder (byte for byte,
 * including escaping) and the append-through-the-IO-thread write path.
 */
class SpawnLoggerTest {

    @Test
    void jsonLineIsStableAndComplete() {
        String line = SpawnLogger.json(0L, "world", "uuid:1:2", "plains", "cow",
                4, 3, 8, 2, 3, 22, 100, 64, -200);

        assertEquals("{\"time\":\"1970-01-01T00:00:00Z\""
                + ",\"world\":\"world\""
                + ",\"cell\":\"uuid:1:2\""
                + ",\"biome\":\"plains\""
                + ",\"species\":\"cow\""
                + ",\"spawned\":4"
                + ",\"wild\":3"
                + ",\"target\":8"
                + ",\"players\":2"
                + ",\"streak\":3"
                + ",\"budget_left\":22"
                + ",\"x\":100"
                + ",\"y\":64"
                + ",\"z\":-200"
                + "}", line);
    }

    @Test
    void jsonStringsAreEscaped() {
        assertEquals("a\\\"b\\\\c\\nd\\te", SpawnLogger.escape("a\"b\\c\nd\te"));
        assertEquals("\\u0001", SpawnLogger.escape("\u0001"));
    }

    @Test
    void writesAppendOneLinePerEventAndDrainOnClose(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested").resolve("spawn-log.jsonl");
        SpawnLogger logger = new SpawnLogger(file, Logger.getLogger("test"));
        logger.write("{\"a\":1}");
        logger.write("{\"b\":2}");
        logger.close(); // close drains the IO thread, so both lines are on disk

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), lines);
    }

    @Test
    void writeAfterCloseIsDroppedWithoutThrowing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("spawn-log.jsonl");
        SpawnLogger logger = new SpawnLogger(file, Logger.getLogger("test"));
        logger.write("{\"a\":1}");
        logger.close();
        // reload race: an in-flight region task can still hold the old sink after
        // stopRuntime closed it; the write must be dropped, never thrown
        logger.write("{\"late\":true}");

        assertEquals(List.of("{\"a\":1}"), Files.readAllLines(file, StandardCharsets.UTF_8));
    }
}
