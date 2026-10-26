package dev.rishav.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Disposable, deterministic fixture. POST returns a response but does not persist products. */
@RestController
@RequestMapping("/sandbox/products")
public class SandboxController {
    private static final Map<String,Object> PRODUCT=Map.of("id",1,"name","Mechanical Keyboard","price",79.0);
    @GetMapping public List<?> list() { return List.of(PRODUCT); }
    @GetMapping("/{id}") public ResponseEntity<?> get(@PathVariable String id) {
        return id.equals("1")?ResponseEntity.ok(PRODUCT):ResponseEntity.status(404).body(Map.of("error","Product not found"));
    }
    @PostMapping public ResponseEntity<?> create(@RequestBody JsonNode body) {
        if (!body.path("name").isTextual() || body.path("name").asText().isBlank()
                || !body.path("price").isNumber() || body.path("price").asDouble()<=0)
            // Deliberate defect for the boundary test: the OpenAPI contract allows price=0.
            return ResponseEntity.badRequest().body(Map.of("error","Name and a price greater than zero are required"));
        return ResponseEntity.status(201).body(Map.of("id",2,"name",body.path("name").asText(),"price",body.path("price").asDouble()));
    }
}
