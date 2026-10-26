package com.globalnewsflash.publication.infrastructure;

import com.globalnewsflash.publication.application.DeliveryGateway;
import com.globalnewsflash.publication.application.DeliveryResult;
import com.globalnewsflash.publication.domain.PublicationOperation;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Component
@ConditionalOnProperty(name = "news.runtime.mode", havingValue = "OFFLINE", matchIfMissing = true)
public final class SimulationGateway implements DeliveryGateway {
    @Override
    public DeliveryResult deliver(PublicationOperation operation) {
        if (!operation.synthetic()) {
            throw new IllegalArgumentException("Only synthetic operations can use the offline gateway");
        }
        return DeliveryResult.success("sim-" + operation.id());
    }
}
