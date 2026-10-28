package partner.api;

import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.grpc.GrpcClientProvider;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpException;
import com.typesafe.config.Config;
import partner.application.PartnerPreferencesClient;
import partner.application.PartnerPreferencesClient.PreferencesCallException;
import preferences.v1.proto.Preferences;

// Open to the internet to keep the sample easy to try. A real service restricts this.
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/customers")
public class PartnerEndpoint extends AbstractHttpEndpoint {

  public record ContactPreferencesResponse(String customerId, boolean email, boolean sms, boolean phone) {}

  public record UpdateRequest(boolean email, boolean sms, boolean phone, String reason) {}

  private final PartnerPreferencesClient preferences;

  public PartnerEndpoint(GrpcClientProvider grpcClientProvider, Config config) {
    this.preferences = new PartnerPreferencesClient(grpcClientProvider, config);
  }

  // Every read is a call. This project cannot read the preferences service stream, so there is no
  // local copy to fall back on.
  @Get("/{customerId}/contact-preferences")
  public ContactPreferencesResponse get(String customerId) {
    try {
      return toApi(preferences.get(customerId));
    } catch (PreferencesCallException e) {
      throw failure(e);
    }
  }

  @Put("/{customerId}/contact-preferences")
  public ContactPreferencesResponse update(String customerId, UpdateRequest request) {
    try {
      return toApi(
        preferences.update(customerId, request.email(), request.sms(), request.phone(), request.reason())
      );
    } catch (PreferencesCallException e) {
      throw failure(e);
    }
  }

  private static ContactPreferencesResponse toApi(Preferences preferences) {
    return new ContactPreferencesResponse(
      preferences.getCustomerId(),
      preferences.getEmail(),
      preferences.getSms(),
      preferences.getPhone()
    );
  }

  private static RuntimeException failure(PreferencesCallException e) {
    return switch (e.code()) {
      case INVALID_ARGUMENT -> HttpException.badRequest(e.getMessage());
      // The preferences service refused our credentials. The caller of this service did nothing wrong.
      case UNAUTHENTICATED, PERMISSION_DENIED -> HttpException.error(StatusCodes.BAD_GATEWAY, e.getMessage());
      default -> HttpException.error(StatusCodes.SERVICE_UNAVAILABLE, e.getMessage());
    };
  }
}
