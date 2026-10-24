package com.globalnewsflash.automation;

import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimePropertiesTest {
    @Test void liveModeCannotBeEnabled() {
        var properties = new RuntimeProperties(); properties.setMode("LIVE");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
    @Test void externalFlagCannotTurnOnClients() {
        var properties = new RuntimeProperties(); properties.setExternalEnabled(true);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
