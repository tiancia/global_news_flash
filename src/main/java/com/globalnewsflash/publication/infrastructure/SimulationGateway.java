package com.globalnewsflash.publication.infrastructure;

import com.globalnewsflash.publication.domain.DeliveryResult;
import com.globalnewsflash.publication.domain.PublicationOperation;
import com.globalnewsflash.publication.port.out.DeliveryGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

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
