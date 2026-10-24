package com.globalnewsflash.editorial.application;

import com.globalnewsflash.editorial.domain.EditorRequest;
import com.globalnewsflash.editorial.domain.EditorResponse;

/** Implement a provider here without making editorial policy depend on its transport or SDK. */
public interface EditorPort {
    EditorResponse edit(EditorRequest request);
}
