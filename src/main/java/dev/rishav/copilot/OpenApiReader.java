package dev.rishav.copilot;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Component;
import java.util.*;
import static dev.rishav.copilot.Models.*;

/** A deliberately bounded OpenAPI 3 reader, not a complete OpenAPI validator. Never fetches references. */
@Component
public class OpenApiReader {
    private final ObjectMapper yaml = new ObjectMapper(YAMLFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40).maxStringLength(60000).build()).build());
    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete", "head", "options");

    public List<Endpoint> read(String content) {
        try {
            JsonNode root = yaml.readTree(content);
            if (root == null || !root.path("openapi").asText().startsWith("3."))
                throw ApiException.badRequest("Expected an OpenAPI 3.x JSON or YAML document");
            if (!root.path("paths").isObject()) throw ApiException.badRequest("paths must be an object");
            // Count resolved nodes so repeated references cannot expand exponentially.
            JsonNode expanded = expand(root, root, new HashSet<>(), new int[]{0}, 0);
            List<Endpoint> endpoints = new ArrayList<>();
            expanded.path("paths").fields().forEachRemaining(path -> {
                if (!path.getKey().matches("/[A-Za-z0-9_/{}/.~-]*") || path.getKey().contains(".."))
                    throw ApiException.badRequest("Unsupported endpoint path: " + path.getKey());
                path.getValue().fields().forEachRemaining(operation -> {
                    if (!METHODS.contains(operation.getKey())) return;
                    ObjectNode contract = operation.getValue().deepCopy();
                    ArrayNode parameters = yaml.createArrayNode();
                    path.getValue().path("parameters").forEach(parameters::add);
                    contract.path("parameters").forEach(parameters::add);
                    // Operation parameters override path parameters with the same name and location.
                    Map<String, JsonNode> merged = new LinkedHashMap<>();
                    parameters.forEach(p -> merged.put(p.path("in").asText()+":"+p.path("name").asText(), p));
                    ArrayNode combined = yaml.createArrayNode(); merged.values().forEach(combined::add);
                    contract.set("parameters", combined);
                    Set<Integer> statuses = new LinkedHashSet<>();
                    contract.path("responses").fieldNames().forEachRemaining(code -> {
                        if (code.matches("[1-5][0-9]{2}")) statuses.add(Integer.parseInt(code));
                    });
                    if (statuses.isEmpty()) throw ApiException.badRequest("Each operation needs at least one explicit numeric response status");
                    String method = operation.getKey().toUpperCase(Locale.ROOT);
                    endpoints.add(new Endpoint(method+" "+path.getKey(), method, path.getKey(),
                            contract.path("summary").asText(""), contract, Set.copyOf(statuses)));
                });
            });
            if (endpoints.isEmpty() || endpoints.size()>40) throw ApiException.badRequest("Import between 1 and 40 operations");
            return List.copyOf(endpoints);
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw ApiException.badRequest("Could not parse the contract. Check JSON/YAML syntax and operation structure."); }
    }

    private JsonNode expand(JsonNode node, JsonNode root, Set<String> seen, int[] visited, int depth) {
        if (++visited[0] > 12000 || depth>40) throw ApiException.badRequest("Contract reference expansion exceeds the safety budget");
        if (node.isObject()) {
            if (node.has("$ref")) {
                String ref = node.path("$ref").asText();
                if (!ref.startsWith("#/")) throw ApiException.badRequest("Only local #/ references are supported; remote references are never fetched");
                if (!seen.add(ref)) throw ApiException.badRequest("Recursive schemas are not supported in this version");
                JsonNode target = root.at(ref.substring(1));
                if (target.isMissingNode()) throw ApiException.badRequest("Unresolved local reference: " + ref);
                JsonNode result = expand(target, root, seen, visited, depth+1); seen.remove(ref); return result;
            }
            ObjectNode result = yaml.createObjectNode();
            node.fields().forEachRemaining(f -> result.set(f.getKey(), expand(f.getValue(), root, seen, visited, depth+1)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = yaml.createArrayNode();
            node.forEach(n -> result.add(expand(n, root, seen, visited, depth+1))); return result;
        }
        return node.deepCopy();
    }
}
