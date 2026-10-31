package dev.rishav.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class Models {
    private Models() {}
    public enum Category { POSITIVE, NEGATIVE, EDGE }
    public record ImportRequest(@NotBlank @Size(max=100) String name,
            @NotBlank @Size(max=60000) String content, @Size(max=30000) String documentation, UUID projectId) {}
    public record Project(UUID id,String name,Instant createdAt,boolean deleting) {}
    public record ProjectName(@NotBlank @Size(max=100) String name) {}
    public record SavedSpecification(UUID projectId,int version,Specification specification,ImportRequest source) {}
    public record Workspace(Project project,List<SavedSpecification> specifications,List<Plan> plans,
                            List<Plan> revisions,List<Execution> executions,Map<UUID,Analysis> analyses) {}
    public record Endpoint(String key, String method, String path, String summary, JsonNode contract,
                           Set<Integer> statuses) {}
    public record Specification(UUID id, String name, List<Endpoint> endpoints, Instant createdAt) {}
    public record Evidence(String id, String content, double score) {}
    public record GenerateRequest(@NotNull UUID specificationId, @NotBlank String endpointKey) {}
    public record Scenario(@NotBlank String title, @NotNull Category category,
            @NotBlank String method, @NotBlank String path, @NotNull Map<String,String> query,
            JsonNode body, @Min(100) @Max(599) int expectedStatus,
            @NotBlank String rationale, @NotEmpty List<String> sourceIds, List<ResponseAssertion> assertions) {
        public Scenario { assertions=assertions==null?List.of():List.copyOf(assertions); }
        public Scenario(String title, Category category, String method, String path, Map<String,String> query,
                        JsonNode body, int expectedStatus, String rationale, List<String> sourceIds) {
            this(title,category,method,path,query,body,expectedStatus,rationale,sourceIds,List.of());
        }
    }
    public enum AssertionKind { HEADER_EQUALS, JSON_EXISTS, JSON_TYPE, JSON_EQUALS, JSON_GTE, JSON_LTE, JSON_SCHEMA }
    public enum CheckOutcome { PASS, FAIL, UNEVALUABLE }
    public record ResponseAssertion(AssertionKind kind, String target, JsonNode expected) {}
    public record AssertionResult(String check, String target, CheckOutcome outcome,
                                  JsonNode expected, JsonNode actual, String detail) {}
    public record Draft(@NotEmpty @Size(max=12) List<@NotNull @jakarta.validation.Valid Scenario> scenarios) {}
    public record Evaluation(int total, Map<Category,Long> categoryCounts,
                             double categoryCoverage, double sourceCitationCoverage,
                             List<String> warnings) {}
    public record Plan(UUID id, UUID specificationId, String endpointKey, String mode,
                       List<Scenario> scenarios, List<Integer> scenarioRevisions, List<Evidence> evidence,
                       Evaluation evaluation, Instant createdAt) {}
    public record EditScenarioRequest(@NotNull @Min(1) Integer expectedRevision,
                                      @NotNull Map<String,String> query, JsonNode body,
                                      @Min(100) @Max(599) int expectedStatus, List<ResponseAssertion> assertions) {}
    public record ExecuteRequest(@NotNull UUID planId, @Min(0) int scenarioIndex,
                                 @NotNull @Min(1) Integer expectedRevision,
                                 @AssertTrue(message="Review the scenario and approve sandbox execution") boolean approved) {}
    public record Execution(UUID id, UUID planId, int scenarioIndex, int expectedStatus, int actualStatus,
                            boolean passed, long durationMs, String responseBody, Instant createdAt,
                            int scenarioRevision, Scenario requestSnapshot, Map<String,List<String>> responseHeaders,
                            boolean bodyTruncated, List<AssertionResult> assertionResults) {
        public Execution(UUID id, UUID planId, int scenarioIndex, int expectedStatus, int actualStatus,
                         boolean passed, long durationMs, String responseBody, Instant createdAt,
                         int scenarioRevision, Scenario requestSnapshot) {
            this(id,planId,scenarioIndex,expectedStatus,actualStatus,passed,durationMs,responseBody,createdAt,
                    scenarioRevision,requestSnapshot,Map.of(),false,List.of());
        }
    }
    public record ConflictDecision(@NotBlank @Size(max=2000) String conflictId,
                                   @NotBlank @Pattern(regexp="USE_CONTRACT|REVISE_CONTRACT") String choice,
                                   @NotBlank @Size(max=1000) String reason) {}
    public record FillMissingRequest(@NotNull List<Integer> expectedRevisions) {}
    public record Analysis(String mode, String explanation) {}
}
