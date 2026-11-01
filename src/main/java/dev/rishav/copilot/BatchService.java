package dev.rishav.copilot;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static dev.rishav.copilot.Models.*;

/** Bounded single-process dispatcher; persisted jobs are never automatically resumed after restart. */
@Service
public class BatchService implements AutoCloseable {
    private final WorkspaceStore store;
    private final CopilotService service;
    private final ThreadPoolExecutor coordinators=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(8));
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private boolean closed;
    public BatchService(WorkspaceStore store,CopilotService service) {
        this.store=store;this.service=service;
        for(var job:store.records("batches",BatchJob.class))if(active(job)) {
            var cases=new ArrayList<BatchCase>();
            for(var c:job.cases()) {
                var a=store.attempt(c.approvalId());String state=c.state();UUID result=c.executionId();
                if(state.equals("RUNNING")) {
                    if(a!=null&&a.state().equals("COMPLETED")){result=a.executionId();state=store.execution(result).passed()?"PASSED":"FAILED";}
                    else state=a==null?"NOT_RUN":"UNKNOWN";
                } else if(state.equals("PENDING"))state="NOT_RUN";
                if(Set.of("NOT_RUN","UNKNOWN").contains(state)&&a==null)store.cancelApproval(c.approvalId());
                cases.add(new BatchCase(c.scenarioIndex(),c.scenarioRevision(),c.approvalId(),c.snapshot(),state,result,"Recovered after interruption; no automatic replay"));
            }
            store.saveBatch(copy(job,cases,"INTERRUPTED",job.cancelRequested()));
        }
    }
    private static boolean active(BatchJob b) { return Set.of("QUEUED","RUNNING","CANCELLING").contains(b.state()); }
    public synchronized BatchJob submit(BatchRequest r) {
        var old=store.batch(r.id());
        if(old!=null) {
            boolean same=old.planId().equals(r.planId())&&Objects.equals(old.environmentId(),r.environmentId())&&old.parallelism()==r.parallelism()&&old.independent()==r.independent()
                    &&(r.expectedTarget()==null||r.expectedTarget().equals(old.target()))
                    &&(r.expectedEnvironmentRevision()==null||r.expectedEnvironmentRevision()==(old.environmentSnapshot()==null?0:old.environmentSnapshot().revision()))&&old.cases().size()==r.cases().size();
            for(int i=0;same&&i<r.cases().size();i++)same=old.cases().get(i).scenarioIndex()==r.cases().get(i).scenarioIndex()&&old.cases().get(i).scenarioRevision()==r.cases().get(i).expectedRevision();
            if(!same||!r.approved())throw new ApiException(HttpStatus.CONFLICT,"Batch ID already belongs to a different approved selection");
            return old;
        }
        if(closed||store.records("batches",BatchJob.class).stream().filter(BatchService::active).count()>=10)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"Batch queue is full; wait for a run to finish");
        if(store.records("batches",BatchJob.class).size()>=200)throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"Batch history capacity reached; delete unused projects");
        var job=service.createBatch(r);
        var id=job.id();
        try { coordinators.execute(()->run(id)); }
        catch(RejectedExecutionException rejected) {
            // A finishing coordinator can briefly occupy a slot after its persisted job becomes terminal.
            // Keep the approved selection visible, but never leave unscheduled work looking runnable.
            var cases=job.cases().stream().map(c->{store.cancelApproval(c.approvalId());return with(c,"NOT_RUN",null,"Dispatcher unavailable; review before submitting a new batch");}).toList();
            job=copy(job,cases,"INTERRUPTED",false);store.saveBatch(job);
        }
        return job;
    }
    public synchronized BatchJob get(UUID id) {var b=store.batch(id);if(b==null)throw new ApiException(HttpStatus.NOT_FOUND,"Batch not found");return b;}
    public synchronized List<BatchJob> list(UUID projectId) {service.workspace(projectId);return store.records("batches",BatchJob.class).stream().filter(b->b.projectId().equals(projectId)).sorted(Comparator.comparing(BatchJob::createdAt).reversed()).toList();}
    public synchronized BatchJob cancel(UUID id) {
        var b=get(id);if(!active(b))return b;
        var cases=new ArrayList<BatchCase>();
        for(var c:b.cases()) {
            if(c.state().equals("PENDING")){store.cancelApproval(c.approvalId());c=with(c,"CANCELLED",null,"Cancelled before dispatch");}
            cases.add(c);
        }
        var updated=copy(b,cases,"CANCELLING",true);store.saveBatch(updated);return updated;
    }
    private synchronized boolean claim(UUID id,int index) {
        var b=get(id);var c=b.cases().get(index);
        if(closed||b.cancelRequested()||!c.state().equals("PENDING"))return false;
        replace(b,index,with(c,"RUNNING",null,"Dispatched; may be in flight"),"RUNNING");return true;
    }
    private void run(UUID id) {
        var completion=new ExecutorCompletionService<Void>(workers);var initial=get(id);int next=0,inFlight=0;
        try {
            while(next<initial.cases().size()||inFlight>0) {
                while(next<initial.cases().size()&&inFlight<initial.parallelism()) {
                    int index=next++;
                    completion.submit(()->{executeCase(id,index);return null;});inFlight++;
                }
                if(inFlight>0){completion.take().get();inFlight--;}
            }
            synchronized(this) {var b=get(id);store.saveBatch(copy(b,b.cases(),b.cancelRequested()?"CANCELLED":"COMPLETED",b.cancelRequested()));}
        } catch(InterruptedException e){Thread.currentThread().interrupt();} catch(Exception e){ /* persisted nonterminal state is recovered conservatively on restart */ }
    }
    private void executeCase(UUID id,int index) {
        // Claim only when a worker starts, so cancellation also stops work waiting for a worker.
        if(!claim(id,index))return;
        var b=get(id);var c=b.cases().get(index);String state,detail;UUID result=null;
        try {
            var e=service.execute(new ExecuteRequest(b.planId(),c.scenarioIndex(),c.scenarioRevision(),true,b.environmentId(),c.approvalId()));
            state=e.passed()?"PASSED":"FAILED";detail=e.passed()?"All configured checks passed":"One or more response checks did not pass";result=e.id();
        } catch(Exception failure) {
            var attempt=store.attempt(c.approvalId());
            if(attempt!=null&&attempt.state().equals("COMPLETED")){result=attempt.executionId();state=store.execution(result).passed()?"PASSED":"FAILED";detail="Recovered recorded result";}
            else {state=attempt==null?"BLOCKED":"UNKNOWN";detail=state.equals("BLOCKED")?"Approval no longer matches current request, target, credentials or policy. Review before a new run.":"Outcome uncertain; inspect the target. No automatic retry.";}
        }
        synchronized(this) {var current=get(id);replace(current,index,with(current.cases().get(index),state,result,detail),current.cancelRequested()?"CANCELLING":"RUNNING");}
    }
    private BatchCase with(BatchCase c,String state,UUID result,String detail) {return new BatchCase(c.scenarioIndex(),c.scenarioRevision(),c.approvalId(),c.snapshot(),state,result,detail);}
    private BatchJob copy(BatchJob b,List<BatchCase> cases,String state,boolean cancelled) {return new BatchJob(b.id(),b.projectId(),b.planId(),b.environmentId(),b.target(),b.environmentSnapshot(),b.parallelism(),b.independent(),List.copyOf(cases),state,cancelled,b.createdAt(),Instant.now());}
    private void replace(BatchJob b,int index,BatchCase c,String state) {var cases=new ArrayList<>(b.cases());cases.set(index,c);store.saveBatch(copy(b,cases,state,b.cancelRequested()));}
    @jakarta.annotation.PreDestroy public void close() {synchronized(this){closed=true;}coordinators.shutdownNow();workers.shutdownNow();try{workers.awaitTermination(6,TimeUnit.SECONDS);coordinators.awaitTermination(1,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
