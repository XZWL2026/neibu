package com.xzwl.cloud.iam.infrastructure.account;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.xzwl.cloud.iam.application.account.AdminStore;
import com.xzwl.cloud.iam.domain.account.Administrator;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

public final class MyBatisAdminStore implements AdminStore {
  private final AdminMapper admins;
  private final TransactionTemplate transactions;
  public MyBatisAdminStore(AdminMapper admins, TransactionTemplate transactions) { this.admins = admins; this.transactions = transactions; }
  public <T> T locked(Supplier<T> work) {
    // ponytail: admin mutations share one lock; use ordered account locks if administration throughput requires it.
    return transactions.execute(status -> {
      admins.lockAdministrators();
      return work.get();
    });
  }
  public void recordAgentEntry(String actorId, String credentialId, String userId) { admins.recordAgentEntry(actorId,credentialId,userId); }
  public long count() { return admins.selectCount(null); }
  public Administrator find(String id) { return domain(admins.selectById(id)); }
  public Administrator byUsername(String username) { return domain(admins.selectOne(new QueryWrapper<AdminEntity>().eq("username", username))); }
  public Administrator primary() { return domain(admins.selectOne(new QueryWrapper<AdminEntity>().eq("is_primary", true))); }
  public List<Administrator> list() { return admins.selectList(new QueryWrapper<AdminEntity>().orderByAsc("created_at", "admin_id")).stream().map(AdminEntity::toDomain).toList(); }
  private static Administrator domain(AdminEntity row) { return row == null ? null : row.toDomain(); }
  public void insert(Administrator a) {
    admins.insert(AdminEntity.from(a));
  }
  public void update(Administrator a) {
    admins.update(null, new UpdateWrapper<AdminEntity>().eq("admin_id", a.id())
        .set("password_hash", a.passwordHash()).set("role", a.role())
        .set("enabled", a.enabled()).set("auth_version", a.version()));
    // Version checks also reject a concurrent request that loaded a session before this deletion.
    admins.revokeSessions("admin:" + a.id());
  }
}
