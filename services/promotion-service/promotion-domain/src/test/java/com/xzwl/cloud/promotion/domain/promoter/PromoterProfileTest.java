package com.xzwl.cloud.promotion.domain.promoter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xzwl.cloud.promotion.domain.application.AccountStatus;
import com.xzwl.cloud.promotion.domain.application.AccountType;
import com.xzwl.cloud.promotion.domain.invitation.InvitationCode;
import com.xzwl.cloud.promotion.domain.invitation.InvitationEligibility;
import com.xzwl.cloud.promotion.domain.invitation.InvitationProfile;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PromoterProfileTest {

  @Test
  void exposesNoPublicConstructorThatCanInjectAnArbitraryInvitationCode() {
    assertThat(PromoterProfile.class.getConstructors()).isEmpty();
    assertThat(
            publicCreationApi()
                .anyMatch(
                    executable ->
                        Arrays.asList(executable.getParameterTypes())
                            .contains(InvitationCode.class)))
        .isFalse();
  }

  @Test
  void preservesValidatedStableIdentityAndReusesTheUsersInvitationCode() {
    Instant createdAt = Instant.parse("2026-08-30T03:00:00Z");
    InvitationProfile invitationProfile =
        InvitationProfile.provision(
            Optional.empty(),
            "promoter-user-1",
            new InvitationCode("ABC123"),
            new InvitationEligibility(AccountType.PERSONAL, AccountStatus.ENABLED),
            createdAt);
    PromoterProfile profile =
        PromoterProfile.create("profile-1", "promoter-user-1", invitationProfile, createdAt);

    assertThat(profile.id()).isEqualTo("profile-1");
    assertThat(profile.userId()).isEqualTo("promoter-user-1");
    assertThat(profile.invitationCode()).isSameAs(invitationProfile.code());
    assertThat(profile.createdAt()).isEqualTo(createdAt);
    assertThat(profile).hasToString("PromoterProfile[redacted]");
  }

  @Test
  void rejectsAnInvitationProfileOwnedByAnotherUser() {
    Instant now = Instant.parse("2026-08-30T03:00:00Z");
    InvitationProfile invitationProfile = invitationProfile("invitation-owner", "ABC123", now);

    assertThatThrownBy(
            () -> PromoterProfile.create("profile-1", "promoter-user-1", invitationProfile, now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("promoter userId must match invitation profile userId");
  }

  @Test
  void rejectsMissingIdentityInvitationProfileAndTime() {
    Instant now = Instant.parse("2026-08-30T03:00:00Z");
    InvitationProfile invitationProfile = invitationProfile("user-1", "ABC123", now);

    assertThatThrownBy(() -> PromoterProfile.create(null, "user-1", invitationProfile, now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("id must not be blank");
    assertThatThrownBy(() -> PromoterProfile.create(" ", "user-1", invitationProfile, now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("id must not be blank");
    assertThatThrownBy(() -> PromoterProfile.create("profile-1", null, invitationProfile, now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("userId must not be blank");
    assertThatThrownBy(() -> PromoterProfile.create("profile-1", " ", invitationProfile, now))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("userId must not be blank");
    assertThatThrownBy(() -> PromoterProfile.create("profile-1", "user-1", null, now))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("invitationProfile");
    assertThatThrownBy(
            () -> PromoterProfile.create("profile-1", "user-1", invitationProfile, null))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("createdAt");
  }

  private static InvitationProfile invitationProfile(
      String userId, String code, Instant createdAt) {
    return InvitationProfile.provision(
        Optional.empty(),
        userId,
        new InvitationCode(code),
        new InvitationEligibility(AccountType.PERSONAL, AccountStatus.ENABLED),
        createdAt);
  }

  private static Stream<Executable> publicCreationApi() {
    return Stream.concat(
        Arrays.stream(PromoterProfile.class.getConstructors()),
        Arrays.stream(PromoterProfile.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers())));
  }
}
