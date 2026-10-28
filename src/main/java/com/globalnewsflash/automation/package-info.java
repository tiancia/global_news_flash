/**
 * Live and synthetic workflow coordination, model accounting, and operational control.
 *
 * <p>Start with
 * {@link com.globalnewsflash.automation.infrastructure.LiveScheduling} for scheduled
 * triggers, {@link com.globalnewsflash.automation.interfaces.OpsController} for
 * manual operations, and
 * {@link com.globalnewsflash.automation.application.LiveAutomationService} for one
 * bounded live cycle. {@code LiveBootstrap} synchronizes source declarations;
 * it does not execute a collection or publication cycle.</p>
 *
 * <p>{@link com.globalnewsflash.automation.application.LivePipelineService} commits
 * an editor job and budget reservation before calling the editorial port, then
 * settles cost and atomically saves the decision and publication intent. Expired
 * or ambiguous billable work becomes {@code UNCERTAIN}; cost reconciliation and
 * requesting a fresh response are separate operator actions.</p>
 *
 * <p>This module owns event/material snapshots, editing jobs, budget periods and
 * reservations, cycle leases/history, local automation alerts, and webhook notice
 * delivery state. Source admission belongs to {@code ingestion}; the persistent
 * pause and message delivery state belong to {@code publication}. A completed
 * cycle is not evidence that every input was eligible or every message arrived.</p>
 */
package com.globalnewsflash.automation;
