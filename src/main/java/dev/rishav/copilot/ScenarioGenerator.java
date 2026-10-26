package dev.rishav.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import java.util.*;
import static dev.rishav.copilot.Models.*;

public interface ScenarioGenerator {
    Draft generate(Endpoint endpoint, List<Evidence> evidence);
    String mode();
}

@Component
@Profile("!ai")
class DemoGenerator implements ScenarioGenerator {
    public String mode() { return "demo"; }
    public Draft generate(Endpoint e, List<Evidence> evidence) {
        List<Scenario> cases = new ArrayList<>();
        List<String> ids = List.of(evidence.getFirst().id());
        String path = e.path().replaceAll("\\{[^}]+}", "1");
        int success = e.statuses().stream().filter(s -> s>=200 && s<300).sorted().findFirst()
                .orElseThrow(() -> ApiException.badRequest("Demo generation needs an explicit 2xx response"));
        JsonNode schema = e.contract().path("requestBody").path("content").path("application/json").path("schema");
        JsonNode body = schema.isMissingNode() ? NullNode.instance : sample(schema, 0);
        Map<String,String> query = new LinkedHashMap<>();
        e.contract().path("parameters").forEach(p -> {
            if (p.path("in").asText().equals("query") && p.path("required").asBoolean())
                query.put(p.path("name").asText(), sample(p.path("schema"),0).asText());
        });
        cases.add(new Scenario("Documented successful request", Category.POSITIVE, e.method(), path,
                Map.copyOf(query), body, success, "Uses the documented successful response and a schema-derived request.", ids));
        if (body.isObject() && !schema.path("required").isEmpty() && e.statuses().contains(400)) {
            ObjectNode invalid = body.deepCopy(); String field=schema.path("required").get(0).asText(); invalid.remove(field);
            cases.add(new Scenario("Missing required field: "+field, Category.NEGATIVE, e.method(), path,
                    Map.copyOf(query), invalid, 400, "The contract requires "+field+" and documents a 400 response.", ids));
        }
        if (e.path().contains("{") && e.statuses().contains(404)) {
            cases.add(new Scenario("Unknown resource identifier", Category.NEGATIVE, e.method(),
                    e.path().replaceAll("\\{[^}]+}","999999"), Map.copyOf(query), body, 404,
                    "Probes the documented not-found response with an assumed absent fixture identifier.",ids));
        }
        if (body.isObject()) {
            Iterator<Map.Entry<String,JsonNode>> fields = schema.path("properties").fields();
            while(fields.hasNext()) {
                var f=fields.next(); var s=f.getValue();
                if (s.has("minimum")) {
                    ObjectNode edge=body.deepCopy(); edge.set(f.getKey(),s.get("minimum"));
                    cases.add(new Scenario("Minimum boundary: "+f.getKey(), Category.EDGE, e.method(), path,
                            Map.copyOf(query),edge,success,"Exactly the inclusive minimum documented for "+f.getKey()+".",ids)); break;
                }
            }
        }
        return new Draft(List.copyOf(cases));
    }
    private JsonNode sample(JsonNode s, int depth) {
        if (depth>8) return NullNode.instance;
        if (s.has("example")) return s.get("example");
        if (s.has("enum") && !s.path("enum").isEmpty()) return s.path("enum").get(0);
        return switch(s.path("type").asText("object")) {
            case "string" -> TextNode.valueOf("sample");
            case "integer" -> IntNode.valueOf(Math.max(1,s.path("minimum").asInt(1)));
            case "number" -> DoubleNode.valueOf(Math.max(1,s.path("minimum").asDouble(1)));
            case "boolean" -> BooleanNode.TRUE;
            case "array" -> JsonNodeFactory.instance.arrayNode().add(sample(s.path("items"),depth+1));
            default -> { ObjectNode o=JsonNodeFactory.instance.objectNode(); s.path("properties").fields()
                    .forEachRemaining(f -> o.set(f.getKey(),sample(f.getValue(),depth+1))); yield o; }
        };
    }
}
