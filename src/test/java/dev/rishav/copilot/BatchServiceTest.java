package dev.rishav.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import static dev.rishav.copilot.Models.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BatchServiceTest {
    @TempDir Path directory;
    private final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    private WorkspaceStore store;
    private CopilotService service;
    private BatchService batches;
    private SandboxRunner runner;
    private Plan plan;
    private UUID project;
    private final AtomicInteger calls=new AtomicInteger();
    private CountDownLatch release;
    @BeforeEach void setup() throws Exception {
        store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");
        runner=mock(SandboxRunner.class);
        doAnswer(invocation->{calls.incrementAndGet();return result(invocation.getArgument(0),invocation.getArgument(1));}).when(runner).execute(any(),anyInt());
        service=service();batches=new BatchService(store,service);
        var spec=service.importSpecification(new ImportRequest("Batches",OpenApiReaderTest.example(),null,null));
        project=service.projects().getFirst().id();plan=service.generate(new GenerateRequest(spec.id(),"POST /products"));
    }
    private CopilotService service() {
        return new CopilotService(new OpenApiReader(),new DemoKnowledge(),new DemoGenerator(),new ScenarioValidator(Validation.buildDefaultValidatorFactory().getValidator()),new PlanEvaluator(),runner,new DemoWorkflow(),store);
    }
    private Execution result(Plan p,int index) {
        var c=p.scenarios().get(index);
        return new Execution(UUID.randomUUID(),p.id(),index,c.expectedStatus(),index==2?400:c.expectedStatus(),index!=2,3,"{}",Instant.now(),p.scenarioRevisions().get(index),c);
    }
    @AfterEach void close() {if(release!=null)release.countDown();batches.close();store.close();}
    private BatchRequest request(int parallelism,boolean independent,int... indices) {
        return new BatchRequest(UUID.randomUUID(),plan.id(),null,Arrays.stream(indices).mapToObj(i->new BatchSelection(i,plan.scenarioRevisions().get(i))).toList(),parallelism,independent,true);
    }
    private BatchJob await(UUID id,Predicate<BatchJob> condition) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        BatchJob job;
        do {job=batches.get(id);if(condition.test(job))return job;Thread.sleep(10);}while(System.nanoTime()<end);
        throw new AssertionError("Batch state did not converge: "+job);
    }
    private BatchJob finished(UUID id) throws Exception {return await(id,b->!Set.of("QUEUED","RUNNING","CANCELLING").contains(b.state()));}
    private CountDownLatch holdFirst() {
        var entered=new CountDownLatch(1);release=new CountDownLatch(1);
        doAnswer(invocation->{
            calls.incrementAndGet();entered.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();
            return result(invocation.getArgument(0),invocation.getArgument(1));
        }).when(runner).execute(any(),anyInt());return entered;
    }
    @Test void mixedResultsPersistAndDuplicateSubmissionNeverRunsAgain() throws Exception {
        var r=request(1,false,0,1,2);var job=finished(batches.submit(r).id());
        assertThat(job.state()).isEqualTo("COMPLETED");
        assertThat(job.cases()).extracting(BatchCase::state).containsExactly("PASSED","PASSED","FAILED");
        assertThat(job.cases()).allSatisfy(c->{var e=store.execution(c.executionId());assertThat(e.requestSnapshot()).isEqualTo(c.snapshot());assertThat(e.scenarioRevision()).isEqualTo(c.scenarioRevision());});
        assertThat(batches.submit(r)).isEqualTo(job);assertThat(calls.get()).isEqualTo(3);
        assertThatThrownBy(()->batches.submit(new BatchRequest(r.id(),plan.id(),null,List.of(new BatchSelection(0,1)),1,false,true))).hasMessageContaining("different approved selection");
        batches.close();store.close();store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");
        service=service();batches=new BatchService(store,service);
        assertThat(batches.submit(r)).isEqualTo(job);assertThat(calls.get()).isEqualTo(3);
        assertThat(batches.list(project)).hasSize(1);
        // A deliberate rerun uses a fresh job and approval, never replays the original batch.
        var rerun=finished(batches.submit(request(1,false,2)).id());
        assertThat(rerun.cases().getFirst().approvalId()).isNotEqualTo(job.cases().get(2).approvalId());assertThat(calls.get()).isEqualTo(4);
    }
    @Test void cancellationStopsQueuedWritesAndDoesNotHoldTheServiceLock() throws Exception {
        var entered=holdFirst();var job=batches.submit(request(1,false,0,1,2));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),()->{
            assertThat(batches.get(job.id()).cases().getFirst().state()).isEqualTo("RUNNING");
            var c=plan.scenarios().get(1);service.editScenario(plan.id(),1,new EditScenarioRequest(1,c.query(),c.body(),c.expectedStatus(),c.assertions()));
            assertThatThrownBy(()->service.deleteProject(project)).hasMessageContaining("active executions");
        });
        var cancelled=batches.cancel(job.id());assertThat(cancelled.state()).isEqualTo("CANCELLING");
        assertThat(cancelled.cases()).extracting(BatchCase::state).containsExactly("RUNNING","CANCELLED","CANCELLED");
        release.countDown();var done=finished(job.id());assertThat(done.state()).isEqualTo("CANCELLED");
        assertThat(done.cases()).extracting(BatchCase::state).containsExactly("PASSED","CANCELLED","CANCELLED");assertThat(calls.get()).isEqualTo(1);
        var c=done.cases().get(2);
        assertThatThrownBy(()->service.execute(new ExecuteRequest(plan.id(),2,1,true,null,c.approvalId()))).hasMessageContaining("cancelled");
        service.deleteProject(project);assertThat(store.batch(job.id())).isNull();
    }
    @Test void editingAQueuedRevisionBlocksItWithoutSendingAnAlteredRequest() throws Exception {
        var entered=holdFirst();var job=batches.submit(request(1,false,0,1));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        var original=plan.scenarios().get(1);service.editScenario(plan.id(),1,new EditScenarioRequest(1,original.query(),original.body(),original.expectedStatus(),original.assertions()));
        release.countDown();var done=finished(job.id());
        assertThat(done.cases()).extracting(BatchCase::state).containsExactly("PASSED","BLOCKED");
        assertThat(done.cases().get(1).snapshot()).isEqualTo(original);assertThat(calls.get()).isEqualTo(1);
    }
    @Test void transportUncertaintyIsRecordedAndNeverRetried() throws Exception {
        doAnswer(invocation->{calls.incrementAndGet();throw new IllegalStateException("transport failed");}).when(runner).execute(any(),anyInt());
        var r=request(1,false,0);var done=finished(batches.submit(r).id());
        assertThat(done.cases().getFirst().state()).isEqualTo("UNKNOWN");assertThat(done.cases().getFirst().executionId()).isNull();
        batches.submit(r);assertThat(calls.get()).isEqualTo(1);
    }
    @Test void parallelismRequiresIndependentReadsAndHasTwoGlobalWorkers() throws Exception {
        assertThatThrownBy(()->batches.submit(request(2,true,0,1))).hasMessageContaining("GET/HEAD");
        plan=service.generate(new GenerateRequest(plan.specificationId(),"GET /products/{id}"));
        assertThatThrownBy(()->batches.submit(request(2,false,0,1))).hasMessageContaining("independent");
        var entered=new CountDownLatch(2);release=new CountDownLatch(1);var concurrent=new AtomicInteger();var peak=new AtomicInteger();
        doAnswer(invocation->{
            calls.incrementAndGet();peak.accumulateAndGet(concurrent.incrementAndGet(),Math::max);entered.countDown();
            try {assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return result(invocation.getArgument(0),invocation.getArgument(1));}finally{concurrent.decrementAndGet();}
        }).when(runner).execute(any(),anyInt());
        var first=batches.submit(request(2,true,0,1));assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
        var second=batches.submit(request(2,true,0,1));batches.cancel(second.id());
        release.countDown();finished(first.id());var cancelled=finished(second.id());
        assertThat(cancelled.cases()).extracting(BatchCase::state).containsOnly("CANCELLED");assertThat(calls.get()).isEqualTo(2);assertThat(peak.get()).isEqualTo(2);
    }
    @Test void startupRecoversRecordedResultsAndNeverResumesUncertainOrPendingCases() throws Exception {
        var job=service.createBatch(request(1,false,0,1,2));var updated=new ArrayList<BatchCase>();
        for(int i=0;i<3;i++) {var c=job.cases().get(i);if(i<2)store.startAttempt(c.approvalId());
            if(i==0)store.completeAttempt(c.approvalId(),result(plan,0));
            updated.add(new BatchCase(c.scenarioIndex(),c.scenarioRevision(),c.approvalId(),c.snapshot(),i<2?"RUNNING":"PENDING",null,""));}
        store.saveBatch(new BatchJob(job.id(),job.projectId(),job.planId(),null,job.target(),null,1,false,updated,"RUNNING",false,job.createdAt(),Instant.now()));
        batches.close();store.close();store=new WorkspaceStore(mapper,"jdbc:h2:file:"+directory.resolve("workspace"),"sa","");
        service=service();batches=new BatchService(store,service);var recovered=batches.get(job.id());
        assertThat(recovered.state()).isEqualTo("INTERRUPTED");assertThat(recovered.cases()).extracting(BatchCase::state).containsExactly("PASSED","UNKNOWN","NOT_RUN");
        assertThat(recovered.cases().getFirst().executionId()).isNotNull();assertThat(store.attempt(job.cases().get(2).approvalId()).state()).isEqualTo("CANCELLED");assertThat(calls.get()).isZero();
    }
    @Test void admissionAndSelectionValidationDoNotPublishPartialBatches() {
        assertThatThrownBy(()->batches.submit(request(1,false,0,0))).hasMessageContaining("once");
        assertThat(store.records("approvals",Approval.class)).isEmpty();
        assertThatThrownBy(()->batches.submit(new BatchRequest(UUID.randomUUID(),plan.id(),null,List.of(new BatchSelection(0,2)),1,false,true))).hasMessageContaining("changed");
        for(int i=0;i<10;i++)service.createBatch(request(1,false,0));
        assertThatThrownBy(()->batches.submit(request(1,false,0))).hasMessageContaining("queue is full");
    }
}
