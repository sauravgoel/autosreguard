package io.autosreguard.remediation;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditLogTest {

    private static AuditRecord record(AuditLog log, String id, AuditRecord.Status status) {
        return new AuditRecord(log.nextSeq(), id, Instant.parse("2026-04-17T14:21:00Z"), status,
                AutonomyTier.AUTO_REMEDIATE, 0.91, "pool", "roll back", "roll forward", "autosreguard", "note",
                List.of("evidence \"quoted\""));
    }

    @Test
    void jsonLinesFileIsAppendOnlyAndOneRecordPerLine() throws Exception {
        Path file = Files.createTempFile("autosreguard-audit", ".jsonl");
        try (AuditLog log = AuditLog.jsonLines(file, 10)) {
            log.append(record(log, "rem-000001", AuditRecord.Status.EXECUTED));
            log.append(record(log, "rem-000001", AuditRecord.Status.REVERTED));
        }
        List<String> lines = Files.readAllLines(file);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("{") && lines.get(0).contains("\"status\":\"EXECUTED\""));
        assertTrue(lines.get(1).contains("\\\"quoted\\\""), "evidence is escaped, never breaks the line");
        Files.deleteIfExists(file);
    }

    @Test
    void historyAndLatestAreKeyedByActionId() {
        AuditLog log = AuditLog.inMemory(10);
        log.append(record(log, "a", AuditRecord.Status.PROPOSED));
        log.append(record(log, "b", AuditRecord.Status.EXECUTED));
        log.append(record(log, "a", AuditRecord.Status.EXECUTED));
        assertEquals(2, log.history("a").size());
        assertEquals(AuditRecord.Status.EXECUTED, log.latest("a").orElseThrow().status());
        assertTrue(log.latest("missing").isEmpty());
    }

    @Test
    void inMemoryWindowIsBounded() {
        AuditLog log = AuditLog.inMemory(3);
        for (int i = 0; i < 10; i++) {
            log.append(record(log, "id-" + i, AuditRecord.Status.EXECUTED));
        }
        assertEquals(3, log.recent().size());
    }
}
