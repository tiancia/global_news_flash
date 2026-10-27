package com.globalnewsflash.digest.port.in;

import com.globalnewsflash.digest.domain.DigestItem;
import java.time.Instant;
import java.util.List;

/** Public use cases of the digest module. */
public interface DigestEvidenceUseCase {

    List<DigestItem> eligibleItems(List<DigestItem> items, String target, Instant start, Instant end);
    List<String> rejectionReasons(List<DigestItem> items, String target, Instant start, Instant end);
}
