package com.globalnewsflash.digest.port.in;

import com.globalnewsflash.digest.domain.DigestRecord;
import java.util.List;
import java.util.Map;

/** Public use cases of the digest module. */
public interface DigestUseCase {

    List<DigestRecord> list();
    Map<String, Object> runDue();
}
