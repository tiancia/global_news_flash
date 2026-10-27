package com.globalnewsflash.editorial.port.out;

import com.globalnewsflash.editorial.domain.EditorRequest;
import com.globalnewsflash.editorial.domain.EditorResponse;

/** Implement a provider here without making editorial policy depend on its transport or SDK. */
public interface EditorPort {
    EditorResponse edit(EditorRequest request);

    /** The default preserves the fixture-only contract for simple test implementations. */
    default boolean supports(boolean synthetic) { return synthetic; }

    /** Identifies the actual instructions used for a new live request. */
    default String livePromptVersion() { return "grounded-english-news-v1"; }
}
