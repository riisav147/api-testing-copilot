package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import javax.xml.stream.XMLOutputFactory;
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import static dev.rishav.copilot.Models.*;

/** Read-only reports derived from recorded evidence. Never executes requests or resolves credentials. */
@Service
public class ResultReports {
    public record RuleReference(String id,String sourcePointer,String explanation,Object sourceValue) {}
    public record RunReport(String schemaVersion,Execution execution,UUID specificationId,int specificationVersion,
                            String endpointKey,List<RuleReference> relatedRules,ExecutionContext context,
                            List<String> limitations,String curl,String markdown) {}
    public record CaseResult(int scenarioIndex,int scenarioRevision,String state,UUID executionId,String detail) {}
    public record BatchReport(String schemaVersion,UUID id,UUID planId,String state,String target,boolean success,
                              List<CaseResult> cases,List<RunReport> runs) {}
    private final WorkspaceStore store;
    private final CopilotService service;
    private final SandboxRunner runner;
    private final ObjectMapper mapper;
    public ResultReports(WorkspaceStore store,CopilotService service,SandboxRunner runner,ObjectMapper mapper) {
        this.store=store;this.service=service;this.runner=runner;this.mapper=mapper;
    }
    public RunReport execution(UUID id) {
        var e=service.execution(id);var p=service.plan(e.planId());var spec=service.savedSpecification(p.specificationId());
        var endpoint=spec.specification().endpoints().stream().filter(v->v.key().equals(p.endpointKey())).findFirst().orElseThrow();
        var coverage=new ContractCoverage();var rules=coverage.rules(spec,endpoint,p.evidence()).stream().filter(r->coverage.matches(r,e.requestSnapshot()))
                .map(r->new RuleReference(r.id(),r.sourcePointer(),r.explanation(),r.sourceValue())).toList();
        var context=store.executionContext(id);var limitations=new ArrayList<String>();
        if(context==null) {
            // Older batch jobs already captured their environment; do not substitute today's mutable environment.
            var batch=store.records("batches",BatchJob.class).stream().filter(b->b.cases().stream().anyMatch(c->id.equals(c.executionId()))).findFirst();
            if(batch.isPresent()&&batch.get().environmentSnapshot()!=null)context=new ExecutionContext(batch.get().target(),batch.get().environmentSnapshot());
            else if("bundled localhost sandbox".equals(e.target())) {
                context=new ExecutionContext(runner.sandboxBase(),null);
                limitations.add("Historical sandbox port was not recorded; reproduction uses this backend's current sandbox URL.");
            } else limitations.add("Historical authentication configuration is unavailable. Configure it manually; reproduction is blocked by default.");
        }
        if(e.bodyTruncated())limitations.add("Response capture was truncated at 8192 bytes. JSON checks could not validate the complete response.");
        if(json(e.requestSnapshot()).contains("[REDACTED]"))limitations.add("Request evidence contains redacted values. Restore placeholders privately before reproduction; execution is blocked by default.");
        limitations.add("Fixture state may have changed. Reproduction sends a new request and can repeat side effects; review before running.");
        limitations.add("Related request rules identify source provenance. Failed configured checks are observations, not proof of a root cause or complete contract violation.");
        String curl=curl(e,context);String markdown=markdown(e,spec,p,rules,limitations,curl);
        return new RunReport("1",e,spec.specification().id(),spec.version(),p.endpointKey(),rules,context,List.copyOf(limitations),curl,markdown);
    }
    public BatchReport batch(BatchJob job) {
        service.plan(job.planId());
        var cases=job.cases().stream().map(c->new CaseResult(c.scenarioIndex(),c.scenarioRevision(),c.state(),c.executionId(),c.detail())).toList();
        var runs=job.cases().stream().map(BatchCase::executionId).filter(Objects::nonNull).map(this::execution).toList();
        return new BatchReport("1",job.id(),job.planId(),job.state(),job.target(),job.state().equals("COMPLETED")&&cases.stream().allMatch(c->c.state().equals("PASSED")),cases,runs);
    }
    private String curl(Execution e,ExecutionContext context) {
        var s=e.requestSnapshot();var out=new StringBuilder("#!/bin/sh\nset -eu\n# Review the target and fixture state. This sends a NEW request; it does not assert correctness.\n# Do not run with shell tracing enabled. Supply referenced credentials through your environment.\n");
        if(context==null||context.baseUrl()==null||json(s).contains("[REDACTED]"))out.append("printf '%s\\n' 'Restore missing configuration or redacted request values privately, then remove this guard.' >&2\nexit 2\n");
        String base=context==null||context.baseUrl()==null?"http://127.0.0.1:8081":context.baseUrl();
        String query=s.query().entrySet().stream().map(v->encode(v.getKey())+"="+encode(v.getValue())).collect(Collectors.joining("&"));
        String url=base.replaceAll("/+$","")+s.path()+(query.isEmpty()?"":"?"+query);
        // Shell-quoted literals never evaluate request text as shell code; curl does not expand URL globbing.
        out.append("curl --silent --show-error --globoff --path-as-is --noproxy '*' --proto '=http' --connect-timeout 2 --max-time 5 \\\n  ");
        out.append(s.method().equals("HEAD")?"--head":"--request "+quote(s.method()));
        var headers=new TreeMap<String,String>(String.CASE_INSENSITIVE_ORDER);headers.put("Content-Type",quote("Content-Type: application/json"));
        if(context!=null&&context.environment()!=null) {
            var env=context.environment();env.headerRefs().forEach((name,ref)->headers.put(name,variableHeader(name,"",ref)));
            if(env.authType().equals("BEARER"))headers.put("Authorization",variableHeader("Authorization","Bearer ",env.secretRef()));
            if(env.authType().equals("API_KEY"))headers.put(env.authHeader(),variableHeader(env.authHeader(),"",env.secretRef()));
        }
        for(var header:headers.values())out.append(" \\\n  --header ").append(header);
        out.append(" \\\n  --url ").append(quote(url)).append(" \\\n  --write-out '\\nHTTP %{http_code}\\n'");
        if(s.body()!=null&&!s.body().isNull()) {
            String body=json(s.body());String delimiter="COPILOT_BODY_"+e.id().toString().replace("-","");
            while(Arrays.asList(body.split("\\R",-1)).contains(delimiter))delimiter+="_";
            out.append(" \\\n  --data-binary @- <<'").append(delimiter).append("'\n").append(body).append('\n').append(delimiter);
        }
        return out.append('\n').toString();
    }
    private String variableHeader(String name,String prefix,String ref) {
        if(!name.matches("[A-Za-z][A-Za-z0-9-]{0,79}")||ref==null||!ref.matches("[A-Z][A-Z0-9_]{0,99}"))throw new IllegalStateException("Invalid saved credential reference");
        return "\""+name+": "+prefix+"${"+ref+":?Set "+ref+" privately}\"";
    }
    static String quote(String value) { return "'"+value.replace("'","'\"'\"'")+"'"; }
    private static String encode(String s) {return URLEncoder.encode(s,StandardCharsets.UTF_8);}
    private String markdown(Execution e,SavedSpecification spec,Plan p,List<RuleReference> rules,List<String> limitations,String curl) {
        return "# Recorded API test result\n\nExecution: "+e.id()+"\n\nPlan: "+p.id()+" · case "+(e.scenarioIndex()+1)+" · revision "+e.scenarioRevision()+"\n\nSpecification: "+spec.specification().id()+" · version "+spec.version()+
                "\n\n## Observed facts\n\nExpected HTTP "+e.expectedStatus()+"; observed HTTP "+e.actualStatus()+". Overall configured checks: "+(e.passed()?"PASSED":"FAILED")+". Duration: "+e.durationMs()+" ms. Recorded at: "+e.createdAt()+".\n\n"+
                "### Exact saved request and assertions\n\n"+fence(json(e.requestSnapshot()),"json")+"\n### Recorded checks (expected and actual)\n\n"+fence(json(e.assertionResults()),"json")+
                "\n### Related documented request rules\n\n"+fence(json(rules),"json")+"\n### Recorded target, headers and bounded response\n\n"+fence(json(Map.of("target",e.target(),"headers",e.responseHeaders(),"bodyTruncated",e.bodyTruncated(),"body",e.responseBody())),"json")+
                "\n## Interpretation\n\nThe root cause is not established by this report. Compare the recorded checks and linked contract rules before diagnosing the implementation. No model-generated cause is presented as a fact.\n\n## Limitations\n\n"+limitations.stream().map(l->"- "+l+"\n").collect(Collectors.joining())+
                "\n## Reproduction\n\n1. Restore the intended development fixture state.\n2. Review the recorded target, body and credential variable references below. Supply secrets privately.\n3. Save this script and run it with `sh`; compare its response with the saved assertions. HTTP errors alone do not make curl fail, because negative tests can expect them.\n\n"+fence(curl,"sh");
    }
    private static String fence(String text,String language) {
        String delimiter="~~~";while(text.contains(delimiter))delimiter+="~";
        return delimiter+language+"\n"+text+"\n"+delimiter+"\n";
    }
    public String json(Object value) {try{return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Could not serialize report",e);}}
    public String junit(RunReport report) {
        var e=report.execution();return junit("execution-"+e.id(),List.of(new CaseResult(e.scenarioIndex(),e.scenarioRevision(),e.passed()?"PASSED":"FAILED",e.id(),"Recorded response checks")),List.of(report));
    }
    public String junit(BatchReport report) {return junit("batch-"+report.id(),report.cases(),report.runs());}
    private String junit(String name,List<CaseResult> cases,List<RunReport> runs) {
        try {
            var out=new StringWriter();var xml=XMLOutputFactory.newFactory().createXMLStreamWriter(out);
            xml.writeStartDocument("UTF-8","1.0");xml.writeStartElement("testsuite");
            xml.writeAttribute("name",name);xml.writeAttribute("tests",String.valueOf(cases.size()));
            xml.writeAttribute("failures",String.valueOf(cases.stream().filter(c->c.state().equals("FAILED")).count()));
            xml.writeAttribute("errors",String.valueOf(cases.stream().filter(c->!Set.of("PASSED","FAILED").contains(c.state())).count()));
            xml.writeAttribute("time",seconds(runs.stream().mapToLong(r->r.execution().durationMs()).sum()));
            for(var c:cases) {
                var run=runs.stream().filter(r->r.execution().id().equals(c.executionId())).findFirst();
                xml.writeStartElement("testcase");xml.writeAttribute("name","case-"+(c.scenarioIndex()+1)+"-revision-"+c.scenarioRevision());xml.writeAttribute("classname",name);
                xml.writeAttribute("time",seconds(run.map(r->r.execution().durationMs()).orElse(0L)));
                if(!c.state().equals("PASSED")) {
                    xml.writeStartElement(c.state().equals("FAILED")?"failure":"error");xml.writeAttribute("type",c.state());xml.writeAttribute("message",clean(c.detail()));
                    xml.writeCharacters(clean(run.map(r->json(r.execution().assertionResults())).orElse("No complete result: "+c.state())));xml.writeEndElement();
                }
                if(run.isPresent()) {xml.writeStartElement("system-out");xml.writeCharacters(clean(run.get().markdown()));xml.writeEndElement();}
                xml.writeEndElement();
            }
            xml.writeEndElement();xml.writeEndDocument();xml.close();return out.toString();
        }catch(Exception e){throw new IllegalStateException("Could not create JUnit report",e);}
    }
    private static String seconds(long millis) {return BigDecimal.valueOf(millis,3).toPlainString();}
    private static String clean(String value) {var s=new StringBuilder();value.codePoints().forEach(c->s.appendCodePoint(c==9||c==10||c==13||c>=0x20&&c<=0xd7ff||c>=0xe000&&c<=0xfffd||c>=0x10000&&c<=0x10ffff?c:0xfffd));return s.toString();}
}
