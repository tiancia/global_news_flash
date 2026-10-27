package com.globalnewsflash.publication.application;

import com.globalnewsflash.publication.domain.PublicationOperation;

/** The delivery boundary implemented by offline simulation and the live Telegram adapter. */
public interface DeliveryGateway {
    DeliveryResult deliver(PublicationOperation operation);
}
