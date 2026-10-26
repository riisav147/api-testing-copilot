package dev.rishav.copilot;

import jakarta.validation.Validator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import static dev.rishav.copilot.Models.*;

@Component
public class ScenarioValidator {
    private final Validator validator;
    public ScenarioValidator(Validator validator) { this.validator=validator; }
    public void validate(Draft draft, Endpoint endpoint, List<Evidence> evidence) {
        if (draft==null || !validator.validate(draft).isEmpty()) reject("Missing or malformed scenario fields");
        Set<String> sourceIds=evidence.stream().map(Evidence::id).collect(Collectors.toSet());
        Set<String> names=new HashSet<>();
        Set<String> queryNames=new HashSet<>();
        endpoint.contract().path("parameters").forEach(p -> {
            if (p.path("in").asText().equals("query")) queryNames.add(p.path("name").asText());
        });
        // Quote literal path segments; only contract path placeholders become variable segments.
        String pathPattern=Arrays.stream(endpoint.path().split("/",-1))
                .map(s -> s.matches("\\{[^}]+}") ? "[A-Za-z0-9_-]+" : Pattern.quote(s))
                .collect(Collectors.joining("/"));
        for (Scenario s:draft.scenarios()) {
            if (!names.add(s.title())) reject("Duplicate scenario titles");
            if (!endpoint.method().equals(s.method()) || !s.path().matches(pathPattern) || s.path().contains(".."))
                reject("Scenario method/path does not match the selected operation");
            if (!endpoint.statuses().contains(s.expectedStatus())) reject("Expected status is not explicitly documented");
            if (!sourceIds.containsAll(s.sourceIds())) reject("Scenario cites evidence outside the retrieved context");
            if (!queryNames.containsAll(s.query().keySet())) reject("Scenario invents query parameters");
            if (s.title().length()>200 || s.rationale().length()>2000 || s.path().length()>500 || s.query().size()>20
                    || (s.body()!=null && s.body().toString().length()>16000)
                    || s.query().values().stream().anyMatch(v -> v==null || v.length()>1000)) reject("Scenario exceeds size limits");
        }
    }
    private void reject(String message) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
}
