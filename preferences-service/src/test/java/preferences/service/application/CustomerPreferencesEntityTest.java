package preferences.service.application;

import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.EventSourcedTestKit;
import org.junit.jupiter.api.Test;
import preferences.service.application.CustomerPreferencesEntity.UpdatePreferences;
import preferences.service.domain.Channel;
import preferences.service.domain.CustomerPreferences;
import preferences.service.domain.CustomerPreferencesEvent;
import preferences.service.domain.CustomerPreferencesEvent.PreferencesUpdated;

public class CustomerPreferencesEntityTest {

  private EventSourcedTestKit<
    CustomerPreferences,
    CustomerPreferencesEvent,
    CustomerPreferencesEntity
  > newCustomer(String customerId) {
    return EventSourcedTestKit.of(customerId, CustomerPreferencesEntity::new);
  }

  @Test
  public void aCustomerWithNoRecordHasEveryChannelOff() {
    var testKit = newCustomer("customer-1");

    var preferences = testKit.method(CustomerPreferencesEntity::get).invoke().getReply();

    assertThat(preferences.customerId()).isEqualTo("customer-1");
    for (var channel : Channel.values()) {
      assertThat(preferences.allows(channel)).isFalse();
    }
  }

  @Test
  public void anUpdateSetsTheChannelsAndRecordsWhoAndWhy() {
    var testKit = newCustomer("customer-1");

    var result = testKit
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(true, false, true, "advisor-service", "asked by phone"));

    assertThat(result.getReply()).isEqualTo(new CustomerPreferences("customer-1", true, false, true));
    var event = result.getNextEventOfType(PreferencesUpdated.class);
    assertThat(event.changedBy()).isEqualTo("advisor-service");
    assertThat(event.reason()).isEqualTo("asked by phone");
    assertThat(event.changedAt()).isNotNull();
    assertThat(testKit.getState()).isEqualTo(result.getReply());
  }

  @Test
  public void theLastWriteWinsAndEveryWriteStaysInTheHistory() {
    var testKit = newCustomer("customer-1");
    testKit
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(true, true, true, "advisor-service", "signup"));

    testKit
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(false, true, false, "partner-service", "opt out of email"));

    assertThat(testKit.getState()).isEqualTo(new CustomerPreferences("customer-1", false, true, false));
    assertThat(testKit.getAllEvents()).hasSize(2);
  }

  @Test
  public void aWriteWithTheSameValuesIsStillRecorded() {
    var testKit = newCustomer("customer-1");
    var command = new UpdatePreferences(true, false, false, "advisor-service", "confirmed");
    testKit.method(CustomerPreferencesEntity::update).invoke(command);

    testKit.method(CustomerPreferencesEntity::update).invoke(command);

    assertThat(testKit.getAllEvents()).hasSize(2);
  }

  @Test
  public void aMissingReasonIsKeptAsAnEmptyText() {
    var testKit = newCustomer("customer-1");

    var result = testKit
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(true, true, true, "advisor-service", null));

    assertThat(result.getNextEventOfType(PreferencesUpdated.class).reason()).isEmpty();
  }

  @Test
  public void anUnidentifiedCallerIsRejectedAndWritesNothing() {
    var testKit = newCustomer("customer-1");

    var result = testKit
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(true, true, true, " ", "x"));

    assertThat(result.isError()).isTrue();
    assertThat(result.getError()).contains("caller must be identified");
    assertThat(testKit.getAllEvents()).isEmpty();
  }

  @Test
  public void aCustomerIdWithAColonIsRejectedAndWritesNothing() {
    var testKit = newCustomer("customer:1");

    var result = testKit
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(true, true, true, "advisor-service", ""));

    assertThat(result.isError()).isTrue();
    assertThat(testKit.getAllEvents()).isEmpty();
  }

  @Test
  public void aTooLongReasonIsRejected() {
    var testKit = newCustomer("customer-1");

    var result = testKit
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(true, true, true, "advisor-service", "x".repeat(201)));

    assertThat(result.isError()).isTrue();
    assertThat(testKit.getAllEvents()).isEmpty();
  }
}
