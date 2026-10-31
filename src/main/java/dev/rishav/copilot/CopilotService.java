package dev.rishav.copilot;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static dev.rishav.copilot.Models.*;

@Service
public class CopilotService {
    private final ContractCoverage coverage=new ContractCoverage();
    private final Map<String,ConflictDecision> decisions=new ConcurrentHashMap<>();
    private final WorkspaceStore store;
    private final Map<UUID,Project> projects=new ConcurrentHashMap<>();
    private final Map<UUID,SavedSpecification> saved=new ConcurrentHashMap<>();
    private final Map<UUID,Analysis> analyses=new ConcurrentHashMap<>();
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
                          PlanEvaluator evaluator, SandboxRunner runner, ModelWorkflow workflow,WorkspaceStore store) {
        this.reader=reader; this.knowledge=knowledge; this.generator=generator; this.validator=validator;
        this.evaluator=evaluator; this.runner=runner; this.workflow=workflow; this.store=store;
        store.projects().forEach(p -> projects.put(p.id(),p));
        store.specifications().forEach(s -> {
            saved.put(s.specification().id(),s); specifications.put(s.specification().id(),s.specification());
            if (!mode().equals("ai")) knowledge.index(s.specification(),s.source().documentation());
        });
        store.records("plans",Plan.class).forEach(p -> plans.put(p.id(),p));
        store.records("executions",Execution.class).forEach(e -> executions.put(e.id(),e));
        analyses.putAll(store.analyses()); store.decisions().forEach(d -> decisions.put(d.conflictId(),d));
    }
    public String mode() { return generator.mode(); }
    public synchronized Specification importSpecification(ImportRequest request) {
        capacity(specifications,200);
        boolean create=request.projectId()==null;
        var project=create?new Project(UUID.randomUUID(),request.name(),Instant.now(),false):activeProject(request.projectId());
        int version=saved.values().stream().filter(s -> s.projectId().equals(project.id())).mapToInt(SavedSpecification::version).max().orElse(0)+1;
        var spec=new Specification(UUID.randomUUID(),request.name(),reader.read(request.content()),Instant.now());
        knowledge.index(spec,request.documentation()); // Publish only after indexing succeeds.
        var record=new SavedSpecification(project.id(),version,spec,request);
        try { store.saveSpecification(project,record,create); }
        catch(RuntimeException failure) { knowledge.delete(spec.id()); throw failure; }
        projects.put(project.id(),project); saved.put(spec.id(),record);
        specifications.put(spec.id(),spec); return spec;
    }
    public List<Specification> specifications() { return specifications.values().stream().sorted(Comparator.comparing(Specification::createdAt)).toList(); }
    public synchronized Plan generate(GenerateRequest request) {
        capacity(plans,1000);
        var spec=required(specifications,request.specificationId());
        var endpoint=spec.endpoints().stream().filter(e -> e.key().equals(request.endpointKey())).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown operation for this specification"));
        activeProject(saved.get(spec.id()).projectId());
        requireResolved(saved.get(spec.id()),endpoint);
        var evidence=knowledge.retrieve(spec.id(),endpoint);
        if (evidence.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"No endpoint evidence retrieved; generation is refused");
        Draft draft=generator.generate(endpoint,evidence);
        validator.validate(draft,endpoint,evidence);
        var plan=new Plan(UUID.randomUUID(),spec.id(),endpoint.key(),mode(),List.copyOf(draft.scenarios()),
                Collections.nCopies(draft.scenarios().size(),1),List.copyOf(evidence),evaluator.evaluate(draft.scenarios()),Instant.now());
        store.savePlan(plan,true); plans.put(plan.id(),plan); return plan;
    }
    public Plan plan(UUID id) { var p=required(plans,id); activeProject(saved.get(p.specificationId()).projectId()); return p; }
    public synchronized Plan editScenario(UUID id, int index, EditScenarioRequest request) {
        var plan=plan(id);
        checkRevision(plan,index,request.expectedRevision());
        var original=plan.scenarios().get(index);
        var edited=new Scenario(original.title(),original.category(),original.method(),original.path(),
                new LinkedHashMap<>(request.query()),request.body()==null?null:request.body().deepCopy(),
                request.expectedStatus(),original.rationale(),original.sourceIds(),request.assertions()==null?original.assertions():request.assertions());
        var scenarios=new ArrayList<>(plan.scenarios()); scenarios.set(index,edited);
        var endpoint=required(specifications,plan.specificationId()).endpoints().stream()
                .filter(e -> e.key().equals(plan.endpointKey())).findFirst().orElseThrow();
        validator.validate(new Draft(scenarios),endpoint,plan.evidence());
        // A validated edit creates a new revision; saved executions retain their own request snapshot.
        var revisions=new ArrayList<>(plan.scenarioRevisions()); revisions.set(index,revisions.get(index)+1);
        var updated=new Plan(plan.id(),plan.specificationId(),plan.endpointKey(),plan.mode(),
                List.copyOf(scenarios),List.copyOf(revisions),plan.evidence(),evaluator.evaluate(scenarios),plan.createdAt());
        store.savePlan(updated,false); plans.put(id,updated); return updated;
    }
    public synchronized Execution execute(ExecuteRequest request) {
        if (!request.approved()) throw ApiException.badRequest("Explicit review and approval is required");
        var plan=plan(request.planId());
        checkRevision(plan,request.scenarioIndex(),request.expectedRevision());
        var spec=saved.get(plan.specificationId()); requireResolved(spec,endpoint(plan));
        capacity(executions,5000);
        var execution=workflow.executeApproved(() -> runner.execute(plan,request.scenarioIndex()));
        store.saveExecution(execution); executions.put(execution.id(),execution); return execution;
    }
    public synchronized Analysis analyse(UUID id) {
        var execution=required(executions,id); plan(execution.planId());
        var analysis=workflow.analyse(execution); store.saveAnalysis(id,analysis); analyses.put(id,analysis); return analysis;
    }
    public List<Project> projects() { return projects.values().stream().sorted(Comparator.comparing(Project::createdAt)).toList(); }
    private Project activeProject(UUID id) {
        var p=required(projects,id);
        if(p.deleting()) throw new ApiException(HttpStatus.CONFLICT,"Project deletion is pending. Retry deletion to finish evidence cleanup.");
        return p;
    }
    public Workspace specificationWorkspace(UUID id) { return workspace(required(saved,id).projectId()); }
    public synchronized Workspace workspace(UUID id) {
        var project=activeProject(id);
        var specs=saved.values().stream().filter(s -> s.projectId().equals(id)).sorted(Comparator.comparingInt(SavedSpecification::version)).toList();
        var ids=specs.stream().map(s -> s.specification().id()).collect(java.util.stream.Collectors.toSet());
        var projectPlans=plans.values().stream().filter(p -> ids.contains(p.specificationId())).sorted(Comparator.comparing(Plan::createdAt)).toList();
        var planIds=projectPlans.stream().map(Plan::id).collect(java.util.stream.Collectors.toSet());
        var runs=executions.values().stream().filter(e -> planIds.contains(e.planId())).sorted(Comparator.comparing(Execution::createdAt)).toList();
        var a=new HashMap<UUID,Analysis>(); runs.forEach(e -> { if(analyses.containsKey(e.id())) a.put(e.id(),analyses.get(e.id())); });
        return new Workspace(project,specs,projectPlans,store.records("plan_revisions",Plan.class).stream().filter(p -> planIds.contains(p.id())).toList(),runs,a);
    }
    public synchronized Project rename(UUID id,String name) {
        var old=activeProject(id); var updated=new Project(id,name,old.createdAt(),false);
        store.rename(id,name); projects.put(id,updated); return updated;
    }
    public synchronized void deleteProject(UUID id) {
        var project=required(projects,id);
        store.markDeleting(id); projects.put(id,new Project(id,project.name(),project.createdAt(),true));
        var specs=saved.values().stream().filter(s -> s.projectId().equals(id)).map(s -> s.specification().id()).toList();
        // A durable tombstone keeps a partially cleaned project unavailable until deletion is retried.
        for(var specId:specs) knowledge.delete(specId);
        store.delete(id);
        var planIds=plans.values().stream().filter(p -> specs.contains(p.specificationId())).map(Plan::id).toList();
        executions.values().stream().filter(e -> planIds.contains(e.planId())).map(Execution::id).toList().forEach(e -> { executions.remove(e); analyses.remove(e); });
        decisions.keySet().removeIf(key -> specs.stream().anyMatch(specId -> key.startsWith(specId.toString()+":")));
        planIds.forEach(plans::remove); specs.forEach(s -> { specifications.remove(s); saved.remove(s); }); projects.remove(id);
    }
    private Endpoint endpoint(Plan p) { return specifications.get(p.specificationId()).endpoints().stream().filter(e -> e.key().equals(p.endpointKey())).findFirst().orElseThrow(); }
    private List<ContractCoverage.Rule> rules(SavedSpecification spec,Endpoint endpoint) {
        var evidence=List.of(new Evidence("contract:"+spec.specification().id()+":"+endpoint.key(),"CONTRACT "+endpoint.key()+"\n"+endpoint.contract(),1));
        return coverage.rules(spec,endpoint,evidence);
    }
    private void requireResolved(SavedSpecification spec,Endpoint endpoint) {
        var conflicts=coverage.conflicts(spec,rules(spec,endpoint),decisions);
        if(conflicts.stream().anyMatch(c -> !c.decision().equals("USE_CONTRACT")))
            throw new ApiException(HttpStatus.CONFLICT,"Review conflicting numeric documentation in the rule coverage panel before generating or executing. Revise the contract as a new version if it should change.");
    }
    public synchronized ContractCoverage.Report coverage(UUID specificationId,String endpointKey,UUID planId) {
        var spec=required(saved,specificationId); activeProject(spec.projectId());
        var endpoint=spec.specification().endpoints().stream().filter(e->e.key().equals(endpointKey)).findFirst().orElseThrow(()->ApiException.badRequest("Unknown operation"));
        var rules=rules(spec,endpoint); Plan plan=planId==null?null:plan(planId);
        if(plan!=null && (!plan.specificationId().equals(specificationId)||!plan.endpointKey().equals(endpointKey)))throw ApiException.badRequest("Plan does not belong to this specification and operation");
        var rows=new ArrayList<ContractCoverage.Row>();
        for(var rule:rules) {
            var indexes=new ArrayList<Integer>();String state="MISSING";
            if(plan!=null) for(int i=0;i<plan.scenarios().size();i++) if(coverage.matches(rule,plan.scenarios().get(i)))indexes.add(i);
            if(!indexes.isEmpty()) {
                state="GENERATED";var results=new ArrayList<Execution>();
                for(int index:indexes) { final Plan current=plan;
                    executions.values().stream().filter(e->e.planId().equals(current.id())&&e.scenarioIndex()==index&&e.scenarioRevision()==current.scenarioRevisions().get(index))
                        .max(Comparator.comparing(Execution::createdAt)).ifPresent(results::add);
                }
                if(!results.isEmpty())state=results.stream().allMatch(Execution::passed)?"PASSED":"FAILED";
            }
            rows.add(new ContractCoverage.Row(rule,state,List.copyOf(indexes)));
        }
        return new ContractCoverage.Report(specificationId,spec.version(),endpointKey,rows,coverage.conflicts(spec,rules,decisions),
                "First 64 supported top-level JSON request rule probes only. Coverage records input probes and configured assertions, not code coverage or proof that the whole contract is correct. Numeric conflicts recognize field > number only.");
    }
    public synchronized ContractCoverage.Report resolve(UUID specId,String endpointKey,ConflictDecision decision) {
        var report=coverage(specId,endpointKey,null);
        if(report.conflicts().stream().noneMatch(c -> c.id().equals(decision.conflictId()))) throw ApiException.badRequest("Unknown conflict for this specification and operation");
        store.saveDecision(specId,decision); decisions.put(decision.conflictId(),decision); return coverage(specId,endpointKey,null);
    }
    public synchronized Plan fillMissing(UUID id,FillMissingRequest request) {
        var plan=plan(id);
        if(!plan.scenarioRevisions().equals(request.expectedRevisions()))throw new ApiException(HttpStatus.CONFLICT,"Plan changed. Reload before generating missing tests.");
        var spec=saved.get(plan.specificationId());var endpoint=endpoint(plan);requireResolved(spec,endpoint);
        var rules=rules(spec,endpoint);var cases=new ArrayList<>(plan.scenarios());var revisions=new ArrayList<>(plan.scenarioRevisions());var evidence=new ArrayList<>(plan.evidence());
        for(var rule:rules) {
            if(cases.size()>=12)break;
            if(cases.stream().anyMatch(c->coverage.matches(rule,c)))continue;
            var c=rule.candidate();
            String title=c.title();int suffix=2;
            var titles=cases.stream().map(Scenario::title).collect(java.util.stream.Collectors.toSet());
            while(titles.contains(title)) title=c.title()+" ("+(suffix++)+")";
            c=new Scenario(title,c.category(),c.method(),c.path(),c.query(),c.body(),c.expectedStatus(),c.rationale(),c.sourceIds(),c.assertions());
            final String sourceId=c.sourceIds().getFirst();
            if(evidence.stream().noneMatch(e->e.id().equals(sourceId))) evidence.add(new Evidence(c.sourceIds().getFirst(),"CONTRACT "+endpoint.key()+"\n"+endpoint.contract(),1));
            cases.add(c);revisions.add(1);
        }
        if(cases.size()==plan.scenarios().size())return plan;
        validator.validate(new Draft(cases),endpoint,evidence);
        var updated=new Plan(plan.id(),plan.specificationId(),plan.endpointKey(),plan.mode(),List.copyOf(cases),List.copyOf(revisions),List.copyOf(evidence),evaluator.evaluate(cases),plan.createdAt());
        store.savePlan(updated,false);plans.put(id,updated);return updated;
    }
    private void checkRevision(Plan plan, int index, Integer expectedRevision) {
        if (index<0 || index>=plan.scenarios().size()) throw ApiException.badRequest("Scenario index out of range");
        if (expectedRevision==null || expectedRevision<1) throw ApiException.badRequest("A scenario revision is required");
        if (!expectedRevision.equals(plan.scenarioRevisions().get(index)))
            throw new ApiException(HttpStatus.CONFLICT,"This scenario changed. Reload the latest plan and review it again.");
    }
    private <T> T required(Map<UUID,T> map, UUID id) {
        T value=map.get(id); if (value==null) throw new ApiException(HttpStatus.NOT_FOUND,"Resource not found in this workspace"); return value;
    }
    private void capacity(Map<?,?> map, int max) {
        if (map.size()>=max) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"Workspace capacity reached. Delete unused projects to free space.");
    }
}
