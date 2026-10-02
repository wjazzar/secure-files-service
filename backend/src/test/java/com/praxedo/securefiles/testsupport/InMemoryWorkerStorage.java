package com.praxedo.securefiles.testsupport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import com.praxedo.securefiles.application.file.exception.ObjectMissingException;
import com.praxedo.securefiles.application.file.exception.StorageUnavailableException;
import com.praxedo.securefiles.application.file.port.out.WorkerStorage;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;

/**
 * Both storage areas in memory, with switchable faults — every way a storage
 * can let the worker down at the worst moment.
 */
public final class InMemoryWorkerStorage implements WorkerStorage {

    public final Map<String, byte[]> quarantine = new HashMap<>();
    public final Map<String, byte[]> servable = new HashMap<>();

    public boolean failOpen;
    public boolean failWrite;
    public boolean failDeleteQuarantined;
    /** From the N-th read on, return other bytes than those stored — a storage that altered the object. */
    public int corruptFromRead = Integer.MAX_VALUE;
    /** Wraps every read of the quarantine: where a test makes the storage trickle. */
    public UnaryOperator<InputStream> quarantineReads = UnaryOperator.identity();
    /** Runs right after a servable copy is written: where a test injects "the lease was lost". */
    public Runnable afterServableWrite = () -> { };
    private int reads;

    @Override
    public InputStream openQuarantined(ObjectKey key) {
        if (failOpen) {
            throw new StorageUnavailableException("storage down", null);
        }
        byte[] content = quarantine.get(key.value());
        if (content == null) {
            throw new ObjectMissingException(key.value());
        }
        reads++;
        if (reads >= corruptFromRead) {
            byte[] altered = content.clone();
            altered[0] ^= 0x01;
            return quarantineReads.apply(new ByteArrayInputStream(altered));
        }
        return quarantineReads.apply(new ByteArrayInputStream(content));
    }

    @Override
    public void writeServable(ObjectKey key, InputStream content, long sizeBytes) {
        if (failWrite) {
            throw new StorageUnavailableException("storage down mid-copy", null);
        }
        try {
            servable.put(key.value(), content.readNBytes((int) sizeBytes));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        afterServableWrite.run();
    }

    @Override
    public void deleteQuarantined(ObjectKey key) {
        if (failDeleteQuarantined) {
            throw new StorageUnavailableException("storage down", null);
        }
        quarantine.remove(key.value());
    }

    @Override
    public void deleteServable(ObjectKey key) {
        servable.remove(key.value());
    }

    @Override
    public List<ObjectKey> listQuarantinedBefore(Instant olderThan, ObjectKey after, int limit) {
        return quarantine.keySet().stream()
                .sorted()
                .filter(key -> after == null || key.compareTo(after.value()) > 0)
                .map(ObjectKey::new)
                .limit(limit)
                .toList();
    }
}
