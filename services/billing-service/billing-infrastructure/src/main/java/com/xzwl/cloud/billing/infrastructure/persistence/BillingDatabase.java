package com.xzwl.cloud.billing.infrastructure.persistence;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;
public final class BillingDatabase {
  private final SqlSessionTemplate session;
  private final TransactionTemplate tx;
  public BillingDatabase(SqlSessionTemplate session, PlatformTransactionManager manager) {
    this.session=session; this.tx=new TransactionTemplate(manager);
    // Replays checked after acquiring a wallet lock must observe the previous writer's commit.
    this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }
  public <T> T mapper(Class<T> type) { return session.getMapper(type); }
  public <T> T transaction(Supplier<T> action) { return tx.execute(s -> action.get()); }
}
