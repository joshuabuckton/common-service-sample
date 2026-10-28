package partner.application;

import akka.grpc.GrpcServiceException;
import akka.javasdk.grpc.GrpcClientProvider;
import com.typesafe.config.Config;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.function.Supplier;
import preferences.v1.proto.GetRequest;
import preferences.v1.proto.Preferences;
import preferences.v1.proto.PreferencesServiceClient;
import preferences.v1.proto.UpdateRequest;

/**
 * Calls the preferences service of another project. The only things this class knows about the
 * service are the generated client from the published proto, its public address and a token.
 */
public class PartnerPreferencesClient {

  /** A call that did not succeed, with the gRPC status code. */
  public static class PreferencesCallException extends RuntimeException {

    private final Status.Code code;

    PreferencesCallException(Status.Code code, String message) {
      super(message);
      this.code = code;
    }

    public Status.Code code() {
      return code;
    }
  }

  private final PreferencesServiceClient client;
  private final String token;

  public PartnerPreferencesClient(GrpcClientProvider grpcClientProvider, Config config) {
    this.client = grpcClientProvider.grpcClientFor(
      PreferencesServiceClient.class,
      config.getString("partner.preferences-host")
    );
    this.token = config.getString("partner.preferences-token");
  }

  public Preferences get(String customerId) {
    return call(() -> authenticated().get(GetRequest.newBuilder().setCustomerId(customerId).build()));
  }

  public Preferences update(String customerId, boolean email, boolean sms, boolean phone, String reason) {
    return call(() ->
      authenticated().update(
        UpdateRequest.newBuilder()
          .setCustomerId(customerId)
          .setEmail(email)
          .setSms(sms)
          .setPhone(phone)
          .setReason(reason == null ? "" : reason)
          .build()
      )
    );
  }

  // The generated client is immutable. Adding a header returns a client that sends it.
  private PreferencesServiceClient authenticated() {
    return client.addRequestHeader("Authorization", "Bearer " + token);
  }

  private static <T> T call(Supplier<T> call) {
    try {
      return call.get();
    } catch (GrpcServiceException e) {
      throw new PreferencesCallException(e.status().getCode(), e.getMessage());
    } catch (StatusRuntimeException e) {
      throw new PreferencesCallException(e.getStatus().getCode(), e.getMessage());
    } catch (RuntimeException e) {
      throw new PreferencesCallException(Status.Code.UNAVAILABLE, e.getMessage());
    }
  }
}
