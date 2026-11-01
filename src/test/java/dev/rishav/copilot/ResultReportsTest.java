package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import java.io.StringReader;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResultReportsTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private WorkspaceStore store;private CopilotService service;private SandboxRunner runner;private ResultReports reports;private Plan plan;
    private String response="{\"error\":\"price rejected\"}";
    @BeforeEach void setup() throws Exception {
        store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");runner=mock(SandboxRunner.class);
        when(runner.sandboxBase()).thenReturn("http://127.0.0.1:8080/sandbox");
        doAnswer(i->result(i.getArgument(0),i.getArgument(1))).when(runner).execute(any(),anyInt());
        doAnswer(i->result(i.getArgument(0),i.getArgument(1))).when(runner).executeAt(any(),anyInt(),any(),anyMap());
        service=service();var spec=service.importSpecification(new ImportRequest("Reports",OpenApiReaderTest.example(),null,null));
        plan=service.generate(new GenerateRequest(spec.id(),"POST /products"));reports=new ResultReports(store,service,runner,mapper);
    }
    private CopilotService service() {
        var s=new CopilotService(new OpenApiReader(),new DemoKnowledge(),new DemoGenerator(),new ScenarioValidator(Validation.buildDefaultValidatorFactory().getValidator()),new PlanEvaluator(),runner,new DemoWorkflow(),store);
        s.targets(new DevelopmentTargets("http://127.0.0.1:8081",mapper,r->"synthetic-secret-value"));return s;
    }
    @AfterEach void close(){store.close();}
    private Execution result(Plan p,int index) {
        var scenario=p.scenarios().get(index);int actual=index==2?400:scenario.expectedStatus();
        var checks=List.of(new AssertionResult("STATUS","HTTP status",actual==scenario.expectedStatus()?CheckOutcome.PASS:CheckOutcome.FAIL,mapper.valueToTree(scenario.expectedStatus()),mapper.valueToTree(actual),"Compared status"));
        return new Execution(UUID.randomUUID(),p.id(),index,scenario.expectedStatus(),actual,actual==scenario.expectedStatus(),12,response,Instant.now(),p.scenarioRevisions().get(index),scenario,Map.of("content-type",List.of("application/json")),false,checks);
    }
    private Execution execute(int index,UUID env) {
        var approval=service.approve(new ApprovalRequest(plan.id(),index,plan.scenarioRevisions().get(index),env,true));
        return service.execute(new ExecuteRequest(plan.id(),index,plan.scenarioRevisions().get(index),true,env,approval.id()));
    }
    @Test void reportUsesHistoricalSnapshotAndRelatedSourceRuleAfterEditing() {
        var e=execute(2,null);var old=plan.scenarios().get(2);
        service.editScenario(plan.id(),2,new EditScenarioRequest(1,old.query(),mapper.createObjectNode().put("name","New").put("price",99),201,old.assertions()));
        var report=reports.execution(e.id());
        assertThat(report.execution().scenarioRevision()).isEqualTo(1);assertThat(report.execution().requestSnapshot().body().path("price").asInt()).isZero();
        assertThat(report.relatedRules()).anySatisfy(r->assertThat(r.sourcePointer()).endsWith("/price/minimum"));
        assertThat(report.markdown()).contains("Expected HTTP 201; observed HTTP 400","root cause is not established","case 3 · revision 1");
        assertThat(report.curl()).contains("http://127.0.0.1:8080/sandbox/products","--data-binary @-");
    }
    @Test void curlEscapesRequestTextAndExpandsCredentialsWithoutEvaluatingTheirContents() throws Exception {
        String malicious="O'Reilly $(touch "+directory.resolve("injected")+") `id` \\ ~~~";
        var c=plan.scenarios().getFirst();plan=service.editScenario(plan.id(),0,new EditScenarioRequest(1,c.query(),mapper.createObjectNode().put("name",malicious).put("price",79),201,c.assertions()));
        var project=service.projects().getFirst().id();var env=service.saveEnvironment(project,null,new EnvironmentRequest("Old environment","http://127.0.0.1:8081","BEARER",null,"OLD_TOKEN",Map.of("X-Fixture","FIXTURE_REF"),true,0));
        response="{\"echo\":\"synthetic-secret-value\"}";var e=execute(0,env.id());
        service.saveEnvironment(project,env.id(),new EnvironmentRequest("New configuration","http://127.0.0.1:8081","API_KEY","X-Key","NEW_TOKEN",Map.of(),true,1));
        store.close();store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");service=service();reports=new ResultReports(store,service,runner,mapper);
        var report=reports.execution(e.id());assertThat(reports.json(report)).doesNotContain("synthetic-secret-value","NEW_TOKEN").contains("OLD_TOKEN","[REDACTED]");
        assertThat(report.context().environment().revision()).isEqualTo(1);
        Path bin=Files.createDirectory(directory.resolve("bin"));Path curl=bin.resolve("curl");
        Files.writeString(curl,"#!/bin/sh\nprintf '%s\\0' \"$@\" > \"$CAPTURE\"\ncat > \"$BODY_CAPTURE\"\n");assertThat(curl.toFile().setExecutable(true)).isTrue();
        Path script=directory.resolve("reproduce.sh");Files.writeString(script,report.curl());
        var process=new ProcessBuilder("/bin/sh",script.toString());var envVars=process.environment();envVars.put("PATH",bin+":/usr/bin:/bin");envVars.put("CAPTURE",directory.resolve("args").toString());envVars.put("BODY_CAPTURE",directory.resolve("body").toString());envVars.put("OLD_TOKEN","$(touch "+directory.resolve("secret-injected")+")");envVars.put("FIXTURE_REF","header 'quoted'");
        assertThat(process.start().waitFor()).isZero();
        assertThat(Files.exists(directory.resolve("injected"))).isFalse();assertThat(Files.exists(directory.resolve("secret-injected"))).isFalse();
        assertThat(mapper.readTree(Files.readString(directory.resolve("body"))).path("name").asText()).isEqualTo(malicious);
        assertThat(Files.readString(directory.resolve("args"))).contains("Authorization: Bearer $(touch","X-Fixture: header 'quoted'");
        assertThat(ResultReports.quote("a'$(id)")).isEqualTo("'a'\"'\"'$(id)'");
    }
    @Test void incompleteAndFailedCasesProduceParseableNonpassingJunit() throws Exception {
        response="<xml>&\"'\u0001\n~~~";var e=execute(2,null);var report=reports.execution(e.id());
        var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var document=factory.newDocumentBuilder().parse(new InputSource(new StringReader(reports.junit(report))));
        assertThat(document.getDocumentElement().getAttribute("failures")).isEqualTo("1");assertThat(document.getElementsByTagName("failure").getLength()).isEqualTo(1);
        var job=service.createBatch(new BatchRequest(UUID.randomUUID(),plan.id(),null,List.of(new BatchSelection(0,1),new BatchSelection(1,1)),1,false,true));
        var cases=List.of(new BatchCase(0,1,job.cases().get(0).approvalId(),job.cases().get(0).snapshot(),"UNKNOWN",null,"Uncertain"),new BatchCase(1,1,job.cases().get(1).approvalId(),job.cases().get(1).snapshot(),"CANCELLED",null,"Cancelled"));
        var incomplete=new BatchJob(job.id(),job.projectId(),job.planId(),null,job.target(),null,1,false,cases,"INTERRUPTED",true,job.createdAt(),job.updatedAt());
        var exported=reports.batch(incomplete);assertThat(exported.success()).isFalse();assertThat(exported.runs()).isEmpty();
        var xml=factory.newDocumentBuilder().parse(new InputSource(new StringReader(reports.junit(exported))));assertThat(xml.getDocumentElement().getAttribute("errors")).isEqualTo("2");
    }
    @Test void targetRevisionRaceIsRejectedBeforeAnyApprovalsOrJobAreSaved() {
        var project=service.projects().getFirst().id();var env=service.saveEnvironment(project,null,new EnvironmentRequest("Development","http://127.0.0.1:8081","NONE",null,null,Map.of(),true,0));
        var request=new BatchRequest(UUID.randomUUID(),plan.id(),env.id(),List.of(new BatchSelection(0,1)),1,false,true,env.revision(),env.baseUrl());
        service.saveEnvironment(project,env.id(),new EnvironmentRequest("Changed","http://127.0.0.1:8081","NONE",null,null,Map.of(),true,1));
        assertThatThrownBy(()->service.createBatch(request)).hasMessageContaining("Target configuration changed");
        assertThat(store.records("approvals",Approval.class)).isEmpty();assertThat(store.batch(request.id())).isNull();
    }
    @Test void redactedRequestsCannotExecuteTheExportedScriptWithoutManualRepair() throws Exception {
        var c=plan.scenarios().getFirst();plan=service.editScenario(plan.id(),0,new EditScenarioRequest(1,c.query(),mapper.createObjectNode().put("name","[REDACTED]").put("price",1),201,c.assertions()));
        var report=reports.execution(execute(0,null).id());assertThat(report.limitations()).anyMatch(s->s.contains("redacted"));
        var script=directory.resolve("blocked.sh");Files.writeString(script,report.curl());assertThat(new ProcessBuilder("/bin/sh",script.toString()).start().waitFor()).isEqualTo(2);
    }
    @Test void queryValuesAreEncodedAndCurlGlobbingIsDisabled() throws Exception {
        String content=OpenApiReaderTest.example().replace("summary: List products","summary: List products\n      parameters:\n        - in: query\n          name: q\n          schema: {type: string}");
        var spec=service.importSpecification(new ImportRequest("Query API",content,null,null));plan=service.generate(new GenerateRequest(spec.id(),"GET /products"));
        var c=plan.scenarios().getFirst();String value="a'$(id)&[] space";plan=service.editScenario(plan.id(),0,new EditScenarioRequest(1,Map.of("q",value),c.body(),200,c.assertions()));
        var report=reports.execution(execute(0,null).id());
        assertThat(report.curl()).contains("--globoff","--url 'http://127.0.0.1:8080/sandbox/products?q="+java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8)+"'").doesNotContain("?q="+value);
    }
    @Test void legacyAuthenticationIsNotGuessedAndTruncationIsVisible() throws Exception {
        var base=result(plan,2);var e=new Execution(UUID.randomUUID(),base.planId(),base.scenarioIndex(),base.expectedStatus(),base.actualStatus(),false,12,"partial<xml>",Instant.now(),1,base.requestSnapshot(),base.responseHeaders(),true,base.assertionResults(),"http://127.0.0.1:8081");
        store.saveExecution(e);service=service();reports=new ResultReports(store,service,runner,mapper);var report=reports.execution(e.id());
        assertThat(report.context()).isNull();assertThat(report.limitations()).anyMatch(l->l.contains("authentication configuration is unavailable")).anyMatch(l->l.contains("truncated"));
        var script=directory.resolve("legacy.sh");Files.writeString(script,report.curl());assertThat(new ProcessBuilder("/bin/sh",script.toString()).start().waitFor()).isEqualTo(2);
    }

}
