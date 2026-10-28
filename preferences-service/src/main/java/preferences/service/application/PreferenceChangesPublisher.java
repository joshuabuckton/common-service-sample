package preferences.service.application;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.Component;
import akka.javasdk.annotations.Consume;
import akka.javasdk.annotations.Produce;
import akka.javasdk.consumer.Consumer;
import preferences.service.domain.CustomerPreferencesEvent;
import preferences.service.domain.CustomerPreferencesEvent.PreferencesUpdated;

/**
 * Publishes every change as a stream for other services in the project. The handler uses only the
 * incoming event and has no side effects, because a reader can replay the stream.
 */
@Component(id = "preference-changes-publisher")
@Consume.FromEventSourcedEntity(CustomerPreferencesEntity.class)
@Produce.ServiceStream(id = "preference-changes")
@Acl(allow = @Acl.Matcher(service = "advisor-service"))
public class PreferenceChangesPublisher extends Consumer {

  public Effect onEvent(CustomerPreferencesEvent event) {
    return switch (event) {
      case PreferencesUpdated updated -> effects()
        .produce(
          new PreferenceChanged(
            updated.customerId(),
            updated.email(),
            updated.sms(),
            updated.phone(),
            updated.changedAt()
          )
        );
    };
  }
}
