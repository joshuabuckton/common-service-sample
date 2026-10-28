package preferences.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static preferences.service.api.TestTokens.bearer;
import static preferences.service.api.TestTokens.bearerFor;
import static preferences.service.api.TestTokens.codeOf;

import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import io.grpc.Status;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import preferences.v1.proto.GetRequest;
import preferences.v1.proto.PreferencesServiceClient;
import preferences.v1.proto.UpdateRequest;

public class PreferencesGrpcEndpointIntegrationTest extends TestKitSupport {

  // The endpoint allows advisor-service and the internet. The ACL has its own test.
  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withAclDisabled();
  }

  // Created per call: the TestKit starts after the test instance is built.
  private PreferencesServiceClient client() {
    return getGrpcEndpointClient(PreferencesServiceClient.class).addRequestHeader(
      "Authorization",
      bearerFor("advisor-service")
    );
  }

  private static GetRequest get(String customer) {
    return GetRequest.newBuilder().setCustomerId(customer).build();
  }

  private static UpdateRequest update(String customer, boolean email, boolean sms, boolean phone) {
    return UpdateRequest.newBuilder()
      .setCustomerId(customer)
      .setEmail(email)
      .setSms(sms)
      .setPhone(phone)
      .setReason("test")
      .build();
  }

  @Test
  public void aCustomerWithNoRecordHasEveryChannelOff() {
    var customer = UUID.randomUUID().toString();

    var preferences = client().get(get(customer));

    assertThat(preferences.getCustomerId()).isEqualTo(customer);
    assertThat(preferences.getEmail()).isFalse();
    assertThat(preferences.getSms()).isFalse();
    assertThat(preferences.getPhone()).isFalse();
  }

  @Test
  public void anUpdateIsReturnedAndRead() {
    var customer = UUID.randomUUID().toString();

    var written = client().update(update(customer, true, false, true));
    var read = client().get(get(customer));

    assertThat(written.getEmail()).isTrue();
    assertThat(written.getSms()).isFalse();
    assertThat(written.getPhone()).isTrue();
    assertThat(read).isEqualTo(written);
  }

  @Test
  public void theLastWriteWins() {
    var customer = UUID.randomUUID().toString();
    client().update(update(customer, true, true, true));

    client().update(update(customer, false, true, false));

    var read = client().get(get(customer));
    assertThat(read.getEmail()).isFalse();
    assertThat(read.getSms()).isTrue();
    assertThat(read.getPhone()).isFalse();
  }

  @Test
  public void aCustomerIdWithAColonIsRejectedAndWritesNothing() {
    assertThatThrownBy(() -> client().update(update("customer:1", true, true, true))).satisfies(error ->
      assertThat(codeOf(error)).isEqualTo(Status.Code.INVALID_ARGUMENT)
    );
    assertThatThrownBy(() -> client().get(get("customer:1"))).satisfies(error ->
      assertThat(codeOf(error)).isEqualTo(Status.Code.INVALID_ARGUMENT)
    );
  }

  @Test
  public void anEmptyCustomerIdIsRejected() {
    assertThatThrownBy(() -> client().get(get(""))).satisfies(error ->
      assertThat(codeOf(error)).isEqualTo(Status.Code.INVALID_ARGUMENT)
    );
  }

  @Test
  public void aTooLongReasonIsRejected() {
    var request = update(UUID.randomUUID().toString(), true, true, true)
      .toBuilder()
      .setReason("x".repeat(201))
      .build();

    assertThatThrownBy(() -> client().update(request)).satisfies(error ->
      assertThat(codeOf(error)).isEqualTo(Status.Code.INVALID_ARGUMENT)
    );
  }

  @Test
  public void aTokenWithoutASubjectCannotWrite() {
    var noSubject = getGrpcEndpointClient(PreferencesServiceClient.class).addRequestHeader(
      "Authorization",
      bearer(Map.of("iss", TestTokens.ISSUER))
    );
    var customer = UUID.randomUUID().toString();

    assertThatThrownBy(() -> noSubject.update(update(customer, true, true, true))).satisfies(error ->
      assertThat(codeOf(error)).isEqualTo(Status.Code.UNAUTHENTICATED)
    );
    assertThat(client().get(get(customer)).getEmail()).isFalse();
  }
}
