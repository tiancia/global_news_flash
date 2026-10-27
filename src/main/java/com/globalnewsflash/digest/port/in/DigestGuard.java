package com.globalnewsflash.digest.port.in;

import com.globalnewsflash.publication.domain.PublicationOperation;
import java.util.List;

/** Public use cases of the digest module. */
public interface DigestGuard {

    boolean handles(PublicationOperation operation);
    List<String> rejectionReasons(PublicationOperation operation);
}
