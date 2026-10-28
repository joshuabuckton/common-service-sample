package preferences.service.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static preferences.service.api.TestTokens.bearerFor;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import preferences.service.api.PreferencesEndpoint.CanContactResponse;
import preferences.service.api.PreferencesEndpoint.ChangeResponse;
import preferences.service.api.PreferencesEndpoint.HistoryResponse;
import preferences.service.api.PreferencesEndpoint.PreferencesResponse;
import preferences.service.api.PreferencesEndpoint.UpdateRequest;

public class PreferencesEndpointIntegrationTest extends TestKitSupport {

  // The ACL has its own test in the gRPC endpoint tests. These tests check the routes and the token rule.
  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAclDisabled();
  }

  private PreferencesResponse put(String customer, String token, UpdateRequest request) {
    return httpClient
      .PUT("/preferences/" + customer)
      .addHeader("Authorization", token)
      .withRequestBody(request)
      .responseBodyAs(PreferencesResponse.class)
      .invoke()
      .body();
  }

  private PreferencesResponse get(String customer) {
    return httpClient
      .GET("/preferences/" + customer)
      .addHeader("Authorization", bearerFor("advisor-service"))
      .responseBodyAs(PreferencesResponse.class)
      .invoke()
      .body();
  }

  private HistoryResponse history(String customer, String token) {
    return httpClient
      .GET("/preferences/" + customer + "/history")
      .addHeader("Authorization", token)
      .responseBodyAs(HistoryResponse.class)
      .invoke()
      .body();
  }

  @Test
  public void aCustomerWithNoRecordHasEveryChannelOff() {
    var customer = UUID.randomUUID().toString();

    var preferences = get(customer);

    assertThat(preferences.customerId()).isEqualTo(customer);
    assertThat(preferences.email()).isFalse();
    assertThat(preferences.sms()).isFalse();
    assertThat(preferences.phone()).isFalse();
  }

  @Test
  public void anUpdateIsReturnedAndRead() {
    var customer = UUID.randomUUID().toString();

    var written = put(customer, bearerFor("advisor-service"), new UpdateRequest(true, false, true, "signup"));

    assertThat(written).isEqualTo(new PreferencesResponse(customer, true, false, true));
    assertThat(get(customer)).isEqualTo(written);
  }

  @Test
  public void canContactAnswersPerChannel() {
    var customer = UUID.randomUUID().toString();
    put(customer, bearerFor("advisor-service"), new UpdateRequest(true, false, false, ""));

    var email = canContact(customer, "email");
    var sms = canContact(customer, "SMS");

    assertThat(email.allowed()).isTrue();
    assertThat(email.channel()).isEqualTo("email");
    assertThat(sms.allowed()).isFalse();
  }

  private CanContactResponse canContact(String customer, String channel) {
    return httpClient
      .GET("/preferences/" + customer + "/can-contact/" + channel)
      .addHeader("Authorization", bearerFor("advisor-service"))
      .responseBodyAs(CanContactResponse.class)
      .invoke()
      .body();
  }

  @Test
  public void anUnknownChannelIsABadRequest() {
    var response = httpClient
      .GET("/preferences/" + UUID.randomUUID() + "/can-contact/fax")
      .addHeader("Authorization", bearerFor("advisor-service"))
      .invoke();

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST);
  }

  @Test
  public void aCustomerIdWithAColonIsABadRequestAndWritesNothing() {
    var write = httpClient
      .PUT("/preferences/customer:1")
      .addHeader("Authorization", bearerFor("advisor-service"))
      .withRequestBody(new UpdateRequest(true, true, true, ""))
      .invoke();
    var read = httpClient
      .GET("/preferences/customer:1")
      .addHeader("Authorization", bearerFor("advisor-service"))
      .invoke();

    assertThat(write.status()).isEqualTo(StatusCodes.BAD_REQUEST);
    assertThat(read.status()).isEqualTo(StatusCodes.BAD_REQUEST);
  }

  @Test
  public void aCallWithoutATokenIsRejected() {
    var response = httpClient.GET("/preferences/" + UUID.randomUUID()).invoke();

    // The runtime answers a missing bearer token with 400 Bad Request.
    assertThat(response.status().isFailure()).isTrue();
    assertThat(response.status().intValue()).isBetween(400, 403);
  }

  @Test
  public void theHistoryShowsTheTokenSubjectAndTheReason() {
    var customer = UUID.randomUUID().toString();
    put(customer, bearerFor("advisor-service"), new UpdateRequest(true, true, true, "signup"));
    put(customer, bearerFor("partner-1"), new UpdateRequest(false, true, true, "opt out of email"));
    var auditor = bearerFor("auditor-1", "auditor");

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() -> {
        var changes = history(customer, auditor).changes();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).changedBy()).isEqualTo("advisor-service");
        assertThat(changes.get(0).reason()).isEqualTo("signup");
        assertThat(changes.get(1).changedBy()).isEqualTo("partner-1");
        assertThat(changes.get(1).email()).isFalse();
      });
  }

  @Test
  public void theHistoryNeedsTheAuditorRole() {
    var customer = UUID.randomUUID().toString();
    var plain = httpClient
      .GET("/preferences/" + customer + "/history")
      .addHeader("Authorization", bearerFor("advisor-service"))
      .invoke();
    var wrongRole = httpClient
      .GET("/preferences/" + customer + "/history")
      .addHeader("Authorization", bearerFor("someone", "support"))
      .invoke();

    assertThat(plain.status()).isIn(StatusCodes.UNAUTHORIZED, StatusCodes.FORBIDDEN);
    assertThat(wrongRole.status()).isIn(StatusCodes.UNAUTHORIZED, StatusCodes.FORBIDDEN);
  }

  @Test
  public void anAuditorListsOnlyTheChangesOfOneCaller() {
    var caller = "caller-" + UUID.randomUUID();
    var mine = UUID.randomUUID().toString();
    var other = UUID.randomUUID().toString();
    put(mine, bearerFor(caller), new UpdateRequest(true, false, false, "mine"));
    put(other, bearerFor("someone-else"), new UpdateRequest(true, false, false, "other"));
    var auditor = bearerFor("auditor-1", "auditor");

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() -> {
        var changes = httpClient
          .GET("/preferences/changes-by/" + caller)
          .addHeader("Authorization", auditor)
          .responseBodyAsListOf(ChangeResponse.class)
          .invoke()
          .body();
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).customerId()).isEqualTo(mine);
        assertThat(changes.get(0).changedBy()).isEqualTo(caller);
      });
  }
}
