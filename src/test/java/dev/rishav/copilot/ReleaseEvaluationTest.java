package dev.rishav.copilot;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fixed independent fixture oracles; measured generation gaps are reported, never repaired by the benchmark. */
class ReleaseEvaluationTest {
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @Test void measureGeneratedTestsAgainstHealthyFixturesAndKnownMutants() throws Exception {
        var corpus=json.readTree(getClass().getResourceAsStream("/evaluation/corpus.json"));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var mutant=new AtomicBoolean();
        for(var fixture:corpus.path("fixtures"))server.createContext("/fixture/"+fixture.path("id").asText(),e->respond(e,fixture.path("id").asText(),mutant.get()));
        server.start();String origin="http://127.0.0.1:"+server.getAddress().getPort();
        var results=new ArrayList<Map<String,Object>>();int valid=0,totalCases=0,healthyAgreement=0,detected=0,represented=0,behaviorCount=0,supportedRepresented=0,supportedCount=0;
        try(var store=new WorkspaceStore(json,"jdbc:h2:mem:evaluation-"+UUID.randomUUID(),"sa","")) {
            var service=new CopilotService(new OpenApiReader(),new DemoKnowledge(),new DemoGenerator(),new ScenarioValidator(Validation.buildDefaultValidatorFactory().getValidator()),new PlanEvaluator(),new SandboxRunner(mock(ServletWebServerApplicationContext.class),json),new DemoWorkflow(),store);
            service.targets(new DevelopmentTargets(origin,json,r->null));
            for(var fixture:corpus.path("fixtures")) {
                String id=fixture.path("id").asText();var spec=service.importSpecification(new ImportRequest("Synthetic "+id,contract(fixture),null,null));
                String operation=fixture.path("method").asText()+" "+fixture.path("path").asText();long start=System.nanoTime();
                var generated=service.generate(new GenerateRequest(spec.id(),operation));valid++;
                var plan=service.fillMissing(generated.id(),new FillMissingRequest(generated.scenarioRevisions()));long generationMillis=(System.nanoTime()-start)/1_000_000;
                var workspace=service.specificationWorkspace(spec.id());var env=service.saveEnvironment(workspace.project().id(),null,new EnvironmentRequest("Synthetic fixture",origin+"/fixture/"+id,"NONE",null,null,Map.of(),true,0));
                var coverage=service.coverage(spec.id(),operation,plan.id());var covered=new HashSet<String>();
                for(var row:coverage.rules())if(!row.scenarioIndexes().isEmpty())covered.add(row.rule().field()+":"+row.rule().probe());
                supportedCount+=coverage.rules().size();supportedRepresented+=covered.size();
                for(var c:plan.scenarios()) {if(c.assertions().stream().anyMatch(a->a.kind()==AssertionKind.JSON_GTE&&a.target().equals("/price")&&a.expected().isNumber()&&a.expected().asDouble()>=0))covered.add("RESPONSE_PRICE_NONNEGATIVE");if(c.path().endsWith("999999"))covered.add("RESOURCE_NOT_FOUND");if(c.query().isEmpty())covered.add("QUERY_MISSING");}
                // QUERY_MISSING counts only GET query fixtures, never an unrelated empty POST query.
                if(!id.equals("query"))covered.remove("QUERY_MISSING");
                int found=0;for(var expected:fixture.path("behaviors"))if(covered.contains(expected.asText()))found++;
                represented+=found;behaviorCount+=fixture.path("behaviors").size();
                int healthy=0,useful=0;var observations=new ArrayList<Map<String,Object>>();
                for(int i=0;i<plan.scenarios().size();i++) {
                    mutant.set(false);var baseline=execute(service,plan,i,env.id());
                    mutant.set(true);var changed=execute(service,plan,i,env.id());
                    if(baseline.passed())healthy++;if(baseline.passed()&&!changed.passed())useful++;
                    observations.add(Map.of("case",i,"request",plan.scenarios().get(i),"healthyPassed",baseline.passed(),"healthyStatus",baseline.actualStatus(),"mutantPassed",changed.passed(),"mutantStatus",changed.actualStatus(),"healthyMs",baseline.durationMs(),"mutantMs",changed.durationMs()));
                }
                totalCases+=plan.scenarios().size();healthyAgreement+=healthy;if(useful>0)detected++;
                results.add(Map.of("fixture",id,"knownDefect",fixture.path("defect").asText(),"generatedCases",generated.scenarios().size(),"afterRuleFill",plan.scenarios().size(),"healthyAgreement",healthy,"defectDetected",useful>0,"representedBehaviors",found,"expectedBehaviors",fixture.path("behaviors").size(),"generationAndFillMs",generationMillis,"observations",observations));
            }
        } finally {server.stop(0);}
        var report=new LinkedHashMap<String,Object>();report.put("schemaVersion","1");report.put("recordedAt",Instant.now());report.put("corpusVersion",corpus.path("version").asText());report.put("reviewStatus",corpus.path("reviewStatus").asText());
        report.put("configuration",Map.of("mode","demo","generator","DemoGenerator plus explicit fillMissing","model","none","providerRequests",0,"providerCostUsd",0,"liveAiVerification","NOT_RUN; API credit and a separately reviewed live protocol are required","java",System.getProperty("java.version")));
        report.put("measurements",Map.ofEntries(Map.entry("operations",results.size()),Map.entry("structurallyAccepted",valid),Map.entry("cases",totalCases),Map.entry("healthyFixtureAgreement",healthyAgreement),Map.entry("misleadingFailuresOnHealthy",totalCases-healthyAgreement),Map.entry("knownDefects",results.size()),Map.entry("detectedDefects",detected),Map.entry("expectedBehaviors",behaviorCount),Map.entry("representedBehaviors",represented),Map.entry("supportedProbes",supportedCount),Map.entry("representedSupportedProbes",supportedRepresented)));
        report.put("fixtures",results);report.put("limitations",List.of("Synthetic authored oracles, not independently human signed off","Known query and response-field gaps are retained","No AI quality, provider latency/cost or real-world coverage inference","One generated plan per fixture; no statistical confidence estimate"));
        Path output=Path.of("target/evaluation/demo-results.json");Files.createDirectories(output.getParent());json.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),report);
        assertThat(results).hasSize(10);assertThat(valid).isEqualTo(10);assertThat(healthyAgreement).isEqualTo(totalCases);
        assertThat(results).filteredOn(r->r.get("fixture").equals("query")||r.get("fixture").equals("response")).allSatisfy(r->assertThat(r.get("defectDetected")).isEqualTo(false));
        assertThat(detected).isEqualTo(8);assertThat(supportedRepresented).isEqualTo(supportedCount);
    }
    private Execution execute(CopilotService service,Plan p,int i,UUID env) {var a=service.approve(new ApprovalRequest(p.id(),i,1,env,true));return service.execute(new ExecuteRequest(p.id(),i,1,true,env,a.id()));}
    private String contract(JsonNode f) {
        var root=json.createObjectNode().put("openapi","3.0.3");var operation=root.putObject("paths").putObject(f.path("path").asText()).putObject(f.path("method").asText().toLowerCase(Locale.ROOT));
        var responses=operation.putObject("responses");responses.putObject(f.path("method").asText().equals("GET")?"200":"201").put("description","Documented success");responses.putObject("400").put("description","Invalid request");
        if(f.path("id").asText().equals("resource"))responses.putObject("404").put("description","Absent fixture");
        if(f.path("id").asText().equals("query")){var p=operation.putArray("parameters").addObject().put("in","query").put("name","limit").put("required",true);p.putObject("schema").put("type","integer").put("minimum",1);}
        if(f.has("schema"))operation.putObject("requestBody").putObject("content").putObject("application/json").set("schema",f.get("schema"));
        return root.toString();
    }
    private boolean validLimit(String query) {
        try {for(String pair:Objects.toString(query,"").split("&")){var parts=pair.split("=",2);if(parts.length==2&&parts[0].equals("limit"))return Integer.parseInt(parts[1])>=1;}}catch(NumberFormatException ignored){}return false;
    }
    // Independent, explicitly authored oracle constants. Do not read schema bounds to determine fixture behavior.
    private void respond(HttpExchange e,String fixture,boolean mutant) throws java.io.IOException {
        byte[] input=e.getRequestBody().readAllBytes();JsonNode body=input.length==0?json.createObjectNode():json.readTree(input);var qty=body.path("qty");var code=body.path("code");
        boolean valid=switch(fixture) {
            case "minimum","response" -> qty.isIntegralNumber()&&(mutant&&fixture.equals("minimum")?qty.asInt()>0:qty.asInt()>=0);
            case "maximum" -> qty.isIntegralNumber()&&(mutant||qty.asInt()<=5);
            case "minLength" -> code.isTextual()&&code.asText().length()>=(mutant?1:2);
            case "maxLength" -> code.isTextual()&&(mutant||code.asText().length()<=4);
            case "enum" -> code.isTextual()&&(mutant||Set.of("a","b").contains(code.asText()));
            case "required" -> mutant||code.isTextual();
            case "combined" -> qty.isIntegralNumber()&&(mutant?qty.asInt()>0:qty.asInt()>=0)&&qty.asInt()<=3;
            case "query" -> mutant||validLimit(e.getRequestURI().getQuery());
            default -> true;
        };
        int status=valid?(e.getRequestMethod().equals("GET")?200:201):400;
        if(fixture.equals("resource")&&!mutant&&!e.getRequestURI().getPath().endsWith("/1"))status=404;
        byte[] output=json.writeValueAsBytes(Map.of("price",mutant&&fixture.equals("response")?-1:3));e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(status,output.length);e.getResponseBody().write(output);e.close();
    }
}
