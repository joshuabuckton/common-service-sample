package partner;

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

/** A stand-in for the preferences service in another project. */
public final class MockPreferencesService extends PreferencesServiceClient {

  /** The address that the partner configuration uses. */
  public static final String HOST = "preferences.local.example";

  public final List<String> headers = new CopyOnWriteArrayList<>();
  public Function<GetRequest, Preferences> onGet = in -> Preferences.getDefaultInstance();
  public Function<UpdateRequest, Preferences> onUpdate = in -> Preferences.getDefaultInstance();

  /** The test class shares one instance across its tests, so each test starts from a clean mock. */
  public void reset() {
    headers.clear();
    onGet = in -> Preferences.getDefaultInstance();
    onUpdate = in -> Preferences.getDefaultInstance();
  }

  @Override
  public Preferences get(GetRequest in) {
    return onGet.apply(in);
  }

  @Override
  public Preferences update(UpdateRequest in) {
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
