package com.xzwl.cloud.billing.infrastructure;
import com.xzwl.cloud.billing.application.pricing.MediaPriceStore;
import com.xzwl.cloud.billing.domain.pricing.MediaPrice;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
/** Catalog lock makes metadata and the complete dimension set one consistent snapshot. */
public final class JdbcMediaPriceStore implements MediaPriceStore {
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  public JdbcMediaPriceStore(JdbcTemplate jdbc,PlatformTransactionManager manager) { this.jdbc=jdbc;this.tx=new TransactionTemplate(manager); }
  public Snapshot load() { return tx.execute(s->loadLocked()); }
  private Snapshot loadLocked() {
    var state=jdbc.queryForObject("SELECT version,managed FROM media_price_catalog WHERE id=1 FOR UPDATE",
        (rs,n)->new Snapshot(rs.getLong(1),rs.getBoolean(2),List.of()));
    var prices=jdbc.query("SELECT * FROM media_price ORDER BY media_type,account_type,model,resolution",
        (rs,n)->new MediaPrice(rs.getString("media_type"),rs.getString("account_type"),rs.getString("model"),
            rs.getString("resolution"),rs.getString("unit"),rs.getString("points_per_unit"),rs.getString("yuan_per_unit"),rs.getBoolean("enabled")));
    return new Snapshot(state.version(),state.managed(),List.copyOf(prices));
  }
  public Snapshot save(long expectedVersion,List<MediaPrice> prices) {
    if(expectedVersion<1 || prices==null || prices.size()>1000) throw new IllegalArgumentException("invalid catalog");
    var requested=List.copyOf(prices);
    if(requested.stream().map(MediaPrice::dimension).distinct().count()!=requested.size()) throw new IllegalArgumentException("duplicate pricing dimension");
    return tx.execute(s->{
      var current=loadLocked();
      if(current.version()!=expectedVersion) throw new IllegalStateException("pricing version changed");
      jdbc.update("DELETE FROM media_price");
      for(var p:requested) jdbc.update("INSERT INTO media_price(media_type,account_type,model,resolution,unit,points_per_unit,yuan_per_unit,enabled) VALUES(?,?,?,?,?,?,?,?)",
          p.type(),p.accountType(),p.model(),p.resolution(),p.unit(),p.pointsPerUnit()==null ? null : new BigDecimal(p.pointsPerUnit()),p.yuanPerUnit()==null ? null : new BigDecimal(p.yuanPerUnit()),p.enabled());
      jdbc.update("UPDATE media_price_catalog SET version=version+1,managed=TRUE WHERE id=1");
      return loadLocked();
    });
  }

