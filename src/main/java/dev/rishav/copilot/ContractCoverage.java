package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;
import java.math.BigDecimal;
import static dev.rishav.copilot.Models.*;

/** Supported top-level JSON request rules only. Coverage is derived from saved requests, never model claims. */
public class ContractCoverage {
    public record Rule(String id,String sourcePointer,String field,String probe,JsonNode value,int expectedStatus,
                       String explanation,JsonNode sourceValue,Scenario candidate) {}
    public record Row(Rule rule,String state,List<Integer> scenarioIndexes) {}
    public record Conflict(String id,String ruleId,String contractStatement,String documentationStatement,String decision,String reason) {}
    public record Report(UUID specificationId,int version,String endpointKey,List<Row> rules,List<Conflict> conflicts,String scope) {}
    private final ObjectMapper yaml=new ObjectMapper(new YAMLFactory());
    private static String escape(String s) { return s.replace("~","~0").replace("/","~1"); }
    public List<Rule> rules(SavedSpecification saved,Endpoint endpoint,List<Evidence> evidence) {
        JsonNode schema=endpoint.contract().path("requestBody").path("content").path("application/json").path("schema");
        if(!schema.path("type").asText().equals("object")) return List.of();
        int success=endpoint.statuses().stream().filter(s->s>=200&&s<300).sorted().findFirst().orElse(0);
        if(success==0) return List.of();
        Scenario seed=new DemoGenerator().generate(endpoint,evidence).scenarios().getFirst();
        if(!seed.body().isObject()) return List.of();
        ObjectNode base=seed.body().deepCopy();
        schema.path("properties").fields().forEachRemaining(f->{var s=f.getValue();
            if(s.has("enum")&&s.get("enum").isArray()&&!s.get("enum").isEmpty()) base.set(f.getKey(),s.get("enum").get(0));
            else if(s.path("type").asText().equals("string")) { int n=Math.max(0,s.path("minLength").asInt(1)); if(s.has("maxLength"))n=Math.min(n,s.path("maxLength").asInt(n)); if(n>=0&&n<=1000) base.put(f.getKey(),"x".repeat(n)); }
            else if(s.has("minimum")&&s.get("minimum").isNumber()) base.set(f.getKey(),s.get("minimum"));
            else if(s.has("maximum")&&s.get("maximum").isNumber()) base.set(f.getKey(),DecimalNode.valueOf(s.get("maximum").decimalValue().min(BigDecimal.ONE)));
        });
        String pointer="/paths/"+escape(endpoint.path())+"/"+endpoint.method().toLowerCase(Locale.ROOT)+"/requestBody/content/application~1json/schema";
        try { var raw=yaml.readTree(saved.source().content()).at(pointer); if(raw.has("$ref")&&raw.path("$ref").asText().startsWith("#/")) pointer=raw.get("$ref").asText().substring(1); } catch(Exception e) { throw new IllegalStateException("Saved contract cannot be read",e); }
        final String schemaPointer=pointer;
        var rules=new ArrayList<Rule>();
        int bad=endpoint.statuses().contains(400)?400:0;
        for(int i=0;i<schema.path("required").size();i++) {
            var field=schema.path("required").get(i).asText();
            if(bad!=0) add(rules,saved,seed,base,field,"MISSING",NullNode.instance,bad,schemaPointer+"/required/"+i,schema.path("required").get(i),"Omit required field "+field);
        }
        schema.path("properties").fields().forEachRemaining(f->{
            String field=f.getKey();JsonNode s=f.getValue();String p=schemaPointer+"/properties/"+escape(field);
            if(s.has("enum")) {
                if(s.get("enum").isArray()&&!s.get("enum").isEmpty()) {
                    add(rules,saved,seed,base,field,"ENUM_VALID",s.get("enum").get(0),success,p+"/enum",s.get("enum"),"Use a documented enum value for "+field);
                    // Preserve field type when constructing an excluded value.
                    JsonNode invalid=null; if(s.path("type").asText().equals("string")) { String v="outside-enum";while(contains(s.get("enum"),TextNode.valueOf(v)))v+="x";invalid=TextNode.valueOf(v); }
                    if(invalid!=null&&bad!=0)add(rules,saved,seed,base,field,"ENUM_INVALID",invalid,bad,p+"/enum",s.get("enum"),"Use an excluded enum value for "+field);
                } return;
            }
            if(s.path("type").asText().matches("number|integer")) for(String key:List.of("minimum","maximum")) {
                if(!s.path(key).isNumber()||s.has("exclusiveMinimum")||s.has("exclusiveMaximum")||s.has("multipleOf"))continue;
                var value=s.get(key);
                if(s.path("type").asText().equals("integer")&&value.decimalValue().stripTrailingZeros().scale()>0)continue;
                if(s.path("minimum").isNumber()&&s.path("maximum").isNumber()&&s.get("minimum").decimalValue().compareTo(s.get("maximum").decimalValue())>0)continue;
                add(rules,saved,seed,base,field,key.toUpperCase(Locale.ROOT),value,success,p+"/"+key,value,"Test the inclusive "+key+" of "+field);
                if(bad!=0) add(rules,saved,seed,base,field,key.equals("minimum")?"BELOW_MINIMUM":"ABOVE_MAXIMUM",
                        DecimalNode.valueOf(value.decimalValue().add(BigDecimal.valueOf(key.equals("minimum")?-1:1))),bad,p+"/"+key,value,"Test outside the "+key+" of "+field);
            }
            if(s.path("type").asText().equals("string")&&!s.has("pattern")&&!s.has("format")) for(String key:List.of("minLength","maxLength")) {
                if(!s.path(key).canConvertToInt()||s.path(key).asInt()<0||s.path(key).asInt()>1000)continue;
                int n=s.get(key).asInt();
                add(rules,saved,seed,base,field,key.toUpperCase(Locale.ROOT),TextNode.valueOf("x".repeat(n)),success,p+"/"+key,s.get(key),"Test "+key+" of "+field);
                int outside=n+(key.equals("minLength")?-1:1);
                if(bad!=0&&outside>=0) add(rules,saved,seed,base,field,key.equals("minLength")?"TOO_SHORT":"TOO_LONG",TextNode.valueOf("x".repeat(outside)),bad,p+"/"+key,s.get(key),"Test outside "+key+" of "+field);
            }
        });
        return List.copyOf(rules);
    }
    private static boolean contains(JsonNode array,JsonNode n) { for(var v:array) if(v.equals(n))return true;return false; }
    private void add(List<Rule> rules,SavedSpecification saved,Scenario seed,ObjectNode base,String field,String probe,JsonNode value,int status,String pointer,JsonNode source,String explanation) {
        if(rules.size()>=64)return;
        var body=base.deepCopy(); if(probe.equals("MISSING"))body.remove(field);else body.set(field,value);
        var candidate=new Scenario("Rule case: "+field+" "+probe,probe.equals("MISSING")||status==400?Category.NEGATIVE:Category.EDGE,
                seed.method(),seed.path(),seed.query(),body,status,explanation+". Review fixture assumptions and other field constraints.",seed.sourceIds());
        if(body.toString().length()>16000)return;
        pointer=originalPointer(saved,pointer);
        rules.add(new Rule(saved.specification().id()+":"+pointer+":"+probe,pointer,field,probe,value,status,explanation,source,candidate));
    }
    private String originalPointer(SavedSpecification saved,String pointer) {
        try {
            var root=yaml.readTree(saved.source().content());String current="";int refs=0;
            for(String segment:pointer.substring(1).split("/")) {
                var node=root.at(current);
                while(node.has("$ref")) { if(++refs>40)throw new IllegalStateException("Reference depth exceeded");current=node.path("$ref").asText().substring(1);node=root.at(current); }
                current+="/"+segment;
            }
            return current;
        } catch(Exception e) { throw new IllegalStateException("Cannot locate original contract rule",e); }
    }
    public boolean matches(Rule rule,Scenario scenario) {
        if(scenario.expectedStatus()!=rule.expectedStatus()||scenario.body()==null||!scenario.body().isObject())return false;
        var value=scenario.body().path(rule.field());
        if(rule.probe().equals("MISSING"))return value.isMissingNode();
        return value.isNumber()&&rule.value().isNumber()?value.decimalValue().compareTo(rule.value().decimalValue())==0:value.equals(rule.value());
    }
    public List<Conflict> conflicts(SavedSpecification saved,List<Rule> rules,Map<String,ConflictDecision> decisions) {
        var result=new ArrayList<Conflict>();String docs=Objects.toString(saved.source().documentation(),"");
        for(var rule:rules) if(rule.probe().equals("MINIMUM")&&rule.value().isNumber()) {
            var pattern=java.util.regex.Pattern.compile("(?i)(?<![\\w])"+java.util.regex.Pattern.quote(rule.field())+"\\s*>\\s*(-?\\d+(?:\\.\\d+)?)(?!\\d|\\.\\d)");
            var matcher=pattern.matcher(docs);while(matcher.find()) {
                if(new BigDecimal(matcher.group(1)).compareTo(rule.value().decimalValue())<0)continue;
                String id=rule.id()+":docs:"+matcher.start();var decision=decisions.get(id);
                result.add(new Conflict(id,rule.id(),rule.field()+" >= "+rule.value(),matcher.group(),decision==null?"UNRESOLVED":decision.choice(),decision==null?"":decision.reason()));
            }
        }
        return result;
    }
}
