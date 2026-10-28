package com.xzwl.cloud.media.application.task;
import com.xzwl.cloud.media.application.provider.MpsConfigurationProbe;
import java.time.Instant;
import java.util.Optional;
/** Server-owned confirmation facts; never reconstructed from a browser amount or duration. */
public interface EnhancementQuoteStore {
  void save(Quote quote);
  Optional<Quote> find(String ownerId,String quoteId);
  record Quote(String id,String ownerId,EnhancementQuoteService.Source source,String targetResolution,long templateVersion,String templateId,
      MpsConfigurationProbe.Result templateEvidence,BillingPort.EnhancementCost cost,Instant expiresAt) {}
}
