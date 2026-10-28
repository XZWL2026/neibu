package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.task.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Durable workflow: each lease executes at most one bounded external operation. */
public final class MediaTaskWorker {
  private static final System.Logger LOG=System.getLogger(MediaTaskWorker.class.getName());
  private final MediaTaskStore store;
  private final RoutingPort routing;
  private final BillingPort billing;
  private final ProviderPort provider;
  private final StoragePort storage;
  private final Clock clock;
  private final TaskPolicy policy;
  private final PortraitReviews portraits;
  private final DepthTasks depth;
  private final EnhancementTasks enhancements;
  public MediaTaskWorker(MediaTaskStore store,RoutingPort routing,BillingPort billing,
      ProviderPort provider,StoragePort storage,Clock clock,TaskPolicy policy) {
    this(store,routing,billing,provider,storage,clock,policy,null);
  }
  public MediaTaskWorker(MediaTaskStore store,RoutingPort routing,BillingPort billing,
      ProviderPort provider,StoragePort storage,Clock clock,TaskPolicy policy,PortraitReviews portraits) {
    this(store,routing,billing,provider,storage,clock,policy,portraits,null);
  }
  public MediaTaskWorker(MediaTaskStore store,RoutingPort routing,BillingPort billing,ProviderPort provider,StoragePort storage,Clock clock,TaskPolicy policy,PortraitReviews portraits,DepthTasks depth) {
    this(store,routing,billing,provider,storage,clock,policy,portraits,depth,null);
  }
  public MediaTaskWorker(MediaTaskStore store,RoutingPort routing,BillingPort billing,ProviderPort provider,StoragePort storage,Clock clock,TaskPolicy policy,PortraitReviews portraits,DepthTasks depth,EnhancementTasks enhancements) {
    this.enhancements=enhancements;this.depth=depth;
    this.portraits=portraits;
    this.store=store; this.routing=routing; this.billing=billing; this.provider=provider;
    this.storage=storage; this.clock=clock; this.policy=policy;
  }

  public void tick() {
    for(String id:store.due(clock.instant(),policy.batchSize())) {
      try { process(id); }
      catch (RuntimeException failure) {
        // Do not log external exception messages: they can contain signed URLs or credentials.
        LOG.log(System.Logger.Level.WARNING,"Media task {0} retained for recovery: {1}",id,failure.getClass().getSimpleName());
      }
    }
  }

