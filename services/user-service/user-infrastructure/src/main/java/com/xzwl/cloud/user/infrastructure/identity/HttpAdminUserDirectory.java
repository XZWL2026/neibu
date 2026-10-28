package com.xzwl.cloud.user.infrastructure.identity;

import com.xzwl.cloud.user.application.identity.AdminUserDirectory;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Service-owned facts are fetched in batches; no task snapshots or foreign-schema joins. */
public final class HttpAdminUserDirectory implements AdminUserDirectory {
  private final JdbcTemplate sql;
  private final RestClient access,payment,billing,promotion;
  private static final String LOCAL="SELECT u.*,EXISTS(SELECT 1 FROM admin_user_create_request c WHERE c.user_id=u.user_id) admin_created,(SELECT c.source FROM admin_user_create_request c WHERE c.user_id=u.user_id LIMIT 1) creation_source,(SELECT c.remark FROM admin_user_create_request c WHERE c.user_id=u.user_id LIMIT 1) remark FROM user_identity u";
  public HttpAdminUserDirectory(JdbcTemplate sql,RestClient access,RestClient payment,RestClient billing,RestClient promotion) {
    this.sql=sql;this.access=access;this.payment=payment;this.billing=billing;this.promotion=promotion;
  }
  public Page search(Query q,boolean primaryAdmin) { return search(q,primaryAdmin,null); }
  public ApiPage searchApi(Query q,boolean primaryAdmin) {
    if(!"API".equals(q.accountType()))throw new IllegalArgumentException("API directory required");
    var totals=new ApiTotals();var page=search(q,primaryAdmin,totals);
    return new ApiPage(List.copyOf(totals.items),page.total(),page.offset(),page.limit(),
        new ApiSummary(page.total(),totals.calls,totals.seconds,totals.amount==null?null:totals.amount.setScale(4).toPlainString(),totals.previousMonthUsers,totals.yesterdayCalls,totals.yesterdaySeconds,totals.yesterdayAmount==null?null:totals.yesterdayAmount.setScale(4).toPlainString()));
  }
  private static final class ApiTotals {
    final List<ApiItem> items=new ArrayList<>();
    final Instant from=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
    final Instant to=from.plusSeconds(86400);
    final Instant monthStart=from.atZone(java.time.ZoneId.of("Asia/Shanghai")).withDayOfMonth(1).toInstant();
    Long calls=0L,seconds=0L,previousMonthUsers=0L,yesterdayCalls=0L,yesterdaySeconds=0L;
    BigDecimal amount=BigDecimal.ZERO,yesterdayAmount=BigDecimal.ZERO;
  }
  private Page search(Query q,boolean primaryAdmin,ApiTotals api) {
    var items=new ArrayList<Item>();long total=0,totalUsers=0,newUsers=0,activeUsers=0,paidUsers=0;
    BigDecimal rate=rate();
    // ponytail: exact cross-service filtering scans bounded user batches; materialize a read model if directory volume warrants it.
    Instant cursorTime=null;String cursorId=null;
    while(true) {
      List<Local> rows;
      if(cursorTime==null)rows=sql.query(LOCAL+" WHERE u.created_at<? ORDER BY u.created_at DESC,u.user_id LIMIT 100",HttpAdminUserDirectory::local,Timestamp.from(q.to()));
      else rows=sql.query(LOCAL+" WHERE (u.created_at<? OR (u.created_at=? AND u.user_id>?)) ORDER BY u.created_at DESC,u.user_id LIMIT 100",HttpAdminUserDirectory::local,Timestamp.from(cursorTime),Timestamp.from(cursorTime),cursorId);
      if(rows.isEmpty())break;
      var facts=facts(rows,q);
      var daily=api==null?Map.<String,ApiDailyFact>of():apiFacts(rows,facts,api.from,api.to);
      var yesterday=api==null?Map.<String,ApiDailyFact>of():apiFacts(rows,facts,api.from.minusSeconds(86400),api.from);

      for(var row:rows) {
        var item=item(row,facts,rate,primaryAdmin);
        if(!matches(q,row,item,facts))continue;
        totalUsers++;if(item.active())activeUsers++;if(item.paid())paidUsers++;
        if(q.from()!=null&&row.registeredAt().isBefore(q.from()))continue;
        newUsers++;
        if(api!=null) {
          if(api.previousMonthUsers!=null&&row.registeredAt().isBefore(api.monthStart)) {
            var since=facts.identities().get(row.id()).apiSince();
            if(since==null)api.previousMonthUsers=null;else if(since.isBefore(api.monthStart))api.previousMonthUsers++;
          }
          var previous=yesterday.get(row.id());if(previous==null)throw new IllegalStateException("missing yesterday API facts");
          api.yesterdayCalls=api.yesterdayCalls==null||previous.todayCalls()==null?null:Math.addExact(api.yesterdayCalls,previous.todayCalls());
          api.yesterdaySeconds=api.yesterdaySeconds==null||previous.todaySeconds()==null?null:Math.addExact(api.yesterdaySeconds,previous.todaySeconds());
          api.yesterdayAmount=api.yesterdayAmount==null||previous.todayAmount4()==null?null:api.yesterdayAmount.add(new BigDecimal(previous.todayAmount4()));
          var d=daily.get(row.id());if(d==null)throw new IllegalStateException("missing API daily facts");
          api.calls=api.calls==null||d.todayCalls()==null?null:Math.addExact(api.calls,d.todayCalls());api.seconds=api.seconds==null||d.todaySeconds()==null?null:Math.addExact(api.seconds,d.todaySeconds());
          api.amount=api.amount==null||d.todayAmount4()==null?null:api.amount.add(new BigDecimal(d.todayAmount4()));
          if(total>=q.offset()&&api.items.size()<q.limit())api.items.add(new ApiItem(item,d.todayCalls(),d.todaySeconds(),d.todayAmount4(),d.lastCallAt()));
        }
        if(total>=q.offset()&&items.size()<q.limit())items.add(item);
        total++;
      }
      var last=rows.getLast();cursorTime=last.registeredAt();cursorId=last.id();
      if(rows.size()<100)break;
    }
    return new Page(List.copyOf(items),total,q.offset(),q.limit(),new Summary(totalUsers,newUsers,activeUsers,paidUsers,totalUsers-paidUsers));
  }
  public Detail detail(String id,boolean primaryAdmin) {
    if(id==null||!id.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid user ID");
    var rows=sql.query(LOCAL+" WHERE u.user_id=?",HttpAdminUserDirectory::local,id);
    if(rows.isEmpty())throw new NoSuchElementException("unknown user");
    var q=new Query(0,1,"",null,null,null,null,null,Instant.now());
    var item=item(rows.getFirst(),facts(rows,q),rate(),primaryAdmin);
    var summary=required(billing.get().uri(uri->uri.path("/internal/v1/billing/owners/{id}/directory-summary").queryParamIfPresent("keyId",Optional.ofNullable(item.keyId())).build(id)).retrieve().body(OwnerSummary.class));
    if(!id.equals(summary.ownerId())||summary.summary()==null)throw new IllegalStateException("invalid billing summary");
    var totals=summary.summary();
    return new Detail(item,new ConsumptionSummary(totals.totalCalls(),totals.debitedPoints4(),totals.refundedPoints4(),totals.netPoints4(),
        totals.chargedAmount4(),totals.totalRechargeAmount4(),summary.netConsumptionAmount4()));
  }
  private Map<String,ApiDailyFact> apiFacts(List<Local> rows,Facts facts,Instant from,Instant to) {
    var owners=rows.stream().filter(row->{var identity=facts.identities().get(row.id());var key=facts.keys().get(row.id());
      return "API".equals(identity.accountType()!=null?identity.accountType():key==null?null:key.accountType());
    }).map(row->new Owner(row.id(),facts.keys().containsKey(row.id())?facts.keys().get(row.id()).keyId():null)).toList();
    if(owners.isEmpty())return Map.of();
    var values=index(required(billing.post().uri("/internal/v1/billing/api-daily-facts").body(new FinanceRequest(owners,from,to)).retrieve().body(ApiDailyFact[].class)),ApiDailyFact::ownerId);
    if(!values.keySet().equals(owners.stream().map(Owner::ownerId).collect(java.util.stream.Collectors.toSet())))throw new IllegalStateException("incomplete API daily facts");
    return values;
  }
  public record ApiDailyFact(String ownerId,Long todayCalls,Long todaySeconds,String todayAmount4,Instant lastCallAt) {}
  private BigDecimal rate() {
    var value=new BigDecimal(required(billing.get().uri("/internal/v1/billing/recharge-rate").retrieve().body(Rate.class)).pointsPerYuan4());
    if(value.signum()<=0)throw new IllegalStateException("invalid conversion rate");
    return value;
  }
  private Facts facts(List<Local> rows,Query q) {
    var ids=rows.stream().map(Local::id).toList();
    var keys=index(required(access.post().uri("/internal/v1/access/directory-keys").body(new KeyRequest(ids,q.search())).retrieve().body(Key[].class)),Key::ownerId);
    var identities=index(required(payment.post().uri("/internal/v1/payments/account-identities").body(ids).retrieve().body(Identity[].class)),Identity::userId);
    if(!identities.keySet().equals(Set.copyOf(ids))||!ids.containsAll(keys.keySet()))throw new IllegalStateException("incomplete or mismatched directory identities");
    var owners=ids.stream().map(id->new Owner(id,keys.containsKey(id)?keys.get(id).keyId():null)).toList();
    var finance=required(billing.post().uri("/internal/v1/billing/directory-facts").body(new FinanceRequest(owners,q.from(),q.to())).retrieve().body(Finance.class));
    var invited=required(promotion.post().uri("/internal/v1/promotions/invitation-sources").body(ids).retrieve().body(String[].class));
    if(finance.paidOwners()==null||finance.activeOwners()==null||finance.balances()==null)throw new IllegalStateException("incomplete billing facts");
    var balances=index(finance.balances().toArray(Balance[]::new),Balance::keyId);
    var expectedKeys=new HashSet<String>();keys.values().forEach(key->expectedKeys.add(key.keyId()));
    if(!balances.keySet().equals(expectedKeys))throw new IllegalStateException("incomplete or mismatched directory balances");
    return new Facts(keys,identities,Set.copyOf(finance.paidOwners()),Set.copyOf(finance.activeOwners()),balances,Set.of(invited));
  }
  private static Item item(Local row,Facts f,BigDecimal rate,boolean primaryAdmin) {
    var key=f.keys().get(row.id());var identity=f.identities().get(row.id());
    String type=identity!=null&&identity.accountType()!=null?identity.accountType():key!=null?key.accountType():"UNSET";
    if(!Set.of("UNSET","PERSONAL","ENTERPRISE","API").contains(type))throw new IllegalStateException("invalid account identity");
    var balance=key==null?null:f.balances().get(key.keyId());
    String points=balance==null?"0.0000":balance.availablePoints4();
    String amount=new BigDecimal(points).divide(rate,4,RoundingMode.DOWN).toPlainString();
    String phone=row.phone();
    if(!primaryAdmin)phone=phone.substring(0,phone.length()-8)+"****"+phone.substring(phone.length()-4);
    return new Item(row.id(),row.nickname(),phone,row.enabled(),row.avatarFileId(),row.registeredAt(),
        row.source()!=null?row.source():row.adminCreated()?"ADMIN":f.invited().contains(row.id())?"REFERRAL":"NATURAL",type,
        key==null?null:key.keyId(),key==null?null:key.maskedKey(),key==null?"NONE":key.enabled()?"ENABLED":"DISABLED",
        points,amount,f.paid().contains(row.id()),f.active().contains(row.id()),row.remark());
  }
  private static boolean matches(Query q,Local row,Item item,Facts facts) {
    return (q.accountType()==null||q.accountType().equals(item.accountType()))
        &&(q.source()==null||q.source().equals(item.source()))&&(q.enabled()==null||q.enabled()==item.enabled())
        &&(q.keyStatus()==null||q.keyStatus().equals(item.keyStatus()))
        &&(q.search().isEmpty()||row.id().contains(q.search())||row.nickname().contains(q.search())||row.phone().contains(q.search())
            ||facts.keys().containsKey(row.id())&&facts.keys().get(row.id()).matchesSearch());
  }
  private static Local local(java.sql.ResultSet rs,int row)throws java.sql.SQLException {
    return new Local(rs.getString("user_id"),rs.getString("nickname"),rs.getString("phone"),rs.getBoolean("enabled"),rs.getString("avatar_file_id"),rs.getTimestamp("created_at").toInstant(),rs.getBoolean("admin_created"),rs.getString("creation_source"),rs.getString("remark"));
  }
  private static <T> T required(T value){if(value==null)throw new IllegalStateException("empty directory dependency response");return value;}
  private static <T> Map<String,T> index(T[] values,Function<T,String> key) {
    var result=new HashMap<String,T>();for(var value:values)if(result.put(key.apply(value),value)!=null)throw new IllegalStateException("duplicate directory fact");return result;
  }
  record Local(String id,String nickname,String phone,boolean enabled,String avatarFileId,Instant registeredAt,boolean adminCreated,String source,String remark) {}
  public record Key(String ownerId,String keyId,String accountType,boolean enabled,String maskedKey,boolean matchesSearch) {}
  public record KeyRequest(List<String> ownerIds,String search) {}
  public record Identity(String userId,String accountType,Instant apiSince) {}
  public record Owner(String ownerId,String keyId) {}
  public record FinanceRequest(List<Owner> owners,Instant from,Instant to) {}
  public record Finance(List<String> paidOwners,List<String> activeOwners,List<Balance> balances) {}
  public record Balance(String keyId,String availablePoints4) {}
  public record Rate(String pointsPerYuan4) {}
  public record OwnerSummary(String ownerId,ConsumptionSummary summary,String netConsumptionAmount4) {}
  record Facts(Map<String,Key> keys,Map<String,Identity> identities,Set<String> paid,Set<String> active,Map<String,Balance> balances,Set<String> invited) {}
}
