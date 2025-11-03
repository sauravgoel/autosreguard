package io.autosreguard.remediation;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Append-only audit trail of every remediation decision.
 *
 * <p>Holds a bounded in-memory window for the admin API and forwards every record to a
 * durable sink (JSON Lines file by default). In production, point the sink at an immutable
 * store (object storage with retention lock, or your SIEM).
 */
public final class AuditLog implements AutoCloseable {

    private final List<AuditRecord> recent = new ArrayList<>();
    private final int maxInMemory;
    private final Consumer<AuditRecord> sink;
    private final java.io.Closeable closeable;
    private long seq;

    private AuditLog(int maxInMemory, Consumer<AuditRecord> sink, java.io.Closeable closeable) {
        if (maxInMemory <= 0) {
            throw new IllegalArgumentException("maxInMemory must be positive");
        }
        this.maxInMemory = maxInMemory;
        this.sink = sink;
        this.closeable = closeable;
    }

    /** In-memory only. Fine for tests and the simulator; never for production. */
    public static AuditLog inMemory(int maxInMemory) {
        return new AuditLog(maxInMemory, r -> { }, () -> { });
    }

    /** Appends every record as a JSON line to {@code file}, flushing each write. */
    public static AuditLog jsonLines(Path file, int maxInMemory) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        Consumer<AuditRecord> sink = r -> {
            try {
                w.write(r.toJson());
                w.newLine();
                w.flush(); // audit durability beats throughput here
            } catch (IOException e) {
                throw new UncheckedIOException("audit write failed; refusing to act without audit", e);
            }
        };
        return new AuditLog(maxInMemory, sink, w);
    }

    /** Assigns the next sequence number. Callers build the record with it, then {@link #append}. */
    public synchronized long nextSeq() {
        return ++seq;
    }

    /**
     * Appends a record. If the durable sink fails, the exception propagates: the engine must
     * not take an action it cannot audit.
     */
    public synchronized void append(AuditRecord r) {
        sink.accept(r);
        recent.add(r);
        if (recent.size() > maxInMemory) {
            recent.remove(0);
        }
    }

    public synchronized List<AuditRecord> recent() {
        return List.copyOf(recent);
    }

    /** Latest record for an action id, i.e. its current state. */
    public synchronized Optional<AuditRecord> latest(String actionId) {
        for (int i = recent.size() - 1; i >= 0; i--) {
            if (recent.get(i).actionId().equals(actionId)) {
                return Optional.of(recent.get(i));
            }
        }
        return Optional.empty();
    }

    public synchronized List<AuditRecord> history(String actionId) {
        return recent.stream().filter(r -> r.actionId().equals(actionId)).toList();
    }

    @Override
    public void close() throws IOException {
        closeable.close();
    }
}
