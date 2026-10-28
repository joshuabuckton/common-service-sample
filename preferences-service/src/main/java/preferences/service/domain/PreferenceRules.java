package preferences.service.domain;

import java.util.Optional;
import java.util.regex.Pattern;

/** Validation rules for a write. */
public final class PreferenceRules {

  public static final int MAX_REASON_LENGTH = 200;
  private static final Pattern CUSTOMER_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private PreferenceRules() {}

  /** Returns the reason that the customer id is invalid, or empty when it is valid. */
  public static Optional<String> customerIdViolation(String customerId) {
    if (customerId == null || !CUSTOMER_ID.matcher(customerId).matches()) {
      return Optional.of("Customer id must have 1 to 64 letters, digits, '-' or '_'");
    }
    return Optional.empty();
  }

  /** Returns the reason that the write is invalid, or empty when it is valid. */
  public static Optional<String> violation(String customerId, String changedBy, String reason) {
    var idViolation = customerIdViolation(customerId);
    if (idViolation.isPresent()) {
      return idViolation;
    }
    if (changedBy == null || changedBy.isBlank()) {
      return Optional.of("The caller must be identified");
    }
    if (reason != null && reason.length() > MAX_REASON_LENGTH) {
      return Optional.of("Reason must have at most " + MAX_REASON_LENGTH + " characters");
    }
    return Optional.empty();
  }
}
