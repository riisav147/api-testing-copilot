package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContractAdoptionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private WorkspaceStore store;private CopilotService service;private UUID project;private Specification oldSpec,newSpec;private Plan source;
    @BeforeEach void setup() throws Exception {
        store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");service=service();
        oldSpec=service.importSpecification(new ImportRequest("Original",OpenApiReaderTest.example(),null,null));project=service.projects().getFirst().id();source=service.generate(new GenerateRequest(oldSpec.id(),"POST /products"));
        newSpec=service.importSpecification(new ImportRequest("Changed minimum",OpenApiReaderTest.example().replace("minimum: 0","minimum: 1"),null,project));
    }
    private CopilotService service(){return new CopilotService(new OpenApiReader(),new DemoKnowledge(),new DemoGenerator(),new ScenarioValidator(Validation.buildDefaultValidatorFactory().getValidator()),new PlanEvaluator(),mock(SandboxRunner.class),new DemoWorkflow(),store);}
    @AfterEach void close(){store.close();}
    private AdoptPlanRequest request(){var rule=service.compareContracts(project,oldSpec.id(),newSpec.id(),source.id()).impacts().get(2).suggestions().getFirst();return new AdoptPlanRequest(UUID.randomUUID(),newSpec.id(),source.scenarioRevisions(),List.of(new AdoptionChoice(2,"ADOPT_INPUT",rule.ruleId())),true);}
    @Test void adoptionPersistsSnapshotsNewEvidenceAndUnchangedChecksWithoutApprovals() throws Exception {
        var c=source.scenarios().get(2);var checks=List.of(new ResponseAssertion(AssertionKind.JSON_GTE,"/price",mapper.valueToTree(0)));
        source=service.editScenario(source.id(),2,new EditScenarioRequest(1,c.query(),c.body(),201,checks));
        var before=new Execution(UUID.randomUUID(),source.id(),2,201,400,false,1,"{}",Instant.now(),2,source.scenarios().get(2));store.saveExecution(before);service=service();
        var r=request();var result=service.adoptPlan(source.id(),r);assertThat(result.specificationId()).isEqualTo(newSpec.id());assertThat(result.scenarioRevisions()).containsExactly(1);
        assertThat(result.scenarios().getFirst().body().path("price").asInt()).isEqualTo(1);assertThat(result.scenarios().getFirst().assertions()).isEqualTo(checks);assertThat(result.scenarios().getFirst().expectedStatus()).isEqualTo(201);
        assertThat(result.scenarios().getFirst().sourceIds()).allMatch(s->s.contains(newSpec.id().toString()));assertThat(service.plan(source.id()).scenarios().get(2).body().path("price").asInt()).isZero();
        assertThat(store.records("approvals",Approval.class)).isEmpty();assertThat(service.adoptPlan(source.id(),r)).isEqualTo(result);
        assertThatThrownBy(()->service.execute(new ExecuteRequest(result.id(),0,1,true,null,null))).hasMessageContaining("durable approval");
        var after=new Execution(UUID.randomUUID(),result.id(),0,201,201,true,1,"{}",Instant.now(),1,result.scenarios().getFirst());store.saveExecution(after);
        store.close();store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");service=service();
        var history=service.adoptionComparison(result.id());assertThat(history.before()).extracting(Execution::id).containsExactly(before.id());assertThat(history.after()).extracting(Execution::id).containsExactly(after.id());assertThat(history.adoption().cases().getFirst().sourceRevision()).isEqualTo(2);
        assertThat(history.adoption().cases().getFirst().sourceSnapshot().body().path("price").asInt()).isZero();
        service.deleteProject(project);assertThat(store.records("plan_adoptions",PlanAdoption.class)).isEmpty();
    }
    @Test void staleReviewsForgedProposalsAndDuplicateSelectionsPublishNothing() {
        var r=request();var c=source.scenarios().getFirst();service.editScenario(source.id(),0,new EditScenarioRequest(1,c.query(),c.body(),201,c.assertions()));
        assertThatThrownBy(()->service.adoptPlan(source.id(),r)).hasMessageContaining("changed");source=service.plan(source.id());
        var forged=new AdoptPlanRequest(UUID.randomUUID(),newSpec.id(),source.scenarioRevisions(),List.of(new AdoptionChoice(2,"ADOPT_INPUT","fake-rule")),true);
        assertThatThrownBy(()->service.adoptPlan(source.id(),forged)).hasMessageContaining("unavailable");
        var duplicate=new AdoptPlanRequest(UUID.randomUUID(),newSpec.id(),source.scenarioRevisions(),List.of(new AdoptionChoice(0,"KEEP",null),new AdoptionChoice(0,"KEEP",null)),true);
        assertThatThrownBy(()->service.adoptPlan(source.id(),duplicate)).hasMessageContaining("once");assertThat(store.records("plan_adoptions",PlanAdoption.class)).isEmpty();assertThat(service.workspace(project).plans()).hasSize(1);
    }
    @Test void removedExpectedStatusIsNotChangedToMakeCopyValid() throws Exception {
        var target=service.importSpecification(new ImportRequest("New success",OpenApiReaderTest.example().replace("'201':","'202':"),null,project));
        var r=new AdoptPlanRequest(UUID.randomUUID(),target.id(),source.scenarioRevisions(),List.of(new AdoptionChoice(0,"KEEP",null)),true);
        assertThatThrownBy(()->service.adoptPlan(source.id(),r)).hasMessageContaining("status is not explicitly documented");assertThat(store.adoption(r.id())).isNull();assertThat(source.scenarios().getFirst().expectedStatus()).isEqualTo(201);
    }
    @Test void conflictGateAndOwnershipAreStillEnforced() throws Exception {
        var conflict=service.importSpecification(new ImportRequest("Conflicting",OpenApiReaderTest.example(),"price > 0",project));
        assertThatThrownBy(()->service.adoptPlan(source.id(),new AdoptPlanRequest(UUID.randomUUID(),conflict.id(),source.scenarioRevisions(),List.of(new AdoptionChoice(0,"KEEP",null)),true))).hasMessageContaining("conflicting");
        var other=service.importSpecification(new ImportRequest("Other project",OpenApiReaderTest.example(),null,null));
        assertThatThrownBy(()->service.compareContracts(project,oldSpec.id(),other.id(),source.id())).hasMessageContaining("belong");
        assertThatThrownBy(()->service.adoptPlan(source.id(),new AdoptPlanRequest(UUID.randomUUID(),other.id(),source.scenarioRevisions(),List.of(new AdoptionChoice(0,"KEEP",null)),true))).hasMessageContaining("same project");
    }
    @Test void explicitKeepPreservesOldInputEvenForKnownImpactAndDuplicateIdIsBound() {
        var r=new AdoptPlanRequest(UUID.randomUUID(),newSpec.id(),source.scenarioRevisions(),List.of(new AdoptionChoice(2,"KEEP",null)),true);var p=service.adoptPlan(source.id(),r);
        assertThat(p.scenarios().getFirst().body().path("price").asInt()).isZero();
        assertThatThrownBy(()->service.adoptPlan(source.id(),new AdoptPlanRequest(r.id(),newSpec.id(),source.scenarioRevisions(),List.of(new AdoptionChoice(0,"KEEP",null)),true))).hasMessageContaining("different selection");
        var history=service.adoptionComparison(p.id());assertThat(history.adoption().cases().getFirst().action()).isEqualTo("KEEP");assertThat(history.after()).isEmpty();
    }
}
