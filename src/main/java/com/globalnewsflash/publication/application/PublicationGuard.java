package com.globalnewsflash.publication.application;

import com.globalnewsflash.publication.domain.PublicationOperation;
import java.util.List;

/** Rechecks current eligibility immediately before the persisted sending boundary. */
public interface PublicationGuard {
    List<String> rejectionReasons(PublicationOperation operation);
}
