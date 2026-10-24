package com.globalnewsflash.automation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:ops-tests;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "news.clock.fixed=2026-10-24T14:00:00Z"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OpsControllerTest {
    static final String TOKEN = "test-operator-token-0123456789abcdef";
    @Autowired MockMvc mvc;
    @Test void healthIsPublicAndOpsRequireHeaderToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/ops/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/status").header("X-Ops-Token", "incorrect")).andExpect(status().isUnauthorized());
        mvc.perform(get("/ops/status").header("X-Ops-Token", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.externalEnabled").value(false)).andExpect(jsonPath("$.activeEditor").value("synthetic"));
    }
    @Test void mutatingRequestsAreValidated() throws Exception {
        mvc.perform(post("/ops/pause").header("X-Ops-Token", TOKEN).contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/ops/pause").header("X-Ops-Token", TOKEN).contentType("application/json").content("{\"paused\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.paused").value(true));
        mvc.perform(post("/ops/sources/usgs:real/state").header("X-Ops-Token", TOKEN)
                .contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isBadRequest());
    }
    @Test void demoEndpointExecutesOnlyFictionalWorkflow() throws Exception {
        mvc.perform(post("/ops/pause").header("X-Ops-Token", TOKEN).contentType("application/json").content("{\"paused\":false}"))
                .andExpect(status().isOk());
        mvc.perform(post("/ops/demo").header("X-Ops-Token", TOKEN).contentType("application/json")
                        .content("{\"scenario\":\"VALID\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("SYNTHETIC_OFFLINE"))
                .andExpect(jsonPath("$.deliveryState").value("SENT"));
        mvc.perform(get("/ops/report").header("X-Ops-Token", TOKEN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.realModelUsage").value("NOT_CALLED"))
                .andExpect(jsonPath("$.growth.knownJoins").value(1)).andExpect(jsonPath("$.growth.unknownJoins").value(1));
        mvc.perform(post("/ops/pause").header("X-Ops-Token", TOKEN).contentType("application/json").content("{\"paused\":true}"))
                .andExpect(status().isOk());
    }
}
