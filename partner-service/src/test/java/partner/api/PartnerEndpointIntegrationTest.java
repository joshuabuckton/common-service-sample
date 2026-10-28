package partner.api;

import static org.assertj.core.api.Assertions.assertThat;

import akka.grpc.GrpcServiceException;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKitSupport;
import com.typesafe.config.ConfigFactory;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import partner.MockPreferencesService;
import partner.api.PartnerEndpoint.ContactPreferencesResponse;
import partner.api.PartnerEndpoint.UpdateRequest;
import preferences.v1.proto.Preferences;
import preferences.v1.proto.PreferencesServiceClient;

public class PartnerEndpointIntegrationTest extends TestKitSupport {

  private final MockPreferencesService service = new MockPreferencesService();

  private void useMock() {
    testKit.getMockedGrpcServices().mockResponse(
      MockPreferencesService.HOST,
      PreferencesServiceClient.class,
      service
    );
  }

  @AfterEach
  public void resetMocks() {
    testKit.getMockedGrpcServices().reset();
    service.reset();
  }

  private static Preferences preferences(boolean email, boolean sms, boolean phone) {
    return Preferences.newBuilder()
      .setCustomerId("customer-1")
      .setEmail(email)
      .setSms(sms)
      .setPhone(phone)
      .build();
  }

  @Test
  public void readsThePreferencesThroughThePublicAddress() {
    service.onGet = in -> preferences(true, false, true);
    useMock();

    var response = httpClient
      .GET("/customers/customer-1/contact-preferences")
      .responseBodyAs(ContactPreferencesResponse.class)
      .invoke();

    assertThat(response.status()).isEqualTo(StatusCodes.OK);
    assertThat(response.body()).isEqualTo(new ContactPreferencesResponse("customer-1", true, false, true));
  }

  @Test
  public void writesThePreferencesAndPassesTheReasonOn() {
    service.onUpdate = in -> {
      assertThat(in.getReason()).isEqualTo("opt out of sms");
      return preferences(in.getEmail(), in.getSms(), in.getPhone());
    };
    useMock();

    var response = httpClient
      .PUT("/customers/customer-1/contact-preferences")
      .withRequestBody(new UpdateRequest(true, false, false, "opt out of sms"))
      .responseBodyAs(ContactPreferencesResponse.class)
      .invoke();

    assertThat(response.body()).isEqualTo(new ContactPreferencesResponse("customer-1", true, false, false));
  }

  @Test
  public void mapsEachStatusToTheHttpStatus() {
    useMock();
    var cases = new Object[][] {
      { Status.INVALID_ARGUMENT, StatusCodes.BAD_REQUEST },
      { Status.UNAVAILABLE, StatusCodes.SERVICE_UNAVAILABLE },
      { Status.DEADLINE_EXCEEDED, StatusCodes.SERVICE_UNAVAILABLE },
      { Status.UNAUTHENTICATED, StatusCodes.BAD_GATEWAY },
      { Status.PERMISSION_DENIED, StatusCodes.BAD_GATEWAY },
    };
    for (var c : cases) {
      service.onGet = in -> {
        throw new GrpcServiceException((Status) c[0]);
      };
      service.onUpdate = in -> {
        throw new GrpcServiceException((Status) c[0]);
      };

      var read = httpClient.GET("/customers/customer-1/contact-preferences").invoke();
      var write = httpClient
        .PUT("/customers/customer-1/contact-preferences")
        .withRequestBody(new UpdateRequest(true, true, true, ""))
        .invoke();

      assertThat(read.status()).as("read " + c[0]).isEqualTo(c[1]);
      assertThat(write.status()).as("write " + c[0]).isEqualTo(c[1]);
    }
  }

  @Test
  public void sendsTheConfiguredBearerTokenOnEachCall() {
    service.onGet = in -> preferences(false, false, false);
    useMock();

    httpClient.GET("/customers/customer-1/contact-preferences").invoke();
    httpClient.GET("/customers/customer-2/contact-preferences").invoke();

    var expected = "Authorization=Bearer " + ConfigFactory.load().getString("partner.preferences-token");
    assertThat(service.headers).containsExactly(expected, expected);
  }
}
