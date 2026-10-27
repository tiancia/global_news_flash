package com.globalnewsflash.digest.port.out;

import com.globalnewsflash.digest.domain.Candidate;
import com.globalnewsflash.digest.domain.DigestRecord;
import java.time.Instant;
import java.util.List;

/** Persistence contract; locking and compare-and-set semantics are implemented by the adapter. */
public interface DigestRepository {
    DigestRecord lockDate(String date, String target);
    DigestRecord byPublication(String id);
    DigestRecord byEvent(String key);
    List<DigestRecord> list();
    int insert(DigestRecord record);
    int attachPublication(String id, String publicationId);
    List<Candidate> candidates(String target, Instant since, Instant now);
    Instant sentAt(String id);
}
