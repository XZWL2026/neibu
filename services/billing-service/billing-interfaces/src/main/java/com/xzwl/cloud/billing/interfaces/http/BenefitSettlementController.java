package com.xzwl.cloud.billing.interfaces.http;
import com.fasterxml.jackson.databind.*;
import com.xzwl.cloud.billing.application.ledger.BenefitSettlementStore;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/internal/v1/billing")
public final class BenefitSettlementController {
  private final BenefitSettlementStore store;private final ObjectMapper json;
  public BenefitSettlementController(BenefitSettlementStore store,ObjectMapper json){this.store=store;this.json=json;}
  @GetMapping("/referral-rewards/{ownerId}/balance") public java.util.Map<String,String> balance(@PathVariable("ownerId")String ownerId) {
    return java.util.Map.of("debtPoints4",store.debtPoints(ownerId));
  }
  @PostMapping("/media-settlements") public void settle(@RequestBody JsonNode body) {store.settle(read(body,BenefitSettlementStore.Settlement.class));}
  @PostMapping("/referral-rewards") public BenefitSettlementStore.Receipt reward(@RequestBody JsonNode body) {return store.reward(read(body,BenefitSettlementStore.Reward.class));}
  private <T>T read(JsonNode body,Class<T> type) {
    if(!body.isObject())throw new IllegalArgumentException("object required");
    var fields=java.util.Arrays.stream(type.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
    if(body.size()!=fields.size())throw new IllegalArgumentException("exact fields required");
    for(String field:fields)if(!body.has(field)||!body.get(field).isTextual())throw new IllegalArgumentException("string fields required");
    try{return json.treeToValue(body,type);}catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw new IllegalArgumentException("invalid command",failure);}
  }
}
