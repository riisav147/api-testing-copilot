package dev.rishav.copilot;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.*;

class ProviderErrorsTest {
    @Test void identifiesExhaustedCreditWithoutExposingProviderMessage() {
        var failure = new RuntimeException("HTTP 429 - {\"error\":{\"code\":\"credit_balance_exhausted\",\"type\":\"insufficient_quota\",\"message\":\"private-provider-text\"}}");
        var result = ProviderErrors.translate(failure, "fallback");
        assertThat(result.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(result.getMessage()).contains("billing").doesNotContain("private-provider-text");
    }
    @Test void readsNestedErrorsAndRejectsUnknownRawText() {
        var nested = new RuntimeException("wrapper", new RuntimeException("401 - {\"error\":{\"code\":\"invalid_api_key\"}}"));
        assertThat(ProviderErrors.translate(nested, "fallback").getMessage()).contains("configured API key");
        var unknown = ProviderErrors.translate(new RuntimeException("secret-key-and-prompt"), "Safe fallback");
        assertThat(unknown.status()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(unknown.getMessage()).isEqualTo("Safe fallback");
    }
    @Test void distinguishesRateLimitsFromQuota() {
        var error = ProviderErrors.translate(new RuntimeException("{\"error\":{\"code\":\"rate_limit_exceeded\"}}"), "fallback");
        assertThat(error.getMessage()).contains("Wait before retrying").doesNotContain("billing");
    }
}
