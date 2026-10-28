package advisor;

import akka.Done;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import preferences.v1.proto.GetRequest;
import preferences.v1.proto.Preferences;
import preferences.v1.proto.PreferencesServiceClient;
import preferences.v1.proto.UpdateRequest;

/** A stand-in for preferences-service, registered with the TestKit as the gRPC service of that name. */
public final class MockPreferencesService extends PreferencesServiceClient {

  public final List<String> headers = new CopyOnWriteArrayList<>();
  public final List<UpdateRequest> updates = new CopyOnWriteArrayList<>();

  public volatile Function<UpdateRequest, Preferences> onUpdate = MockPreferencesService::echo;

  public void reset() {
    headers.clear();
    updates.clear();
    onUpdate = MockPreferencesService::echo;
  }

  private static Preferences echo(UpdateRequest in) {
    return Preferences.newBuilder()
      .setCustomerId(in.getCustomerId())
      .setEmail(in.getEmail())
      .setSms(in.getSms())
      .setPhone(in.getPhone())
      .build();
  }

  @Override
  public Preferences get(GetRequest in) {
    throw new UnsupportedOperationException("advisor-service does not read through gRPC");
  }

  @Override
  public Preferences update(UpdateRequest in) {
    updates.add(in);
    return onUpdate.apply(in);
  }

  @Override
  public CompletionStage<Done> close() {
    return CompletableFuture.completedFuture(Done.getInstance());
  }

  @Override
  public CompletionStage<Done> closed() {
    return CompletableFuture.completedFuture(Done.getInstance());
  }

  @Override
  public PreferencesServiceClient addRequestHeader(String key, String value) {
    headers.add(key + "=" + value);
    return this;
  }
}
