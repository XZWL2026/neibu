import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** User-authorized correction of exactly two local untouched recharge lots. No Billing writes. */
class CorrectLocalRates {
  static final String PROMOTER="6b4c3d7c-d2da-4f1c-aaaf-5ff421118bf5";
  static final String[] ORDERS={"2ed6d85c-1331-4206-b21d-3b1e88f9ea04","7c41fc17-5f4d-438f-b226-b7a12345d341"};
  static final String[] USERS={"ce16cf2d-cf1e-4c57-86ee-92d0c3b57303","c2c6136f-d909-4329-ae4a-ad883273b575"};
  public static void main(String[] args) throws Exception {
    String url=System.getenv("BENEFIT_CORRECTION_JDBC_URL");
    check(url!=null && url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/(promotion_db|promotion_mp_[a-f0-9]+)"),"local promotion schema required");
    try(var connection=DriverManager.getConnection(url,System.getenv("BENEFIT_CORRECTION_USER"),System.getenv("BENEFIT_CORRECTION_PASSWORD"))) {
      connection.setAutoCommit(false);
      try { correct(connection);connection.commit(); }
      catch(Exception error) {connection.rollback();throw error;}
    }
  }
  static void correct(Connection c) throws Exception {
    // Same lock order as settlement/refund: funding before commission account.
    var funding=new ArrayList<Map<String,String>>();
    for(String id:ORDERS)funding.add(one(c,"SELECT * FROM promotion_funding_benefit WHERE order_id=? FOR UPDATE",id));
    var account=one(c,"SELECT * FROM promotion_commission_account WHERE promoter_user_id=? FOR UPDATE",PROMOTER);
    int corrected=0;
    for(int i=0;i<2;i++) {
      var audit=rows(c,"SELECT * FROM promotion_rate_correction WHERE order_id=?",ORDERS[i]);
      if(!audit.isEmpty()) {
        corrected++;
        var row=audit.getFirst();
        eq(row,"old_commission4",i==0?"5":"10");eq(row,"new_commission4",i==0?"3":"8");
        eq(funding.get(i),"frozen_commission4",i==0?"3":"8");
        check(funding.get(i).get("benefit_policy_version").equals(row.get("new_policy_version")),"corrected version drift");
      }
    }
    if(corrected==2) {System.out.println("PASS: two corrections already applied; no writes");return;}
    check(corrected==0,"partial correction requires investigation");
    var policy=one(c,"SELECT * FROM promotion_benefit_policy ORDER BY version DESC LIMIT 1 FOR UPDATE");
    eq(policy,"point_rate6","0.1");eq(policy,"level1_cash_rate6","0.08");eq(policy,"level2_cash_rate6","0.03");
    check(Long.parseLong(policy.get("version"))>1,"saved page configuration required");
    check(rows(c,"SELECT * FROM promotion_benefit_override WHERE policy_version=? AND promoter_user_id=?",policy.get("version"),PROMOTER).isEmpty(),"individual override requires investigation");
    eq(account,"frozen4","15");
    for(String field:List.of("available4","withdrawing4","withdrawn4","cash_debt4"))eq(account,field,"0");
    check(rows(c,"SELECT * FROM promotion_funding_benefit WHERE promoter_user_id=?",PROMOTER).size()==2,"unexpected additional lots");
    check(rows(c,"SELECT * FROM promotion_commission_ledger WHERE promoter_user_id=?",PROMOTER).size()==2,"unexpected account activity");
    long sequence=Long.parseLong(account.get("version"));
    String now=Instant.now().toString();
    for(int i=0;i<2;i++) {
      var lot=funding.get(i);
      check(PROMOTER.equals(lot.get("promoter_user_id")) && USERS[i].equals(lot.get("wallet_owner_user_id")),"identity mismatch");
      check(("recharge:"+ORDERS[i]).equals(lot.get("funding_lot_id")),"lot mismatch");
      check((i==0?"LEVEL_2":"LEVEL_1").equals(lot.get("cash_tier")),"tier mismatch");
      eq(lot,"benefit_policy_version","1");eq(lot,"paid_amount4","100");
      eq(lot,"cash_rate6",i==0?"0.05":"0.1");eq(lot,"frozen_commission4",i==0?"5":"10");
      for(String field:List.of("released_commission4","clawed_unreleased_commission4","reversed_released_commission4","settled_paid_amount4","refunded_paid_amount4"))eq(lot,field,"0");
      var original=one(c,"SELECT * FROM promotion_commission_ledger WHERE order_id=?",ORDERS[i]);
      check("COMMISSION_FROZEN".equals(original.get("action_type")),"original entry mismatch");
      eq(original,"amount4",i==0?"5":"10");
      String rate=i==0?"0.03":"0.08",amount=i==0?"3.0000":"8.0000";
      update(c,"INSERT INTO promotion_rate_correction(order_id,old_policy_version,new_policy_version,old_rate6,new_rate6,old_commission4,new_commission4,reason,operator_id,corrected_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
          ORDERS[i],lot.get("benefit_policy_version"),policy.get("version"),lot.get("cash_rate6"),rate,lot.get("frozen_commission4"),amount,"用户要求按页面全局配置更正此前本地补投的两笔佣金","local-control:user-request",now);
      update(c,"UPDATE promotion_funding_benefit SET benefit_policy_version=?,cash_rate6=?,frozen_commission4=? WHERE order_id=?",policy.get("version"),rate,amount,ORDERS[i]);
      update(c,"INSERT INTO promotion_commission_ledger(promoter_user_id,sequence_no,action_type,amount4,source_event_id,order_id,funding_lot_id,created_at) VALUES(?,?,'COMMISSION_RATE_CORRECTION',2,?,?,?,?)",
          PROMOTER,++sequence,"rate-correction:"+ORDERS[i],ORDERS[i],lot.get("funding_lot_id"),now);
    }
    update(c,"UPDATE promotion_commission_account SET frozen4=11,version=? WHERE promoter_user_id=? AND version=?",sequence,PROMOTER,account.get("version"));
    System.out.println("PASS: attributed recharge remains 200; frozen commission 15 -> 11; two immutable correction entries");
  }
  static List<Map<String,String>> rows(Connection c,String sql,Object... args) throws Exception {
    try(var query=c.prepareStatement(sql)) {
      for(int i=0;i<args.length;i++)query.setObject(i+1,args[i]);
      try(var result=query.executeQuery()) {
        var list=new ArrayList<Map<String,String>>();
        while(result.next()) {var row=new HashMap<String,String>();for(int i=1;i<=result.getMetaData().getColumnCount();i++)row.put(result.getMetaData().getColumnLabel(i),result.getString(i));list.add(row);}
        return list;
      }
    }
  }
  static Map<String,String> one(Connection c,String sql,Object... args) throws Exception {var result=rows(c,sql,args);check(result.size()==1,"expected exactly one record");return result.getFirst();}
  static void update(Connection c,String sql,Object... args) throws Exception {
    try(var query=c.prepareStatement(sql)) {for(int i=0;i<args.length;i++)query.setObject(i+1,args[i]);check(query.executeUpdate()==1,"expected exactly one write");}
  }
  static void eq(Map<String,String> row,String field,String expected) {check(new BigDecimal(row.get(field)).compareTo(new BigDecimal(expected))==0,"unexpected "+field);}
  static void check(boolean ok,String message) {if(!ok)throw new IllegalStateException(message);}
}
