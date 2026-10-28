package preferences.service.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The transformation itself runs in {@link PreferenceChangesStreamIntegrationTest}. The consumer
 * TestKit cannot decode an event into the sealed event interface that the handler declares.
 */
public class PreferenceChangesPublisherTest {

  // The stream goes to every reader in the project, so it must not grow fields that identify a caller.
  @Test
  public void thePublicEventHasNoCallerOrReasonField() {
    var fields = Arrays.stream(PreferenceChanged.class.getRecordComponents()).map(c -> c.getName());

    assertThat(fields).containsExactly("customerId", "email", "sms", "phone", "changedAt");
  }
}
