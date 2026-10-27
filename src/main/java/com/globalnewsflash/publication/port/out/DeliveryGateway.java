package com.globalnewsflash.publication.port.out;

import com.globalnewsflash.publication.domain.DeliveryResult;
import com.globalnewsflash.publication.domain.PublicationOperation;

/** The delivery boundary implemented by offline simulation and the live Telegram adapter. */
public interface DeliveryGateway {
    DeliveryResult deliver(PublicationOperation operation);
}
