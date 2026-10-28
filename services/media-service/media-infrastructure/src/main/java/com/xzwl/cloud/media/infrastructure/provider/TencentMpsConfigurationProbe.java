package com.xzwl.cloud.media.infrastructure.provider;

import com.xzwl.cloud.media.application.provider.*;
import com.xzwl.cloud.media.application.task.*;
import java.time.Clock;
import java.util.List;

public final class TencentMpsConfigurationProbe implements MpsConfigurationProbe {
  private final MpsConfigurationStore store;
  private final ProviderSettings settings;
  private final TencentMpsAdapter mps;
  private final Clock clock;
  public TencentMpsConfigurationProbe(MpsConfigurationStore store,ProviderSettings settings,TencentMpsAdapter mps,Clock clock) {
    this.store=store;this.settings=settings;this.mps=mps;this.clock=clock;
  }
  public List<MpsConfigurationStore.EnhancementTemplate> templates(MpsConfigurationStore.Configuration config) {
    return settings.withMpsConfiguration(config).projectedTemplates();
  }
  public Result test(long expectedVersion,String templateId,String aspectRatio) {
    var config=store.load();
    if(config.version()!=expectedVersion) throw new MpsConfigurationStore.VersionConflict();
    var live=settings.withMpsConfiguration(config);
    var selected=live.projectedTemplates().stream().filter(t->t.id().equals(templateId)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown MPS template"));
    long started=System.nanoTime();
    try {
      var plan=mps.prepare(live.enhancementPlan(selected,aspectRatio),live);
      var evidence=plan.enhancement();
      return new Result(config.version(),true,clock.instant().toString(),elapsed(started),evidence.width(),evidence.height(),evidence.definitions(),evidence.videoBitrateKbps(),"配置校验通过");
    } catch(DefinitiveRejectionException rejected) {
      return new Result(config.version(),false,clock.instant().toString(),elapsed(started),0,0,List.of(),null,"配置校验失败，请检查模板、规格与访问权限。");
    } catch(DependencyUnavailableException unavailable) {
      return new Result(config.version(),false,clock.instant().toString(),elapsed(started),0,0,List.of(),null,"配置查询失败，请检查连接后重试。");
    }
  }
  private static long elapsed(long start) { return Math.max(0,(System.nanoTime()-start)/1_000_000); }
}
