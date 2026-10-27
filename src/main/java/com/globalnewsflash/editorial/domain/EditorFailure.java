package com.globalnewsflash.editorial.domain;

/** Safe failure metadata. Never carries a remote body, credential, or transport exception. */
public final class EditorFailure extends RuntimeException {
    private final String code;
    private final boolean chargedUnknown;
    private final EditorResponse.Usage usage;

    public EditorFailure(String code, boolean chargedUnknown) {
        this(code, chargedUnknown, null);
    }

    public EditorFailure(String code, boolean chargedUnknown, EditorResponse.Usage usage) {
        super(code != null && code.matches("[A-Z][A-Z0-9_]{0,127}") ? code : "EDITOR_FAILURE");
        this.code = getMessage();
        this.chargedUnknown = chargedUnknown;
        this.usage = usage;
    }

    public String code() { return code; }
    public boolean chargedUnknown() { return chargedUnknown; }
    public EditorResponse.Usage usage() { return usage; }
}
