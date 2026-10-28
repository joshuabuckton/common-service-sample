package preferences.service.application;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import preferences.service.application.CustomerPreferencesEntity.UpdatePreferences;

/** Checks the stream as another service sees it, with the real entity and the real publisher. */
public class PreferenceChangesStreamIntegrationTest extends TestKitSupport {

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withStreamOutgoingMessages("preferences-service", "preference-changes");
  }

  private void write(String customer, boolean email, boolean sms, boolean phone) {
    componentClient
      .forEventSourcedEntity(customer)
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(email, sms, phone, "advisor-service", "test"));
  }

  @Test
  public void aWriteIsPublishedOnTheStream() {
    var outgoing = testKit.getStreamOutgoingMessages("preferences-service", "preference-changes");
    var customer = UUID.randomUUID().toString();

    write(customer, true, false, true);

    var message = outgoing.expectOneTyped(PreferenceChanged.class, ofSeconds(20)).getPayload();
    assertThat(message.customerId()).isEqualTo(customer);
    assertThat(message.email()).isTrue();
    assertThat(message.sms()).isFalse();
    assertThat(message.phone()).isTrue();
    assertThat(message.changedAt()).isNotNull();
  }

  @Test
  public void everyWriteIsPublishedEvenWhenTheValuesDoNotChange() {
    var outgoing = testKit.getStreamOutgoingMessages("preferences-service", "preference-changes");
    var customer = UUID.randomUUID().toString();

    write(customer, true, true, true);
    write(customer, true, true, true);

    var messages = outgoing.expectN(2, ofSeconds(20));
    assertThat(messages).hasSize(2);
  }
}
