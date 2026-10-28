package com.xzwl.cloud.media.application.task;

import static org.junit.jupiter.api.Assertions.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MediaWorkflowTest {
  final TestClock clock = new TestClock();
  final MemoryStore store = new MemoryStore();
  final Peers peers = new Peers();
  final TaskPolicy policy = new TaskPolicy(Duration.ofMinutes(1), Duration.ofMinutes(2),
      Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(30), 10);
  final MediaTaskService service = new MediaTaskService(store, clock, policy);
  MediaTaskWorker worker() { return new MediaTaskWorker(store, peers, peers, peers, peers, clock, policy); }
  TaskRequest request(int count) { return new TaskRequest(MediaType.IMAGE,"model","prompt","1024",count,0,List.of()); }
  MediaTask submit(int count) { return service.submit("owner", "request-1", request(count)); }
  void drive(int steps) { for (int i=0;i<steps;i++) { worker().tick(); clock.advance(Duration.ofSeconds(2)); } }
  MediaTask current() { return service.get("owner", store.tasks.values().iterator().next().id()); }

  @Test void portraitWithoutReadyReviewNeverReachesCharging() {
    var ref=new MediaReference("file-1",null,MediaReference.Kind.IMAGE,null,"portrait-1");
    service.submit("owner","portrait-key",new TaskRequest(MediaType.VIDEO,"model","prompt","16:9","720p",1,5,List.of(ref),ReferenceMode.OMNI));
    drive(4);
    assertEquals(TaskStatus.FAILED,current().status());
    assertTrue(peers.charges.isEmpty());
    assertTrue(peers.submissions.isEmpty());
  }

  @Test void idempotentSubmissionRejectsDifferentPayloadAndHidesOtherOwners() {
    MediaTask first = submit(1);
    assertEquals(first.id(), submit(1).id());
    assertThrows(TaskConflictException.class, () -> service.submit("owner","request-1",request(2)));
    assertThrows(TaskNotFoundException.class, () -> service.get("other", first.id()));
    assertEquals(1, service.list("owner", 10, null,false).size());
    assertTrue(service.list("other", 10, null,false).isEmpty());
  }

  @Test void persistsSnapshotsChargesBeforeProviderAndArchivesBeforeSuccessAcrossWorkerRestarts() {
    submit(1); drive(8);
    assertEquals(TaskStatus.SUCCEEDED, current().status());
    assertEquals(List.of("route", "charge", "submit", "poll", "archive"), peers.events);
    assertEquals("provider-model", current().data().route().providerModel());
    assertEquals("file-0", service.results("owner",current().id()).getFirst().fileId());
    assertEquals(1, peers.charges.size());
    assertEquals(1, peers.enhancementArchives);
  }

  @Test void lostChargeAcknowledgementRetriesSameBusinessIdBeforeSubmitting() {
    peers.loseChargeResponse = true;
    submit(1); drive(10);
    assertEquals(TaskStatus.SUCCEEDED, current().status());
    assertEquals(1, peers.charges.size());
    assertEquals(1, peers.submissions.size());
  }

  @Test void uncertainProviderSubmissionReusesIdAndDoesNotRefundTransientFailure() {
    peers.loseSubmitResponse = true;
    submit(1); drive(10);
    assertEquals(TaskStatus.SUCCEEDED,current().status());
    assertEquals(1,peers.submissions.size());
    assertEquals(0,peers.refunds.size());
  }

  @Test void failureRefundIsRetriedWithOriginalFourDecimalPoints() {
    peers.failProvider = true; peers.loseRefundResponse = true;
    submit(1); drive(10);
    assertEquals(TaskStatus.FAILED,current().status());
    assertEquals(List.of(new BigDecimal("12.3456")),new ArrayList<>(peers.refunds.values()));
  }

  @Test void timeoutRefundsEvenWhenProviderNeverCompletes() {
    peers.pending = true; submit(1); drive(4);
    clock.advance(Duration.ofMinutes(3)); drive(4);
    assertEquals(TaskStatus.TIMED_OUT,current().status());
    assertEquals(new BigDecimal("12.3456"),peers.refunds.values().iterator().next());
  }

  @Test void partialImageFailureArchivesSuccessAndRefundsOnlyFailedShare() {
    peers.partial = true; submit(3); drive(10);
    assertEquals(TaskStatus.PARTIAL_SUCCESS,current().status());
    assertEquals(new BigDecimal("8.2304"),peers.refunds.values().iterator().next());
    assertEquals(1,service.results("owner",current().id()).size());
  }

  @Test void archiveFailureRetriesWithoutSubmittingOrChargingAgain() {
    peers.failArchiveOnce = true; submit(1); drive(10);
    assertEquals(TaskStatus.SUCCEEDED,current().status());
    assertEquals(1,peers.submissions.size());
    assertEquals(1,peers.charges.size());
  }

  @Test void rejectedChargeNeverCallsProviderOrRefund() {
    peers.rejectCharge = true; submit(1); drive(6);
    assertEquals(TaskStatus.FAILED,current().status());
    assertTrue(peers.submissions.isEmpty()); assertTrue(peers.refunds.isEmpty());
  }

  @Test void missingCredentialsPreserveFirstRouteAndExpireWithoutCharging() {
    peers.unavailable=true; submit(1); drive(3);
    assertNotNull(current().data().route());
    assertEquals(1,Collections.frequency(peers.events,"route"));
    clock.advance(Duration.ofMinutes(3)); drive(2);
    assertEquals(TaskStatus.TIMED_OUT,current().status());
    assertTrue(peers.charges.isEmpty()); assertTrue(peers.refunds.isEmpty());
  }

  @Test void archiveTimeoutKeepsConfirmedImagesAndRefundsOnlyUndeliveredShare() {
    submit(3); drive(5); // first output has been archived
    clock.advance(Duration.ofMinutes(3)); drive(3);
    assertEquals(TaskStatus.PARTIAL_SUCCESS,current().status());
    assertEquals(1,service.results("owner",current().id()).size());
    assertEquals(new BigDecimal("8.2304"),peers.refunds.values().iterator().next());
  }

  @Test void deadlineBetweenLastArchiveAndFinalizationDoesNotRefundDeliveredWork() {
    submit(1); drive(5);
    clock.advance(Duration.ofMinutes(3)); drive(2);
    assertEquals(TaskStatus.SUCCEEDED,current().status());
    assertTrue(peers.refunds.isEmpty());
  }

  @Test void debitAcknowledgementRecoveredAfterDeadlineIsRefundedBeforeProviderSubmission() {
    peers.loseChargeResponse=true; submit(1); drive(2);
    clock.advance(Duration.ofMinutes(3)); drive(4);
    assertEquals(TaskStatus.TIMED_OUT,current().status());
    assertEquals(1,peers.charges.size()); assertEquals(1,peers.refunds.size());
    assertTrue(peers.submissions.isEmpty());
  }

  @Test void oneRejectedArchiveDoesNotDiscardOtherProviderOutputs() {
    peers.rejectArchiveIndex=0; submit(3); drive(10);
    assertEquals(TaskStatus.PARTIAL_SUCCESS,current().status());
    assertEquals(2,service.results("owner",current().id()).size());
    assertEquals(new BigDecimal("4.1152"),peers.refunds.values().iterator().next());
  }

  @Test void resolvesAndFreezesReferencesBeforeChargeWhileKeepingOriginalIdempotencyRequest() {
    var original = request(1).withReferences(List.of(new MediaReference("file-1", null)));
    service.submit("owner", "request-1", original);
    drive(1);
    assertEquals(TaskStatus.CHARGE_PENDING, current().status());
    assertEquals("file-1", current().data().request().references().getFirst().fileId());
    assertEquals("https://storage.example/reference", current().data().providerRequest().references().getFirst().httpsUrl());
    assertTrue(peers.charges.isEmpty());
    drive(7);
    assertEquals(TaskStatus.SUCCEEDED, current().status());
    assertEquals(List.of("route", "resolve", "validate", "charge", "submit", "poll", "archive"), peers.events);
    assertEquals(current().id(), service.submit("owner", "request-1", original).id());
  }

  @Test void rejectedReferenceAndInvalidProviderRequestNeverCharge() {
    peers.rejectReferences = true;
    service.submit("owner", "request-1", request(1).withReferences(List.of(new MediaReference("file-1", null))));
    drive(3);
    assertEquals(TaskStatus.FAILED, current().status());
    assertNull(current().data().charge());
    assertTrue(peers.charges.isEmpty());
    assertTrue(peers.submissions.isEmpty());
  }

  @Test void totalArchiveRejectionRefundsTheWholeDebit() {
    peers.rejectArchiveIndex = 0; submit(1); drive(9);
    assertEquals(TaskStatus.FAILED, current().status());
    assertEquals(new BigDecimal("12.3456"), peers.refunds.values().iterator().next());
  }

  static class TestClock extends Clock {
    Instant now = Instant.parse("2026-09-07T00:00:00Z");
    void advance(Duration duration) { now = now.plus(duration); }
    public ZoneId getZone() { return ZoneOffset.UTC; }
    public Clock withZone(ZoneId zone) { return this; }
    public Instant instant() { return now; }
  }

  static class MemoryStore implements MediaTaskStore {
    Map<String,MediaTask> tasks = new LinkedHashMap<>(); Map<String,List<TaskResult>> results = new HashMap<>();
    public MediaTask create(MediaTask task) {
      return tasks.values().stream().filter(t -> t.ownerId().equals(task.ownerId()) && t.idempotencyKey().equals(task.idempotencyKey()))
          .findFirst().orElseGet(() -> { tasks.put(task.id(),task); return task; });
    }
    public Optional<MediaTask> findRequest(String owner,String key) { return tasks.values().stream().filter(t->t.ownerId().equals(owner) && t.idempotencyKey().equals(key)).findFirst(); }
    public Optional<MediaTask> find(String id) { return Optional.ofNullable(tasks.get(id)); }
    public List<MediaTask> list(String owner,int limit,String before,boolean excludeCanvas,boolean commerceOnly) { return tasks.values().stream().filter(t -> t.ownerId().equals(owner)).filter(t -> !excludeCanvas || !t.idempotencyKey().startsWith("canvas-")).limit(limit).toList(); }
    public List<String> due(Instant now,int limit) { return tasks.values().stream().filter(t -> !t.status().terminal() && !t.nextRunAt().isAfter(now)).map(MediaTask::id).limit(limit).toList(); }
    public Optional<Lease> claim(String id,String token,Instant now,Instant until) { return Optional.of(new Lease(tasks.get(id),token)); }
    public void save(Lease lease,MediaTask next,List<TaskResult> changes,Instant now) {
      assertEquals(lease.task().version(),tasks.get(next.id()).version());
      tasks.put(next.id(),next);
      List<TaskResult> all = new ArrayList<>(results.getOrDefault(next.id(),List.of()));
      for (TaskResult result : changes) { all.removeIf(r -> r.index()==result.index()); all.add(result); }
      results.put(next.id(),all);
    }
    public List<TaskResult> results(String taskId) { return results.getOrDefault(taskId,List.of()); }
  }

  static class Peers implements RoutingPort, BillingPort, ProviderPort, StoragePort {
    List<String> events = new ArrayList<>(); Map<String,ChargeReceipt> charges = new HashMap<>();
    Set<String> submissions = new HashSet<>(); Map<String,BigDecimal> refunds = new HashMap<>();
    boolean loseChargeResponse,loseSubmitResponse,loseRefundResponse,failProvider,pending,partial,failArchiveOnce,rejectCharge,unavailable;
    boolean rejectReferences;
    int rejectArchiveIndex=-1,enhancementArchives;
    public void archiveEnhancement(String id,String owner,StoragePort storage) {
      assertEquals("owner",owner);assertSame(this,storage);assertTrue(events.contains("archive"));enhancementArchives++;
    }
    public RouteSnapshot route(String id,String model) { events.add("route"); return new RouteSnapshot(id,model,"provider","Provider","https://provider.example","provider-model","{}","{}",Instant.EPOCH); }
    public ChargeReceipt charge(String id,String owner,TaskRequest request,RouteSnapshot route) {
      if(rejectCharge) throw new DefinitiveRejectionException("INSUFFICIENT_FUNDS");
      events.add("charge"); ChargeReceipt receipt = charges.computeIfAbsent(id,k -> new ChargeReceipt("charge-1",new BigDecimal("1.2345"),new BigDecimal("10"),new BigDecimal("12.3456")));
      if(loseChargeResponse) { loseChargeResponse=false; throw new DependencyUnavailableException("acknowledgement lost"); } return receipt;
    }
    public void refund(String id,String owner,ChargeReceipt receipt,BigDecimal points) {
      refunds.putIfAbsent(id,points); events.add("refund");
      if(loseRefundResponse) { loseRefundResponse=false; throw new DependencyUnavailableException("acknowledgement lost"); }
    }
    public void requireAvailable(RouteSnapshot route) { if(unavailable) throw new DependencyUnavailableException("missing credentials"); }
    public String submit(String id,String owner,TaskRequest request,RouteSnapshot route) {
      assertEquals("owner",owner,"reference file authorization must use the persisted task owner");
      assertTrue(request.references().stream().allMatch(r -> r.fileId() == null));
      events.add("submit"); submissions.add(id);
      if(loseSubmitResponse) { loseSubmitResponse=false; throw new DependencyUnavailableException("acknowledgement lost"); }
      return "provider-task";
    }
    public ProviderPoll poll(String id,TaskRequest request,RouteSnapshot route) {
      events.add("poll");
      if(failProvider) return ProviderPoll.failed();
      if(pending) return ProviderPoll.pending();
      return ProviderPoll.completed(partial ? List.of(new ProviderOutput(0,"https://cdn.example/output.png"))
          : java.util.stream.IntStream.range(0,request.count()).mapToObj(i -> new ProviderOutput(i,"https://cdn.example/output.png")).toList());
    }
    public List<MediaReference> resolveReferences(String owner, List<MediaReference> references) {
      assertEquals("owner", owner);
      if (rejectReferences) throw new DefinitiveRejectionException("REFERENCE_REJECTED");
      if (!references.isEmpty()) events.add("resolve");
      return references.stream().map(r -> r.fileId() == null ? r : new MediaReference(null, "https://storage.example/reference")).toList();
    }
    public void requireAvailable(TaskRequest request, RouteSnapshot route) {
      requireAvailable(route);
      if (!request.references().isEmpty()) {
        assertTrue(request.references().stream().allMatch(r -> r.fileId() == null));
        events.add("validate");
      }
    }
    public String archive(String owner,String taskId,int index,String url) {
      if(index==rejectArchiveIndex) throw new DefinitiveRejectionException("invalid output");
      if(failArchiveOnce) { failArchiveOnce=false; throw new DependencyUnavailableException("temporary"); }
      events.add("archive"); return "file-"+index;
    }
  }
}
