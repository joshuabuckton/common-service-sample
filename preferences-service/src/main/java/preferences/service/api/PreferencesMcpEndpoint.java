package preferences.service.api;

import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.Description;
import akka.javasdk.annotations.JWT;
import akka.javasdk.annotations.mcp.McpEndpoint;
import akka.javasdk.annotations.mcp.McpTool;
import akka.javasdk.client.ComponentClient;
import preferences.service.application.CustomerPreferencesEntity;
import preferences.service.domain.Channel;
import preferences.service.domain.PreferenceRules;

/**
 * The MCP front of the preferences service, for clients such as chat assistants. It has read-only
 * tools. A change goes through the gRPC or HTTP endpoint, because the ACL and the token rule of an
 * MCP endpoint apply to the whole class, and a write needs the caller to be recorded.
 */
@Acl(
  allow = {
    @Acl.Matcher(service = "advisor-service"),
    @Acl.Matcher(principal = Acl.Principal.INTERNET),
  }
)
@JWT(validate = JWT.JwtMethodMode.BEARER_TOKEN, bearerTokenIssuers = "preferences-callers")
@McpEndpoint(serverName = "customer-preferences", serverVersion = "1.0.0")
public class PreferencesMcpEndpoint {

  public record ContactPreferences(
    @Description("The customer id") String customerId,
    @Description("True when the customer accepts email") boolean email,
    @Description("True when the customer accepts SMS") boolean sms,
    @Description("True when the customer accepts phone calls") boolean phone
  ) {}

  public record ContactDecision(
    @Description("The customer id") String customerId,
    @Description("The channel that was asked about: email, sms or phone") String channel,
    @Description("True when the customer accepts contact on this channel") boolean allowed
  ) {}

  private final ComponentClient componentClient;

  public PreferencesMcpEndpoint(ComponentClient componentClient) {
    this.componentClient = componentClient;
  }

  @McpTool(
    name = "get_contact_preferences",
    description = "Returns which channels (email, sms, phone) a customer accepts. " +
    "A customer with no record accepts none."
  )
  public ContactPreferences getContactPreferences(
    @Description("The customer id, 1 to 64 letters, digits, '-' or '_'") String customerId
  ) {
    checkCustomerId(customerId);
    var preferences = componentClient
      .forEventSourcedEntity(customerId)
      .method(CustomerPreferencesEntity::get)
      .invoke();
    return new ContactPreferences(
      preferences.customerId(),
      preferences.email(),
      preferences.sms(),
      preferences.phone()
    );
  }

  @McpTool(
    name = "can_contact",
    description = "Answers whether a customer may be contacted on one channel. " +
    "Check this before you contact a customer. A customer with no record may not be contacted."
  )
  public ContactDecision canContact(
    @Description("The customer id, 1 to 64 letters, digits, '-' or '_'") String customerId,
    @Description("The channel: email, sms or phone") String channel
  ) {
    checkCustomerId(customerId);
    var parsed = Channel.parse(channel).orElseThrow(() ->
      new IllegalArgumentException("Channel must be one of email, sms, phone")
    );
    var preferences = componentClient
      .forEventSourcedEntity(customerId)
      .method(CustomerPreferencesEntity::get)
      .invoke();
    return new ContactDecision(customerId, parsed.name().toLowerCase(), preferences.allows(parsed));
  }

  private static void checkCustomerId(String customerId) {
    PreferenceRules.customerIdViolation(customerId).ifPresent(reason -> {
      throw new IllegalArgumentException(reason);
    });
  }
}
