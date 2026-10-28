package advisor.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Query;
import akka.javasdk.view.TableUpdater;
import akka.javasdk.view.View;
import java.time.Instant;
import java.util.List;

/**
 * The contact preferences of each customer, built from the "preference-changes" stream of the
 * preferences service. The read route uses this view, so it keeps answering when the preferences
 * service is down. It can lag behind a write by a short time.
 *
 * <p>The stream delivers an event at least once and its order is not guaranteed. Each event sets all
 * three channels, so a second delivery changes nothing, and an event older than the stored row is
 * ignored.
 */
@Component(id = "contact-preferences-by-customer")
public class ContactPreferencesByCustomerView extends View {

  public record ContactEntry(
    String customerId,
    boolean email,
    boolean sms,
    boolean phone,
    Instant changedAt
  ) {}

  public record ContactEntries(List<ContactEntry> customers) {}

  @Consume.FromServiceStream(service = "preferences-service", id = "preference-changes")
  public static class Contacts extends TableUpdater<ContactEntry> {

    public Effect<ContactEntry> onChange(PreferenceChanged change) {
      var current = rowState();
      if (current != null && current.changedAt().isAfter(change.changedAt())) {
        return effects().ignore();
      }
      return effects()
        .updateRow(
          new ContactEntry(
            change.customerId(),
            change.email(),
            change.sms(),
            change.phone(),
            change.changedAt()
          )
        );
    }
  }

  @Query("SELECT * AS customers FROM contact_preferences_by_customer WHERE customerId = :customerId")
  public QueryEffect<ContactEntries> forCustomer(String customerId) {
    return queryResult();
  }

  /** A list across customers, which a plain lookup of the preferences service could not give. */
  @Query("SELECT * AS customers FROM contact_preferences_by_customer WHERE email = true")
  public QueryEffect<ContactEntries> acceptingEmail() {
    return queryResult();
  }
}
