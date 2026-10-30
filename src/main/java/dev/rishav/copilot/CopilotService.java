package dev.rishav.copilot;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static dev.rishav.copilot.Models.*;

@Service
public class CopilotService {
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
        analyses.putAll(store.analyses());
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
        planIds.forEach(plans::remove); specs.forEach(s -> { specifications.remove(s); saved.remove(s); }); projects.remove(id);
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
