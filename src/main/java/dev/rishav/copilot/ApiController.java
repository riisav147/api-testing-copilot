package dev.rishav.copilot;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static dev.rishav.copilot.Models.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final CopilotService service;
    public ApiController(CopilotService service) { this.service=service; }
    @GetMapping("/info") public Map<String,Object> info() {
        return Map.of("mode",service.mode(),"executionTarget","bundled sandbox or explicitly approved allowlisted local development API","storage","persistent workspace database; AI vectors persist in pgvector");
    }
    @GetMapping("/projects") public List<Project> projects() { return service.projects(); }
    @GetMapping("/projects/{id}") public Workspace workspace(@PathVariable UUID id) { return service.workspace(id); }
    @PutMapping("/projects/{id}") public Project rename(@PathVariable UUID id,@Valid @RequestBody ProjectName request) { return service.rename(id,request.name()); }
    @DeleteMapping("/projects/{id}") public Map<String,Boolean> delete(@PathVariable UUID id) { service.deleteProject(id); return Map.of("deleted",true); }
    @PostMapping("/specifications") public Specification create(@Valid @RequestBody ImportRequest request) { return service.importSpecification(request); }
    @GetMapping("/specifications/{id}/workspace") public Workspace owner(@PathVariable UUID id) { return service.specificationWorkspace(id); }
    @GetMapping("/specifications") public List<Specification> specifications() { return service.specifications(); }
    @PostMapping("/plans") public Plan generate(@Valid @RequestBody GenerateRequest request) { return service.generate(request); }
    @GetMapping("/plans/{id}") public Plan plan(@PathVariable UUID id) { return service.plan(id); }
    @PutMapping("/plans/{id}/scenarios/{index}") public Plan edit(@PathVariable UUID id, @PathVariable int index,
            @Valid @RequestBody EditScenarioRequest request) { return service.editScenario(id,index,request); }
    @GetMapping("/specifications/{id}/coverage") public ContractCoverage.Report coverage(@PathVariable UUID id,@RequestParam String endpointKey,@RequestParam(required=false) UUID planId) { return service.coverage(id,endpointKey,planId); }
    @PostMapping("/specifications/{id}/conflicts") public ContractCoverage.Report resolve(@PathVariable UUID id,@RequestParam String endpointKey,@Valid @RequestBody ConflictDecision decision) { return service.resolve(id,endpointKey,decision); }
    @PostMapping("/plans/{id}/missing-tests") public Plan fill(@PathVariable UUID id,@Valid @RequestBody FillMissingRequest request) { return service.fillMissing(id,request); }
    @GetMapping("/projects/{id}/environments") public List<TargetEnvironment> environments(@PathVariable UUID id) { return service.environments(id); }
    @PostMapping("/projects/{id}/environments") public TargetEnvironment createEnvironment(@PathVariable UUID id,@Valid @RequestBody EnvironmentRequest r) { return service.saveEnvironment(id,null,r); }
    @PutMapping("/projects/{id}/environments/{envId}") public TargetEnvironment updateEnvironment(@PathVariable UUID id,@PathVariable UUID envId,@Valid @RequestBody EnvironmentRequest r) { return service.saveEnvironment(id,envId,r); }
    @PostMapping("/projects/{id}/environments/{envId}/check") public Map<String,Object> connection(@PathVariable UUID id,@PathVariable UUID envId) { return service.checkConnection(id,envId); }
    @PostMapping("/approvals") public Approval approval(@Valid @RequestBody ApprovalRequest r) { return service.approve(r); }
    @GetMapping("/approvals/{id}/attempt") public Attempt attempt(@PathVariable UUID id) { return service.attempt(id); }
    @PostMapping("/executions") public Execution execute(@Valid @RequestBody ExecuteRequest request) { return service.execute(request); }
    @PostMapping("/executions/{id}/analysis") public Analysis analyse(@PathVariable UUID id) { return service.analyse(id); }
}
