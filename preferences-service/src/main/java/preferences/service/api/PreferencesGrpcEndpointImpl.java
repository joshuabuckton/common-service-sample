package preferences.service.api;

import akka.grpc.GrpcServiceException;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.GrpcEndpoint;
import akka.javasdk.annotations.JWT;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.grpc.AbstractGrpcEndpoint;
import io.grpc.Status;
import preferences.service.application.CustomerPreferencesEntity;
import preferences.service.application.CustomerPreferencesEntity.UpdatePreferences;
import preferences.service.domain.CustomerPreferences;
import preferences.service.domain.PreferenceRules;
import preferences.v1.proto.GetRequest;
import preferences.v1.proto.Preferences;
import preferences.v1.proto.PreferencesService;
import preferences.v1.proto.UpdateRequest;

/**
 * The ACL decides which services may reach the endpoint. The token rule applies on top of it, to
 * every caller including advisor-service, because the token subject is recorded in the audit history.
 */
@GrpcEndpoint
@Acl(
  allow = {
    @Acl.Matcher(service = "advisor-service"),
    @Acl.Matcher(principal = Acl.Principal.INTERNET),
  }
)
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN, bearerTokenIssuers = "preferences-callers")
public class PreferencesGrpcEndpointImpl extends AbstractGrpcEndpoint implements PreferencesService {

  private final ComponentClient componentClient;

  public PreferencesGrpcEndpointImpl(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Override
  public Preferences get(GetRequest in) {
    PreferenceRules.customerIdViolation(in.getCustomerId()).ifPresent(PreferencesGrpcEndpointImpl::invalid);
    var preferences = componentClient
      .forEventSourcedEntity(in.getCustomerId())
      .method(CustomerPreferencesEntity::get)
      .invoke();
    return toApi(preferences);
  }

  @Override
  public Preferences update(UpdateRequest in) {
    var changedBy = requestContext()
      .getJwtClaims()
      .subject()
      .orElseThrow(() ->
        new GrpcServiceException(Status.UNAUTHENTICATED.augmentDescription("The token has no subject"))
      );
    PreferenceRules.violation(in.getCustomerId(), changedBy, in.getReason()).ifPresent(
      PreferencesGrpcEndpointImpl::invalid
    );
    var preferences = componentClient
      .forEventSourcedEntity(in.getCustomerId())
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(in.getEmail(), in.getSms(), in.getPhone(), changedBy, in.getReason()));
    return toApi(preferences);
  }

  private static void invalid(String reason) {
    throw new GrpcServiceException(Status.INVALID_ARGUMENT.augmentDescription(reason));
  }

  private static Preferences toApi(CustomerPreferences preferences) {
    return Preferences.newBuilder()
      .setCustomerId(preferences.customerId())
      .setEmail(preferences.email())
      .setSms(preferences.sms())
      .setPhone(preferences.phone())
      .build();
  }
}
