package com.xzwl.cloud.billing.infrastructure;

import com.xzwl.cloud.billing.application.ledger.*;
import java.util.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.web.client.*;

/** Request-scoped aggregation over service APIs. It never reads another service's database. */
public final class HttpAdminConsumptionQuery implements AdminConsumptionQuery {
  private final ConsumptionStore store;
  private final RestClient media,users,keys;
  public HttpAdminConsumptionQuery(ConsumptionStore store,RestClient media,RestClient users,RestClient keys) {
    this.store=store;this.media=media;this.users=users;this.keys=keys;
  }
  public Page search(Query q,String cookie) {
    validate(q);
    var context=new Context(cookie);
    if(q.status()==null && q.userId()==null && q.type()==null && q.model()==null) {
      var page=store.search(filter(q,q.offset(),q.limit()));
      return new Page(page.items().stream().map(context::item).toList(),page.total(),q.offset(),q.limit());
    }
    // Task dimensions/status and legacy Key ownership come from their owning services.
    // Scan every database-paged candidate, retaining only the requested page.
    // No recent-N cap: a disabled Key or old task must remain queryable.
    var items=new ArrayList<Item>();long matched=0;
    for(int offset=0;;offset+=100) {
      var page=store.search(filter(q,offset,100));
      for(var row:page.items()) {
        var snapshot=context.snapshot(row);
        if(!matches(q,context,row,snapshot))continue;
        if(matched>=q.offset() && items.size()<q.limit()) items.add(context.item(row,snapshot));
        matched++;
      }
      if(offset+(long)page.items().size()>=page.total() || page.items().isEmpty())break;
    }
    return new Page(List.copyOf(items),matched,q.offset(),q.limit());
  }
  private static void validate(Query q) {
    if(q.offset()<0 || q.limit()<1 || q.limit()>100 || q.to()==null
        || q.from()!=null && q.from().isAfter(q.to())) throw new IllegalArgumentException("invalid query");
    if(q.status()!=null && !Set.of("MISSING","ROUTING","CHARGE_PENDING","SUBMIT_PENDING","RUNNING","ARCHIVING","REFUND_PENDING",
        "SUCCEEDED","PARTIAL_SUCCESS","FAILED","TIMED_OUT").contains(q.status())) throw new IllegalArgumentException("invalid task status");
    if(q.type()!=null && !Set.of("IMAGE","VIDEO").contains(q.type()))throw new IllegalArgumentException("invalid type");
  }
  private static String type(ConsumptionStore.Row row,Snapshot snapshot) {return row.type()!=null?row.type():snapshot==null?null:snapshot.task().type();}
  private static String model(ConsumptionStore.Row row,Snapshot snapshot) {return row.model()!=null?row.model():snapshot==null?null:snapshot.task().model();}
  private static boolean matches(Query q,Context context,ConsumptionStore.Row row,Snapshot snapshot) {
    String status=snapshot==null?"MISSING":snapshot.task().status();
    return (q.status()==null || q.status().equals(status))
        && (q.type()==null || q.type().equals(type(row,snapshot)))
        && (q.model()==null || q.model().equals(model(row,snapshot)))
        && (q.userId()==null || q.userId().equals(context.owner(row)));
  }
  public Analytics analytics(Query q,String cookie) {
    validate(q);var context=new Context(cookie);var total=new Totals();
    var models=new LinkedHashMap<ModelKey,ModelGroup>();var days=new TreeMap<String,DayGroup>();
    for(int offset=0;;offset+=100) {
      var page=store.search(filter(q,offset,100));
      for(var row:page.items()) {
        var snapshot=context.snapshot(row);
        context.verifyOwner(row,snapshot);
        if(!matches(q,context,row,snapshot))continue;
        String type=type(row,snapshot),model=model(row,snapshot);
        Task task=snapshot!=null && Objects.equals(type,snapshot.task().type()) && Objects.equals(model,snapshot.task().model())?snapshot.task():null;
        Long quantity=quantity(task);
        total.add(row.amount4(),quantity);
        var key=new ModelKey(model,type);
        var group=models.computeIfAbsent(key,ignored->new ModelGroup());group.total.add(row.amount4(),quantity);
        if(group.modelName==null && task!=null && task.modelName()!=null && !task.modelName().isBlank())group.modelName=task.modelName();
        String resolution=task==null?null:task.resolution();
        if(resolution!=null && resolution.isBlank())resolution=null;
        group.resolutions.computeIfAbsent(resolution,ignored->new Totals()).add(row.amount4(),quantity);
        String date=Instant.parse(row.createdAt()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString();
        var day=days.computeIfAbsent(date,ignored->new DayGroup());day.total.add(row.amount4(),quantity);
        if("VIDEO".equals(type))day.video.add(row.amount4(),quantity);
        else if("IMAGE".equals(type))day.image.add(row.amount4(),quantity);
        else day.unknownType=true;
      }
      if(offset+(long)page.items().size()>=page.total() || page.items().isEmpty())break;
    }
    return new Analytics(total.calls,total.amount(),total.unknownAmounts,
        models.entrySet().stream().map(e->{var k=e.getKey();var g=e.getValue();return new ModelTotal(k.model(),g.modelName==null?k.model():g.modelName,k.type(),g.total.calls,g.total.quantity,g.total.amount(),
            g.resolutions.entrySet().stream().map(r->new ResolutionTotal(r.getKey(),r.getValue().calls,r.getValue().quantity,r.getValue().amount())).toList());}).toList(),
        days.entrySet().stream().map(e->{var d=e.getValue();return new DayTotal(e.getKey(),d.total.calls,d.total.amount(),d.unknownType?null:d.video.amount(),d.unknownType?null:d.image.amount());}).toList());
  }
  private static Long quantity(Task task) {
    if(task==null || task.count()<=0)return null;
    if("IMAGE".equals(task.type()))return (long)task.count();
    return "VIDEO".equals(task.type()) && task.durationSeconds()>0?(long)task.count()*task.durationSeconds():null;
  }
  private record ModelKey(String model,String type) {}
  private static final class ModelGroup {
    String modelName;final Totals total=new Totals();final Map<String,Totals> resolutions=new LinkedHashMap<>();
  }
  private static final class DayGroup {
    final Totals total=new Totals(),video=new Totals(),image=new Totals();boolean unknownType;
  }
  private static final class Totals {
    long calls,unknownAmounts;Long quantity=0L;BigDecimal amount=BigDecimal.ZERO;
    void add(String amount4,Long units) {
      calls++;
      if(amount4==null)unknownAmounts++;else amount=amount.add(new BigDecimal(amount4));
      quantity=quantity==null || units==null?null:Math.addExact(quantity,units);
    }
    String amount(){return unknownAmounts>0?null:amount.setScale(4).toPlainString();}
  }
  private static ConsumptionStore.Filter filter(Query q,int offset,int limit) {
    return new ConsumptionStore.Filter(null,null,null,q.from(),q.to(),offset,limit);
  }
  public Detail detail(String id,String cookie) {
    var context=new Context(cookie);var row=store.get(id);
    var entries=store.entries(id);
    // Detail totals come from this exact returned set, even if a refund posts during enrichment.
    var debit=entries.stream().filter(e->e.kind().equals("CONSUME")).map(e->new java.math.BigDecimal(e.points4()).negate()).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
    var refund=entries.stream().filter(e->e.kind().equals("REFUND")).map(e->new java.math.BigDecimal(e.points4())).reduce(java.math.BigDecimal.ZERO,java.math.BigDecimal::add);
    row=new ConsumptionStore.Row(row.id(),row.taskId(),row.keyId(),row.ownerId(),row.type(),row.model(),row.accountType(),row.createdAt(),
        debit.setScale(4).toPlainString(),refund.setScale(4).toPlainString(),debit.subtract(refund).setScale(4).toPlainString(),row.amount4(),row.rate4());
    var snapshot=context.snapshot(row);
    return new Detail(context.item(row,snapshot),entries,snapshot);
  }
  public Access access(String id,String fileId,String cookie) {
    var detail=detail(id,cookie);
    if(detail.snapshot()==null)throw new NoSuchElementException("task missing");
    var result=media.get().uri("/api/v1/admin/media/tasks/{id}/files/{file}/access-url",detail.item().ledger().taskId(),fileId)
        .header("Cookie",cookie).retrieve().body(Access.class);
    if(result==null)throw new IllegalStateException("empty storage response");
    return result;
  }
  public record Key(String keyId,String ownerId,String accountType,boolean enabled) {}
  private final class Context {
    private final String cookie;
    private final Map<String,Key> keyCache=new HashMap<>();
    private final Map<String,User> userCache=new HashMap<>();
    Context(String cookie) {this.cookie=cookie==null?"":cookie;}
    Snapshot snapshot(ConsumptionStore.Row row) {
      Snapshot value=optional(media.get().uri("/api/v1/admin/media/tasks/{id}",row.taskId()).header("Cookie",cookie),Snapshot.class);
      if(value!=null && (!row.taskId().equals(value.task().id()) || row.ownerId()!=null && !row.ownerId().equals(value.ownerId())))
        throw new IllegalStateException("ledger and task ownership disagree");
      return value;
    }
    Key key(ConsumptionStore.Row row) {
      if(!keyCache.containsKey(row.keyId()))keyCache.put(row.keyId(),optional(keys.get().uri("/internal/v1/access/keys/{id}",row.keyId()),Key.class));
      return keyCache.get(row.keyId());
    }
    String owner(ConsumptionStore.Row row) {if(row.ownerId()!=null)return row.ownerId();Key key=key(row);return key!=null?key.ownerId():null;}
    void verifyOwner(ConsumptionStore.Row row,Snapshot snapshot) {
      String owner=owner(row);
      if(snapshot!=null && (owner==null || !owner.equals(snapshot.ownerId())))throw new IllegalStateException("ledger owner unavailable or mismatched");
    }
    Item item(ConsumptionStore.Row row) {return item(row,snapshot(row));}
    Item item(ConsumptionStore.Row row,Snapshot snapshot) {
      Key key=key(row);String owner=owner(row);
      verifyOwner(row,snapshot);
      if(owner!=null && !userCache.containsKey(owner))userCache.put(owner,optional(users.get().uri("/api/v1/admin/users/{id}",owner).header("Cookie",cookie),User.class));
      User user=owner==null?null:userCache.get(owner);
      if(snapshot!=null) row=new ConsumptionStore.Row(row.id(),row.taskId(),row.keyId(),owner,
          row.type()==null?snapshot.task().type():row.type(),row.model()==null?snapshot.task().model():row.model(),row.accountType(),
          row.createdAt(),row.debitedPoints4(),row.refundedPoints4(),row.netPoints4(),row.amount4(),row.rate4());
      return new Item(row,user,key==null?null:key.accountType(),key!=null&&key.enabled(),
          snapshot==null?"MISSING":snapshot.task().status(),snapshot==null?row.model():snapshot.task().modelName());
    }
  }
  private static <T> T optional(RestClient.RequestHeadersSpec<?> request,Class<T> type) {
    try {T value=request.retrieve().body(type);if(value==null)throw new IllegalStateException("empty service response");return value;}
    catch(HttpClientErrorException.NotFound missing) {return null;}
  }
}
