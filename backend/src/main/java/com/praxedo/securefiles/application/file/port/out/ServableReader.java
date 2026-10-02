package com.praxedo.securefiles.application.file.port.out;

import com.praxedo.securefiles.application.file.model.ByteRange;
import com.praxedo.securefiles.application.file.model.ContentStream;
import com.praxedo.securefiles.domain.file.valueobject.ObjectKey;

/**
 * What the download path may do with the object storage — read cleared
 * content, and nothing else.
 *
 * <p><strong>This interface is the invariant's last line of defence.</strong>
 * It has no method that could even name the quarantine, and its implementation
 * holds the {@code delivery} credentials, which the storage itself refuses on
 * the quarantine. Even with a corrupted status in the database and the
 * application check removed, the download path could not reach an unscanned
 * object.
 */
public interface ServableReader {

    /**
     * Opens a cleared object, whole or in part.
     *
     * @throws ObjectMissingException    when no such object exists
     * @throws RangeNotSatisfiableException when the range starts past the end
     * @throws StorageUnavailableException  when the storage cannot be reached
     */
    ContentStream open(ObjectKey key, ByteRange range);
}
