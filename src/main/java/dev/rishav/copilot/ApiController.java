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
        return Map.of("mode",service.mode(),"executionTarget","bundled localhost sandbox only","storage","session memory; AI vectors persist in pgvector");
    }
    @PostMapping("/specifications") public Specification create(@Valid @RequestBody ImportRequest request) { return service.importSpecification(request); }
    @GetMapping("/specifications") public List<Specification> specifications() { return service.specifications(); }
    @PostMapping("/plans") public Plan generate(@Valid @RequestBody GenerateRequest request) { return service.generate(request); }
    @PostMapping("/executions") public Execution execute(@Valid @RequestBody ExecuteRequest request) { return service.execute(request); }
    @PostMapping("/executions/{id}/analysis") public Analysis analyse(@PathVariable UUID id) { return service.analyse(id); }
}
