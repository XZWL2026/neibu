package com.xzwl.cloud.user.infrastructure.identity;

import com.xzwl.cloud.user.application.identity.UserIdentityStore;
import com.xzwl.cloud.user.domain.identity.UserIdentity;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public final class MyBatisUserIdentityStore implements UserIdentityStore {
  private final UserIdentityMapper users;
  private final TransactionTemplate transactions;
  public MyBatisUserIdentityStore(UserIdentityMapper users, TransactionTemplate transactions) { this.users = users; this.transactions = transactions; }
  public UserIdentity resolve(String phone) {
    if (phone == null || !phone.matches("\\+[1-9][0-9]{7,14}")) throw new IllegalArgumentException("Canonical phone number required");
    return transactions.execute(status -> {
      users.insertIfAbsent(UUID.randomUUID().toString(), phone, "用户" + phone.substring(phone.length()-4));
      return users.lockByPhone(phone).toDomain();
    });
  }
  public UserIdentity create(String requestId, String creatorId, String phone, String nickname, String source, String remark) {
    if (requestId == null || !requestId.matches("[A-Za-z0-9_-]{1,64}") || creatorId == null || creatorId.isBlank()
        || phone == null || !phone.matches("\\+[1-9][0-9]{7,14}")
        || nickname == null || nickname.isBlank() || nickname.length() > 64
        || source != null && !java.util.Set.of("NATURAL", "REFERRAL", "ADMIN").contains(source)
        || remark != null && remark.length() > 200) throw new IllegalArgumentException("Invalid user creation input");
    return transactions.execute(status -> {
      String proposedId = UUID.randomUUID().toString();
      String creationSource = source == null ? "ADMIN" : source;
      users.reserveCreate(requestId, creatorId, phone, nickname, proposedId, creationSource, remark);
      var request = users.lockCreate(requestId);
      if (!request.creatorId().equals(creatorId) || !request.phone().equals(phone) || !request.nickname().equals(nickname)
          || !creationSource.equals(request.source() == null ? "ADMIN" : request.source())
          || !java.util.Objects.equals(remark, request.remark()))
        throw new Conflict("IDEMPOTENCY_CONFLICT");
      if (request.userId().equals(proposedId)) {
        try { users.insertCreated(proposedId, phone, nickname.strip()); }
        catch (org.springframework.dao.DuplicateKeyException duplicate) { throw new Conflict("USER_PHONE_EXISTS"); }
      }
      return users.selectById(request.userId()).toDomain();
    });
  }
  public Page list(int offset, int limit, String search) {
    if (offset < 0 || limit < 1 || limit > 100 || search == null || search.length() > 128) throw new IllegalArgumentException("Invalid directory query");
    return transactions.execute(status -> new Page(users.list(offset, limit, search).stream().map(UserIdentityEntity::toDomain).toList(), users.count(search), offset, limit));
  }
  public UserIdentity updateProfile(String id, String nickname, String avatarFileId) {
    if (nickname == null && avatarFileId == null) throw new IllegalArgumentException("Empty profile update");
    if (nickname != null && (nickname.isBlank() || nickname.length() > 64 || nickname.chars().anyMatch(Character::isISOControl)))
      throw new IllegalArgumentException("Invalid nickname");
    if (avatarFileId != null && !avatarFileId.isEmpty() && !avatarFileId.matches("[A-Za-z0-9_-]{1,128}"))
      throw new IllegalArgumentException("Invalid avatar file");
    return transactions.execute(status -> {
      if (users.updateProfile(id, nickname == null ? null : nickname.strip(), avatarFileId) == 0) {
        var current = find(id);
        if (current == null || !current.enabled()) throw new Conflict("USER_UNAVAILABLE");
      }
      return find(id);
    });
  }
  public UserIdentity find(String id) {
    var row = users.selectById(id);
    return row == null ? null : row.toDomain();
  }
  public UserIdentity rename(String id, String nickname) {
    if (nickname == null || nickname.isBlank() || nickname.length() > 64
        || nickname.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid nickname");
    return transactions.execute(status -> {
      users.rename(id, nickname.strip());
      return find(id);
    });
  }
  public java.time.Instant registeredAt(String id) { var at=users.registeredAt(id); if(at==null)throw new IllegalArgumentException("unknown user"); return at.toInstant(); }
  public void setEnabled(String id, boolean enabled) {
    users.setEnabledIfChanged(id, enabled);
  }
}
