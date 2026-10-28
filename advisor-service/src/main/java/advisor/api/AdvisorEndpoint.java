package advisor.api;

import advisor.application.ContactAdvisorAgent;
import advisor.application.ContactPreferencesByCustomerView;
import advisor.application.PreferencesServiceCaller;
import advisor.application.PreferencesServiceCaller.Refused;
import advisor.application.PreferencesServiceCaller.Rejected;
import advisor.application.PreferencesServiceCaller.Unavailable;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import akka.javasdk.annotations.http.Put;
import akka.javasdk.client.ComponentClient;
import akka.javasdk.grpc.GrpcClientProvider;
import akka.javasdk.http.AbstractHttpEndpoint;
import akka.javasdk.http.HttpClientProvider;
import akka.javasdk.http.HttpException;
import com.typesafe.config.Config;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

  public record AssistantRequest(String question) {}

  public record AssistantResponse(String answer) {}

  private static final Logger log = LoggerFactory.getLogger(AdvisorEndpoint.class);

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

  // The agent answers from the MCP tools of the preferences service, which it calls by service name
  // with the same token. Every request is its own session, so the agent keeps no earlier questions.
  // It fails when the model or the preferences service is unavailable.
  @Post("/assistant")
  public AssistantResponse ask(AssistantRequest request) {
    if (request == null || request.question() == null || request.question().isBlank()) {
      throw HttpException.badRequest("The question must not be empty");
    }
    try {
      var answer = componentClient
        .forAgent()
        .inSession(UUID.randomUUID().toString())
        .method(ContactAdvisorAgent::ask)
        .invoke(request.question());
      return new AssistantResponse(answer);
    } catch (RuntimeException e) {
      log.warn("The assistant could not answer", e);
      throw HttpException.error(StatusCodes.SERVICE_UNAVAILABLE, "The assistant could not answer");
    }
  }
}
