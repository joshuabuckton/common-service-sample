package advisor.application;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;

/**
 * A change that the preferences service publishes on its "preference-changes" stream. This record
 * mirrors the one in the preferences service. The type name and the fields must match.
 */
@TypeName("preference-changed")
public record PreferenceChanged(
  String customerId,
  boolean email,
  boolean sms,
  boolean phone,
  Instant changedAt
) {}
