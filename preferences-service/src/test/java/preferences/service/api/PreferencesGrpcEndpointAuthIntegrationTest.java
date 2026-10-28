package preferences.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static preferences.service.api.TestTokens.bearer;
import static preferences.service.api.TestTokens.bearerFor;
import static preferences.service.api.TestTokens.codeOf;

import akka.javasdk.Principal;
import akka.javasdk.testkit.TestKitSupport;
import io.grpc.Status;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import preferences.v1.proto.PreferencesServiceClient;
import preferences.v1.proto.UpdateRequest;

/** Who may call the preferences service, with the ACL and the token rule switched on. */
public class PreferencesGrpcEndpointAuthIntegrationTest extends TestKitSupport {

  private PreferencesServiceClient clientFor(Principal principal) {
    return getGrpcEndpointClient(PreferencesServiceClient.class, principal);
  }

  private static UpdateRequest write() {
    return UpdateRequest.newBuilder()
      .setCustomerId(UUID.randomUUID().toString())
      .setEmail(true)
      .build();
  }

  @Test
  public void anInternetCallerWithAValidTokenIsAllowed() {
    var client = clientFor(Principal.INTERNET).addRequestHeader("Authorization", bearerFor("partner-1"));

    assertThat(client.update(write()).getEmail()).isTrue();
  }

  @Test
  public void anInternetCallerWithoutATokenIsRejected() {
    var client = clientFor(Principal.INTERNET);

    assertThatThrownBy(() -> client.update(write())).satisfies(error ->
      assertThat(codeOf(error)).isIn(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED)
    );
  }

  @Test
  public void anInternetCallerWithATokenFromAnotherIssuerIsRejected() {
    var token = bearer(Map.of("iss", "someone-else", "sub", "partner-1"));
    var client = clientFor(Principal.INTERNET).addRequestHeader("Authorization", token);

    assertThatThrownBy(() -> client.update(write())).satisfies(error ->
      assertThat(codeOf(error)).isIn(Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED)
    );
  }

  @Test
  public void anotherServiceIsRejectedEvenWithAValidToken() {
    var client = clientFor(Principal.localService("other-service")).addRequestHeader(
      "Authorization",
      bearerFor("other-service")
    );

    assertThatThrownBy(() -> client.update(write())).satisfies(error ->
      assertThat(codeOf(error)).isEqualTo(Status.Code.PERMISSION_DENIED)
    );
  }

  // The token rule applies to every caller, including a service in the same project.
  @Test
  public void aSameProjectServiceWithoutATokenIsRejected() {
    var client = clientFor(Principal.localService("advisor-service"));

    assertThatThrownBy(() -> client.update(write())).satisfies(error ->
      assertThat(codeOf(error)).isEqualTo(Status.Code.UNAUTHENTICATED)
    );
  }

  @Test
  public void aSameProjectServiceWithATokenIsAllowed() {
    var client = clientFor(Principal.localService("advisor-service")).addRequestHeader(
      "Authorization",
      bearerFor("advisor-service")
    );

    assertThat(client.update(write()).getEmail()).isTrue();
  }
}
