package com.globalnewsflash.automation;

import com.globalnewsflash.automation.application.NotificationGateway;
import com.globalnewsflash.automation.application.OpsNotificationService;
import com.globalnewsflash.automation.infrastructure.LiveAutomationMapper;
import com.globalnewsflash.automation.infrastructure.NotificationMapper;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.integrations.webhook.AlertProperties;
import com.globalnewsflash.publication.application.PublicationService;
import com.globalnewsflash.publication.domain.PublicationAlert;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpsNotificationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    private final RuntimeProperties runtime = new RuntimeProperties();
    private final AlertProperties properties = new AlertProperties();
    private final LiveAutomationMapper automation = mock(LiveAutomationMapper.class);
    private final PublicationService publications = mock(PublicationService.class);
    private final NotificationMapper notices = mock(NotificationMapper.class);
    private final NotificationGateway gateway = mock(NotificationGateway.class);
    private final OpsNotificationService service = new OpsNotificationService(runtime, properties, automation,
            publications, notices, gateway, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void configure() {
        runtime.setMode("LIVE");
        runtime.setExternalEnabled(true);
        properties.setEnabled(true);
        properties.setWebhookUrl("https://alerts.example.org/receive");
    }

    @ParameterizedTest
    @ValueSource(strings = {"offline", "external-disabled", "alert-disabled", "unconfigured"})
    void disabledOrUnconfiguredAlertsDoNotClaimOrSend(String mode) {
        switch (mode) {
            case "offline" -> runtime.setMode("OFFLINE");
            case "external-disabled" -> runtime.setExternalEnabled(false);
            case "alert-disabled" -> properties.setEnabled(false);
            case "unconfigured" -> properties.setWebhookUrl("");
        }
        assertThat(service.flush()).isZero();
        verifyNoInteractions(automation, publications, notices, gateway);
    }

    @Test void successfulAttemptKeepsNotificationIdentityAndCompletesClaim() {
        due(0, true);
        var notification = new NotificationGateway.Notification("notice-1", "EDITOR", "job-1", "PROVIDER_UNAVAILABLE");
        when(gateway.deliver(notification)).thenReturn(true);

        assertThat(service.flush()).isEqualTo(1);

        var order = inOrder(notices, gateway);
        order.verify(notices).claim("notice-1", NOW.toEpochMilli() + 20_000, NOW.toEpochMilli());
        order.verify(gateway).deliver(notification);
        order.verify(notices).finish("notice-1", 1, "SENT", NOW.toEpochMilli() + 60_000, null);
    }

    @Test void unconfirmedDeliveryIsRescheduledWithoutTransportRetry() {
        due(0, true);
        assertThat(service.flush()).isZero();
        verify(gateway, times(1)).deliver(any());
        verify(notices).finish("notice-1", 1, "PENDING", NOW.toEpochMilli() + 60_000, "ALERT_WEBHOOK_UNAVAILABLE");
    }

    @Test void fifthUnconfirmedAttemptBecomesFailed() {
        due(4, true);
        assertThat(service.flush()).isZero();
        verify(gateway, times(1)).deliver(any());
        verify(notices).finish("notice-1", 5, "FAILED", NOW.toEpochMilli() + 960_000, "ALERT_WEBHOOK_UNAVAILABLE");
    }

    @Test void exhaustedNoticeIsNotClaimedOrSentAgain() {
        due(5, false);
        assertThat(service.flush()).isZero();
        verifyNoInteractions(gateway);
        verify(notices, never()).claim(anyString(), anyLong(), anyLong());
        verify(notices).finish("notice-1", 5, "FAILED", NOW.toEpochMilli(), "ALERT_ATTEMPTS_EXHAUSTED");
    }

    @Test void losingClaimCannotSendOrFinishAnotherWorkersNotice() {
        due(0, false);
        assertThat(service.flush()).isZero();
        verifyNoInteractions(gateway);
        verify(notices, never()).finish(anyString(), anyInt(), anyString(), anyLong(), any());
    }

    @Test void gathersBothAlertSourcesAndSkipsAcknowledgedPublications() {
        when(automation.unnotifiedAlerts()).thenReturn(List.of(
                new LiveAutomationMapper.Alert("editor-alert", "EDITOR", "job", "EDITOR_FAILED", NOW.toEpochMilli())));
        when(publications.unnotifiedAlerts()).thenReturn(List.of(
                new PublicationAlert("send-alert", "operation", "DELIVERY", "UNKNOWN", NOW, null),
                new PublicationAlert("acknowledged", "old-operation", "DELIVERY", "UNKNOWN", NOW, NOW)));
        assertThat(service.flush()).isZero();
        verify(notices).insert(new NotificationMapper.Notice("editor-alert", "EDITOR", "job", "EDITOR_FAILED", "PENDING", 0, 0, null));
        verify(notices).insert(new NotificationMapper.Notice("send-alert", "DELIVERY", "operation", "UNKNOWN", "PENDING", 0, 0, null));
        verify(notices, times(2)).insert(any());
        verifyNoInteractions(gateway);
    }

    private void due(int attempts, boolean claimed) {
        when(notices.due(NOW.toEpochMilli())).thenReturn(List.of(new NotificationMapper.Notice(
                "notice-1", "EDITOR", "job-1", "PROVIDER_UNAVAILABLE", "PENDING", attempts, 0, null)));
        if (claimed) when(notices.claim(eq("notice-1"), anyLong(), eq(NOW.toEpochMilli()))).thenReturn(1);
    }
}
