package dev.rishav.copilot;

import org.springframework.ai.tool.annotation.Tool;
import java.util.function.Supplier;
import static dev.rishav.copilot.Models.*;

/** Per-request capability: cannot select a target, method, body, or a different scenario. */
public class ApprovedExecutionTool {
    private final Supplier<Execution> action;
    private Execution result;
    private boolean attempted;
    public ApprovedExecutionTool(Supplier<Execution> action) { this.action=action; }
    @Tool(description="Execute the single already-reviewed and approved scenario against the local sandbox. No arguments are accepted.",returnDirect=true)
    public synchronized Execution executeApprovedScenario() {
        if (result!=null) return result;
        if (attempted) throw new IllegalStateException("The one permitted execution attempt has already been used");
        attempted=true; result=action.get(); return result;
    }
    public Execution result() { return result; }
}
