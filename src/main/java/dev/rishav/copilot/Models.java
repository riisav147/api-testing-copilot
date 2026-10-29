package dev.rishav.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class Models {
    private Models() {}
    public enum Category { POSITIVE, NEGATIVE, EDGE }
    public record ImportRequest(@NotBlank @Size(max=100) String name,
            @NotBlank @Size(max=60000) String content, @Size(max=30000) String documentation) {}
    public record Endpoint(String key, String method, String path, String summary, JsonNode contract,
                           Set<Integer> statuses) {}
    public record Specification(UUID id, String name, List<Endpoint> endpoints, Instant createdAt) {}
    public record Evidence(String id, String content, double score) {}
    public record GenerateRequest(@NotNull UUID specificationId, @NotBlank String endpointKey) {}
    public record Scenario(@NotBlank String title, @NotNull Category category,
            @NotBlank String method, @NotBlank String path, @NotNull Map<String,String> query,
            JsonNode body, @Min(100) @Max(599) int expectedStatus,
            @NotBlank String rationale, @NotEmpty List<String> sourceIds) {}
    public record Draft(@NotEmpty @Size(max=12) List<@NotNull @jakarta.validation.Valid Scenario> scenarios) {}
    public record Evaluation(int total, Map<Category,Long> categoryCounts,
                             double categoryCoverage, double sourceCitationCoverage,
                             List<String> warnings) {}
    public record Plan(UUID id, UUID specificationId, String endpointKey, String mode,
                       List<Scenario> scenarios, List<Integer> scenarioRevisions, List<Evidence> evidence,
                       Evaluation evaluation, Instant createdAt) {}
    public record EditScenarioRequest(@NotNull @Min(1) Integer expectedRevision,
                                      @NotNull Map<String,String> query, JsonNode body,
                                      @Min(100) @Max(599) int expectedStatus) {}
    public record ExecuteRequest(@NotNull UUID planId, @Min(0) int scenarioIndex,
                                 @NotNull @Min(1) Integer expectedRevision,
                                 @AssertTrue(message="Review the scenario and approve sandbox execution") boolean approved) {}
    public record Execution(UUID id, UUID planId, int scenarioIndex, int expectedStatus, int actualStatus,
                            boolean passed, long durationMs, String responseBody, Instant createdAt,
                            int scenarioRevision, Scenario requestSnapshot) {}
    public record Analysis(String mode, String explanation) {}
}
