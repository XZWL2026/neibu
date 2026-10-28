package com.xzwl.cloud.media.application.task;

import com.xzwl.cloud.media.domain.task.*;
import com.xzwl.cloud.media.domain.generation.MediaType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import com.xzwl.cloud.media.application.provider.*;
import java.time.Clock;

/** Only a successful owned generation output may become an enhancement source. */
public final class EnhancementQuoteService {
  private final MediaTaskService tasks;
  private final MediaTaskStore store;
  private final StoragePort storage;
  private final BillingPort billing;
  private final MpsConfigurationStore templates;
  private final MpsConfigurationProbe probe;
  private final EnhancementQuoteStore quotes;
  private final Clock clock;
  public EnhancementQuoteService(MediaTaskService tasks,MediaTaskStore store,StoragePort storage,BillingPort billing,MpsConfigurationStore templates,MpsConfigurationProbe probe,EnhancementQuoteStore quotes,Clock clock) {
    this.tasks=tasks;this.store=store;this.storage=storage;this.billing=billing;this.templates=templates;this.probe=probe;this.quotes=quotes;this.clock=clock;
  }
  public Options options(String owner,String taskId,int index) {
    var source=source(owner,taskId,index);
    var config=templates.load();
    var prices=billing.availablePrices(owner);
    var offered=new ArrayList<TargetQuote>();
    for(String target:List.of("720p","1080p","2K","4K")) {
      if(MpsConfigurationStore.resolutionRank(target)<=MpsConfigurationStore.resolutionRank(source.resolution())) continue;
      if(prices.stream().noneMatch(p->"ENHANCEMENT".equals(p.type()) && "video-enhancement".equals(p.model()) && target.equals(p.resolution()))) continue;
      var template=probe.templates(config).stream().filter(t->t.enabled() && target.equals(t.outputResolution()) && t.acceptedInputResolutions().contains(source.resolution())).findFirst();
      if(template.isEmpty()) continue;
      var evidence=probe.test(config.version(),template.get().id(),source.aspectRatio());
      if(!evidence.success() || evidence.width()<=source.video().width() || evidence.height()<=source.video().height()) continue;
      BillingPort.EnhancementCost cost;
      try {cost=billing.enhancementQuote(owner,target,source.billableSeconds());}
      catch(DefinitiveRejectionException missingPrice) {continue;}
      var quote=new EnhancementQuoteStore.Quote(UUID.randomUUID().toString(),owner,source,target,config.version(),template.get().id(),evidence,cost,clock.instant().plusSeconds(900));
      quotes.save(quote);offered.add(view(quote));
    }
    return new Options(taskId,index,source.fileId(),source.resolution(),source.aspectRatio(),source.video().durationSeconds(),source.billableSeconds(),List.copyOf(offered));
  }
  public EnhancementQuoteStore.Quote confirmed(String owner,String quoteId) {
    var quote=quotes.find(owner,quoteId).orElseThrow(()->new TaskNotFoundException("quote not found"));
    if(!quote.expiresAt().isAfter(clock.instant())) throw new TaskConflictException("enhancement quote expired");
    var live=source(owner,quote.source().taskId(),quote.source().outputIndex());
    if(!live.fileId().equals(quote.source().fileId()) || !live.resolution().equals(quote.source().resolution()) || !live.aspectRatio().equals(quote.source().aspectRatio())
        || live.billableSeconds()!=quote.source().billableSeconds() || !live.video().width().equals(quote.source().video().width()) || !live.video().height().equals(quote.source().video().height())
        || !live.video().durationSeconds().equals(quote.source().video().durationSeconds())) throw new TaskConflictException("enhancement source changed");
    return new EnhancementQuoteStore.Quote(quote.id(),quote.ownerId(),live,quote.targetResolution(),quote.templateVersion(),quote.templateId(),quote.templateEvidence(),quote.cost(),quote.expiresAt());
  }
  private TargetQuote view(EnhancementQuoteStore.Quote quote) {
    var cost=quote.cost();return new TargetQuote(quote.id(),quote.targetResolution(),cost.amount().setScale(4).toPlainString(),cost.rate().setScale(4).toPlainString(),cost.points().setScale(4).toPlainString(),quote.expiresAt(),true);
  }
  public Source source(String owner,String taskId,int index) {
    var task=tasks.get(owner,taskId);
    if(!store.isHistoryVisible(owner,taskId)) throw new TaskNotFoundException("task not found");
    if(task.data().request().type()!=MediaType.VIDEO || task.data().request().operation()!=TaskOperation.GENERATION
        || (task.status()!=TaskStatus.SUCCEEDED && task.status()!=TaskStatus.PARTIAL_SUCCESS))
      throw new DefinitiveRejectionException("INVALID_ENHANCEMENT_SOURCE");
    var output=tasks.results(owner,taskId).stream().filter(r->r.index()==index && r.fileId()!=null).findFirst()
        .orElseThrow(()->new TaskNotFoundException("native output not found"));
    var video=storage.probeVideo(owner,output.fileId());
    if(video.durationSeconds()==null || !Double.isFinite(video.durationSeconds()) || video.durationSeconds()<=0 || video.durationSeconds()>3600
        || video.width()==null || video.height()==null || video.width()<=0 || video.height()<=0
        || video.mimeType()==null || !video.mimeType().startsWith("video/")) throw new DefinitiveRejectionException("INVALID_ENHANCEMENT_SOURCE");
    MediaReference.https(video.url());
    int seconds=BigDecimal.valueOf(video.durationSeconds()).setScale(0,java.math.RoundingMode.CEILING).intValueExact();
    double ratio=(double)video.width()/video.height();
    String aspect=null;
    for(String candidate:List.of("16:9","9:16","1:1","4:3","3:4","21:9")) {
      var parts=candidate.split(":");double expected=Double.parseDouble(parts[0])/Double.parseDouble(parts[1]);
      if(Math.abs(ratio/expected-1)<0.025) {aspect=candidate;break;}
    }
    if(aspect==null) throw new DefinitiveRejectionException("INVALID_ENHANCEMENT_SOURCE");
    String resolution=switch(task.data().request().resolution().toLowerCase(java.util.Locale.ROOT)) {case "480p"->"480p";case "720p"->"720p";case "1080p"->"1080p";case "2k"->"2K";case "4k"->"4K";default->null;};
    if(resolution==null) throw new DefinitiveRejectionException("INVALID_ENHANCEMENT_SOURCE");
    int tier=switch(resolution) {case "480p"->480;case "720p"->720;case "1080p"->1080;case "2K"->1440;default->2160;};
    double shape=Math.max(ratio,1/ratio),shortEdge=Math.min(video.width(),video.height());
    double areaShort=tier*Math.sqrt(16/(9*shape));
    boolean nativeGeometry=Math.abs(shortEdge/tier-1)<0.04 || Math.abs(shortEdge/areaShort-1)<0.04;
    // Ultrawide providers may retain the ordinary widescreen long edge and crop vertically.
    if(shape>2) nativeGeometry|=Math.abs(shortEdge/(tier*16/(9*shape))-1)<0.04;
    if(!nativeGeometry) throw new DefinitiveRejectionException("INVALID_ENHANCEMENT_SOURCE");
    return new Source(taskId,index,output.fileId(),resolution,aspect,seconds,video);
  }
  public record Source(String taskId,int outputIndex,String fileId,String resolution,String aspectRatio,int billableSeconds,StoragePort.VideoSource video) {}
  public record TargetQuote(String quoteId,String targetResolution,String amount,String rate,String points,Instant expiresAt,boolean executionSupported) {}
  public record Options(String taskId,int outputIndex,String fileId,String sourceResolution,String aspectRatio,double actualDurationSeconds,int billableSeconds,List<TargetQuote> targets) {}
}
