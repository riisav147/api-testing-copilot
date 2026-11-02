package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.*;
import static dev.rishav.copilot.Models.*;

/** Bounded structural comparison, not a general OpenAPI compatibility or semantic analysis engine. */
public class ContractDiff {
    public record Change(String id,String endpointKey,String kind,String pointer,JsonNode before,JsonNode after,
                         String beforeSource,String afterSource,String certainty) {}
    public record Suggestion(String ruleId,String sourcePointer,String field,String probe,Scenario request) {}
    public record Impact(int scenarioIndex,int revision,String state,List<String> changeIds,List<String> ruleIds,
                         List<Suggestion> suggestions,String detail) {}
    public record Report(UUID fromSpecificationId,int fromVersion,UUID toSpecificationId,int toVersion,UUID planId,
                         List<Integer> expectedRevisions,List<Change> changes,List<Impact> impacts,boolean limited,String scope) {}
    private static final int LIMIT=256;
    private final ObjectMapper yaml=new ObjectMapper(new YAMLFactory());
    private final ContractCoverage coverage=new ContractCoverage();
    public Report compare(SavedSpecification from,SavedSpecification to,Plan plan) {
        if(!from.projectId().equals(to.projectId())||from.specification().id().equals(to.specification().id())||from.version()>=to.version())throw ApiException.badRequest("Choose an older and a newer version in the same project");
        if(plan!=null&&!plan.specificationId().equals(from.specification().id()))throw ApiException.badRequest("Choose a plan belonging to the older specification version");
        var changes=new ArrayList<Change>();var old=operations(from);var next=operations(to);
        var keys=new TreeSet<>(old.keySet());keys.addAll(next.keySet());
        for(var key:keys) {
            var a=old.get(key);var b=next.get(key);
            if(a==null||b==null)add(changes,key,a==null?"OPERATION_ADDED":"OPERATION_REMOVED","",a==null?null:a.contract(),b==null?null:b.contract(),from,to,"KNOWN");
            else walk(changes,key,"",a.contract(),b.contract(),from,to);
        }
        var a=global(from);var b=global(to);if(!a.equals(b))add(changes,"*","GLOBAL_CONTEXT","",a,b,from,to,"UNKNOWN");
        if(!Objects.equals(from.source().documentation(),to.source().documentation()))add(changes,"*","DOCUMENTATION","supporting-documentation",yaml.valueToTree(from.source().documentation()),yaml.valueToTree(to.source().documentation()),from,to,"UNKNOWN");
        boolean limited=changes.size()>LIMIT;if(limited)changes=new ArrayList<>(changes.subList(0,LIMIT));
        var impacts=new ArrayList<Impact>();
        if(plan!=null) {
            var relevant=changes.stream().filter(c->c.endpointKey().equals(plan.endpointKey())||c.endpointKey().equals("*")).toList();
            var source=old.get(plan.endpointKey());var target=next.get(plan.endpointKey());
            var sourceRules=coverage.rules(from,source,plan.evidence());
            var targetEvidence=List.of(new Evidence("contract:"+to.specification().id()+":"+plan.endpointKey(),"CONTRACT "+plan.endpointKey()+"\n"+(target==null?"":target.contract()),1));
            var targetRules=target==null?List.<ContractCoverage.Rule>of():coverage.rules(to,target,targetEvidence);
            for(int i=0;i<plan.scenarios().size();i++) {
                var s=plan.scenarios().get(i);var linked=sourceRules.stream().filter(r->coverage.matches(r,s)).toList();
                var affected=linked.stream().filter(r->relevant.stream().anyMatch(c->touchesRule(c,r))).toList();
                boolean unknown=limited||relevant.stream().anyMatch(c->c.certainty().equals("UNKNOWN"));
                String state=unknown?"UNKNOWN":target==null||!affected.isEmpty()?"AFFECTED":relevant.isEmpty()?"NO_DETECTED_CHANGE":"REVIEW";
                var proposals=new ArrayList<Suggestion>();
                // Only deterministic input replacements. Status and response assertions are NEVER rewritten.
                if(!unknown&&target!=null)for(var rule:affected) {
                    if(!Set.of("MINIMUM","MAXIMUM","BELOW_MINIMUM","ABOVE_MAXIMUM","MINLENGTH","MAXLENGTH","TOO_SHORT","TOO_LONG","ENUM_VALID","ENUM_INVALID").contains(rule.probe()))continue;
                    for(var replacement:targetRules)if(replacement.field().equals(rule.field())&&replacement.probe().equals(rule.probe())&&replacement.expectedStatus()==s.expectedStatus()&&s.body()!=null&&s.body().isObject()) {
                        var body=s.body().deepCopy();((ObjectNode)body).set(rule.field(),replacement.value());
                        if(body.equals(s.body()))continue;
                        var proposed=new Scenario(s.title(),s.category(),s.method(),s.path(),s.query(),body,s.expectedStatus(),s.rationale(),s.sourceIds(),s.assertions());
                        proposals.add(new Suggestion(replacement.id(),replacement.sourcePointer(),rule.field(),rule.probe(),proposed));
                    }
                }
                impacts.add(new Impact(i,plan.scenarioRevisions().get(i),state,relevant.stream().map(Change::id).toList(),affected.stream().map(ContractCoverage.Rule::id).toList(),List.copyOf(proposals),
                        unknown?"Changed unsupported/context data or comparison limit: impact is unknown. Review manually.":target==null?"Operation was removed; this plan cannot be copied to the new version.":!affected.isEmpty()?"A represented request rule changed. Proposed inputs need review; assertions and expected status remain unchanged.":relevant.isEmpty()?"No normalized operation/global changes detected in this comparison; this is not a compatibility guarantee.":"Operation changed without a matching supported request probe. Review this saved request and its response checks manually."));
            }
        }
        return new Report(from.specification().id(),from.version(),to.specification().id(),to.version(),plan==null?null:plan.id(),plan==null?List.of():plan.scenarioRevisions(),List.copyOf(changes),List.copyOf(impacts),limited,
                "Up to 256 structural changes. Operations, numeric response statuses, JSON schema required/type/bounds/enums are recognized. Other operation/global/documentation changes have unknown semantic impact. Rule links cover the first 64 supported top-level request probes; unchanged checks are not proof of compatibility. Source pointers are best-effort original JSON Pointers; normalized parameters may have no exact source pointer.");
    }
    private Map<String,Endpoint> operations(SavedSpecification s) {var map=new TreeMap<String,Endpoint>();s.specification().endpoints().forEach(e->map.put(e.key(),e));return map;}
    private void walk(List<Change> out,String key,String pointer,JsonNode a,JsonNode b,SavedSpecification from,SavedSpecification to) {
        if(out.size()>LIMIT||equivalent(a,b))return;
        String last=pointer.substring(pointer.lastIndexOf('/')+1);
        if(last.equals("required")&&arrayStrings(a)&&arrayStrings(b)) {if(!strings(a).equals(strings(b)))add(out,key,"REQUIRED_FIELDS",pointer,a,b,from,to,"KNOWN");return;}
        if(last.equals("enum")) {add(out,key,"ENUM",pointer,a,b,from,to,arrayValues(a)&&arrayValues(b)?"KNOWN":"UNKNOWN");return;}
        if(Set.of("minimum","maximum","minLength","maxLength","type").contains(last)&&pointer.contains("/schema")) {
            boolean supported=last.equals("type")?(a==null||a.isTextual())&&(b==null||b.isTextual()):(a==null||a.isNumber())&&(b==null||b.isNumber());
            add(out,key,last.toUpperCase(Locale.ROOT),pointer,a,b,from,to,supported?"KNOWN":"UNKNOWN");return;
        }
        if(pointer.matches("/responses/[1-5][0-9]{2}")&&(a==null||b==null)) {add(out,key,"RESPONSE_STATUS",pointer,a,b,from,to,"KNOWN");return;}
        if(a!=null&&b!=null&&a.isObject()&&b.isObject()) {
            var fields=new TreeSet<String>();a.fieldNames().forEachRemaining(fields::add);b.fieldNames().forEachRemaining(fields::add);
            for(var field:fields)walk(out,key,pointer+"/"+escape(field),a.get(field),b.get(field),from,to);
        } else add(out,key,"UNSUPPORTED_OR_CONTEXT",pointer,a,b,from,to,"UNKNOWN");
    }
    private static boolean arrayValues(JsonNode n){return n==null||n.isArray();}
    private static boolean arrayStrings(JsonNode n){if(n==null)return true;if(!n.isArray())return false;for(var x:n)if(!x.isTextual())return false;return true;}
    private static Set<String> strings(JsonNode n){var s=new TreeSet<String>();if(n!=null)n.forEach(x->s.add(x.asText()));return s;}
    private static boolean equivalent(JsonNode a,JsonNode b){return a==null?b==null:b!=null&&(a.isNumber()&&b.isNumber()?a.decimalValue().compareTo(b.decimalValue())==0:a.equals(b));}
    private void add(List<Change> out,String key,String kind,String pointer,JsonNode a,JsonNode b,SavedSpecification from,SavedSpecification to,String certainty) {
        if(out.size()>LIMIT)return;
        out.add(new Change(key+":"+pointer,key,kind,pointer,a,b,source(from,key,pointer),source(to,key,pointer),certainty));
    }
    private boolean touchesRule(Change c,ContractCoverage.Rule r) {
        String schema="/requestBody/content/application~1json/schema";
        if(r.probe().equals("MISSING"))return c.pointer().equals(schema+"/required");
        String key=switch(r.probe()) {case "BELOW_MINIMUM","MINIMUM"->"minimum";case "ABOVE_MAXIMUM","MAXIMUM"->"maximum";case "MINLENGTH","TOO_SHORT"->"minLength";case "MAXLENGTH","TOO_LONG"->"maxLength";case "ENUM_VALID","ENUM_INVALID"->"enum";default->"";};
        return c.pointer().equals(schema+"/properties/"+escape(r.field())+"/"+key);
    }
    private JsonNode global(SavedSpecification spec) {
        try {var n=(ObjectNode)yaml.readTree(spec.source().content());n.remove(List.of("paths","components"));
            if(n.path("info").isObject()){var info=(ObjectNode)n.get("info");info.remove(List.of("title","version"));if(info.isEmpty())n.remove("info");}
            return n;
        }catch(Exception e){throw new IllegalStateException("Saved contract cannot be compared",e);}
    }
    private String source(SavedSpecification spec,String key,String pointer) {
        if(key.equals("*")||pointer.startsWith("/parameters"))return null;
        try {
            String current="";String full="/paths/"+escape(key.substring(key.indexOf(' ')+1))+"/"+key.substring(0,key.indexOf(' ')).toLowerCase(Locale.ROOT)+pointer;
            var root=yaml.readTree(spec.source().content());int count=0;
            for(String part:full.substring(1).split("/")) {
                var n=root.at(current);while(n.has("$ref")){if(++count>40)return null;current=n.path("$ref").asText().substring(1);n=root.at(current);}
                current+="/"+part;
            }
            return root.at(current).isMissingNode()?null:current;
        }catch(Exception ignored){return null;}
    }
    private static String escape(String s){return s.replace("~","~0").replace("/","~1");}
}