  public void process(String id) {
    Instant now=clock.instant();
    Optional<MediaTaskStore.Lease> acquired=store.claim(id,UUID.randomUUID().toString(),now,now.plus(policy.leaseDuration()));
    if(acquired.isEmpty()) return;
    MediaTaskStore.Lease lease=acquired.get();
    MediaTask task=lease.task(); TaskData data=task.data();
    try {
      boolean expired=!clock.instant().isBefore(task.deadlineAt());
      // CHARGE_PENDING must resolve an uncertain debit before it can refund or terminate.
      if(expired && task.status()!=TaskStatus.CHARGE_PENDING && task.status()!=TaskStatus.REFUND_PENDING) {
        fail(lease,TaskStatus.TIMED_OUT,"GENERATION_TIMEOUT"); return;
      }
      if(data.request().operation()==TaskOperation.DEPTH) {
        if(depth==null) throw new DependencyUnavailableException("depth unavailable");
        switch(task.status()) {
          case ROUTING -> { depth.start(task); save(lease,TaskStatus.RUNNING,data,List.of(),policy.pollInterval()); }
          case RUNNING -> {
            String state=depth.poll(task);
            if("FAILED".equals(state)) fail(lease,TaskStatus.FAILED,"DEPTH_FAILED");
            else save(lease,"COMPLETE".equals(state)?TaskStatus.ARCHIVING:TaskStatus.RUNNING,data,List.of(),policy.pollInterval());
          }
          case ARCHIVING -> save(lease,TaskStatus.SUCCEEDED,data,List.of(new TaskResult(task.id(),0,null,depth.archive(task))),Duration.ZERO);
          default -> throw new DefinitiveRejectionException("INVALID_DEPTH_STATE");
        }
        return;
      }
      switch(task.status()) {
        case ROUTING -> {
          RouteSnapshot route=data.route()==null ? routing.route(task.id(),data.request().model()):data.route();
          if(!route.taskId().equals(task.id()) || !route.requestedModel().equals(data.request().model()))
            throw new DefinitiveRejectionException("INVALID_ROUTE");
          // Availability is checked before entering the potentially ambiguous charging phase.
          try { provider.requireAvailable(route); }
          catch(DependencyUnavailableException missingConfiguration) {
            save(lease,TaskStatus.ROUTING,data.withRoute(route),List.of(),policy.retryInterval());
            break;
          }
          if(portraits==null) PortraitReviews.rejectUnsupported(data.request());
          else portraits.freeze(task.id(),task.ownerId(),data.request(),route);
          TaskData resolved=data.withRoute(route).withExecutionReferences(storage.resolveReferences(task.ownerId(),data.request().references(),data.request().referenceMode()));
          provider.requireAvailable(resolved.providerRequest(),route,task.ownerId());
          save(lease,TaskStatus.CHARGE_PENDING,resolved,List.of(),Duration.ZERO);
        }
        case CHARGE_PENDING -> {
          ChargeReceipt receipt;
          if(data.request().operation()==TaskOperation.ENHANCEMENT) {
            var quote=enhancements.quote(task.id());receipt=Objects.requireNonNull(billing.chargeEnhancement(task.id(),task.ownerId(),quote.targetResolution(),quote.source().billableSeconds(),quote.cost()));
          } else receipt=Objects.requireNonNull(billing.charge(task.id(),task.ownerId(),data.request(),data.route()));
          TaskData charged=data.withCharge(receipt);
          if(!clock.instant().isBefore(task.deadlineAt())) {
            save(lease,TaskStatus.REFUND_PENDING,charged.withOutcome(TaskStatus.TIMED_OUT,receipt.points(),"GENERATION_TIMEOUT"),List.of(),Duration.ZERO);
          } else save(lease,TaskStatus.SUBMIT_PENDING,charged,List.of(),Duration.ZERO);
        }
        case SUBMIT_PENDING -> {
          String upstream=data.request().operation()==TaskOperation.ENHANCEMENT ? enhancements.submit(task) : provider.submit(task.id(),task.ownerId(),data.providerRequest(),data.route());
          save(lease,TaskStatus.RUNNING,data.withProviderTask(upstream),List.of(),policy.pollInterval());
        }
        case RUNNING -> {
          if(data.request().operation()!=TaskOperation.ENHANCEMENT)provider.archiveNative(task.id(),task.ownerId(),storage);
          ProviderPort.ProviderPoll poll=data.request().operation()==TaskOperation.ENHANCEMENT ? enhancements.poll(task) : provider.poll(data.providerTaskId(),data.providerRequest(),data.route());
          switch(poll.status()) {
            case PENDING -> save(lease,TaskStatus.RUNNING,data,List.of(),policy.pollInterval());
            case FAILED -> fail(lease,TaskStatus.FAILED,"PROVIDER_FAILED");
            case COMPLETED -> {
              if(poll.outputs().isEmpty()) { fail(lease,TaskStatus.FAILED,"NO_PROVIDER_OUTPUT"); break; }
              if(poll.outputs().stream().anyMatch(o -> o.index()>=data.request().count()))
                throw new DefinitiveRejectionException("INVALID_PROVIDER_OUTPUT");
              List<TaskResult> results=poll.outputs().stream().map(o -> new TaskResult(task.id(),o.index(),o.httpsUrl(),null)).toList();
              save(lease,TaskStatus.ARCHIVING,data,results,Duration.ZERO);
            }
          }
        }
        case ARCHIVING -> {
          List<TaskResult> results=store.results(task.id());
          Optional<TaskResult> pending=results.stream().filter(r -> r.fileId()==null && r.failureCode()==null).findFirst();
          if(pending.isPresent()) {
            TaskResult result=pending.get();
            TaskResult archived;
            try { archived=result.archived(storage.archive(task.ownerId(),task.id(),result.index(),result.sourceUrl())); }
            catch(DefinitiveRejectionException invalidOutput) { archived=result.failed(); }
            save(lease,TaskStatus.ARCHIVING,data,List.of(archived),Duration.ZERO);
          } else {
            int delivered=(int)results.stream().filter(r -> r.fileId()!=null).count();
            int failed=data.request().count()-delivered;
            if(delivered>0 && data.request().operation()!=TaskOperation.ENHANCEMENT) provider.archiveEnhancement(task.id(),task.ownerId(),storage);
            if(failed==0) save(lease,TaskStatus.SUCCEEDED,data,List.of(),Duration.ZERO);
            else save(lease,TaskStatus.REFUND_PENDING,data.withOutcome(delivered==0?TaskStatus.FAILED:TaskStatus.PARTIAL_SUCCESS,
                data.charge().refundPoints(failed,data.request().count()),"PARTIAL_PROVIDER_OUTPUT"),List.of(),Duration.ZERO);
          }
        }
        case REFUND_PENDING -> {
          billing.refund(task.id()+"-refund",task.ownerId(),data.charge(),data.refundPoints());
          save(lease,data.outcome(),data,List.of(),Duration.ZERO);
        }
        default -> throw new IllegalStateException("terminal task cannot be claimed");
      }
    } catch(LostLeaseException lost) {
      throw lost;
    } catch(DefinitiveRejectionException rejected) {
      if(task.status()==TaskStatus.REFUND_PENDING) retry(lease);
      else fail(lease,TaskStatus.FAILED,"DEPENDENCY_REJECTED");
    } catch(RuntimeException transientFailure) {
      retry(lease);
    }
  }

  private void fail(MediaTaskStore.Lease lease,TaskStatus outcome,String code) {
    TaskData data=lease.task().data();
    if(lease.task().status()==TaskStatus.ARCHIVING && data.request().operation()!=TaskOperation.DEPTH) {
      int delivered=(int)store.results(lease.task().id()).stream().filter(r -> r.fileId()!=null).count();
      if(delivered==data.request().count()) {
        save(lease,TaskStatus.SUCCEEDED,data,List.of(),Duration.ZERO);
        return;
      }
      if(delivered>0) {
        BigDecimal refund=data.charge().refundPoints(data.request().count()-delivered,data.request().count());
        save(lease,TaskStatus.REFUND_PENDING,data.withOutcome(TaskStatus.PARTIAL_SUCCESS,refund,code),List.of(),Duration.ZERO);
        return;
      }
    }
    if(data.charge()==null) {
      save(lease,outcome,data.withOutcome(outcome,BigDecimal.ZERO,code),List.of(),Duration.ZERO);
    } else {
      save(lease,TaskStatus.REFUND_PENDING,data.withOutcome(outcome,data.charge().points(),code),List.of(),Duration.ZERO);
    }
  }
  private void retry(MediaTaskStore.Lease lease) {
    save(lease,lease.task().status(),lease.task().data(),List.of(),policy.retryInterval());
  }
  private void save(MediaTaskStore.Lease lease,TaskStatus status,TaskData data,List<TaskResult> results,Duration delay) {
    Instant now=clock.instant();
    store.save(lease,lease.task().advance(status,data,now.plus(delay)),results,now);
  }
}
