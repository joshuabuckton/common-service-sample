package advisor.application;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import advisor.AdvisorTestBase;
import advisor.application.ContactPreferencesByCustomerView.ContactEntry;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

public class ContactPreferencesByCustomerViewIntegrationTest extends AdvisorTestBase {

  private static final Instant T1 = Instant.parse("2026-10-27T10:00:00Z");
  private static final Instant T2 = Instant.parse("2026-10-27T11:00:00Z");

  private void publish(PreferenceChanged change) {
    testKit
      .getStreamIncomingMessages("preferences-service", "preference-changes")
      .publish(change, change.customerId());
  }

  private java.util.List<ContactEntry> rows(String customer) {
    return componentClient
      .forView()
      .method(ContactPreferencesByCustomerView::forCustomer)
      .invoke(customer)
      .customers();
  }

  @Test
  public void aChangeOnTheStreamFillsTheView() {
    var customer = UUID.randomUUID().toString();

    publish(new PreferenceChanged(customer, true, false, true, T1));

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() ->
        assertThat(rows(customer)).containsExactly(new ContactEntry(customer, true, false, true, T1))
      );
  }

  @Test
  public void aLaterChangeReplacesTheRow() {
    var customer = UUID.randomUUID().toString();
    publish(new PreferenceChanged(customer, true, true, true, T1));

    publish(new PreferenceChanged(customer, false, true, false, T2));

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() ->
        assertThat(rows(customer)).containsExactly(new ContactEntry(customer, false, true, false, T2))
      );
  }

  @Test
  public void theSameChangeTwiceGivesTheSameRow() {
    var customer = UUID.randomUUID().toString();
    var change = new PreferenceChanged(customer, true, false, false, T1);

    publish(change);
    publish(change);

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() -> assertThat(rows(customer)).containsExactly(new ContactEntry(customer, true, false, false, T1)));
  }

  @Test
  public void anOlderChangeThatArrivesLateIsIgnored() {
    var customer = UUID.randomUUID().toString();
    var newer = new PreferenceChanged(customer, false, false, false, T2);
    publish(newer);
    Awaitility.await().atMost(10, SECONDS).untilAsserted(() -> assertThat(rows(customer)).hasSize(1));

    publish(new PreferenceChanged(customer, true, true, true, T1));
    // A marker for another customer tells us the older change has been processed.
    var marker = UUID.randomUUID().toString();
    publish(new PreferenceChanged(marker, true, false, false, T2));
    Awaitility.await().atMost(10, SECONDS).untilAsserted(() -> assertThat(rows(marker)).hasSize(1));

    assertThat(rows(customer)).containsExactly(new ContactEntry(customer, false, false, false, T2));
  }

  @Test
  public void aListFindsTheCustomersThatAcceptEmail() {
    var yes = UUID.randomUUID().toString();
    var no = UUID.randomUUID().toString();
    publish(new PreferenceChanged(yes, true, false, false, T1));
    publish(new PreferenceChanged(no, false, true, true, T1));

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() -> {
        var ids = componentClient
          .forView()
          .method(ContactPreferencesByCustomerView::acceptingEmail)
          .invoke()
          .customers()
          .stream()
          .map(ContactEntry::customerId)
          .toList();
        assertThat(ids).contains(yes).doesNotContain(no);
      });
  }
}
