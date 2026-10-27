package com.globalnewsflash.publication.infrastructure;

import com.globalnewsflash.shared.BoundedBodySubscriber;
import com.globalnewsflash.automation.infrastructure.RuntimeProperties;
import com.globalnewsflash.publication.application.DeliveryGateway;
import com.globalnewsflash.publication.application.DeliveryResult;
import com.globalnewsflash.publication.domain.PublicationOperation;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Plain-text Bot API transport. Exceptions, request URLs and response bodies never leave this boundary. */
@Component
@ConditionalOnProperty(name = "news.runtime.mode", havingValue = "LIVE")
public final class TelegramDeliveryGateway implements DeliveryGateway {
    private final RuntimeProperties runtime;
    private final TelegramProperties properties;
    private final PublicationMapper publications;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient client;
    private Permission cachedPermission;

    public TelegramDeliveryGateway(RuntimeProperties runtime, TelegramProperties properties,
            PublicationMapper publications, JsonMapper json, Clock clock) {
        this.runtime = runtime;
        this.properties = properties;
        this.publications = publications;
        this.json = json;
        this.clock = clock;
        this.client = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    @Override
    public DeliveryResult deliver(PublicationOperation operation) {
        if (!"LIVE".equals(runtime.getMode()) || !runtime.isExternalEnabled() || !properties.isEnabled()) {
            return DeliveryResult.failed("TELEGRAM_DELIVERY_DISABLED");
        }
        if (operation.synthetic()) return DeliveryResult.failed("TELEGRAM_SYNTHETIC_CONTENT_REJECTED");
        if (!properties.isConfigured()) return DeliveryResult.failed("TELEGRAM_CONFIGURATION_REQUIRED");
        if (!properties.getChatId().equals(operation.target())) return DeliveryResult.failed("TELEGRAM_TARGET_NOT_ALLOWED");
        String text = TelegramMessageText.render(operation);
        if (!TelegramMessageText.withinLimit(text)) {
            return DeliveryResult.failed("TELEGRAM_TEXT_LIMIT_EXCEEDED");
        }
        boolean edit = "EDIT".equals(operation.kind());
        if (!List.of("SEND", "EDIT", "UPDATE", "CORRECTION").contains(operation.kind())) {
            return DeliveryResult.failed("TELEGRAM_OPERATION_NOT_SUPPORTED");
        }
        Long originalId = null;
        if (!"SEND".equals(operation.kind())) {
            PublicationOperation original = operation.originalOperationId() == null ? null
                    : publications.find(operation.originalOperationId());
            if (original == null || original.synthetic() || !"SENT".equals(original.state())
                    || !operation.eventKey().equals(original.eventKey())
                    || !operation.target().equals(original.target())) {
                return DeliveryResult.failed("TELEGRAM_ORIGINAL_MESSAGE_REQUIRED");
            }
            originalId = positiveId(original.messageId());
            if (originalId == null) return DeliveryResult.failed("TELEGRAM_ORIGINAL_MESSAGE_REQUIRED");
        }

        Permission permission;
        try {
            permission = permission();
        } catch (PreflightFailure failure) {
            return DeliveryResult.failed(failure.code);
        } catch (RuntimeException failure) {
            return DeliveryResult.failed("TELEGRAM_PREFLIGHT_UNAVAILABLE");
        }
        if (edit && !permission.canEdit()) return DeliveryResult.failed("TELEGRAM_EDIT_PERMISSION_REQUIRED");
        if (operation.leaseUntil() == null || !clock.instant().isBefore(operation.leaseUntil())) {
            return DeliveryResult.failed("TELEGRAM_LEASE_EXPIRED_BEFORE_SEND");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        // Use the numeric ID resolved and verified by getChat, even when configuration uses @username.
        payload.put("chat_id", permission.chatId());
        payload.put("text", text);
        payload.put("link_preview_options", Map.of("is_disabled", true));
        if (edit) payload.put("message_id", originalId);
        else if (originalId != null) payload.put("reply_parameters", Map.of("message_id", originalId,
                "allow_sending_without_reply", false));
        try {
            ApiResponse response = call(edit ? "editMessageText" : "sendMessage", payload);
            JsonNode body = response.body();
            if (response.status() == 429 || body.path("error_code").asInt(0) == 429) {
                JsonNode delay = body.path("parameters").path("retry_after");
                if (delay.isIntegralNumber() && delay.canConvertToLong()
                        && delay.longValue() > 0 && delay.longValue() <= 604_800) {
                    return DeliveryResult.rateLimited(Duration.ofSeconds(delay.longValue()));
                }
                return DeliveryResult.unknown("TELEGRAM_INVALID_RETRY_AFTER");
            }
            int error = body.path("error_code").asInt(response.status());
            if (!body.path("ok").asBoolean(false)) {
                if (edit && error == 400 && originalId != null
                        && body.path("description").asText("").contains("message is not modified")) {
                    return DeliveryResult.success(originalId.toString());
                }
                if (response.status() >= 500 || error >= 500) return DeliveryResult.unknown("TELEGRAM_SERVER_ERROR");
                if (error == 400 || error == 401 || error == 403
                        || response.status() == 400 || response.status() == 401 || response.status() == 403) {
                    if (error == 401 || error == 403) clearPermission();
                    return DeliveryResult.failed("TELEGRAM_REQUEST_REJECTED_" + error);
                }
                return DeliveryResult.unknown("TELEGRAM_UNEXPECTED_RESPONSE");
            }
            if (response.status() != 200) return DeliveryResult.unknown("TELEGRAM_UNEXPECTED_HTTP_STATUS");
            JsonNode message = body.path("result");
            JsonNode id = message.path("message_id");
            JsonNode chat = message.path("chat").path("id");
            if (!id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0
                    || !chat.isIntegralNumber() || !chat.canConvertToLong()
                    || chat.longValue() != permission.chatId()
                    || (edit && id.longValue() != originalId)) {
                return DeliveryResult.unknown("TELEGRAM_INVALID_MESSAGE_RESULT");
            }
            return DeliveryResult.success(Long.toString(id.longValue()));
        } catch (RuntimeException failure) {
            // A timeout or malformed response may follow a successful remote side effect.
            return DeliveryResult.unknown("TELEGRAM_DELIVERY_RESULT_UNKNOWN");
        }
    }

    private synchronized Permission permission() {
        Instant now = clock.instant();
        if (cachedPermission != null && cachedPermission.expiresAt().isAfter(now)) return cachedPermission;
        JsonNode me = preflight("getMe", Map.of());
        JsonNode botId = me.path("id");
        if (!me.path("is_bot").asBoolean(false) || !botId.isIntegralNumber()
                || !botId.canConvertToLong() || botId.longValue() <= 0) {
            throw new PreflightFailure("TELEGRAM_INVALID_BOT_IDENTITY");
        }
        JsonNode chat = preflight("getChat", Map.of("chat_id", properties.getChatId()));
        JsonNode chatId = chat.path("id");
        if (!"channel".equals(chat.path("type").asText()) || !chatId.isIntegralNumber()
                || !chatId.canConvertToLong() || chatId.longValue() >= 0) {
            throw new PreflightFailure("TELEGRAM_TARGET_MUST_BE_CHANNEL");
        }
        String configured = properties.getChatId();
        if (configured.startsWith("@")
                ? !configured.substring(1).equalsIgnoreCase(chat.path("username").asText())
                : !configured.equals(Long.toString(chatId.longValue()))) {
            throw new PreflightFailure("TELEGRAM_TARGET_IDENTITY_MISMATCH");
        }
        JsonNode member = preflight("getChatMember", Map.of("chat_id", chatId.longValue(), "user_id", botId.longValue()));
        if (!"administrator".equals(member.path("status").asText())
                || !member.path("can_post_messages").asBoolean(false)
                || member.path("user").path("id").asLong(0) != botId.longValue()) {
            throw new PreflightFailure("TELEGRAM_CHANNEL_ADMIN_PERMISSION_REQUIRED");
        }
        Permission verified = new Permission(chatId.longValue(), member.path("can_edit_messages").asBoolean(false),
                clock.instant().plus(properties.getPermissionCacheTtl()));
        cachedPermission = verified;
        return verified;
    }

    private JsonNode preflight(String method, Map<String, Object> payload) {
        ApiResponse response = call(method, payload);
        if (response.status() != 200 || !response.body().path("ok").asBoolean(false)
                || !response.body().path("result").isObject()) {
            throw new PreflightFailure("TELEGRAM_PREFLIGHT_REJECTED");
        }
        return response.body().path("result");
    }

    private synchronized void clearPermission() { cachedPermission = null; }

    private ApiResponse call(String method, Map<String, Object> payload) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(
                    URI.create("https://api.telegram.org/bot" + properties.getBotToken() + "/" + method))
                    .timeout(properties.getRequestTimeout()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload))).build();
            pending = client.sendAsync(request, ignored -> new BoundedBodySubscriber(properties.getMaxResponseBytes(),
                    () -> new IllegalStateException("TELEGRAM_RESPONSE_TOO_LARGE"),
                    () -> new IllegalStateException("TELEGRAM_RESPONSE_FAILED")));
            HttpResponse<byte[]> response = pending.get(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            JsonNode body;
            try {
                body = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                        DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(response.body());
                if (body == null || !body.isObject() || !body.path("ok").isBoolean()) {
                    throw new IllegalStateException("TELEGRAM_INVALID_RESPONSE");
                }
            } catch (RuntimeException invalid) {
                // These HTTP rejections establish non-delivery even if the error JSON is malformed.
                if (!List.of(400, 401, 403).contains(response.statusCode())) throw invalid;
                body = json.valueToTree(Map.of("ok", false, "error_code", response.statusCode()));
            }
            return new ApiResponse(response.statusCode(), body);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TELEGRAM_TRANSPORT_INTERRUPTED");
        } catch (Exception failure) {
            throw new IllegalStateException("TELEGRAM_TRANSPORT_FAILED");
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private static Long positiveId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return null;
        try { return Long.valueOf(value); } catch (NumberFormatException failure) { return null; }
    }

    private record Permission(long chatId, boolean canEdit, Instant expiresAt) {}
    private record ApiResponse(int status, JsonNode body) {}
    private static final class PreflightFailure extends RuntimeException {
        private final String code;
        private PreflightFailure(String code) { super(code); this.code = code; }
    }

}
