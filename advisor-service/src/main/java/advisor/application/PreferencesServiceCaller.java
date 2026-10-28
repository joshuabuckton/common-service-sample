package advisor.application;

import akka.grpc.GrpcServiceException;
import akka.javasdk.JsonSupport;
import akka.javasdk.grpc.GrpcClientProvider;
import akka.javasdk.http.HttpClient;
import akka.javasdk.http.HttpClientProvider;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import preferences.v1.proto.PreferencesServiceClient;
import preferences.v1.proto.UpdateRequest;

/**
 * The calls from this service to preferences-service, both by service name. A write goes over gRPC.
 * The question "may I contact this customer" goes over HTTP, to show the second client style.
 *
 * <p>The preferences service requires a bearer token from every caller, so each call sends the token
 * that the supplier returns. A call that times out can still have been applied: read before you write
 * again.
 */
public class PreferencesServiceCaller {

  /** The name that the preferences service is deployed with. */
  public static final String SERVICE_NAME = "preferences-service";

  public static final Duration TIMEOUT = Duration.ofSeconds(5);

  private static final Executor CALLS = Executors.newVirtualThreadPerTaskExecutor();

  public record Preferences(String customerId, boolean email, boolean sms, boolean phone) {}

  public record ContactDecision(String customerId, String channel, boolean allowed) {}

  /** The preferences service could not be reached, or did not answer in time. */
  public static class Unavailable extends RuntimeException {

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** The preferences service rejected the request as invalid. */
  public static class Rejected extends RuntimeException {

    public Rejected(String message) {
      super(message);
    }
  }

  /** The preferences service refused the credentials of this service. The caller did nothing wrong. */
  public static class Refused extends RuntimeException {

    public Refused(String message) {
      super(message);
    }
  }

  private final PreferencesServiceClient grpc;
  private final HttpClient http;
  private final Supplier<String> bearerToken;

  public static PreferencesServiceCaller using(
    GrpcClientProvider grpcClientProvider,
    HttpClientProvider httpClientProvider,
    Supplier<String> bearerToken
  ) {
    return new PreferencesServiceCaller(
      grpcClientProvider.grpcClientFor(PreferencesServiceClient.class, SERVICE_NAME),
      httpClientProvider.httpClientFor(SERVICE_NAME),
      bearerToken
    );
  }

  PreferencesServiceCaller(PreferencesServiceClient grpc, HttpClient http, Supplier<String> bearerToken) {
    this.grpc = grpc;
    this.http = http;
    this.bearerToken = bearerToken;
  }

  public Preferences update(String customerId, boolean email, boolean sms, boolean phone, String reason) {
    var request = UpdateRequest.newBuilder()
      .setCustomerId(customerId)
      .setEmail(email)
      .setSms(sms)
      .setPhone(phone)
      .setReason(reason == null ? "" : reason)
      .build();
    // The generated client is immutable. Adding a header returns a client that sends it.
    var reply = callGrpc(() ->
      grpc.addRequestHeader("Authorization", "Bearer " + bearerToken.get()).update(request)
    );
    return new Preferences(reply.getCustomerId(), reply.getEmail(), reply.getSms(), reply.getPhone());
  }

  public ContactDecision canContact(String customerId, String channel) {
    var path = "/preferences/" + encode(customerId) + "/can-contact/" + encode(channel);
    akka.javasdk.http.StrictResponse<akka.util.ByteString> response;
    try {
      response = http
        .GET(path)
        .addHeader("Authorization", "Bearer " + bearerToken.get())
        .withTimeout(TIMEOUT)
        .invoke();
    } catch (RuntimeException e) {
      throw new Unavailable("The preferences service did not answer", e);
    }
    var status = response.status();
    if (status.isSuccess()) {
      try {
        return JsonSupport.getObjectMapper().readValue(response.body().toArray(), ContactDecision.class);
      } catch (Exception e) {
        throw new Unavailable("The preferences service sent an answer that cannot be read", e);
      }
    }
    var code = status.intValue();
    if (code == 400) {
      throw new Rejected(response.body().utf8String());
    }
    if (code == 401 || code == 403) {
      throw new Refused("The preferences service refused the credentials");
    }
    throw new Unavailable("The preferences service answered " + code, null);
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private <T> T callGrpc(Supplier<T> call) {
    var future = CompletableFuture.supplyAsync(call, CALLS);
    try {
      return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new Unavailable("The preferences service did not answer in time", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new Unavailable("Interrupted while calling the preferences service", e);
    } catch (ExecutionException e) {
      throw failure(e.getCause());
    }
  }

  private static RuntimeException failure(Throwable error) {
    var status = statusOf(error);
    var message = status.getDescription() != null ? status.getDescription() : status.getCode().name();
    return switch (status.getCode()) {
      case INVALID_ARGUMENT -> new Rejected(message);
      case UNAUTHENTICATED, PERMISSION_DENIED -> new Refused(message);
      default -> new Unavailable("The preferences service call failed: " + status.getCode(), error);
    };
  }

  private static Status statusOf(Throwable error) {
    if (error instanceof GrpcServiceException e) return e.status();
    if (error instanceof StatusRuntimeException e) return e.getStatus();
    return Status.UNAVAILABLE;
  }
}
