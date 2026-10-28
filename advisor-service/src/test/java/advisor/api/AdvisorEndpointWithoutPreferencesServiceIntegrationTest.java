package advisor.api;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import advisor.AdvisorTestBase;
import advisor.api.AdvisorEndpoint.ContactPreferencesResponse;
import advisor.api.AdvisorEndpoint.UpdateRequest;
import advisor.application.PreferenceChanged;
import akka.grpc.GrpcServiceException;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import io.grpc.Status;
import java.time.Instant;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/** The read keeps answering from the local view while the preferences service is unavailable. */
public class AdvisorEndpointWithoutPreferencesServiceIntegrationTest extends AdvisorTestBase {

  private void preferencesServiceIsDown() {
    GRPC.onUpdate = in -> {
      throw new GrpcServiceException(Status.UNAVAILABLE);
    };
    testKit
      .getMockedHttpServices()
      .mockResponse("preferences-service", request ->
        HttpResponse.create().withStatus(StatusCodes.SERVICE_UNAVAILABLE)
      );
  }

  @Test
  public void theReadKeepsAnsweringAndAWriteFailsWith503() {
    var customer = UUID.randomUUID().toString();
    testKit
      .getStreamIncomingMessages("preferences-service", "preference-changes")
      .publish(new PreferenceChanged(customer, true, true, false, Instant.now()), customer);
    Awaitility.await()
      .atMost(10, SECONDS)
      .untilAsserted(() ->
        assertThat(
          httpClient
            .GET("/customers/" + customer + "/contact-preferences")
            .responseBodyAs(ContactPreferencesResponse.class)
            .invoke()
            .body()
            .known()
        ).isTrue()
      );

    preferencesServiceIsDown();

    for (int i = 0; i < 50; i++) {
      var read = httpClient
        .GET("/customers/" + customer + "/contact-preferences")
        .responseBodyAs(ContactPreferencesResponse.class)
        .invoke();
      assertThat(read.status()).isEqualTo(StatusCodes.OK);
      assertThat(read.body().email()).isTrue();
      assertThat(read.body().sms()).isTrue();
    }
    var write = httpClient
      .PUT("/customers/" + customer + "/contact-preferences")
      .withRequestBody(new UpdateRequest(false, false, false, "opt out"))
      .invoke();
    assertThat(write.status()).isEqualTo(StatusCodes.SERVICE_UNAVAILABLE);
  }

  @Test
  public void theLiveQuestionFailsWith503AndDoesNotGuess() {
    preferencesServiceIsDown();

    var response = httpClient.GET("/customers/customer-1/can-contact/email").invoke();

    assertThat(response.status()).isEqualTo(StatusCodes.SERVICE_UNAVAILABLE);
  }
}
