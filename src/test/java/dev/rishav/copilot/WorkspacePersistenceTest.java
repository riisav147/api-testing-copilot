package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import jakarta.validation.Validation;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspacePersistenceTest {
    @TempDir Path directory;
    private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    private CopilotService service(WorkspaceStore store,Knowledge knowledge) {
        return new CopilotService(new OpenApiReader(),knowledge,new DemoGenerator(),
                new ScenarioValidator(Validation.buildDefaultValidatorFactory().getValidator()),new PlanEvaluator(),mock(SandboxRunner.class),new DemoWorkflow(),store);
    }
    @Test void databaseReopenRestoresVersionsRevisionsRunsAndAnalysisThenCascadesDeletion() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("workspace");
        UUID projectId,planId,executionId;
        try(var store=new WorkspaceStore(json,url,"sa","")) {
            var service=service(store,new DemoKnowledge());
            var spec=service.importSpecification(new ImportRequest("Catalog",OpenApiReaderTest.example(),"saved business context",null));
            projectId=service.projects().getFirst().id();
            service.importSpecification(new ImportRequest("Catalog v2",OpenApiReaderTest.example(),"changed context",projectId));
            var plan=service.generate(new GenerateRequest(spec.id(),"POST /products"));planId=plan.id();
            var scenario=plan.scenarios().getFirst();
            service.editScenario(planId,0,new EditScenarioRequest(1,scenario.query(),scenario.body(),400,List.of()));
            var execution=new Execution(UUID.randomUUID(),planId,0,201,201,true,10,"{}",Instant.now(),1,scenario);
            executionId=execution.id();store.saveExecution(execution);store.saveAnalysis(executionId,new Analysis("demo","saved explanation"));
            service.rename(projectId,"Renamed catalog");
        }
        try(var store=new WorkspaceStore(json,url,"sa","")) {
            var knowledge=new DemoKnowledge();var service=service(store,knowledge);
            var workspace=service.workspace(projectId);
            assertThat(workspace.project().name()).isEqualTo("Renamed catalog");
            assertThat(workspace.specifications()).extracting(SavedSpecification::version).containsExactly(1,2);
            assertThat(workspace.revisions()).hasSize(2);
            assertThat(service.plan(planId).scenarioRevisions().getFirst()).isEqualTo(2);
            assertThat(workspace.executions().getFirst().scenarioRevision()).isEqualTo(1);
            assertThat(workspace.executions().getFirst().requestSnapshot().expectedStatus()).isEqualTo(201);
            assertThat(workspace.analyses().get(executionId).explanation()).isEqualTo("saved explanation");
            var spec=workspace.specifications().getFirst().specification();
            assertThat(knowledge.retrieve(spec.id(),spec.endpoints().getFirst())).isNotEmpty();
            // Restored imports can generate again without a provider call in demo mode.
            assertThat(service.generate(new GenerateRequest(spec.id(),"POST /products")).scenarios()).isNotEmpty();
            service.deleteProject(projectId);
            assertThat(store.projects()).isEmpty();assertThat(store.specifications()).isEmpty();
            assertThat(store.records("plans",Plan.class)).isEmpty();assertThat(store.records("plan_revisions",Plan.class)).isEmpty();
            assertThat(store.records("executions",Execution.class)).isEmpty();assertThat(store.analyses()).isEmpty();
            assertThat(knowledge.retrieve(spec.id(),spec.endpoints().getFirst())).isEmpty();
        }
    }
    @Test void evidenceCleanupFailureLeavesRetryableDeletionAcrossRestart() throws Exception {
        String url="jdbc:h2:file:"+directory.resolve("cleanup");UUID id;
        try(var store=new WorkspaceStore(json,url,"sa","")) {
            var knowledge=mock(Knowledge.class);var service=service(store,knowledge);
            service.importSpecification(new ImportRequest("Delete me",OpenApiReaderTest.example(),null,null));id=service.projects().getFirst().id();
            doThrow(new IllegalStateException("offline")).when(knowledge).delete(any());
            assertThatThrownBy(()->service.deleteProject(id)).isInstanceOf(IllegalStateException.class);
            assertThat(store.projects().getFirst().deleting()).isTrue();
            assertThatThrownBy(()->service.workspace(id)).isInstanceOf(ApiException.class);
            assertThat(store.specifications()).hasSize(1);
        }
        try(var store=new WorkspaceStore(json,url,"sa","")) {
            var service=service(store,new DemoKnowledge());service.deleteProject(id);assertThat(store.projects()).isEmpty();
        }
    }
}
