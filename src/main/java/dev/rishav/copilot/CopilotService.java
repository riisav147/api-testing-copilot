package dev.rishav.copilot;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static dev.rishav.copilot.Models.*;

@Service
public class CopilotService {
    private final OpenApiReader reader;
    private final Knowledge knowledge;
    private final ScenarioGenerator generator;
    private final ScenarioValidator validator;
    private final PlanEvaluator evaluator;
    private final SandboxRunner runner;
    private final ModelWorkflow workflow;
    private final Map<UUID,Specification> specifications=new ConcurrentHashMap<>();
    private final Map<UUID,Plan> plans=new ConcurrentHashMap<>();
    private final Map<UUID,Execution> executions=new ConcurrentHashMap<>();

    public CopilotService(OpenApiReader reader, Knowledge knowledge, ScenarioGenerator generator, ScenarioValidator validator,
                          PlanEvaluator evaluator, SandboxRunner runner, ModelWorkflow workflow) {
        this.reader=reader; this.knowledge=knowledge; this.generator=generator; this.validator=validator;
        this.evaluator=evaluator; this.runner=runner; this.workflow=workflow;
    }
    public String mode() { return generator.mode(); }
    public synchronized Specification importSpecification(ImportRequest request) {
        capacity(specifications,20);
        var spec=new Specification(UUID.randomUUID(),request.name(),reader.read(request.content()),Instant.now());
        knowledge.index(spec,request.documentation()); // Publish only after indexing succeeds.
        specifications.put(spec.id(),spec); return spec;
    }
    public List<Specification> specifications() { return specifications.values().stream().sorted(Comparator.comparing(Specification::createdAt)).toList(); }
    public synchronized Plan generate(GenerateRequest request) {
        capacity(plans,100);
        var spec=required(specifications,request.specificationId());
        var endpoint=spec.endpoints().stream().filter(e -> e.key().equals(request.endpointKey())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown operation for this specification"));
        var evidence=knowledge.retrieve(spec.id(),endpoint);
        if (evidence.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"No endpoint evidence retrieved; generation is refused");
        Draft draft=generator.generate(endpoint,evidence);
        validator.validate(draft,endpoint,evidence);
        var plan=new Plan(UUID.randomUUID(),spec.id(),endpoint.key(),mode(),List.copyOf(draft.scenarios()),
                Collections.nCopies(draft.scenarios().size(),1),List.copyOf(evidence),evaluator.evaluate(draft.scenarios()),Instant.now());
        plans.put(plan.id(),plan); return plan;
    }
    public Plan plan(UUID id) { return required(plans,id); }
    public synchronized Plan editScenario(UUID id, int index, EditScenarioRequest request) {
        var plan=required(plans,id);
        checkRevision(plan,index,request.expectedRevision());
        var original=plan.scenarios().get(index);
        var edited=new Scenario(original.title(),original.category(),original.method(),original.path(),
                new LinkedHashMap<>(request.query()),request.body()==null?null:request.body().deepCopy(),
                request.expectedStatus(),original.rationale(),original.sourceIds());
        var scenarios=new ArrayList<>(plan.scenarios()); scenarios.set(index,edited);
        var endpoint=required(specifications,plan.specificationId()).endpoints().stream()
                .filter(e -> e.key().equals(plan.endpointKey())).findFirst().orElseThrow();
        validator.validate(new Draft(scenarios),endpoint,plan.evidence());
        // A validated edit creates a new revision; saved executions retain their own request snapshot.
        var revisions=new ArrayList<>(plan.scenarioRevisions()); revisions.set(index,revisions.get(index)+1);
        var updated=new Plan(plan.id(),plan.specificationId(),plan.endpointKey(),plan.mode(),
                List.copyOf(scenarios),List.copyOf(revisions),plan.evidence(),evaluator.evaluate(scenarios),plan.createdAt());
        plans.put(id,updated); return updated;
    }
    public synchronized Execution execute(ExecuteRequest request) {
        if (!request.approved()) throw ApiException.badRequest("Explicit review and approval is required");
        var plan=required(plans,request.planId());
        checkRevision(plan,request.scenarioIndex(),request.expectedRevision());
        capacity(executions,200);
        var execution=workflow.executeApproved(() -> runner.execute(plan,request.scenarioIndex()));
        executions.put(execution.id(),execution); return execution;
    }
    public Analysis analyse(UUID id) { return workflow.analyse(required(executions,id)); }
    private void checkRevision(Plan plan, int index, Integer expectedRevision) {
        if (index<0 || index>=plan.scenarios().size()) throw ApiException.badRequest("Scenario index out of range");
        if (expectedRevision==null || expectedRevision<1) throw ApiException.badRequest("A scenario revision is required");
        if (!expectedRevision.equals(plan.scenarioRevisions().get(index)))
            throw new ApiException(HttpStatus.CONFLICT,"This scenario changed. Reload the latest plan and review it again.");
    }
    private <T> T required(Map<UUID,T> map, UUID id) {
        T value=map.get(id); if (value==null) throw new ApiException(HttpStatus.NOT_FOUND,"Resource not found in this application session"); return value;
    }
    private void capacity(Map<?,?> map, int max) {
        if (map.size()>=max) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"Local session capacity reached. Restart the app to clear session state.");
    }
}
