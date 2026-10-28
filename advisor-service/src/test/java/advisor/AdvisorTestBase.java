package advisor;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestKitSupport;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import preferences.v1.proto.PreferencesServiceClient;

/**
 * Starts advisor-service with preferences-service replaced by mocks: a gRPC mock for the writes and
 * an HTTP mock for the can-contact question. The stream of preference changes is mocked as well.
 */
public abstract class AdvisorTestBase extends TestKitSupport {

  protected static final MockPreferencesService GRPC = new MockPreferencesService();
  protected static final List<HttpRequest> HTTP_REQUESTS = new CopyOnWriteArrayList<>();

  private static HttpResponse allowEmailOnly(HttpRequest request) {
    HTTP_REQUESTS.add(request);
    var path = request.getUri().path();
    var allowed = path.endsWith("/email");
    return HttpResponse.create()
      .withStatus(StatusCodes.OK)
      .withEntity(
        ContentTypes.APPLICATION_JSON,
        "{\"customerId\":\"c\",\"channel\":\"x\",\"allowed\":" + allowed + "}"
      );
  }

  @Override
  protected TestKit.Settings testKitSettings() {
    return TestKit.Settings.DEFAULT.withStreamIncomingMessages("preferences-service", "preference-changes")
      .withMockedGrpcService("preferences-service", PreferencesServiceClient.class, GRPC)
      .withMockedHttpService("preferences-service", AdvisorTestBase::allowEmailOnly);
  }

  @AfterEach
  public void resetMocks() {
    GRPC.reset();
    HTTP_REQUESTS.clear();
    testKit.getMockedHttpServices().reset();
  }
}
