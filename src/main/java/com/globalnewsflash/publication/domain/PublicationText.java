package com.globalnewsflash.publication.domain;

/** Deterministic transport labels are derived from the immutable, audited operation kind. */
public final class PublicationText {
    private PublicationText() {}

    public static String render(PublicationOperation operation) {
        if (operation.body() == null) return null;
        return switch (operation.kind()) {
            case "UPDATE" -> "UPDATE\n\n" + operation.body();
            case "CORRECTION" -> "CORRECTION\n\n" + operation.body();
            default -> operation.body();
        };
    }

    public static boolean withinLimit(String text) {
        return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= 4096;
    }
}
