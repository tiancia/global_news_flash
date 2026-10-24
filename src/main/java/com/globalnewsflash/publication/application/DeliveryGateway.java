package com.globalnewsflash.publication.application;

import com.globalnewsflash.publication.domain.PublicationOperation;

/** The delivery boundary. The current application only supplies an offline implementation. */
public interface DeliveryGateway {
    DeliveryResult deliver(PublicationOperation operation);
}
