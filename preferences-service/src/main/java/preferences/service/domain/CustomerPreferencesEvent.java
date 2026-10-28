package preferences.service.domain;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;

public sealed interface CustomerPreferencesEvent {
  /**
   * Sets all three channels at once, so applying the event twice gives the same state.
   * The audit history reads who changed the preferences and why.
   */
  @TypeName("preferences-updated")
  record PreferencesUpdated(
    String customerId,
    boolean email,
    boolean sms,
    boolean phone,
    String changedBy,
    String reason,
    Instant changedAt
  ) implements CustomerPreferencesEvent {}
}
