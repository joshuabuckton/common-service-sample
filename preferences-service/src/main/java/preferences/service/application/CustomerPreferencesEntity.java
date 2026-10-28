package preferences.service.application;

import akka.javasdk.annotations.Component;
import akka.javasdk.eventsourcedentity.EventSourcedEntity;
import akka.javasdk.eventsourcedentity.EventSourcedEntityContext;
import java.time.Instant;
import preferences.service.domain.CustomerPreferences;
import preferences.service.domain.CustomerPreferencesEvent;
import preferences.service.domain.CustomerPreferencesEvent.PreferencesUpdated;
import preferences.service.domain.PreferenceRules;

/**
 * The contact preferences of one customer. The entity id is the customer id.
 *
 * <p>Every write is kept as an event, so the history shows who changed the preferences and why.
 * The last write wins.
 */
@Component(id = "customer-preferences")
public class CustomerPreferencesEntity
  extends EventSourcedEntity<CustomerPreferences, CustomerPreferencesEvent> {

  /** The caller is identified by the endpoint from its token, because an entity cannot see tokens. */
  public record UpdatePreferences(
    boolean email,
    boolean sms,
    boolean phone,
    String changedBy,
    String reason
  ) {}

  private final String customerId;

  public CustomerPreferencesEntity(EventSourcedEntityContext context) {
    this.customerId = context.entityId();
  }

  @Override
  public CustomerPreferences emptyState() {
    return CustomerPreferences.empty(customerId);
  }

  public Effect<CustomerPreferences> update(UpdatePreferences command) {
    var violation = PreferenceRules.violation(customerId, command.changedBy(), command.reason());
    if (violation.isPresent()) {
      return effects().error(violation.get());
    }
    var event = new PreferencesUpdated(
      customerId,
      command.email(),
      command.sms(),
      command.phone(),
      command.changedBy(),
      command.reason() == null ? "" : command.reason(),
      Instant.now()
    );
    return effects().persist(event).thenReply(newState -> newState);
  }

  public ReadOnlyEffect<CustomerPreferences> get() {
    return effects().reply(currentState());
  }

  @Override
  public CustomerPreferences applyEvent(CustomerPreferencesEvent event) {
    return switch (event) {
      case PreferencesUpdated updated ->
        currentState().withChannels(updated.email(), updated.sms(), updated.phone());
    };
  }
}
