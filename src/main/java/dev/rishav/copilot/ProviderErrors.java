package dev.rishav.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import java.util.HashSet;
import java.util.Set;

/** Maps known provider codes to fixed messages; never forwards raw provider text. */
final class ProviderErrors {
    private static final ObjectMapper JSON = new ObjectMapper();
    private ProviderErrors() {}

    static ApiException translate(Exception failure, String fallback) {
        Set<Throwable> visited = new HashSet<>();
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message == null) continue;
            int start = message.indexOf('{');
            if (start < 0) continue;
            try {
                JsonNode error = JSON.readTree(message.substring(start)).path("error");
                String code = error.path("code").asText();
                String type = error.path("type").asText();
                if (code.equals("credit_balance_exhausted") || code.equals("insufficient_quota") || type.equals("insufficient_quota"))
                    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "The AI provider has no available API credit or quota. Check API billing and project limits, then retry.");
                if (code.equals("invalid_api_key"))
                    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "The AI provider rejected the configured API key. Update the local configuration and restart AI mode.");
                if (code.equals("rate_limit_exceeded") || type.equals("rate_limit_exceeded"))
                    return new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                            "The AI provider rate limit was reached. Wait before retrying.");
            } catch (Exception ignored) {
                // Unknown or non-JSON failures get a fixed fallback, never their raw message.
            }
        }
        return new ApiException(HttpStatus.BAD_GATEWAY, fallback);
    }
}
