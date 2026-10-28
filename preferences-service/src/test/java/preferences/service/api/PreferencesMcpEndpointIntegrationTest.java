package preferences.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static preferences.service.api.TestTokens.bearer;
import static preferences.service.api.TestTokens.bearerFor;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import preferences.service.application.CustomerPreferencesEntity;
import preferences.service.application.CustomerPreferencesEntity.UpdatePreferences;

/** Exercises the MCP endpoint with hand-written JSON-RPC, as the TestKit has no MCP client. */
public class PreferencesMcpEndpointIntegrationTest extends TestKitSupport {

  private static final ObjectMapper JSON = new ObjectMapper();

  private record Reply(int status, JsonNode json) {}

  // The ACL is covered by the gRPC access tests. These tests check the tools and the token rule.
  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAclDisabled();
  }

  private Reply rpc(String token, String method, Map<String, Object> params) throws Exception {
    var request = httpClient
      .POST("/mcp")
      .addHeader("Accept", "application/json, text/event-stream")
      .withRequestBody(Map.of("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
    if (token != null) {
      request = request.addHeader("Authorization", token);
    }
    var response = request.invoke();
    var text = response.body().utf8String();
    // The transport answers with plain JSON or with one server-sent event.
    var payload = text
      .lines()
      .filter(line -> line.startsWith("data:"))
      .map(line -> line.substring("data:".length()).trim())
      .findFirst()
      .orElse(text);
    return new Reply(response.status().intValue(), parseOrNull(payload));
  }

  // A rejected call answers with plain text, not JSON-RPC.
  private static JsonNode parseOrNull(String payload) {
    try {
      return payload.isBlank() ? null : JSON.readTree(payload);
    } catch (Exception e) {
      return null;
    }
  }

  private JsonNode call(String tool, Map<String, Object> arguments) throws Exception {
    var reply = rpc(bearerFor("assistant-1"), "tools/call", Map.of("name", tool, "arguments", arguments));
    assertThat(reply.status()).isEqualTo(200);
    return reply.json().path("result");
  }

  private void write(String customer, boolean email, boolean sms, boolean phone) {
    componentClient
      .forEventSourcedEntity(customer)
      .method(CustomerPreferencesEntity::update)
      .invoke(new UpdatePreferences(email, sms, phone, "advisor-service", "test"));
  }

  @Test
  public void theServerOffersTwoReadOnlyTools() throws Exception {
    var reply = rpc(bearerFor("assistant-1"), "tools/list", Map.of());

    assertThat(reply.status()).isEqualTo(200);
    List<String> names = new ArrayList<>();
    reply.json().path("result").path("tools").forEach(tool -> names.add(tool.path("name").asText()));
    assertThat(names).containsExactlyInAnyOrder("get_contact_preferences", "can_contact");
  }

  @Test
  public void toolsHaveDescriptionsForTheModel() throws Exception {
    var reply = rpc(bearerFor("assistant-1"), "tools/list", Map.of());

    reply
      .json()
      .path("result")
      .path("tools")
      .forEach(tool -> assertThat(tool.path("description").asText()).isNotBlank());
  }

  @Test
  public void getContactPreferencesReturnsTheChannels() throws Exception {
    var customer = UUID.randomUUID().toString();
    write(customer, true, false, true);

    var result = call("get_contact_preferences", Map.of("customerId", customer));

    assertThat(result.path("isError").asBoolean()).isFalse();
    var content = JSON.readTree(result.path("content").path(0).path("text").asText());
    assertThat(content.path("customerId").asText()).isEqualTo(customer);
    assertThat(content.path("email").asBoolean()).isTrue();
    assertThat(content.path("sms").asBoolean()).isFalse();
    assertThat(content.path("phone").asBoolean()).isTrue();
  }

  @Test
  public void canContactAnswersPerChannel() throws Exception {
    var customer = UUID.randomUUID().toString();
    write(customer, true, false, false);

    var email = call("can_contact", Map.of("customerId", customer, "channel", "email"));
    var sms = call("can_contact", Map.of("customerId", customer, "channel", "SMS"));

    assertThat(JSON.readTree(email.path("content").path(0).path("text").asText()).path("allowed").asBoolean()).isTrue();
    assertThat(JSON.readTree(sms.path("content").path(0).path("text").asText()).path("allowed").asBoolean()).isFalse();
  }

  @Test
  public void aCustomerWithNoRecordMayNotBeContacted() throws Exception {
    var result = call("can_contact", Map.of("customerId", UUID.randomUUID().toString(), "channel", "phone"));

    assertThat(JSON.readTree(result.path("content").path(0).path("text").asText()).path("allowed").asBoolean()).isFalse();
  }

  @Test
  public void anUnknownChannelIsAToolError() throws Exception {
    var reply = rpc(
      bearerFor("assistant-1"),
      "tools/call",
      Map.of("name", "can_contact", "arguments", Map.of("customerId", "customer-1", "channel", "fax"))
    );

    var failed = reply.json().has("error") || reply.json().path("result").path("isError").asBoolean();
    assertThat(failed).isTrue();
  }

  @Test
  public void aCallWithoutATokenIsRejected() throws Exception {
    var reply = rpc(null, "tools/list", Map.of());

    assertThat(reply.status()).isBetween(400, 403);
  }

  @Test
  public void aTokenFromAnotherIssuerIsRejected() throws Exception {
    var reply = rpc(bearer(Map.of("iss", "someone-else", "sub", "x")), "tools/list", Map.of());

    assertThat(reply.status()).isBetween(400, 403);
  }
}
