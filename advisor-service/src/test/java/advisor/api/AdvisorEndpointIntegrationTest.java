package advisor.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import advisor.AdvisorTestBase;
import advisor.api.AdvisorEndpoint.CanContactResponse;
import advisor.api.AdvisorEndpoint.ContactPreferencesResponse;
import advisor.api.AdvisorEndpoint.UpdateRequest;
import advisor.application.PreferenceChanged;
import akka.grpc.GrpcServiceException;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import io.grpc.Status;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

public class AdvisorEndpointIntegrationTest extends AdvisorTestBase {

  private ContactPreferencesResponse read(String customer) {
    return httpClient
      .GET("/customers/" + customer + "/contact-preferences")
      .responseBodyAs(ContactPreferencesResponse.class)
      .invoke()
      .body();
  }

  private akka.javasdk.http.StrictResponse<akka.util.ByteString> put(String customer, UpdateRequest body) {
    return httpClient.PUT("/customers/" + customer + "/contact-preferences").withRequestBody(body).invoke();
  }

  @Test
  public void aCustomerNotSeenOnTheStreamHasEveryChannelOffAndIsNotKnown() {
    var customer = UUID.randomUUID().toString();

    var preferences = read(customer);

    assertThat(preferences).isEqualTo(new ContactPreferencesResponse(customer, false, false, false, false));
  }

  @Test
  public void theReadComesFromTheLocalViewAndNeverCallsThePreferencesService() {
    var customer = UUID.randomUUID().toString();
    testKit
      .getStreamIncomingMessages("preferences-service", "preference-changes")
      .publish(new PreferenceChanged(customer, true, false, true, Instant.now()), customer);

    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() ->
        assertThat(read(customer)).isEqualTo(new ContactPreferencesResponse(customer, true, false, true, true))
      );
    assertThat(GRPC.updates).isEmpty();
    assertThat(HTTP_REQUESTS).isEmpty();
  }

  @Test
  public void aWriteGoesToThePreferencesServiceOverGrpcWithTheBearerToken() {
    var customer = UUID.randomUUID().toString();

    var response = httpClient
      .PUT("/customers/" + customer + "/contact-preferences")
      .withRequestBody(new UpdateRequest(true, false, true, "asked by phone"))
      .responseBodyAs(ContactPreferencesResponse.class)
      .invoke();

    assertThat(response.status()).isEqualTo(StatusCodes.OK);
    assertThat(response.body()).isEqualTo(new ContactPreferencesResponse(customer, true, false, true, true));
    assertThat(GRPC.updates).hasSize(1);
    var sent = GRPC.updates.get(0);
    assertThat(sent.getCustomerId()).isEqualTo(customer);
    assertThat(sent.getReason()).isEqualTo("asked by phone");
    assertThat(GRPC.headers).hasSize(1);
    assertThat(GRPC.headers.get(0)).startsWith("Authorization=Bearer ");
  }

  @Test
  public void aRequestThatThePreferencesServiceRejectsGives400() {
    GRPC.onUpdate = in -> {
      throw new GrpcServiceException(Status.INVALID_ARGUMENT.augmentDescription("Reason is too long"));
    };

    var response = put("customer-1", new UpdateRequest(true, true, true, "x"));

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST);
  }

  @Test
  public void refusedCredentialsGive502BecauseTheCallerDidNothingWrong() {
    GRPC.onUpdate = in -> {
      throw new GrpcServiceException(Status.UNAUTHENTICATED);
    };

    var response = put("customer-1", new UpdateRequest(true, true, true, ""));

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_GATEWAY);
  }

  @Test
  public void canContactAsksThePreferencesServiceOverHttpWithTheBearerToken() {
    var yes = httpClient
      .GET("/customers/customer-1/can-contact/email")
      .responseBodyAs(CanContactResponse.class)
      .invoke();
    var no = httpClient
      .GET("/customers/customer-1/can-contact/sms")
      .responseBodyAs(CanContactResponse.class)
      .invoke();

    assertThat(yes.body().allowed()).isTrue();
    assertThat(no.body().allowed()).isFalse();
    assertThat(HTTP_REQUESTS).hasSize(2);
    var first = HTTP_REQUESTS.get(0);
    assertThat(first.getUri().path()).isEqualTo("/preferences/customer-1/can-contact/email");
    assertThat(first.getHeader("Authorization").orElseThrow().value()).startsWith("Bearer ");
  }

  @Test
  public void anUnknownChannelGives400() {
    testKit
      .getMockedHttpServices()
      .mockResponse("preferences-service", request ->
        HttpResponse.create()
          .withStatus(StatusCodes.BAD_REQUEST)
          .withEntity(ContentTypes.TEXT_PLAIN_UTF8, "Channel must be one of email, sms, phone")
      );

    var response = httpClient.GET("/customers/customer-1/can-contact/fax").invoke();

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST);
  }
}
