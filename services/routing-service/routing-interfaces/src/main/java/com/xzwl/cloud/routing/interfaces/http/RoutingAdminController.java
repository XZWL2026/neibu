package com.xzwl.cloud.routing.interfaces.http;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xzwl.cloud.routing.application.provider.*;
import com.xzwl.cloud.routing.application.model.*;
import com.xzwl.cloud.web.PrimaryAdministrator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/v1/admin/routing")
public final class RoutingAdminController {
  private final ProviderConfigurationStore providerStore;
  private final ModelConfigurationStore modelStore;
  private final ConfigureProviderHandler providers;
  private final ConfigureModelHandler models;
  private final ObjectMapper json;
  public RoutingAdminController(ProviderConfigurationStore providerStore,ModelConfigurationStore modelStore,
      ConfigureProviderHandler providers,ConfigureModelHandler models,ObjectMapper json) {
    this.providerStore=providerStore;this.modelStore=modelStore;this.providers=providers;this.models=models;this.json=json;
  }
  @GetMapping("/providers") public List<ProviderView> providers(HttpServletRequest request) {
    PrimaryAdministrator.require(request);
    return providerStore.findProviders().stream().map(p->new ProviderView(p.id(),p.displayName(),p.endpoint(),p.enabled())).toList();
  }
  @PutMapping("/providers/{providerId}") public ProviderView saveProvider(HttpServletRequest request,
      @PathVariable("providerId") String providerId,@RequestBody ProviderInput body) {
    PrimaryAdministrator.require(request);
    try {
      identifier(providerId);text(body.displayName());
      RoutingController.validatedEndpoint(body.endpoint());
      if(body.endpoint().length()>512) throw new IllegalArgumentException();
      var p=providers.handle(new ConfigureProviderCommand(providerId,body.displayName(),body.endpoint(),body.enabled(),"{}"));
      return new ProviderView(p.id(),p.displayName(),p.endpoint(),p.enabled());
    } catch(IllegalArgumentException | NullPointerException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
  }
  @GetMapping("/models") public List<ModelView> models(HttpServletRequest request) {
    PrimaryAdministrator.require(request);
    return modelStore.findAll().stream().map(this::view).toList();
  }
  @PutMapping("/models/{modelId}") public ModelView saveModel(HttpServletRequest request,
      @PathVariable("modelId") String modelId,@RequestBody ModelInput body) {
    PrimaryAdministrator.require(request);
    try {
      identifier(modelId);identifier(body.providerId());identifier(body.providerModel());
      validate(body.capability(),body.providerModel());
      validatePolicy(body.timeoutSeconds(),body.retryCount());
      var configuration=json.valueToTree(body.capability());
      if(body.timeoutSeconds()!=null || body.retryCount()!=null) {
        var policy=((com.fasterxml.jackson.databind.node.ObjectNode)configuration).putObject("requestPolicy");
        if(body.timeoutSeconds()!=null) policy.put("timeoutSeconds",body.timeoutSeconds());
        if(body.retryCount()!=null) policy.put("retryCount",body.retryCount());
      }
      return view(models.handle(new ConfigureModelCommand(modelId,body.providerId(),body.providerModel(),
          json.writeValueAsString(configuration),!Boolean.FALSE.equals(body.enabled()))));
    } catch(Exception invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
  }
  @DeleteMapping("/models/{modelId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteModel(HttpServletRequest request, @PathVariable("modelId") String modelId) {
    PrimaryAdministrator.require(request);
    try { identifier(modelId); }
    catch (IllegalArgumentException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    models.delete(modelId);
  }
  private ModelView view(com.xzwl.cloud.routing.domain.model.ModelConfiguration model) {
    Capability capability=null;Integer timeout=null,retries=null;
    try {
      var configuration=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(model.configurationJson());
      var policy=configuration.remove("requestPolicy");
      if(policy!=null && policy.isObject()) {
        timeout=policy.hasNonNull("timeoutSeconds")?policy.get("timeoutSeconds").intValue():null;
        retries=policy.hasNonNull("retryCount")?policy.get("retryCount").intValue():null;
        validatePolicy(timeout,retries);
      }
      capability=json.treeToValue(configuration,Capability.class);
    } catch(Exception invalid) { capability=null; }
    return new ModelView(model.modelId(),model.providerId(),model.providerModel(),capability,model.enabled(),timeout,retries);
  }
  private static void validatePolicy(Integer timeout,Integer retries) {
    if(timeout!=null && (timeout<1 || timeout>180) || retries!=null && (retries<0 || retries>3)) throw new IllegalArgumentException();
  }
  private static void validate(Capability c,String model) {
    Objects.requireNonNull(c);text(c.displayName());
    if(!Set.of("IMAGE","VIDEO").contains(c.type())) throw new IllegalArgumentException();
    if(c.resolutions()==null || c.resolutions().isEmpty() || c.resolutions().size()>30) throw new IllegalArgumentException();
    c.resolutions().forEach(RoutingAdminController::identifier);
    if(new HashSet<>(c.resolutions()).size()!=c.resolutions().size()) throw new IllegalArgumentException();
    if(c.aspectRatios()==null || c.aspectRatios().isEmpty() || c.aspectRatios().size()>6
        || !Set.of("1:1","16:9","9:16","4:3","3:4","21:9").containsAll(c.aspectRatios())) throw new IllegalArgumentException();
    if(new HashSet<>(c.aspectRatios()).size()!=c.aspectRatios().size()) throw new IllegalArgumentException();
    if("IMAGE".equals(c.type()) ? c.maxCount()<1 || c.maxCount()>4 || c.minDurationSeconds()!=0 || c.maxDurationSeconds()!=0
        : c.maxCount()!=1 || c.minDurationSeconds()<1 || c.maxDurationSeconds()<c.minDurationSeconds() || c.maxDurationSeconds()>600)
      throw new IllegalArgumentException();
    // These native image models accept resolution tiers and ratios, not a fixed pixel-size matrix.
    boolean nativeImage=Set.of("gemini-3-pro-image-preview","gemini-3.1-flash-image-preview",
        "gpt-image-2","gpt-image-2-2K","gpt-image-2-4K","gpt-image-2.5","gpt-image-2.5-flare","gpt-image-2.5-sunburst","nano_banana_2","nano_banana_2-2K","nano_banana_2-4K","nano_banana_pro-1K","nano_banana_pro-2K","nano_banana_pro-4K").contains(model);
    if("IMAGE".equals(c.type()) && !(nativeImage && c.sizes()==null)) {
      if(c.sizes()==null || !c.sizes().keySet().equals(new HashSet<>(c.resolutions()))) throw new IllegalArgumentException();
      for(var ratios:c.sizes().values())
        if(ratios==null || !ratios.keySet().equals(new HashSet<>(c.aspectRatios()))) throw new IllegalArgumentException();
    }
    if(c.sizes()!=null) {
      if(!"IMAGE".equals(c.type()) || !new HashSet<>(c.resolutions()).containsAll(c.sizes().keySet())) throw new IllegalArgumentException();
      c.sizes().values().forEach(ratios->{
        if(ratios==null || !new HashSet<>(c.aspectRatios()).containsAll(ratios.keySet())) throw new IllegalArgumentException();
        ratios.values().forEach(size->{if(size==null || !size.matches("[1-9][0-9]{1,4}x[1-9][0-9]{1,4}")) throw new IllegalArgumentException();});
      });
    }
  }
  private static void identifier(String value) { if(value==null || !value.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException(); }
  private static void text(String value) { if(value==null || value.isBlank() || value.length()>128) throw new IllegalArgumentException(); }
  public record ProviderInput(String displayName,String endpoint,boolean enabled) {}
  public record ProviderView(String providerId,String displayName,String endpoint,boolean enabled) {}
  public record ModelInput(String providerId,String providerModel,Capability capability,Boolean enabled,Integer timeoutSeconds,Integer retryCount) {}
  public record ModelView(String modelId,String providerId,String providerModel,Capability capability,boolean enabled,Integer timeoutSeconds,Integer retryCount) {}
  public record Capability(String type,String displayName,List<String> resolutions,List<String> aspectRatios,
      int maxCount,int minDurationSeconds,int maxDurationSeconds,Map<String,Map<String,String>> sizes) {}
}
