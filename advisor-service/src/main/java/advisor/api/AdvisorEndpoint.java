package advisor.api;

import advisor.application.ContactPreferencesByCustomerView;
import advisor.application.PreferencesServiceCaller;
import advisor.application.PreferencesServiceCaller.Refused;
import advisor.application.PreferencesServiceCaller.Rejected;
import advisor.application.PreferencesServiceCaller.Unavailable;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.grpc.GrpcClientProvider;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.HttpException;
import com.typesafe.config.Config;

// Open to the internet to keep the sample easy to try. A real service restricts this.
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.INTERNET))
@HttpEndpoint("/customers")
public class AdvisorEndpoint extends AbstractHttpEndpoint {

  /** known is false when this service has not yet seen the customer on the stream. */
  public record ContactPreferencesResponse(
    String customerId,
    boolean email,
    boolean sms,
    boolean phone,
    boolean known
  ) {}

  public record UpdateRequest(boolean email, boolean sms, boolean phone, String reason) {}

  public record CanContactResponse(String customerId, String channel, boolean allowed) {}

  private final ComponentClient componentClient;
  private final PreferencesServiceCaller preferences;

  public AdvisorEndpoint(
    ComponentClient componentClient,
    GrpcClientProvider grpcClientProvider,
    HttpClientProvider httpClientProvider,
    Config config
  ) {
    this.componentClient = componentClient;
    this.preferences = PreferencesServiceCaller.using(grpcClientProvider, httpClientProvider, () ->
      config.getString("advisor.preferences-token")
    );
  }

  // Answers from the local view. It does not call the preferences service, so it can lag behind a
  // write and keeps answering when the preferences service is down.
  @Get("/{customerId}/contact-preferences")
  public ContactPreferencesResponse get(String customerId) {
    return componentClient
      .forView()
      .method(ContactPreferencesByCustomerView::forCustomer)
      .invoke(customerId)
      .customers()
      .stream()
      .findFirst()
      .map(row -> new ContactPreferencesResponse(customerId, row.email(), row.sms(), row.phone(), true))
      .orElse(new ContactPreferencesResponse(customerId, false, false, false, false));
  }

  // A write goes to the preferences service over gRPC. It fails when that service is unavailable.
  @Put("/{customerId}/contact-preferences")
  public ContactPreferencesResponse update(String customerId, UpdateRequest request) {
    try {
      var saved = preferences.update(
        customerId,
        request.email(),
        request.sms(),
        request.phone(),
        request.reason()
      );
      return new ContactPreferencesResponse(saved.customerId(), saved.email(), saved.sms(), saved.phone(), true);
    } catch (Rejected e) {
      throw HttpException.badRequest(e.getMessage());
    } catch (Refused e) {
      throw HttpException.error(StatusCodes.BAD_GATEWAY, e.getMessage());
    } catch (Unavailable e) {
      throw HttpException.error(StatusCodes.SERVICE_UNAVAILABLE, e.getMessage());
    }
  }

  // The live answer, from the preferences service over HTTP. Use it when a stale answer is not good
  // enough, for example right before a contact. It fails when that service is unavailable.
  @Get("/{customerId}/can-contact/{channel}")
  public CanContactResponse canContact(String customerId, String channel) {
    try {
      var decision = preferences.canContact(customerId, channel);
      return new CanContactResponse(decision.customerId(), decision.channel(), decision.allowed());
    } catch (Rejected e) {
      throw HttpException.badRequest(e.getMessage());
    } catch (Refused e) {
      throw HttpException.error(StatusCodes.BAD_GATEWAY, e.getMessage());
    } catch (Unavailable e) {
      throw HttpException.error(StatusCodes.SERVICE_UNAVAILABLE, e.getMessage());
    }
  }
}
