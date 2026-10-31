package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DevelopmentExecutionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private HttpServer server;
    private WorkspaceStore store;
    private CopilotService service;
    private String origin;
    private final String token="synthetic-test-secret-123";
    private final AtomicInteger writes=new AtomicInteger();
    private boolean disconnect,redirect;
    private Plan plan;private UUID project;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/products",exchange->{
            writes.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer "+token);
            exchange.getRequestBody().readAllBytes();
            if(disconnect){exchange.close();return;}
            if(redirect){exchange.getResponseHeaders().set("Location","http://169.254.169.254/latest/meta-data/");exchange.sendResponseHeaders(302,-1);exchange.close();return;}
            byte[] response=mapper.writeValueAsBytes(Map.of("price",5,"echo",token));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Set-Cookie","session="+token);
            exchange.sendResponseHeaders(201,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();origin="http://127.0.0.1:"+server.getAddress().getPort();
        store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");
        service=service();
        var spec=service.importSpecification(new ImportRequest("Real local API",OpenApiReaderTest.example(),null,null));project=service.projects().getFirst().id();
        plan=service.generate(new GenerateRequest(spec.id(),"POST /products"));
    }
    private CopilotService service() {
        var runner=new SandboxRunner(mock(ServletWebServerApplicationContext.class),mapper);
        var s=new CopilotService(new OpenApiReader(),new DemoKnowledge(),new DemoGenerator(),new ScenarioValidator(Validation.buildDefaultValidatorFactory().getValidator()),new PlanEvaluator(),runner,new DemoWorkflow(),store);
        s.targets(new DevelopmentTargets(origin,mapper,ref->ref.equals("DEV_TOKEN")?token:null));return s;
    }
    @AfterEach void close() { server.stop(0);store.close(); }
    private TargetEnvironment env() { return service.saveEnvironment(project,null,new EnvironmentRequest("Local development",origin,"BEARER",null,"DEV_TOKEN",Map.of(),true,0)); }
    private Approval approve(TargetEnvironment e) { return service.approve(new ApprovalRequest(plan.id(),0,1,e.id(),true)); }
    private ExecuteRequest request(TargetEnvironment e,Approval a) { return new ExecuteRequest(plan.id(),0,1,true,e.id(),a.id()); }
    @Test void sendsApprovedCredentialAndMasksResultsWhileDuplicateSubmissionsExecuteOnceAcrossReopen() throws Exception {
        var e=env();var a=approve(e);var r=request(e,a);var result=service.execute(r);
        assertThat(result.passed()).isTrue();assertThat(result.target()).isEqualTo(origin);
        assertThat(mapper.writeValueAsString(result)).doesNotContain(token).contains("[REDACTED]");
        assertThat(service.execute(r).id()).isEqualTo(result.id());assertThat(writes.get()).isEqualTo(1);
        store.close();store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");
        service=service();assertThat(service.execute(r).id()).isEqualTo(result.id());assertThat(writes.get()).isEqualTo(1);
        assertThat(service.attempt(a.id()).state()).isEqualTo("COMPLETED");
        assertThat(mapper.writeValueAsString(service.workspace(project))).doesNotContain(token);
    }
    @Test void environmentEditExpiryAndRequestEditInvalidateUnusedApprovals() {
        var e=env();var a=approve(e);
        service.saveEnvironment(project,e.id(),new EnvironmentRequest("Changed",origin,"BEARER",null,"DEV_TOKEN",Map.of(),true,1));
        assertThatThrownBy(()->service.execute(new ExecuteRequest(plan.id(),0,1,true,a.environmentId(),a.id()))).hasMessageContaining("environment changed");
        e=service.environments(project).getFirst();var current=e;
        var expired=new Approval(UUID.randomUUID(),plan.id(),0,1,e.id(),"unused",e.revision(),Instant.now().minusSeconds(1),origin);store.saveApproval(expired);
        assertThatThrownBy(()->service.execute(request(current,expired))).hasMessageContaining("expired");
        var fresh=approve(e);var c=plan.scenarios().getFirst();service.editScenario(plan.id(),0,new EditScenarioRequest(1,c.query(),c.body(),201,c.assertions()));
        assertThatThrownBy(()->service.execute(request(current,fresh))).hasMessageContaining("scenario changed");
        assertThat(writes.get()).isZero();
    }
    @Test void uncertainAndInterruptedAttemptsNeverAutomaticallyRepeatWrites() {
        var e=env();var a=approve(e);disconnect=true;
        assertThatThrownBy(()->service.execute(request(e,a))).hasMessageContaining("uncertain");
        int attempts=writes.get();assertThat(attempts).isEqualTo(1);
        service=service();assertThatThrownBy(()->service.execute(request(e,a))).hasMessageContaining("uncertain");assertThat(writes.get()).isEqualTo(attempts);
        var interrupted=approve(e);store.startAttempt(interrupted.id());service=service();
        assertThat(service.attempt(interrupted.id()).state()).isEqualTo("UNKNOWN");assertThatThrownBy(()->service.execute(request(e,interrupted))).hasMessageContaining("uncertain");
    }
    @Test void targetOwnershipMissingCredentialsAndCredentialRotationAreEnforced() throws Exception {
        var e=env();
        var other=service.importSpecification(new ImportRequest("Other project",OpenApiReaderTest.example(),null,null));
        var p=service.generate(new GenerateRequest(other.id(),"POST /products"));
        assertThatThrownBy(()->service.approve(new ApprovalRequest(p.id(),0,1,e.id(),true))).hasMessageContaining("belong");
        var a=approve(e);
        service.targets(new DevelopmentTargets(origin,mapper,ref->"rotated-test-token"));
        assertThatThrownBy(()->service.execute(request(e,a))).hasMessageContaining("credential configuration changed");
        service.targets(new DevelopmentTargets(origin,mapper,ref->null));
        assertThatThrownBy(()->approve(e)).hasMessageContaining("missing");
        assertThat(writes.get()).isZero();
    }
    @Test void disallowsUnapprovedDestinationsAndDoesNotFollowRedirects() {
        var targets=new DevelopmentTargets(origin,mapper,ref->token);
        for(String url:List.of("http://localhost:8081","http://169.254.169.254:8081","http://127.0.0.1:1","https://127.0.0.1:8081","http://user:pass@127.0.0.1:8081",origin+"/../x",origin+"?token=x"))assertThatThrownBy(()->targets.base(url)).isInstanceOf(ApiException.class);
        var e=env();redirect=true;var result=service.execute(request(e,approve(e)));assertThat(result.actualStatus()).isEqualTo(302);assertThat(writes.get()).isEqualTo(1);
    }
    @Test void numericCredentialReflectionsAreMaskedWithoutRetainingTheirRawBody() throws Exception {
        var targets=new DevelopmentTargets(origin,mapper,ref->"12345678");
        var e=new TargetEnvironment(UUID.randomUUID(),project,"Numeric token",origin,1,"BEARER",null,"DEV_TOKEN",Map.of());
        var raw=new Execution(UUID.randomUUID(),plan.id(),0,201,201,true,1,"{\"echo\":12345678}",Instant.now(),1,plan.scenarios().getFirst());
        assertThat(mapper.writeValueAsString(targets.redact(raw,e))).doesNotContain("12345678").contains("REDACTED");
    }

}
