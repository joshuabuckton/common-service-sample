package preferences.service.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import akka.javasdk.testkit.TestKitSupport;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import preferences.service.application.CustomerPreferencesEntity.UpdatePreferences;

public class PreferenceHistoryViewIntegrationTest extends TestKitSupport {

  private void write(String customer, boolean email, String changedBy, String reason) {
    componentClient
      .forEventSourcedEntity(customer)
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(email, false, false, changedBy, reason));
  }

  private PreferenceHistoryView.HistoryEntries history(String customer) {
    return componentClient.forView().method(PreferenceHistoryView::forCustomer).invoke(customer);
  }

  @Test
  public void theHistoryKeepsEveryChangeOldestFirstWithWhoAndWhy() {
    var customer = UUID.randomUUID().toString();
    write(customer, true, "advisor-service", "signup");
    write(customer, false, "partner-service", "opt out");

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() -> {
        var rows = history(customer).histories();
        assertThat(rows).hasSize(1);
        var changes = rows.get(0).changes();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).changedBy()).isEqualTo("advisor-service");
        assertThat(changes.get(0).reason()).isEqualTo("signup");
        assertThat(changes.get(0).email()).isTrue();
        assertThat(changes.get(1).changedBy()).isEqualTo("partner-service");
        assertThat(changes.get(1).email()).isFalse();
        assertThat(rows.get(0).changers()).containsExactlyInAnyOrder("advisor-service", "partner-service");
      });
  }

  @Test
  public void aCustomerWithNoChangesHasAnEmptyHistory() {
    assertThat(history(UUID.randomUUID().toString()).histories()).isEmpty();
  }

  @Test
  public void aCallerFindsTheCustomersItChanged() {
    var mine = UUID.randomUUID().toString();
    var other = UUID.randomUUID().toString();
    var caller = "caller-" + UUID.randomUUID();
    write(mine, true, caller, "x");
    write(other, true, "someone-else", "y");

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() -> {
        var rows = componentClient
          .forView()
          .method(PreferenceHistoryView::touchedBy)
          .invoke(caller)
          .histories();
        assertThat(rows).extracting(PreferenceHistoryView.HistoryEntry::customerId).containsExactly(mine);
      });
  }
}
