package com.globalnewsflash.automation.domain;

public record EventVersion(String id, String eventKey, int eventVersion, String fingerprint,
                        String editorialState, String decisionPayload, String policyVersion,
                        String publicationId, String materialsPayload, String policyPayload, String sourcesPayload,
                        String budgetReservationId, long createdAt) {}
