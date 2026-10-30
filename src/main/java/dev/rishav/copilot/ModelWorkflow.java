package dev.rishav.copilot;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.function.Supplier;
import static dev.rishav.copilot.Models.*;

public interface ModelWorkflow {
    Execution executeApproved(Supplier<Execution> action);
    Analysis analyse(Execution execution);
}

@Component
@Profile("!ai")
class DemoWorkflow implements ModelWorkflow {
    public Execution executeApproved(Supplier<Execution> action) { return action.get(); }
    public Analysis analyse(Execution e) {
        if (!e.assertionResults().isEmpty() && e.actualStatus()==e.expectedStatus()) {
            var problems=e.assertionResults().stream().filter(c -> c.outcome()!=CheckOutcome.PASS)
                    .map(c -> c.check()+" "+c.target()+": "+c.outcome()+". "+c.detail()).toList();
            return new Analysis("demo",problems.isEmpty()
                    ? "All "+e.assertionResults().size()+" configured checks passed. This covers only the assertions reviewed for this request; business side effects need separate tests."
                    : "HTTP status matched, but response checks did not all pass: "+String.join("; ",problems));
        }
        return new Analysis("demo",e.passed()
                ? "Observed HTTP "+e.actualStatus()+" matches expected HTTP "+e.expectedStatus()+". This checks status only; response schema and business side effects need separate assertions."
                : "Expected HTTP "+e.expectedStatus()+" but observed HTTP "+e.actualStatus()+". Inspect the request and sandbox response. The bundled POST fixture deliberately rejects price=0 although the contract permits minimum=0. A status mismatch is evidence of a contract/implementation disagreement; it does not establish which is correct.");
    }
}
