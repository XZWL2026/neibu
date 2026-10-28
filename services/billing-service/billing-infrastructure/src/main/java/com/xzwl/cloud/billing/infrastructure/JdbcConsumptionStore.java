package com.xzwl.cloud.billing.infrastructure;

import com.xzwl.cloud.billing.application.ledger.ConsumptionStore;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Only Billing-owned tables participate in this query; Key enabled state is irrelevant. */
public final class JdbcConsumptionStore implements ConsumptionStore {
  private final NamedParameterJdbcTemplate sql;
  private static final String BASE=" FROM wallet_ledger l LEFT JOIN media_charge_receipt r ON r.operation_id=l.source_id AND r.key_id=l.wallet_id";
  private static final String TIME="CAST(REPLACE(REPLACE(SUBSTRING(l.recorded_at,1,26),'T',' '),'Z','') AS DATETIME(6))";
  private static final String PAID_TIME=TIME.replace("l.recorded_at","paid_at");
  private static final String FIELDS="""
      SELECT l.*,r.owner_id,r.request_facts,r.account_type,r.amount4,r.rate4,
        COALESCE((SELECT SUM(f.points4) FROM wallet_ledger f
          WHERE f.wallet_id=l.wallet_id AND f.source_id=l.source_id AND f.kind='REFUND'),0) refunded
      """;
  public JdbcConsumptionStore(JdbcTemplate jdbc) { sql=new NamedParameterJdbcTemplate(jdbc); }
  public DirectoryFacts directoryFacts(List<DirectoryOwner> owners,java.time.Instant from,java.time.Instant to) {
    if(owners==null||owners.isEmpty()||owners.size()>100||to==null||from!=null&&!from.isBefore(to)
        ||owners.stream().anyMatch(o->o==null||o.ownerId()==null||!o.ownerId().matches("[A-Za-z0-9._:-]{1,128}")
            ||o.keyId()!=null&&!o.keyId().matches("[A-Za-z0-9._:-]{1,128}")))throw new IllegalArgumentException("invalid directory batch");
    var ids=owners.stream().map(DirectoryOwner::ownerId).distinct().toList();
    var args=new HashMap<String,Object>();args.put("owners",ids);args.put("until",utc(to));
    var paid=sql.queryForList("SELECT DISTINCT user_id FROM recharge_credit_record WHERE user_id IN (:owners) AND "+PAID_TIME+"<:until",args,String.class);
    String where=" AND "+TIME+"<:until";
    if(from!=null){args.put("since",utc(from));where+=" AND "+TIME+">=:since";}
    var active=new HashSet<>(sql.queryForList("SELECT DISTINCT r.owner_id"+BASE+" WHERE l.kind='CONSUME' AND r.owner_id IN (:owners)"+where,args,String.class));
    var keyOwners=new HashMap<String,String>();
    owners.forEach(o->{if(o.keyId()!=null)keyOwners.put(o.keyId(),o.ownerId());});
    List<DirectoryBalance> balances=List.of();
    if(!keyOwners.isEmpty()) {
      args.put("keys",keyOwners.keySet());
      // Primary key ownership also covers legacy consumption receipts without owner snapshots.
      for(String key:sql.queryForList("SELECT DISTINCT l.wallet_id FROM wallet_ledger l WHERE l.kind='CONSUME' AND l.wallet_id IN (:keys)"+where,args,String.class))active.add(keyOwners.get(key));
      balances=sql.query("SELECT wallet_id,available_points4 FROM wallet WHERE wallet_id IN (:keys)",args,
          (rs,n)->new DirectoryBalance(rs.getString("wallet_id"),rs.getBigDecimal("available_points4").setScale(4).toPlainString()));
      var known=new HashMap<String,String>();balances.forEach(balance->known.put(balance.keyId(),balance.availablePoints4()));
      // MybatisLedgerStore.balance defines an unopened wallet as zero; return that fact explicitly, never as an omitted response row.
      balances=keyOwners.keySet().stream().map(key->new DirectoryBalance(key,known.getOrDefault(key,"0.0000"))).toList();
    }
    return new DirectoryFacts(paid,List.copyOf(active),balances);
  }
  public List<ApiDailyFact> apiDailyFacts(List<DirectoryOwner> owners,java.time.Instant from,java.time.Instant to) {
    if(owners==null||owners.isEmpty()||owners.size()>100||from==null||to==null||!from.isBefore(to))throw new IllegalArgumentException("invalid daily batch");
    var result=new LinkedHashMap<String,DailyTotal>();var keyOwners=new HashMap<String,String>();
    for(var owner:owners) {
      MybatisLedgerStore.id(owner.ownerId());if(result.put(owner.ownerId(),new DailyTotal())!=null)throw new IllegalArgumentException("duplicate owner");
      if(owner.keyId()!=null){MybatisLedgerStore.id(owner.keyId());if(keyOwners.put(owner.keyId(),owner.ownerId())!=null)throw new IllegalArgumentException("duplicate key");}
    }
    var args=new HashMap<String,Object>();args.put("owners",result.keySet());
    // Include zero-priced receipts and use the original ledger time for pre-timestamp receipts.
    args.put("until",utc(to));
    String source="SELECT r.*,COALESCE(l.recorded_at,r.charged_at) ledger_at,COALESCE((SELECT SUM(f.points4) FROM wallet_ledger f WHERE f.wallet_id=r.key_id AND f.source_id=r.operation_id AND f.kind='REFUND' AND "+TIME.replace("l.","f.")+"<:until),0) refunded FROM media_charge_receipt r LEFT JOIN wallet_ledger l ON l.source_id=r.operation_id AND l.wallet_id=r.key_id AND l.kind='CONSUME' WHERE r.owner_id IN (:owners)";
    sql.query("SELECT owner_id,request_facts,COALESCE(charged_at,ledger_at) at,CASE WHEN refunded=0 THEN amount4 WHEN rate4>0 AND points4>0 AND refunded BETWEEN 0 AND points4 THEN CASE WHEN refunded=points4 THEN 0 ELSE TRUNCATE(amount4-refunded/rate4,4) END ELSE NULL END amount4 FROM ("+source+") daily",args,rs->{
      addDaily(result.get(rs.getString("owner_id")),rs.getString("at"),rs.getString("request_facts"),rs.getBigDecimal("amount4"),from,to);
    });
    if(!keyOwners.isEmpty()) {
      args.put("keys",keyOwners.keySet());
      sql.query("SELECT l.wallet_id,l.recorded_at FROM wallet_ledger l LEFT JOIN media_charge_receipt r ON r.operation_id=l.source_id AND r.key_id=l.wallet_id WHERE l.kind='CONSUME' AND l.wallet_id IN (:keys) AND r.operation_id IS NULL",args,rs->{
        addDaily(result.get(keyOwners.get(rs.getString("wallet_id"))),rs.getString("recorded_at"),null,null,from,to);
      });
    }
    return result.entrySet().stream().map(e->new ApiDailyFact(e.getKey(),e.getValue().calls,e.getValue().seconds,e.getValue().amount==null?null:e.getValue().amount.setScale(4).toPlainString(),e.getValue().undated?null:e.getValue().last)).toList();
  }
  private static final class DailyTotal { Long calls=0L;Long seconds=0L;BigDecimal amount=BigDecimal.ZERO;java.time.Instant last;boolean undated; }
  private static void addDaily(DailyTotal total,String timestamp,String facts,BigDecimal amount,java.time.Instant from,java.time.Instant to) {
    if(timestamp==null) {
      // A receipt without either timestamp cannot be assigned to today or ordered as the last call.
      total.undated=true;total.calls=null;total.seconds=null;
      if(amount==null||amount.signum()!=0)total.amount=null;
      return;
    }
    var at=java.time.Instant.parse(timestamp);
    if(total.last==null||at.isAfter(total.last))total.last=at;
    if(at.isBefore(from)||!at.isBefore(to))return;
    if(total.calls!=null)total.calls++;total.amount=total.amount==null||amount==null?null:total.amount.add(amount);
    String[] parts=facts==null?new String[0]:facts.split("\n",-1);
    if(total.seconds==null)return;
    if(parts.length==6&&"IMAGE".equals(parts[1]))return;
    if(parts.length!=6||!"VIDEO".equals(parts[1])||!parts[5].matches("[0-9]+")){total.seconds=null;return;}
    try {total.seconds=Math.addExact(total.seconds,Long.parseLong(parts[5]));}catch(ArithmeticException|NumberFormatException invalid){total.seconds=null;}
  }
  public Page search(Filter f) {
    if(f.offset()<0 || f.limit()<1 || f.limit()>100 || f.to()==null
        || f.from()!=null && f.from().isAfter(f.to())) throw new IllegalArgumentException("invalid consumption query");
    if(f.type()!=null && !Set.of("IMAGE","VIDEO").contains(f.type())) throw new IllegalArgumentException("invalid type");
    var args=new HashMap<String,Object>();
    var where=new StringBuilder(" WHERE l.kind='CONSUME' AND "+TIME+"<=:until");
    args.put("until",utc(f.to()));
    if(f.from()!=null) { where.append(" AND "+TIME+">=:since");args.put("since",utc(f.from())); }
    if(f.userId()!=null) { where.append(" AND r.owner_id=:owner");args.put("owner",f.userId()); }
    if(f.type()!=null) { where.append(" AND SUBSTRING_INDEX(SUBSTRING_INDEX(r.request_facts,CHAR(10),2),CHAR(10),-1)=:type");args.put("type",f.type()); }
    if(f.model()!=null) { where.append(" AND SUBSTRING_INDEX(SUBSTRING_INDEX(r.request_facts,CHAR(10),3),CHAR(10),-1)=:model");args.put("model",f.model()); }
    long total=sql.queryForObject("SELECT COUNT(*)"+BASE+where,args,Long.class);
    args.put("offset",f.offset());args.put("limit",f.limit());
    return new Page(sql.query(FIELDS+BASE+where+" ORDER BY "+TIME+" DESC,l.entry_id DESC LIMIT :limit OFFSET :offset",args,this::row),total);
  }
  public Summary directorySummary(String ownerId,String keyId,java.time.Instant to) { return directoryFinancialSummary(ownerId,keyId,to).summary(); }
  public DirectorySummary directoryFinancialSummary(String ownerId,String keyId,java.time.Instant to) {
    if(keyId!=null&&!keyId.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid key ID");
    return summary(ownerId,null,to,keyId);
  }
  public Summary summary(String ownerId, java.time.Instant from, java.time.Instant to) { return summary(ownerId,from,to,null).summary(); }
  private DirectorySummary summary(String ownerId,java.time.Instant from,java.time.Instant to,String keyId) {
    if (ownerId == null || ownerId.isBlank() || to == null || from != null && from.isAfter(to)) throw new IllegalArgumentException("invalid summary range");
    var args = new HashMap<String,Object>(); args.put("owner", ownerId); args.put("until", utc(to));
    String source = "SELECT l.points4,r.amount4,r.rate4,COALESCE((SELECT SUM(f.points4) FROM wallet_ledger f WHERE f.wallet_id=l.wallet_id AND f.source_id=l.source_id AND f.kind='REFUND' AND "
        + TIME.replace("l.", "f.") + "<:until),0) refunded" + BASE + " WHERE l.kind='CONSUME' AND r.owner_id=:owner AND " + TIME + "<:until";
    if(keyId!=null) {
      args.put("key",keyId);
      source=source.replace("r.owner_id=:owner","(r.owner_id=:owner OR (r.owner_id IS NULL AND l.wallet_id=:key))");
    }
    String rechargeQuery = "SELECT COALESCE(SUM(amount4),0) FROM recharge_credit_record WHERE user_id=:owner AND " + PAID_TIME + "<:until";
    if (from != null) { args.put("since", utc(from)); source += " AND " + TIME + ">=:since"; rechargeQuery += " AND " + PAID_TIME + ">=:since"; }
    String recharge = sql.queryForObject(rechargeQuery, args, BigDecimal.class).setScale(4).toPlainString();
    return sql.queryForObject("""
        SELECT COUNT(*) calls,COALESCE(SUM(-points4),0) debited,COALESCE(SUM(refunded),0) refunded,
          CASE WHEN COUNT(*)=COUNT(amount4) THEN COALESCE(SUM(amount4),0) ELSE NULL END charged,
          CASE WHEN COUNT(*)=COUNT(CASE WHEN amount4 IS NOT NULL AND rate4>0 AND points4<0 AND refunded BETWEEN 0 AND -points4 THEN 1 END)
            THEN COALESCE(SUM(CASE WHEN refunded=-points4 THEN 0 ELSE TRUNCATE(amount4-refunded/rate4,4) END),0)
            ELSE NULL END net_amount FROM (
        """ + source + ") consumption", args,
        (rs,n) -> new DirectorySummary(new Summary(rs.getLong("calls"), rs.getBigDecimal("debited").setScale(4).toPlainString(),
            rs.getBigDecimal("refunded").setScale(4).toPlainString(),
            rs.getBigDecimal("debited").subtract(rs.getBigDecimal("refunded")).setScale(4).toPlainString(),
            decimal(rs.getBigDecimal("charged")), recharge),
            rs.getBigDecimal("net_amount")==null?null:rs.getBigDecimal("net_amount").setScale(4).toPlainString()));
  }
  public Optional<AllocationFact> allocation(String ownerId, String allocationId, String fundingLotId) {
    for (String id : List.of(ownerId, allocationId, fundingLotId)) {
      if (id.isBlank() || id.length() > 256) throw new IllegalArgumentException("invalid allocation identity");
    }
    var rows = sql.query("""
        SELECT a.id,a.lot_id,a.points4,r.owner_id,r.request_facts,r.rate4,l.balance_after4,
          l.balance_after4-l.points4 balance_before4
        FROM funding_allocation a
        JOIN funding_lot f ON f.lot_id=a.lot_id AND f.wallet_id=a.wallet_id AND f.lot_type='REAL_RECHARGE'
        JOIN recharge_credit_record c ON CONCAT('recharge:',c.order_id)=f.lot_id AND c.wallet_id=f.wallet_id
        JOIN wallet_ledger l ON l.wallet_id=a.wallet_id AND l.source_id=a.operation_id AND l.kind='CONSUME'
        JOIN media_charge_receipt r ON r.key_id=a.wallet_id AND r.operation_id=a.operation_id
        WHERE a.kind='CONSUME' AND a.id=:allocation AND a.lot_id=:lot AND c.user_id=:owner AND r.owner_id=:owner
        """, Map.of("owner",ownerId,"allocation",allocationId,"lot",fundingLotId), (rs,n) -> {
          String[] parts=rs.getString("request_facts").split("\n",-1);
          String type=parts.length==6 && Set.of("IMAGE","VIDEO").contains(parts[1]) ? parts[1] : null;
          return new AllocationFact(rs.getString("owner_id"),rs.getString("id"),rs.getString("lot_id"),type,
              rs.getBigDecimal("points4").toPlainString(),rs.getBigDecimal("balance_before4").toPlainString(),rs.getBigDecimal("balance_after4").toPlainString(),
              yuan(rs.getBigDecimal("balance_before4"),rs.getBigDecimal("rate4")),yuan(rs.getBigDecimal("balance_after4"),rs.getBigDecimal("rate4")));
        });
    if(rows.size()>1) throw new IllegalStateException("ambiguous allocation ledger");
    return rows.stream().findFirst();
  }
  private static String yuan(BigDecimal points, BigDecimal rate) {
    return points==null || rate==null || rate.signum()<=0 ? null : points.divide(rate,4,java.math.RoundingMode.DOWN).toPlainString();
  }
  public Row get(String id) {
    var rows=sql.query(FIELDS+BASE+" WHERE l.kind='CONSUME' AND (l.entry_id=:id OR l.source_id=:id)",Map.of("id",id),this::row);
    if(rows.size()!=1) throw new NoSuchElementException("consumption not found");
    return rows.getFirst();
  }
  public List<Entry> entries(String id) {
    var debit=get(id);
    return sql.query("""
        SELECT * FROM wallet_ledger WHERE wallet_id=:wallet AND source_id=:source
          AND kind IN ('CONSUME','REFUND') ORDER BY balance_version
        """,Map.of("wallet",debit.keyId(),"source",debit.taskId()),(rs,n)->new Entry(
            rs.getString("entry_id"),rs.getString("request_id"),rs.getString("kind"),rs.getBigDecimal("points4").toPlainString(),
            rs.getBigDecimal("balance_after4").subtract(rs.getBigDecimal("points4")).toPlainString(),
            rs.getBigDecimal("balance_after4").toPlainString(),rs.getString("recorded_at")));
  }
  private Row row(ResultSet rs,int index) throws SQLException {
    String facts=rs.getString("request_facts");String[] parts=facts==null?new String[0]:facts.split("\n",-1);
    var debit=rs.getBigDecimal("points4").negate();var refund=rs.getBigDecimal("refunded").setScale(4);
    return new Row(rs.getString("entry_id"),rs.getString("source_id"),rs.getString("wallet_id"),rs.getString("owner_id"),
        parts.length==6?parts[1]:null,parts.length==6?parts[2]:null,rs.getString("account_type"),rs.getString("recorded_at"),
        debit.toPlainString(),refund.toPlainString(),debit.subtract(refund).toPlainString(),
        decimal(rs.getBigDecimal("amount4")),decimal(rs.getBigDecimal("rate4")),
        parts.length==6 && !parts[3].isBlank()?parts[3]:null,
        parts.length==6?quantity(parts[4],1):null,parts.length==6?quantity(parts[5],0):null);
  }
  private static Integer quantity(String value,int minimum) {
    if(!value.matches("[0-9]+"))return null;
    try { int parsed=Integer.parseInt(value);return parsed>=minimum?parsed:null; }
    catch(NumberFormatException invalid) { return null; }
  }
  private static String utc(java.time.Instant value) {return java.time.LocalDateTime.ofInstant(value,java.time.ZoneOffset.UTC).toString().replace('T',' ');}
  private static String decimal(BigDecimal value) { return value==null?null:value.toPlainString(); }
}
