package com.xzwl.cloud.media.interfaces.http;

import com.xzwl.cloud.media.application.provider.MpsConfigurationStore;
import com.xzwl.cloud.media.application.provider.MpsConfigurationProbe;
import java.util.List;
import com.xzwl.cloud.media.application.provider.MpsConfigurationStore.Configuration;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/media/mps-configuration")
public final class MpsConfigurationController {
  private final MpsConfigurationStore store;
  private final MpsConfigurationProbe probe;
  public MpsConfigurationController(MpsConfigurationStore store,MpsConfigurationProbe probe) { this.store=store;this.probe=probe; }
  @GetMapping public View get(HttpServletRequest request,HttpServletResponse response) {
    response.setHeader("Cache-Control","no-store");PrimaryAdministrator.require(request);
    return view(store.load());
  }
  @PutMapping public View save(HttpServletRequest request,HttpServletResponse response,@RequestBody SaveRequest body) {
    response.setHeader("Cache-Control","no-store");PrimaryAdministrator.require(request);
    try { return view(store.save(new Configuration(body.expectedVersion(),body.secretId(),body.secretKey(),body.region(),body.bucket(),body.bucketRegion(),body.outputDir(),body.template720p(),body.template1080p(),body.template4k(),body.enhancementTemplates(),body.bitrateProfiles()))); }
    catch(MpsConfigurationStore.VersionConflict conflict) { throw new ResponseStatusException(HttpStatus.CONFLICT); }
    catch(IllegalArgumentException|NullPointerException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
  }
  @PostMapping("/test") public MpsConfigurationProbe.Result test(HttpServletRequest request,HttpServletResponse response,@RequestBody TestRequest body) {
    response.setHeader("Cache-Control","no-store");PrimaryAdministrator.require(request);
    try { return probe.test(body.expectedVersion(),body.templateId(),body.aspectRatio()); }
    catch(MpsConfigurationStore.VersionConflict conflict) { throw new ResponseStatusException(HttpStatus.CONFLICT); }
    catch(IllegalArgumentException|NullPointerException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
  }
  public record TestRequest(long expectedVersion,String templateId,String aspectRatio) {}
  private View view(Configuration c) { return new View(c.version(),c.secretId(),c.secretKey()!=null&&!c.secretKey().isBlank(),c.region(),c.bucket(),c.bucketRegion(),c.outputDir(),c.template720p(),c.template1080p(),c.template4k(),c.version()>0?"DATABASE":"ENVIRONMENT",probe.templates(c),c.bitrateProfiles()==null?List.of():c.bitrateProfiles(),c.enhancementTemplates()!=null); }
  public record View(long version,String secretId,boolean secretKeyConfigured,String region,String bucket,String bucketRegion,String outputDir,long template720p,long template1080p,long template4k,String source,List<MpsConfigurationStore.EnhancementTemplate> enhancementTemplates,List<MpsConfigurationStore.BitrateProfile> bitrateProfiles,boolean policyConfigured) {}
  public record SaveRequest(long expectedVersion,String secretId,String secretKey,String region,String bucket,String bucketRegion,String outputDir,long template720p,long template1080p,long template4k,List<MpsConfigurationStore.EnhancementTemplate> enhancementTemplates,List<MpsConfigurationStore.BitrateProfile> bitrateProfiles) {
    @Override public String toString() { return "MpsSaveRequest[credentials=REDACTED]"; }
  }
}
