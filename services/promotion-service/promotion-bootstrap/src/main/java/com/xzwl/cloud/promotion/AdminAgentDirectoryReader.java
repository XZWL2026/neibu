package com.xzwl.cloud.promotion;

import com.xzwl.cloud.promotion.application.production.AdminAgentDirectory;
import com.xzwl.cloud.promotion.infrastructure.MybatisPromotionOperations;
import com.xzwl.cloud.promotion.application.production.PromotionStore;
import com.xzwl.cloud.promotion.application.production.PromotionStore.Page;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/** Administrator read model; joins foreign facts through authenticated service APIs only. */
public final class AdminAgentDirectoryReader implements AdminAgentDirectory {
  private final JdbcTemplate sql;
  private final PromotionStore store;
  private final RestClient users,payment,billing;
  private final Clock clock;
  private static final String CURRENT="r.version=(SELECT MAX(v.version) FROM referral_binding v WHERE v.referred_user_id=r.referred_user_id)";
  private static final String AGENTS="""
      SELECT p.*,
        EXISTS(SELECT 1 FROM promotion_command_receipt c WHERE c.aggregate_id=p.promoter_user_id
          AND c.command_type='ADMIN_PROMOTER_CREATE' AND c.recorded_at=p.created_at) admin_created,
        EXISTS(SELECT 1 FROM promotion_application a WHERE a.user_id=p.promoter_user_id
          AND a.status='APPROVED' AND a.decided_at=p.created_at) application_created
      FROM promoter_account p
      """;
  public AdminAgentDirectoryReader(JdbcTemplate sql,PromotionStore store,RestClient users,RestClient payment,RestClient billing,Clock clock) {
    this.sql=sql;this.store=store;this.users=users;this.payment=payment;this.billing=billing;this.clock=clock;
  }
  public DirectoryPage search(Query q,boolean fullPhone) {
    var found=new ArrayList<Local>();var selectedProfiles=new HashMap<String,Profile>();long total=0,totalAgents=0,todayNew=0;
    var today=clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    var yesterdayStart=today.minus(1,java.time.temporal.ChronoUnit.DAYS);
    var previousAgents=new HashSet<String>();long yesterdayNew=0;
    // ponytail: exact cross-service search scans bounded batches; materialize a directory projection when volume requires it.
    String cursor=null,cursorTime=null;
    while(true) {
      var rows=cursor==null?sql.query(AGENTS+" ORDER BY p.created_at DESC,p.promoter_user_id LIMIT 100",AdminAgentDirectoryReader::local)
          :sql.query(AGENTS+" WHERE p.created_at<? OR (p.created_at=? AND p.promoter_user_id>?) ORDER BY p.created_at DESC,p.promoter_user_id LIMIT 100",AdminAgentDirectoryReader::local,cursorTime,cursorTime,cursor);
      if(rows.isEmpty())break;
      var profiles=profiles(rows.stream().map(Local::id).toList());
      for(var row:rows) {
        totalAgents++;if(!row.createdAt().isBefore(today)&&row.createdAt().isBefore(today.plus(1,java.time.temporal.ChronoUnit.DAYS)))todayNew++;
        if(row.createdAt().isBefore(today)){previousAgents.add(row.id());if(!row.createdAt().isBefore(yesterdayStart))yesterdayNew++;}
        var profile=profiles.get(row.id());
        if(!matches(profile.user(),q.search())||q.source()!=null&&!q.source().equals(row.source())
            ||q.enabled()!=null&&q.enabled()!=row.enabled()||q.from()!=null&&row.createdAt().isBefore(q.from())
            ||q.to()!=null&&!row.createdAt().isBefore(q.to()))continue;
        if(total>=(long)q.page()*q.size()&&found.size()<q.size()) {found.add(row);selectedProfiles.put(row.id(),profile);}
        total++;
      }
      cursor=rows.getLast().id();cursorTime=rows.getLast().createdAtText();if(rows.size()<100)break;
    }
    var allRelations=sql.query("SELECT r.referred_user_id,r.effective_at FROM referral_binding r JOIN promoter_account p ON p.promoter_user_id=r.direct_inviter_user_id WHERE "+CURRENT,
        (rs,n)->new Relation(rs.getString(1),Instant.parse(rs.getString(2))));
    long todayReferred=profiles(allRelations.stream().map(Relation::id).toList()).values().stream()
        .filter(profile->!profile.registeredAt().isBefore(today)&&profile.registeredAt().isBefore(today.plus(1,java.time.temporal.ChronoUnit.DAYS))).count();
    var pending=sql.queryForObject("SELECT COALESCE(SUM(c.frozen4+c.available4+c.withdrawing4),0) FROM promotion_commission_account c JOIN promoter_account p ON p.promoter_user_id=c.promoter_user_id",BigDecimal.class);
    // Use the binding version effective at yesterday's close, including relations later moved elsewhere.
    var previousBindings=new HashMap<String,String>();
    sql.query("SELECT referred_user_id,direct_inviter_user_id,effective_at FROM referral_binding ORDER BY version",rs->{
      if(Instant.parse(rs.getString(3)).isBefore(today))previousBindings.put(rs.getString(1),rs.getString(2));
    });
    var previousIds=previousBindings.entrySet().stream().filter(e->previousAgents.contains(e.getValue())).map(Map.Entry::getKey).toList();
    var previousProfiles=profiles(previousIds);
    previousIds=previousIds.stream().filter(id->previousProfiles.get(id).registeredAt().isBefore(today)).toList();
    long yesterdayReferred=previousIds.stream().filter(id->!previousProfiles.get(id).registeredAt().isBefore(yesterdayStart)).count();
    var ledgerByAgent=new HashMap<String,List<Map<String,Object>>>();
    for(var entry:sql.queryForList("SELECT * FROM promotion_commission_ledger ORDER BY promoter_user_id,sequence_no")) {
      String owner=(String)entry.get("promoter_user_id");
      if(previousAgents.contains(owner)&&Instant.parse((String)entry.get("created_at")).isBefore(today))ledgerByAgent.computeIfAbsent(owner,id->new ArrayList<>()).add(entry);
    }
    BigDecimal previousPending=BigDecimal.ZERO;
    for(var entries:ledgerByAgent.values()) {
      var balance=MybatisPromotionOperations.commissionBalances(entries);
      previousPending=previousPending.add(balance.get("frozen4")).add(balance.get("available4")).add(balance.get("withdrawing4"));
    }
    var yesterday=new Yesterday(previousAgents.size(),yesterdayNew,yesterdayReferred,paid(previousIds,today).size(),money(previousPending));
    var summary=new Summary(totalAgents,todayNew,todayReferred,paid(allRelations.stream().map(Relation::id).toList()).size(),money(pending),yesterday);
    return new DirectoryPage(found.stream().map(row->agent(row,selectedProfiles.get(row.id()),fullPhone)).toList(),total,q.page(),q.size(),summary);
  }
  private Agent agent(Local row,Profile profile,boolean fullPhone) {
    var relations=relations(row.id());var ids=relations.stream().map(Relation::id).toList();
    var paid=paid(ids);var identities=identities(new ArrayList<>(paid));
    var counts=new HashMap<String,Long>();identities.values().forEach(type->counts.merge(type,1L,Long::sum));
    var overview=store.portalOverview(row.id());var c=overview.commission();
    BigDecimal recharge=BigDecimal.ZERO;for(String id:ids)recharge=recharge.add(new BigDecimal(recharge(id)));
    return new Agent(row.id(),profile.user().nickname(),phone(profile.user().phone(),fullPhone),row.enabled(),row.createdAt(),row.source(),row.code(),
        ids.size(),paid.size(),money(recharge),overview.totalCommissionAmount4(),money(c.frozen().add(c.available()).add(c.withdrawing())),
        counts.getOrDefault("PERSONAL",0L),counts.getOrDefault("ENTERPRISE",0L),counts.getOrDefault("API",0L),profile.registeredAt());
  }
  public Detail detail(String userId,boolean fullPhone) {
    var row=required(userId);var agent=agent(row,profiles(List.of(userId)).get(userId),fullPhone);
    var rates=sql.query("SELECT COALESCE(o.level1_cash_rate6,p.level1_cash_rate6),COALESCE(o.level2_cash_rate6,p.level2_cash_rate6) FROM promotion_benefit_policy p LEFT JOIN promotion_benefit_override o ON o.policy_version=p.version AND o.promoter_user_id=? ORDER BY p.version DESC LIMIT 1",
        (rs,n)->List.of(rs.getBigDecimal(1).setScale(6).toPlainString(),rs.getBigDecimal(2).setScale(6).toPlainString()),userId).getFirst();
    String percent=agent.invitedCount()==0?"0.00":BigDecimal.valueOf(agent.paidCount()).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(agent.invitedCount()),2,RoundingMode.DOWN).toPlainString();
    return new Detail(agent,agent.personalPaidCount(),agent.enterprisePaidCount(),agent.apiPaidCount(),percent,
        money(store.getCommission(userId).withdrawn()),rates.get(0),rates.get(1));
  }
  public Page<Downline> downlines(String userId,String search,String accountType,int page,int size,boolean fullPhone) {
    AdminAgentDirectory.pagination(page,size);required(userId);search=search==null?"":search.strip();
    if(search.length()>128||accountType!=null&&!Set.of("UNSET","PERSONAL","ENTERPRISE","API").contains(accountType))throw new IllegalArgumentException("invalid downline filter");
    var relations=relations(userId);var ids=relations.stream().map(Relation::id).toList();
    var profiles=profiles(ids);var identities=identities(ids);var paid=paid(ids);var items=new ArrayList<Downline>();long total=0;
    for(var relation:relations) {
      var profile=profiles.get(relation.id());String type=identities.get(relation.id());
      if(!matches(profile.user(),search)||accountType!=null&&!accountType.equals(type))continue;
      if(total>=(long)page*size&&items.size()<size)items.add(new Downline(relation.id(),profile.user().nickname(),phone(profile.user().phone(),fullPhone),type,
          profile.user().enabled(),profile.registeredAt(),relation.boundAt(),paid.contains(relation.id()),recharge(relation.id())));
      total++;
    }
    return new Page<>(List.copyOf(items),page,size,total);
  }
  public Page<Operation> operations(String userId,int page,int size) {
    AdminAgentDirectory.pagination(page,size);required(userId);
    // Receipts identify commands only: their opaque semantics can contain secrets and never enter this response.
    var values=new ArrayList<>(sql.query("SELECT command_id,command_type,recorded_at FROM promotion_command_receipt WHERE aggregate_id=? AND command_type IN ('ADMIN_PROMOTER_CREATE','PROMOTER_STATUS')",
        (rs,n)->new Operation(rs.getString(1),rs.getString(2),null,Instant.parse(rs.getString(3))),userId));
    values.addAll(sql.query("SELECT application_id,status,decided_by,decided_at FROM promotion_application WHERE user_id=? AND decided_at IS NOT NULL",
        (rs,n)->new Operation(rs.getString(1),"APPLICATION_"+rs.getString(2),rs.getString(3),Instant.parse(rs.getString(4))),userId));
    values.addAll(sql.query("SELECT command_id,operator_id,effective_at FROM promotion_benefit_policy WHERE command_id IS NOT NULL AND (changed_promoter_id=? OR change_scope='GLOBAL')",
        (rs,n)->new Operation(rs.getString(1),"BENEFIT_RATE",rs.getString(2),Instant.parse(rs.getString(3))),userId));
    values.sort(Comparator.comparing(Operation::occurredAt).reversed().thenComparing(Operation::id));
    return new Page<>(values.stream().skip((long)page*size).limit(size).toList(),page,size,values.size());
  }
  private List<Relation> relations(String id) {
    return sql.query("SELECT r.referred_user_id,r.effective_at FROM referral_binding r WHERE r.direct_inviter_user_id=? AND "+CURRENT+" ORDER BY r.effective_at DESC,r.referred_user_id",
        (rs,n)->new Relation(rs.getString(1),Instant.parse(rs.getString(2))),id);
  }
  private Local required(String id) {
    if(id==null||!id.matches("[A-Za-z0-9._:-]{1,128}"))throw new IllegalArgumentException("invalid promoter ID");
    var rows=sql.query(AGENTS+" WHERE p.promoter_user_id=?",AdminAgentDirectoryReader::local,id);
    if(rows.isEmpty())throw new IllegalArgumentException("promoter not found");return rows.getFirst();
  }
  private Map<String,Profile> profiles(List<String> ids) {
    var result=new HashMap<String,Profile>();
    batches(ids,batch->{
      var values=required(users.post().uri("/internal/v1/users/promotion-profiles").body(batch).retrieve().body(Profile[].class));
      var facts=index(values,p->p.user().id());if(!facts.keySet().equals(Set.copyOf(batch))||facts.values().stream().anyMatch(p->p.registeredAt()==null))throw new IllegalStateException("incomplete user profiles");
      result.putAll(facts);
    });return result;
  }
  private Map<String,String> identities(List<String> ids) {
    var result=new HashMap<String,String>();batches(ids,batch->{
      var values=required(payment.post().uri("/internal/v1/payments/account-identities").body(batch).retrieve().body(Identity[].class));
      var facts=index(values,Identity::userId);if(!facts.keySet().equals(Set.copyOf(batch)))throw new IllegalStateException("incomplete account identities");
      facts.forEach((id,f)->{String type=f.accountType()==null?"UNSET":f.accountType();if(!Set.of("UNSET","PERSONAL","ENTERPRISE","API").contains(type))throw new IllegalStateException("invalid account identity");result.put(id,type);});
    });return result;
  }
  private Set<String> paid(List<String> ids) { return paid(ids,clock.instant()); }
  private Set<String> paid(List<String> ids,Instant to) {
    var result=new HashSet<String>();batches(ids,batch->{
      var value=required(billing.post().uri("/internal/v1/billing/directory-facts").body(new FinanceRequest(batch.stream().map(id->new Owner(id,null)).toList(),null,to)).retrieve().body(Finance.class));
      if(value.paidOwners()==null||!batch.containsAll(value.paidOwners()))throw new IllegalStateException("invalid paid identities");result.addAll(value.paidOwners());
    });return result;
  }
  private String recharge(String id) {
    var value=required(billing.get().uri("/internal/v1/billing/owners/{id}/portal-summary",id).retrieve().body(BillingSummary.class));
    if(!id.equals(value.ownerId())||value.summary()==null||value.summary().totalRechargeAmount4()==null)throw new IllegalStateException("invalid recharge summary");
    return money(new BigDecimal(value.summary().totalRechargeAmount4()));
  }
  private static void batches(List<String> ids,java.util.function.Consumer<List<String>> consume) {
    for(int i=0;i<ids.size();i+=100)consume.accept(ids.subList(i,Math.min(i+100,ids.size())));
  }
  private static boolean matches(User user,String search) {
    String q=search.toLowerCase(Locale.ROOT);return user.id().toLowerCase(Locale.ROOT).contains(q)||user.nickname()!=null&&user.nickname().toLowerCase(Locale.ROOT).contains(q)||user.phone()!=null&&user.phone().contains(q);
  }
  private static String phone(String phone,boolean full) { return phone==null||full?phone:phone.length()<8?"****":phone.substring(0,phone.length()-8)+"****"+phone.substring(phone.length()-4); }
  private static String money(BigDecimal value) { return value.setScale(4).toPlainString(); }
  private static <T> T required(T value) { if(value==null)throw new IllegalStateException("empty directory response");return value; }
  private static <T> Map<String,T> index(T[] values,Function<T,String> key) { var map=new HashMap<String,T>();for(T value:values)if(value==null||map.put(key.apply(value),value)!=null)throw new IllegalStateException("duplicate directory identity");return map; }
  private static Local local(java.sql.ResultSet rs,int n)throws java.sql.SQLException {
    boolean admin=rs.getBoolean("admin_created"),application=rs.getBoolean("application_created");
    return new Local(rs.getString("promoter_user_id"),rs.getString("invitation_code"),"ENABLED".equals(rs.getString("status")),rs.getString("created_at"),admin&&!application?"ADMIN":application&&!admin?"APPLICATION":"UNKNOWN");
  }
  record Local(String id,String code,boolean enabled,String createdAtText,String source) {
    Instant createdAt() { return Instant.parse(createdAtText); }
  }
  record Relation(String id,Instant boundAt) {}
  public record User(String id,String nickname,String phone,boolean enabled) {}
  public record Profile(User user,Instant registeredAt) {}
  public record Identity(String userId,String accountType) {}
  public record Owner(String ownerId,String keyId) {}
  public record FinanceRequest(List<Owner> owners,Instant from,Instant to) {}
  public record Finance(List<String> paidOwners) {}
  public record BillingSummary(String ownerId,Amounts summary) {}
  public record Amounts(String totalRechargeAmount4) {}
}
