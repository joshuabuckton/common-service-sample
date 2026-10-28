package preferences.service.application;

import akka.javasdk.annotations.TypeName;
import java.time.Instant;

/**
 * The public event of the "preference-changes" stream. It sets all three channels, so a reader that
 * gets it twice ends in the same state, and it carries the time of the change so that a reader can
 * ignore an older event that arrives late. It does not carry who changed the preferences or why.
 * A service that reads the stream declares its own record with the same type name and fields.
 */
@TypeName("preference-changed")
public record PreferenceChanged(
  String customerId,
  boolean email,
  boolean sms,
  boolean phone,
  Instant changedAt
) {}
