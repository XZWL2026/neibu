package com.xzwl.cloud.media.infrastructure.provider;

import com.xzwl.cloud.media.application.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import com.xzwl.cloud.media.domain.task.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;

/** A durable ProviderPort handle denotes the local operation, including synchronous image and MPS stages. */
public final class ProductionProviderPort implements ProviderPort {
  private final Function<String,ProviderSettings> settingsSource;
  private final ProviderJournal journal;
  private final ArkMediaAdapter ark;
  private final TencentMpsAdapter mps;
  private final JdbcPortraitReviews portraits;
  public ProductionProviderPort(ProviderSettings settings,ProviderJournal journal,ArkMediaAdapter ark,TencentMpsAdapter mps) {
    this(providerId->settings,journal,ark,mps);
  }
  public ProductionProviderPort(Supplier<ProviderSettings> settingsSource,ProviderJournal journal,ArkMediaAdapter ark,TencentMpsAdapter mps) {
    this(providerId -> settingsSource.get(),journal,ark,mps);
  }
  public ProductionProviderPort(Function<String,ProviderSettings> settingsSource,ProviderJournal journal,ArkMediaAdapter ark,TencentMpsAdapter mps) {
    this(settingsSource,journal,ark,mps,null);
  }
  public ProductionProviderPort(Function<String,ProviderSettings> settingsSource,ProviderJournal journal,ArkMediaAdapter ark,TencentMpsAdapter mps,JdbcPortraitReviews portraits) {
    this.settingsSource=Objects.requireNonNull(settingsSource); this.journal=journal; this.ark=ark; this.mps=mps; this.portraits=portraits;
  }
  @Override public void archiveNative(String id,String owner,StoragePort storage) {
    var op=journal.find(id);
    if(op==null || !op.phase().equals("MPS_READY")) return;
    var outputs=new ArrayList<>(op.nativeOutputs());
    for(int i=0;i<outputs.size();i++) {
      var output=outputs.get(i);
      if(output.fileId()!=null || output.failureCode()!=null) continue;
      try {
        outputs.set(i,new ProviderJournal.NativeOutput(output.index(),output.sourceUrl(),
            storage.archive(owner,id,output.index(),output.sourceUrl(),StoragePort.ArchiveStage.NATIVE),null));
      } catch(DefinitiveRejectionException rejected) {
        outputs.set(i,new ProviderJournal.NativeOutput(output.index(),output.sourceUrl(),null,"STORAGE_REJECTED"));
      }
      journal.saveNative(op,outputs);
    }
  }
  @Override public void archiveEnhancement(String id,String owner,StoragePort storage) {
    var op=journal.find(id);
    if(op==null || !op.phase().equals("COMPLETE")) return;
    var steps=new ArrayList<>(op.enhancementSteps());
    // The terminal step uses the existing final TaskResult file, avoiding duplicate archival.
    for(int i=0;i<steps.size()-1;i++) {
      var step=steps.get(i);
      if(step.fileId()!=null || step.failureCode()!=null) continue;
      String file=null,error=null;
      try { file=storage.archive(owner,id,i,step.output().sourceUrl(),StoragePort.ArchiveStage.ENHANCEMENT); }
      catch(DefinitiveRejectionException rejected) { error="STORAGE_REJECTED"; }
      catch(DependencyUnavailableException unavailable) { error="STORAGE_UNAVAILABLE"; }
      steps.set(i,new ProviderJournal.EnhancementStep(step.output(),file,error));
      journal.saveEnhancements(op,steps);
    }
  }
  @Override public Diagnostics diagnostics(String id) {
    var op=journal.find(id); if(op==null) return null;
    boolean enhanced=op.phase().startsWith("MPS_") || op.enhancementId()!=null
        || op.failureStage()!=null && op.failureStage().startsWith("MPS_")
        || op.phase().equals("COMPLETE") && op.plan().enhanced();
    String generationId=op.generationId()!=null?op.generationId():op.phase().equals("ARK_RUNNING")?op.upstream():null;
    String enhancementId=op.enhancementId()!=null?op.enhancementId():op.phase().equals("MPS_RUNNING")?op.upstream():null;
    String provider=ProviderSettings.zex(op.plan().arkBase())?"ZEX":
        "ark.cn-beijing.volces.com".equals(java.net.URI.create(op.plan().arkBase()).getHost())?"火山":null;
    var originals=op.nativeOutputs().stream().map(o->new DiagnosticOutput(o.index(),o.fileId(),o.failureCode())).toList();
    var generation=new Stage(provider,generationId,enhanced?"COMPLETE":op.phase(),
        op.resolution()!=null?op.resolution():op.plan().sourceResolution(),elapsed(op.generationStarted(),op.generationFinished()),
        op.tokens(),null,null,enhanced?null:op.failureStage(),enhanced?null:op.failureCode(),originals);
    var steps=new ArrayList<EnhancementStep>();
    for(int i=0;i<op.enhancementSteps().size();i++) {
      var step=op.enhancementSteps().get(i);var output=step.output();
      steps.add(new EnhancementStep(i,output.definitionId(),output.width(),output.height(),"COMPLETE",output.durationMs(),step.fileId(),step.failureCode()));
    }
    boolean knownSchedule=op.plan().schedule()!=null && op.plan().schedule().id()>0;
    var enhancement=enhanced?new Stage("腾讯云 MPS",enhancementId,op.phase(),op.plan().targetResolution(),
        elapsed(op.enhancementStarted(),op.enhancementFinished()),null,
        knownSchedule?Long.toString(op.plan().schedule().id()):null,knownSchedule?null:op.plan().template(),
        op.failureStage(),op.failureCode(),List.of(),steps):null;
    return new Diagnostics(generation,enhancement);
  }
  private static Long elapsed(Long start,Long end) { return start!=null && end!=null && end>=start?end-start:null; }
  @Override public FailureDetail failure(String id) {
    var op=journal.find(id);
    return op==null || op.failureStage()==null ? null : new FailureDetail(op.failureStage(),op.failureCode(),op.failureStatus());
  }
  @Override public void requireAvailable(RouteSnapshot route) { settingsSource.apply(route.providerId()).available(route); }
  @Override public void requireAvailable(TaskRequest request,RouteSnapshot route) {
    requireAvailable(request,route,null);
  }
  @Override public void requireAvailable(TaskRequest request,RouteSnapshot route,String owner) {
    ProviderSettings settings=settingsSource.apply(route.providerId());
    settings.available(route);
    if(portraits!=null && owner!=null) portraits.assets(route.taskId(),owner,request,route,settings);
    else PortraitReviews.rejectUnsupported(request);
    if(journal.find(route.taskId())==null) journal.create(route.taskId(),mps.prepare(settings.plan(request,route),settings));
    checkEnvironment(Objects.requireNonNull(journal.find(route.taskId())).plan(),settings);
  }
  @Override public String submit(String id,String owner,TaskRequest request,RouteSnapshot route) {
    ProviderSettings settings=settingsSource.apply(route.providerId());
    MediaReference.identifier(owner,"ownerId");
    if(!id.equals(route.taskId())) throw new DefinitiveRejectionException("INVALID_PROVIDER_OPERATION");
    var op=journal.find(id);
    if(op==null) op=journal.create(id,mps.prepare(settings.plan(request,route),settings));
    if(!op.phase().equals("COMPLETE") && !op.phase().equals("FAILED")) checkProvider(route,settings);
    switch(op.phase()) {
      case "FAILED" -> throw new DefinitiveRejectionException("PROVIDER_REJECTED");
      case "ARK_SENDING" -> throw ProviderHttp.uncertain();
      case "ARK_READY" -> {
        checkEnvironment(op.plan(),settings);
        var assetReferences=portraits==null ? new JdbcPortraitReviews.AssetReferences(Map.of(),null) : portraits.assets(id,owner,request,route,settings);
        if(portraits==null) PortraitReviews.rejectUnsupported(request);
        if(!journal.move(op,"ARK_SENDING",null,null,null)) throw ProviderHttp.uncertain();
        var sending=journal.find(id);
        try(var diagnostic=ProviderHttpDiagnostics.open(journal,id,"ARK_SENDING",request,settings)) {
          if(request.type()==MediaType.IMAGE && ProviderSettings.zex(op.plan().arkBase())
              && ZexMediaAdapter.asynchronousImage(op.plan().model())) {
            String upstream=ark.submitImage(request,op.plan(),settings.arkKey);
            journal.move(sending,"ARK_RUNNING",upstream,null,null);
          } else if(request.type()==MediaType.IMAGE) {
            List<ProviderOutput> outputs=safe(ark.image(request,op.plan(),settings.arkKey),settings);
            journal.move(sending,"COMPLETE",null,null,outputs);
          } else {
            String upstream=ark.submitVideo(request,op.plan(),settings.arkKey,assetReferences.ids(),assetReferences.project());
            journal.move(sending,"ARK_RUNNING",upstream,null,null);
          }
        } catch(DefinitiveRejectionException rejected) {
          journal.fail(sending,diagnostic(rejected.getMessage(),settings)); throw new DefinitiveRejectionException("PROVIDER_REJECTED");
        }
      }
      default -> { /* Accepted receipt replay; never submit again. */ }
    }
    return id;
  }
  @Override public ProviderPoll poll(String id,TaskRequest request,RouteSnapshot route) {
    ProviderSettings settings=settingsSource.apply(route.providerId());
    if(!id.equals(route.taskId())) throw new DefinitiveRejectionException("INVALID_PROVIDER_OPERATION");
    var op=journal.find(id);
    if(op==null) throw ProviderHttp.uncertain();
    if(op.phase().equals("COMPLETE")) return ProviderPoll.completed(op.outputs());
    if(op.phase().equals("FAILED")) return ProviderPoll.failed();
    checkProvider(route,settings);
    checkEnvironment(op.plan(),settings);
    try(var diagnostic=ProviderHttpDiagnostics.open(journal,id,op.phase().equals("MPS_READY")?"MPS_SENDING":op.phase(),request,settings)) {
      return switch(op.phase()) {
        case "ARK_RUNNING" -> {
          ProviderPoll result=ark.poll(op.upstream(),op.plan(),settings.arkKey);
          if(result.status()==ProviderPoll.Status.COMPLETED && op.plan().enhanced()) {
            journal.move(op,"MPS_READY",null,safe(result.outputs(),settings).getFirst().httpsUrl(),result.outputs(),result.videoTokens(),result.resolution());
            yield ProviderPoll.pending();
          }
          yield finish(op,result,settings);
        }
        case "MPS_READY" -> {
          if(journal.move(op,"MPS_SENDING",null,op.source(),null)) {
            var sending=journal.find(id);
            try { journal.move(sending,"MPS_RUNNING",mps.submit(id,op.source(),op.plan(),settings),null,null); }
            catch(DefinitiveRejectionException rejected) { journal.fail(sending,diagnostic(rejected.getMessage(),settings)); yield ProviderPoll.failed(); }
          }
          yield ProviderPoll.pending();
        }
        case "MPS_RUNNING" -> finish(op,mps.poll(op.upstream(),op.plan(),settings),settings);
        default -> ProviderPoll.pending(); // uncertain submit waits for the task's existing deadline/refund
      };
    } catch(DefinitiveRejectionException rejected) {
      journal.fail(op,diagnostic(rejected.getMessage(),settings)); return ProviderPoll.failed();
    }
  }
  private ProviderPoll finish(ProviderJournal.Operation op,ProviderPoll result,ProviderSettings settings) {
    if(result.status()==ProviderPoll.Status.PENDING) return result;
    List<ProviderOutput> outputs=safe(result.outputs(),settings);
    safe(result.enhancementOutputs().stream().filter(o->o.sourceUrl()!=null).map(o->new ProviderOutput(0,o.sourceUrl())).toList(),settings);
    boolean saved=result.status()==ProviderPoll.Status.FAILED
        ? journal.fail(op,diagnostic(result.failureCode(),settings),result.failureStatus()) : journal.move(op,"COMPLETE",null,null,outputs,result.videoTokens(),result.resolution(),result.enhancementOutputs());
    if(!saved) return ProviderPoll.pending();
    return new ProviderPoll(result.status(),outputs);
  }
  private String diagnostic(String code,ProviderSettings settings) {
    if(code==null || !code.matches("[A-Za-z0-9_.-]{1,256}")) return "PROVIDER_FAILED";
    for(String secret:List.of(settings.arkKey,settings.secretId,settings.secretKey))
      if(!secret.isEmpty() && code.contains(secret)) return "PROVIDER_FAILED";
    return code;
  }
  private List<ProviderOutput> safe(List<ProviderOutput> outputs,ProviderSettings settings) {
    for(var output:outputs) {
      for(String credential:List.of(settings.arkKey,settings.secretId,settings.secretKey))
        if(!credential.isEmpty() && output.httpsUrl().contains(credential)) throw new DefinitiveRejectionException("INVALID_PROVIDER_OUTPUT");
    }
    return outputs;
  }
  private void checkProvider(RouteSnapshot route,ProviderSettings settings) {
    if(settings.providerId.isEmpty()) throw ProviderSettings.unavailable();
    if(!settings.providerId.equals(route.providerId())) throw new DefinitiveRejectionException("UNSUPPORTED_PROVIDER_MODEL");
  }
  private void checkEnvironment(ProviderSettings.Plan plan,ProviderSettings settings) {
    if(settings.arkBase.isEmpty() || !ProviderSettings.providerEndpoint(plan.arkBase()).equals(ProviderSettings.providerEndpoint(settings.arkBase)) || settings.arkKey.isEmpty()
        || (plan.enhanced() && (!Objects.equals(plan.mpsEndpoint(),settings.mpsEndpoint)
        || settings.secretId.isEmpty() || settings.secretKey.isEmpty()))) throw ProviderSettings.unavailable();
  }
}
