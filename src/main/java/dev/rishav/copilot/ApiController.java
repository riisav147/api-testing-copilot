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
        return Map.of("mode",service.mode(),"executionTarget","bundled localhost sandbox only","storage","persistent workspace database; AI vectors persist in pgvector");
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
    @PostMapping("/executions") public Execution execute(@Valid @RequestBody ExecuteRequest request) { return service.execute(request); }
    @PostMapping("/executions/{id}/analysis") public Analysis analyse(@PathVariable UUID id) { return service.analyse(id); }
}
