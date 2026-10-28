package com.xzwl.cloud.promotion.domain.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PayoutAccountTest {

  private static final Instant CREATED_AT = Instant.parse("2026-09-03T00:00:00Z");

  @Test
  void createsEachSupportedAccountTypeAndRejectsMixedTypeDetails() {
    PayoutAccount bank =
        account(
            "bank-1",
            PayoutAccountType.BANK_CARD,
            new BankCardDetails("张三", "6222021234567890", "测试银行"));
    PayoutAccount alipay =
        account(
            "alipay-1",
            PayoutAccountType.ALIPAY,
            new AlipayDetails("张三", "alice@example.com", Optional.of("qr://alipay-secret")));
    PayoutAccount wechat =
        account(
            "wechat-1",
            PayoutAccountType.WECHAT,
            new WechatDetails("张三", "13812345678", Optional.empty()));

    assertThat(bank.status()).isEqualTo(PayoutAccountStatus.ACTIVE);
    assertThat(alipay.type()).isEqualTo(PayoutAccountType.ALIPAY);
    assertThat(wechat.type()).isEqualTo(PayoutAccountType.WECHAT);
    assertThatThrownBy(
            () ->
                account(
                    "mixed-1",
                    PayoutAccountType.ALIPAY,
                    new BankCardDetails("张三", "6222021234567890", "测试银行")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("details must match payout account type");
  }

  @Test
  void rejectsBlankOverlongAndInvalidTypeSpecificFields() {
    assertThatThrownBy(() -> new BankCardDetails(" ", "6222021234567890", "测试银行"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("holderName must not be blank");
    assertThatThrownBy(() -> new BankCardDetails("张三", "6222021234567890", "银".repeat(101)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("bankName is too long");
    assertThatThrownBy(() -> new AlipayDetails("张三", " ", Optional.empty()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("account must not be blank");
    assertThatThrownBy(
            () -> new WechatDetails("张三", "not-a-phone", Optional.of("qr://wechat-secret")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("boundPhone must be a valid mobile number");
    assertThatThrownBy(
            () -> new WechatDetails("张三", "13812345678", Optional.of(" ")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("qrCodeReference must not contain blank text");
  }

  @Test
  void masksSensitiveFieldsWithFixedRules() {
    PayoutAccountMaskedDetails bank =
        account(
                "bank-1",
                PayoutAccountType.BANK_CARD,
                new BankCardDetails("张三", "6222021234567890", "测试银行"))
            .maskedDetails();
    PayoutAccountMaskedDetails wechat =
        account(
                "wechat-1",
                PayoutAccountType.WECHAT,
                new WechatDetails("张三", "13812345678", Optional.of("qr://wechat-secret")))
            .maskedDetails();

    assertThat(bank.maskedIdentifier()).isEqualTo("7890");
    assertThat(bank.maskedIdentifier()).doesNotContain("622202123456");
    assertThat(bank.qrCodeConfigured()).isFalse();
    assertThat(wechat.maskedIdentifier()).isEqualTo("138****5678");
    assertThat(wechat.qrCodeConfigured()).isTrue();
    assertThat(
            account(
                    "alipay-phone",
                    PayoutAccountType.ALIPAY,
                    new AlipayDetails("张三", "13812345678", Optional.empty()))
                .maskedDetails()
                .maskedIdentifier())
        .isEqualTo("138****5678");
    assertThat(
            account(
                    "alipay-email",
                    PayoutAccountType.ALIPAY,
                    new AlipayDetails("张三", "alice@example.com", Optional.empty()))
                .maskedDetails()
                .maskedIdentifier())
        .isEqualTo("a***@example.com");
    assertThat(
            account(
                    "alipay-general",
                    PayoutAccountType.ALIPAY,
                    new AlipayDetails("张三", "user-abc", Optional.empty()))
                .maskedDetails()
                .maskedIdentifier())
        .isEqualTo("u***c");
  }

  @Test
  void masksShortBankCardNumbersWithoutExposingTheirCompleteValue() {
    for (String cardNumber : new String[] {"1", "12", "123", "1234"}) {
      String masked =
          account(
                  "bank-short-" + cardNumber.length(),
                  PayoutAccountType.BANK_CARD,
                  new BankCardDetails("张三", cardNumber, "测试银行"))
              .maskedDetails()
              .maskedIdentifier();

      assertThat(masked).isEqualTo("****").isNotEqualTo(cardNumber);
    }
  }

  @Test
  void snapshotsRemainUnchangedAfterAccountUpdateAndDisable() {
    PayoutAccount account =
        account(
            "bank-1",
            PayoutAccountType.BANK_CARD,
            new BankCardDetails("张三", "6222021234567890", "测试银行"));
    PayoutAccountSnapshot snapshot =
        account.snapshot(Instant.parse("2026-09-03T00:01:00Z"));

    account.update(
        new BankCardDetails("李四", "6222020000004321", "新银行"),
        Instant.parse("2026-09-03T00:02:00Z"));
    account.disable(Instant.parse("2026-09-03T00:03:00Z"));

    assertThat(((BankCardDetails) snapshot.details()).cardNumber())
        .isEqualTo("6222021234567890");
    assertThat(((BankCardDetails) account.details()).cardNumber()).isEqualTo("6222020000004321");
    assertThat(account.status()).isEqualTo(PayoutAccountStatus.DISABLED);
    assertThatThrownBy(
            () ->
                account.update(
                    new BankCardDetails("王五", "6222020000009999", "其他银行"),
                    Instant.parse("2026-09-03T00:04:00Z")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("disabled payout account cannot be modified");
    assertThat(PayoutAccount.class.getMethods())
        .noneMatch(method -> method.getName().equals("enable") || method.getName().equals("activate"));
  }

  @Test
  void failedUpdateDoesNotPartiallyReplaceSensitiveDetails() {
    BankCardDetails original = new BankCardDetails("张三", "6222021234567890", "测试银行");
    PayoutAccount account = account("bank-1", PayoutAccountType.BANK_CARD, original);

    assertThatThrownBy(
            () ->
                account.update(
                    new BankCardDetails("李四", "6222020000004321", "新银行"), null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("changedAt");
    assertThat(account.details()).isSameAs(original);
  }

  @Test
  void rejectsOutOfOrderUpdatesWithoutChangingAccountDetailsOrTime() {
    BankCardDetails original = new BankCardDetails("张三", "6222021234567890", "测试银行");
    BankCardDetails current = new BankCardDetails("李四", "6222020000004321", "新银行");
    PayoutAccount account = account("bank-1", PayoutAccountType.BANK_CARD, original);
    Instant currentUpdate = CREATED_AT.plusSeconds(20);
    account.update(current, currentUpdate);

    assertThatThrownBy(
            () ->
                account.update(
                    new BankCardDetails("王五", "6222020000009999", "其他银行"),
                    currentUpdate.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("changedAt must not be before updatedAt");
    assertThat(account.details()).isSameAs(current);
    assertThat(account.updatedAt()).isEqualTo(currentUpdate);
    assertThat(account.status()).isEqualTo(PayoutAccountStatus.ACTIVE);
  }

  @Test
  void rejectsOutOfOrderDisableWithoutChangingAccountStatusOrTime() {
    PayoutAccount account =
        account(
            "bank-1",
            PayoutAccountType.BANK_CARD,
            new BankCardDetails("张三", "6222021234567890", "测试银行"));

    assertThatThrownBy(() -> account.disable(CREATED_AT.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("changedAt must not be before updatedAt");
    assertThat(account.status()).isEqualTo(PayoutAccountStatus.ACTIVE);
    assertThat(account.updatedAt()).isEqualTo(CREATED_AT);
  }

  @Test
  void rejectsSnapshotBeforeTheCurrentAccountVersionWithoutChangingAccount() {
    BankCardDetails current = new BankCardDetails("李四", "6222020000004321", "新银行");
    PayoutAccount account =
        account(
            "bank-1",
            PayoutAccountType.BANK_CARD,
            new BankCardDetails("张三", "6222021234567890", "测试银行"));
    Instant currentUpdate = CREATED_AT.plusSeconds(20);
    account.update(current, currentUpdate);

    assertThatThrownBy(() -> account.snapshot(currentUpdate.minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("capturedAt must not be before updatedAt");
    assertThat(account.details()).isSameAs(current);
    assertThat(account.updatedAt()).isEqualTo(currentUpdate);
    assertThat(account.status()).isEqualTo(PayoutAccountStatus.ACTIVE);
  }

  @Test
  void stringRepresentationsAndValidationErrorsNeverExposeSecrets() {
    String card = "6222021234567890";
    String phone = "13812345678";
    String alipay = "alice@example.com";
    String qrCode = "qr://top-secret";
    PayoutAccount account =
        account(
            "alipay-1",
            PayoutAccountType.ALIPAY,
            new AlipayDetails("张三", alipay, Optional.of(qrCode)));
    PayoutAccountSnapshot snapshot = account.snapshot(CREATED_AT.plusSeconds(1));

    assertThat(new BankCardDetails("张三", card, "测试银行").toString()).doesNotContain(card);
    assertThat(new WechatDetails("张三", phone, Optional.of(qrCode)).toString())
        .doesNotContain(phone, qrCode);
    assertThat(account.toString()).doesNotContain(alipay, qrCode);
    assertThat(snapshot.toString()).doesNotContain(alipay, qrCode);
    assertThatThrownBy(() -> new WechatDetails("张三", "invalid-secret-phone", Optional.empty()))
        .hasMessage("boundPhone must be a valid mobile number")
        .hasMessageNotContaining("invalid-secret-phone");
  }

  private static PayoutAccount account(
      String id, PayoutAccountType type, PayoutAccountDetails details) {
    return PayoutAccount.create(id, "promoter-1", type, details, CREATED_AT);
  }
}
