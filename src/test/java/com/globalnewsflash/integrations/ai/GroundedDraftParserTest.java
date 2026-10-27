package com.globalnewsflash.integrations.ai;

import com.globalnewsflash.editorial.domain.EditorRequest;
import com.globalnewsflash.editorial.domain.EditorResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GroundedDraftParserTest {
    private final GroundedDraftParser parser = new GroundedDraftParser(new JsonMapper());
    private final EditorRequest request = new EditorRequest("id", "event", "prompt", "contract",
            List.of(), Instant.parse("2026-01-01T00:00:00Z"), false);
    private final EditorResponse.Usage usage = new EditorResponse.Usage(100, 50, false);
    private static final String VALID = """
            {"risk":"LOW","relevant":true,"statements":[{"sourceId":"s","materialId":"m","factId":"f",
              "text":"A supported fact.","quote":"A supported fact."}]}
            """;

    @Test void keepsTrustedMetadataAndEvidenceReferencesForBothProviders() {
        for (String provider : List.of("openai", "deepseek")) {
            var result = parser.parse(VALID, provider, "model", request, usage);
            assertThat(result.provider()).isEqualTo(provider);
            assertThat(result.promptVersion()).isEqualTo("prompt");
            assertThat(result.usage()).isEqualTo(usage);
            assertThat(result.statements().getFirst().quote()).isEqualTo("A supported fact.");
            assertThat(result.synthetic()).isFalse();
        }
    }

    @Test void permitsExplicitlyIrrelevantEmptyDrafts() {
        var result = parser.parse("""
                {"risk":"REVIEW","relevant":false,"statements":[]}
                """, "deepseek", "model", request, usage);
        assertThat(result.statements()).isEmpty();
        assertThat(result.relevant()).isFalse();
        assertThat(result.risk()).isEqualTo("REVIEW");
    }

    @Test void rejectsMalformedCoercedUnreferencedAndOversizedDrafts() {
        for (String invalid : new String[]{"{not json}", "{}", "null", "[]", VALID + "{}",
                VALID.replace("\"LOW\"", "\"unsafe\""),
                VALID.replace("\"relevant\":true", "\"relevant\":\"true\""),
                VALID.replace("\"risk\":\"LOW\"", "\"risk\":\"LOW\",\"risk\":\"BLOCKED\""),
                VALID.replace("\"factId\":\"f\",", ""),
                VALID.replace("\"quote\":\"A supported fact.\"", "\"quote\":null"),
                VALID.replace("\"risk\":\"LOW\"", "\"risk\":\"LOW\",\"body\":\"Unreferenced\""),
                VALID.replace("\"text\":\"A supported fact.\"", "\"text\":\"" + "x".repeat(601) + "\""),
                " ".repeat(65_537)}) {
            assertThatThrownBy(() -> parser.parse(invalid, "openai", "model", request, usage))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
