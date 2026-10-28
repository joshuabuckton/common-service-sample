package preferences.service.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import java.time.Instant;
import java.util.List;
import preferences.service.application.CustomerPreferencesEntity;
import preferences.service.application.CustomerPreferencesEntity.UpdatePreferences;
import preferences.service.application.PreferenceHistoryView;
import preferences.service.application.PreferenceHistoryView.Change;
import preferences.service.application.PreferenceHistoryView.HistoryEntries;
import preferences.service.domain.Channel;
import preferences.service.domain.CustomerPreferences;
import preferences.service.domain.PreferenceRules;

/**
 * The HTTP front of the preferences service. It follows the same rules as the gRPC endpoint: the
 * ACL decides which services may reach it, and every caller presents a bearer token. The history
 * routes also need the claim role=auditor.
 */
@HttpEndpoint("/preferences")
@Acl(
  allow = {
    @Acl.Matcher(service = "advisor-service"),
    @Acl.Matcher(principal = Acl.Principal.INTERNET),
  }
)
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN, bearerTokenIssuers = "preferences-callers")
public class PreferencesEndpoint extends AbstractHttpEndpoint {

  public record PreferencesResponse(String customerId, boolean email, boolean sms, boolean phone) {}

  public record UpdateRequest(boolean email, boolean sms, boolean phone, String reason) {}

  public record CanContactResponse(String customerId, String channel, boolean allowed) {}

  public record ChangeResponse(
    String customerId,
    boolean email,
    boolean sms,
    boolean phone,
    String changedBy,
    String reason,
    Instant changedAt
  ) {}

  public record HistoryResponse(String customerId, List<ChangeResponse> changes) {}

  private final ComponentClient componentClient;

  public PreferencesEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @Get("/{customerId}")
  public PreferencesResponse get(String customerId) {
    checkCustomerId(customerId);
    return toApi(read(customerId));
  }

  @Put("/{customerId}")
  public PreferencesResponse update(String customerId, UpdateRequest request) {
    var changedBy = requestContext()
      .getJwtClaims()
      .subject()
      .orElseThrow(() -> HttpException.error(StatusCodes.UNAUTHORIZED, "The token has no subject"));
    PreferenceRules.violation(customerId, changedBy, request.reason()).ifPresent(reason -> {
      throw HttpException.badRequest(reason);
    });
    var preferences = componentClient
      .forEventSourcedEntity(customerId)
      .method(CustomerPreferencesEntity::update)
      .invoke(
        new UpdatePreferences(request.email(), request.sms(), request.phone(), changedBy, request.reason())
      );
    return toApi(preferences);
  }

  /** The question most callers ask: may I contact this customer on this channel? */
  @Get("/{customerId}/can-contact/{channel}")
  public CanContactResponse canContact(String customerId, String channel) {
    checkCustomerId(customerId);
    var parsed = Channel.parse(channel).orElseThrow(() ->
      HttpException.badRequest("Channel must be one of email, sms, phone")
    );
    return new CanContactResponse(customerId, parsed.name().toLowerCase(), read(customerId).allows(parsed));
  }

  @Get("/{customerId}/history")
  @JWT(
    validate = JWT.JwtMethodMode.BEARER_TOKEN,
    bearerTokenIssuers = "preferences-callers",
    staticClaims = @JWT.StaticClaim(claim = "role", values = "auditor")
  )
  public HistoryResponse history(String customerId) {
    checkCustomerId(customerId);
    var rows = componentClient
      .forView()
      .method(PreferenceHistoryView::forCustomer)
      .invoke(customerId);
    var changes = rows
      .histories()
      .stream()
      .flatMap(entry -> entry.changes().stream().map(change -> toApi(entry.customerId(), change)))
      .toList();
    return new HistoryResponse(customerId, changes);
  }

  /** Every change that one caller made, across customers. */
  @Get("/changes-by/{changedBy}")
  @JWT(
    validate = JWT.JwtMethodMode.BEARER_TOKEN,
    bearerTokenIssuers = "preferences-callers",
    staticClaims = @JWT.StaticClaim(claim = "role", values = "auditor")
  )
  public List<ChangeResponse> changesBy(String changedBy) {
    return componentClient
      .forView()
      .method(PreferenceHistoryView::touchedBy)
      .invoke(changedBy)
      .histories()
      .stream()
      .flatMap(entry ->
        entry
          .changes()
          .stream()
          .filter(change -> change.changedBy().equals(changedBy))
          .map(change -> toApi(entry.customerId(), change))
      )
      .toList();
  }

  private CustomerPreferences read(String customerId) {
    return componentClient
      .forEventSourcedEntity(customerId)
      .method(CustomerPreferencesEntity::get)
      .invoke();
  }

  private static void checkCustomerId(String customerId) {
    PreferenceRules.customerIdViolation(customerId).ifPresent(reason -> {
      throw HttpException.badRequest(reason);
    });
  }

  private static PreferencesResponse toApi(CustomerPreferences preferences) {
    return new PreferencesResponse(
      preferences.customerId(),
      preferences.email(),
      preferences.sms(),
      preferences.phone()
    );
  }

  private static ChangeResponse toApi(String customerId, Change change) {
    return new ChangeResponse(
      customerId,
      change.email(),
      change.sms(),
      change.phone(),
      change.changedBy(),
      change.reason(),
      change.changedAt()
    );
  }
}
