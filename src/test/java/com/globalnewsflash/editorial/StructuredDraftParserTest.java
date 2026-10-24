package com.globalnewsflash.editorial;

import com.globalnewsflash.editorial.infrastructure.StructuredDraftParser;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredDraftParserTest {
    private final StructuredDraftParser parser = new StructuredDraftParser(JsonMapper.builder().build());
    private static final String VALID = """
            {"statements":[{"sourceId":"usgs","materialId":"synthetic-1","factId":"magnitude",
              "text":"USGS reports a magnitude 5.8 earthquake."}],
             "provider":"synthetic","model":"exact-evidence-v1","promptVersion":"synthetic-evidence-v1",
             "contractVersion":"referenced-statements-v1","usage":{"inputTokens":0,"outputTokens":0,"estimated":true},
             "synthetic":true}
            """;

    @Test
    void parsesReferencedStatementsAndTraceMetadata() {
        var response = parser.parse(VALID);
        assertThat(response.statements()).hasSize(1);
        assertThat(response.statements().getFirst().factId()).isEqualTo("magnitude");
        assertThat(response.provider()).isEqualTo("synthetic");
        assertThat(response.usage().estimated()).isTrue();
    }

    @Test
    void rejectsInvalidJsonMissingFieldsAndFreeFormBody() {
        for (String invalid : new String[]{"{not json}", "{}", "null", "[]",
                VALID.replace("\"provider\":\"synthetic\",", ""),
                VALID.replace("\"provider\":\"synthetic\",", "\"provider\":\"synthetic\",\"body\":\"Unreferenced claim\","),
                VALID.replace("\"text\":\"USGS reports a magnitude 5.8 earthquake.\"", "\"text\":null")}) {
            assertThatThrownBy(() -> parser.parse(invalid)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid structured editorial response");
        }
    }

    @Test
    void rejectsCoercionNegativeUsageAndOversizedResponses() {
        for (String invalid : new String[]{VALID.replace("\"synthetic\":true", "\"synthetic\":\"true\""),
                VALID.replace("\"inputTokens\":0", "\"inputTokens\":-1"),
                VALID.replace("\"inputTokens\":0", "\"inputTokens\":0.5"),
                VALID.replace("\"inputTokens\":0", "\"inputTokens\":\"0\""),
                " ".repeat(65_537)}) {
            assertThatThrownBy(() -> parser.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsTrailingJsonAndDuplicateKeysInsteadOfSilentlyChoosingAValue() {
        for (String invalid : new String[]{VALID + "{}", VALID + " true",
                VALID.replace("\"provider\":\"synthetic\"", "\"provider\":\"other\",\"provider\":\"synthetic\"")}) {
            assertThatThrownBy(() -> parser.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
