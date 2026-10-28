package preferences.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PreferenceRulesTest {

  @Test
  void aValidWriteHasNoViolation() {
    assertThat(PreferenceRules.violation("customer-1", "advisor-service", "asked by phone")).isEmpty();
  }

  @Test
  void aMissingReasonIsAllowed() {
    assertThat(PreferenceRules.violation("customer-1", "advisor-service", null)).isEmpty();
  }

  @Test
  void aCustomerIdWithAColonOrASlashIsRejected() {
    assertThat(PreferenceRules.violation("a:b", "advisor-service", "")).isPresent();
    assertThat(PreferenceRules.violation("a/b", "advisor-service", "")).isPresent();
  }

  @Test
  void anEmptyOrTooLongCustomerIdIsRejected() {
    assertThat(PreferenceRules.violation("", "advisor-service", "")).isPresent();
    assertThat(PreferenceRules.violation("x".repeat(65), "advisor-service", "")).isPresent();
  }

  @Test
  void anUnidentifiedCallerIsRejected() {
    assertThat(PreferenceRules.violation("customer-1", " ", "")).isPresent();
    assertThat(PreferenceRules.violation("customer-1", null, "")).isPresent();
  }

  @Test
  void aTooLongReasonIsRejected() {
    assertThat(PreferenceRules.violation("customer-1", "advisor-service", "x".repeat(201))).isPresent();
  }
}
