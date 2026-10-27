package com.globalnewsflash.automation;

import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RuntimePropertiesTest {
    @Test void liveModeWithoutExternalSwitchDoesNotActivateClients() {
        var properties = new RuntimeProperties(); properties.setMode("LIVE");
        properties.validate();
        assertThat(properties.live()).isFalse();
        properties.setExternalEnabled(true);
        properties.validate();
        assertThat(properties.live()).isTrue();
    }
    @Test void invalidProviderFailsClosed() {
        var properties = new RuntimeProperties(); properties.setPreferredProvider("unrecognized");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
    @Test void externalFlagCannotTurnOnClients() {
        var properties = new RuntimeProperties(); properties.setExternalEnabled(true);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
