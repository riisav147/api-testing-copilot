package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.http.HttpStatus;
import java.util.*;
import static dev.rishav.copilot.Models.*;

/** Deterministic checks only. JSON targets use RFC 6901 pointers, not executable expressions. */
public class AssertionEngine {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> TYPES=Set.of("object","array","string","number","integer","boolean","null");
    private static final Set<String> SCHEMA_KEYS=Set.of("type","properties","required","items","minimum","maximum","enum","additionalProperties","nullable",
            "description","title","example","examples","default","deprecated","readOnly","writeOnly");

    public static void validate(List<ResponseAssertion> checks) {
        if (checks==null || checks.size()>20) reject("Use at most 20 response assertions");
        for (var c:checks) {
            if(c==null || c.kind()==null || c.target()==null || c.target().length()>300
                    || (c.expected()!=null && c.expected().toString().length()>8000)) reject("Malformed or oversized response assertion");
            if(c.kind()==AssertionKind.HEADER_EQUALS) {
                if(!c.target().matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}") || c.expected()==null || !c.expected().isTextual())
                    reject("Header checks need a header name and expected text");
            } else {
                if(!c.target().isEmpty() && !c.target().matches("/(?:[^~]|~[01])*")) reject("Use a JSON Pointer such as /price or /items/0/id; empty means the whole body");
                switch(c.kind()) {
                    case JSON_TYPE -> { if(c.expected()==null || !c.expected().isTextual() || !TYPES.contains(c.expected().asText())) reject("Choose a supported JSON type"); }
                    case JSON_GTE,JSON_LTE -> { if(c.expected()==null || !c.expected().isNumber()) reject("Numeric checks require a JSON number"); }
                    case JSON_SCHEMA -> { if(c.expected()==null || !c.expected().isObject()) reject("Schema checks require a JSON schema object"); }
                    case JSON_EQUALS -> { if(c.expected()==null) reject("Equality checks require an expected JSON value, including null"); }
                    default -> { }
                }
            }
        }
    }
    private static void reject(String text) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,text); }
    public List<AssertionResult> evaluate(Scenario scenario,int status,Map<String,List<String>> headers,String body,boolean truncated) {
        var results=new ArrayList<AssertionResult>();
        results.add(result("STATUS","HTTP status",status==scenario.expectedStatus(),JSON.valueToTree(scenario.expectedStatus()),JSON.valueToTree(status),"HTTP status equality"));
        JsonNode parsed=null;
        if(!truncated) try { parsed=JSON.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body); } catch(Exception ignored) { }
        for(var c:scenario.assertions()) {
            if(c.kind()==AssertionKind.HEADER_EQUALS) {
                var values=headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(c.target())).map(Map.Entry::getValue).findFirst().orElse(List.of());
                String actual=String.join(", ",values);
                results.add(result(c.kind().name(),c.target(),!values.isEmpty() && actual.equals(c.expected().asText()),c.expected(),values.isEmpty()?MissingNode.getInstance():TextNode.valueOf(actual),"Header values are joined with comma and space; comparison is exact"));
                continue;
            }
            if(truncated || parsed==null || parsed.isMissingNode()) {
                results.add(new AssertionResult(c.kind().name(),c.target(),CheckOutcome.UNEVALUABLE,c.expected(),null,
                        truncated?"Response exceeds 8,192 bytes; JSON checks require the complete body":"Response is empty or is not valid JSON")); continue;
            }
            JsonNode actual=parsed.at(c.target());
            if(c.kind()==AssertionKind.JSON_SCHEMA) {
                try {
                    schemaSupported(c.expected(),0,new int[]{0});
                    var errors=new ArrayList<String>(); schemaCheck(c.expected(),actual,c.target(),errors);
                    results.add(result(c.kind().name(),c.target(),errors.isEmpty(),c.expected(),actual,errors.isEmpty()?"Supported schema rules matched":String.join("; ",errors)));
                } catch(IllegalArgumentException e) {
                    results.add(new AssertionResult(c.kind().name(),c.target(),CheckOutcome.UNEVALUABLE,c.expected(),actual,e.getMessage()));
                }
                continue;
            }
            boolean pass=switch(c.kind()) {
                case JSON_EXISTS -> !actual.isMissingNode();
                case JSON_TYPE -> matchesType(actual,c.expected().asText());
                case JSON_EQUALS -> equivalent(actual,c.expected());
                case JSON_GTE -> actual.isNumber() && actual.decimalValue().compareTo(c.expected().decimalValue())>=0;
                case JSON_LTE -> actual.isNumber() && actual.decimalValue().compareTo(c.expected().decimalValue())<=0;
                default -> false;
            };
            results.add(result(c.kind().name(),c.target(),pass,c.expected(),actual,actual.isMissingNode()?"Field is missing":pass?"Check matched":"Expected and actual values do not match this check"));
        }
        return List.copyOf(results);
    }
    private AssertionResult result(String kind,String target,boolean pass,JsonNode expected,JsonNode actual,String detail) {
        return new AssertionResult(kind,target,pass?CheckOutcome.PASS:CheckOutcome.FAIL,expected,actual,detail);
    }
    private static boolean matchesType(JsonNode n,String type) {
        if(n.isMissingNode()) return false;
        return switch(type) { case "object" -> n.isObject(); case "array" -> n.isArray(); case "string" -> n.isTextual();
            case "number" -> n.isNumber(); case "integer" -> n.isNumber() && n.decimalValue().stripTrailingZeros().scale()<=0;
            case "boolean" -> n.isBoolean(); case "null" -> n.isNull(); default -> false; };
    }
    private static boolean equivalent(JsonNode a,JsonNode b) {
        if(a.isMissingNode()) return false;
        if(a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue())==0;
        if(a.isArray() && b.isArray()) { if(a.size()!=b.size()) return false; for(int i=0;i<a.size();i++) if(!equivalent(a.get(i),b.get(i))) return false; return true; }
        if(a.isObject() && b.isObject()) { if(a.size()!=b.size()) return false; var names=a.fieldNames(); while(names.hasNext()) { var name=names.next(); if(!b.has(name)||!equivalent(a.get(name),b.get(name))) return false; } return true; }
        return a.equals(b);
    }
    private static void schemaSupported(JsonNode s,int depth,int[] nodes) {
        if(depth>20 || ++nodes[0]>500) throw new IllegalArgumentException("Schema exceeds supported complexity");
        if(!s.isObject()) throw new IllegalArgumentException("Only object schema definitions are supported");
        var keys=s.fieldNames(); while(keys.hasNext()) { String key=keys.next(); if(!SCHEMA_KEYS.contains(key)) throw new IllegalArgumentException("Unsupported schema keyword: "+key); }
        if(s.has("type") && (!s.get("type").isTextual() || !TYPES.contains(s.get("type").asText()))) throw new IllegalArgumentException("Unsupported schema type");
        if(s.has("nullable") && !s.get("nullable").isBoolean()) throw new IllegalArgumentException("nullable must be boolean");
        if(s.has("properties")) { if(!s.get("properties").isObject()) throw new IllegalArgumentException("properties must be an object"); s.get("properties").forEach(child -> schemaSupported(child,depth+1,nodes)); }
        if(s.has("items")) schemaSupported(s.get("items"),depth+1,nodes);
        if(s.has("required")) { if(!s.get("required").isArray()) throw new IllegalArgumentException("required must be an array"); for(var n:s.get("required")) if(!n.isTextual()) throw new IllegalArgumentException("required entries must be strings"); }
        for(String key:List.of("minimum","maximum")) if(s.has(key)&&!s.get(key).isNumber()) throw new IllegalArgumentException(key+" must be numeric");
        if(s.has("enum")&&(!s.get("enum").isArray()||s.get("enum").isEmpty())) throw new IllegalArgumentException("enum must be a nonempty array");
        if(s.has("additionalProperties")&&!s.get("additionalProperties").isBoolean()) throw new IllegalArgumentException("Only boolean additionalProperties is supported");
    }
    private static void schemaCheck(JsonNode s,JsonNode value,String path,List<String> errors) {
        if(errors.size()>=20) return;
        if(value.isMissingNode()) { errors.add(path+": value is missing"); return; }
        if(value.isNull() && s.path("nullable").asBoolean()) return;
        if(s.has("type")&&!matchesType(value,s.get("type").asText())) { errors.add(path+": expected type "+s.get("type").asText()); return; }
        if(s.has("enum")) { boolean found=false; for(var option:s.get("enum")) if(equivalent(value,option)) found=true; if(!found) errors.add(path+": value is outside enum"); }
        if(value.isNumber()) {
            if(s.has("minimum")&&value.decimalValue().compareTo(s.get("minimum").decimalValue())<0) errors.add(path+": below minimum");
            if(s.has("maximum")&&value.decimalValue().compareTo(s.get("maximum").decimalValue())>0) errors.add(path+": above maximum");
        }
        if(value.isObject()) {
            for(var name:s.path("required")) if(!value.has(name.asText())) errors.add(path+"/"+name.asText()+": required field missing");
            var fields=value.fields(); while(fields.hasNext()) { var f=fields.next(); if(s.path("properties").has(f.getKey())) schemaCheck(s.path("properties").get(f.getKey()),f.getValue(),path+"/"+f.getKey(),errors);
                else if(s.has("additionalProperties")&&!s.get("additionalProperties").asBoolean()) errors.add(path+"/"+f.getKey()+": additional property"); }
        }
        if(value.isArray()&&s.has("items")) for(int i=0;i<value.size()&&errors.size()<20;i++) schemaCheck(s.get("items"),value.get(i),path+"/"+i,errors);
    }
}
