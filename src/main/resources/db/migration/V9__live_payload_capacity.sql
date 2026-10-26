-- Real source bodies and retained evidence can exceed MySQL TEXT's 64 KiB byte limit.
-- H2 development databases use MODE=MySQL; real MySQL migration remains to be verified.
ALTER TABLE news_material MODIFY COLUMN payload MEDIUMTEXT NOT NULL;
ALTER TABLE news_event_version MODIFY COLUMN decision_payload MEDIUMTEXT NOT NULL;
ALTER TABLE news_event_version MODIFY COLUMN materials_payload MEDIUMTEXT NOT NULL;
ALTER TABLE news_event_version MODIFY COLUMN policy_payload MEDIUMTEXT NOT NULL;
ALTER TABLE news_event_version MODIFY COLUMN sources_payload MEDIUMTEXT NOT NULL;
