package dev.rishav.copilot;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;

class ApprovedExecutionToolTest {
    @Test void repeatedToolCallsExecuteOnlyOnce() {
        AtomicInteger calls=new AtomicInteger();
        var result=new Execution(UUID.randomUUID(),UUID.randomUUID(),0,200,200,true,1,"{}",Instant.now());
        var tool=new ApprovedExecutionTool(() -> { calls.incrementAndGet(); return result; });
        assertThat(tool.executeApprovedScenario()).isSameAs(result);
        assertThat(tool.executeApprovedScenario()).isSameAs(result);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void failedAttemptCannotBeRetriedByModel() {
        AtomicInteger calls=new AtomicInteger();
        var tool=new ApprovedExecutionTool(() -> { calls.incrementAndGet(); throw new IllegalStateException("timeout"); });
        assertThatThrownBy(tool::executeApprovedScenario).hasMessage("timeout");
        assertThatThrownBy(tool::executeApprovedScenario).hasMessageContaining("already been used");
        assertThat(calls.get()).isEqualTo(1);
    }
}