  private final com.fasterxml.jackson.databind.ObjectMapper json=new com.fasterxml.jackson.databind.ObjectMapper();
  public Snapshot loadForOwner(String ownerId,String accountType) {
    return tx.execute(s->{var global=loadLocked();var resolved=loadOwnerLocked(ownerId,accountType,global);return new Snapshot(global.version(),global.managed(),resolved.prices());});
  }
  public ApiSnapshot loadOwner(String ownerId,String accountType) { return tx.execute(s->loadOwnerLocked(ownerId,accountType,loadLocked())); }
  private ApiSnapshot loadOwnerLocked(String ownerId,String accountType,Snapshot global) {
    MybatisLedgerStore.id(ownerId);accountType(accountType);
    jdbc.update("INSERT IGNORE INTO api_media_price_catalog(owner_id) VALUES(?)",ownerId);
    long version=jdbc.queryForObject("SELECT version FROM api_media_price_catalog WHERE owner_id=? FOR UPDATE",Long.class,ownerId);
    var defaults=global.prices().stream().filter(p->p.accountType().equals(accountType)&&p.enabled()).toList();
    var overrides=jdbc.query("SELECT * FROM api_media_price_override WHERE owner_id=? AND account_type=? ORDER BY media_type,model,resolution",
        (rs,n)->new MediaPrice(rs.getString("media_type"),accountType,rs.getString("model"),rs.getString("resolution"),
            rs.getString("media_type").equals("IMAGE")?"IMAGE":"SECOND",null,rs.getString("yuan_per_unit"),true),ownerId,accountType);
    var indexed=new HashMap<String,MediaPrice>();overrides.forEach(p->indexed.put(p.dimension(),p));
    return new ApiSnapshot(version,defaults,List.copyOf(overrides),defaults.stream().map(p->indexed.getOrDefault(p.dimension(),p)).toList());
  }
  public ApiSnapshot saveOwner(String ownerId,String accountType,String requestId,String operatorId,long expectedVersion,List<MediaPrice> prices) {
    MybatisLedgerStore.id(ownerId);MybatisLedgerStore.id(requestId);MybatisLedgerStore.id(operatorId);accountType(accountType);
    if(expectedVersion<0||prices==null||prices.size()>1000)throw new IllegalArgumentException("invalid user prices");
    var requested=prices.stream().sorted(Comparator.comparing(MediaPrice::dimension)).toList();
    if(requested.stream().map(MediaPrice::dimension).distinct().count()!=requested.size())throw new IllegalArgumentException("duplicate pricing dimension");
    String encoded=encode(requested);
    return tx.execute(s->{
      var global=loadLocked();var current=loadOwnerLocked(ownerId,accountType,global);
      var prior=jdbc.queryForList("SELECT * FROM api_media_price_audit WHERE request_id=?",requestId);
      if(!prior.isEmpty()) {
        var row=prior.getFirst();
        if(!ownerId.equals(row.get("owner_id"))||!accountType.equals(row.get("account_type"))||!operatorId.equals(row.get("operator_id"))||((Number)row.get("expected_version")).longValue()!=expectedVersion||!encoded.equals(row.get("prices_json")))
          throw new IllegalStateException("pricing request conflict");
        return decode((String)row.get("snapshot_json"),ApiSnapshot.class);
      }
      if(current.version()!=expectedVersion)throw new IllegalStateException("pricing version changed");
      var allowed=current.defaults().stream().map(MediaPrice::dimension).collect(java.util.stream.Collectors.toSet());
      if(requested.stream().anyMatch(p->!p.accountType().equals(accountType)||!p.enabled()||p.yuanPerUnit()==null||!allowed.contains(p.dimension())))
        throw new IllegalArgumentException("user override must use an enabled catalog dimension and yuan price");
      jdbc.update("DELETE FROM api_media_price_override WHERE owner_id=? AND account_type=?",ownerId,accountType);
      for(var p:requested)jdbc.update("INSERT INTO api_media_price_override(owner_id,account_type,media_type,model,resolution,yuan_per_unit) VALUES(?,?,?,?,?,?)",ownerId,accountType,p.type(),p.model(),p.resolution(),new BigDecimal(p.yuanPerUnit()));
      jdbc.update("UPDATE api_media_price_catalog SET version=version+1 WHERE owner_id=?",ownerId);
      var result=loadOwnerLocked(ownerId,accountType,global);
      jdbc.update("INSERT INTO api_media_price_audit(request_id,owner_id,account_type,operator_id,expected_version,version,prices_json,snapshot_json,changed_at) VALUES(?,?,?,?,?,?,?,?,?)",
          requestId,ownerId,accountType,operatorId,expectedVersion,result.version(),encoded,encode(result),java.time.Instant.now().toString());
      return result;
    });
  }
  public List<ApiAudit> ownerAudit(String ownerId,String accountType) {
    MybatisLedgerStore.id(ownerId);accountType(accountType);
    return jdbc.query("SELECT * FROM api_media_price_audit WHERE owner_id=? AND account_type=? ORDER BY version DESC LIMIT 100",
        (rs,n)->new ApiAudit(rs.getString("request_id"),rs.getString("operator_id"),rs.getLong("version"),rs.getString("changed_at"),List.of(decode(rs.getString("prices_json"),MediaPrice[].class))),ownerId,accountType);
  }
  private static void accountType(String value) {
    if(value==null||!Set.of("PERSONAL","ENTERPRISE","API").contains(value))throw new IllegalArgumentException("invalid account type");
  }
  private String encode(Object value) { try{return json.writeValueAsString(value);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException("invalid prices",e);} }
  private <T> T decode(String value,Class<T> type) { try{return json.readValue(value,type);}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("invalid stored prices",e);} }
}
