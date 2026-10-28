package partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The partner builds from the proto jar only. The code of the other services is not on its path. */
public class NoOtherServiceDependencyTest {

  @Test
  public void theGeneratedClientFromTheProtoJarIsAvailable() throws ClassNotFoundException {
    assertThat(Class.forName("preferences.v1.proto.PreferencesServiceClient")).isNotNull();
  }

  @Test
  public void thePreferencesServiceCodeIsNotOnTheClasspath() {
    assertThatThrownBy(() -> Class.forName("preferences.service.domain.Channel")).isInstanceOf(
      ClassNotFoundException.class
    );
  }

  @Test
  public void theAdvisorServiceCodeIsNotOnTheClasspath() {
    assertThatThrownBy(() -> Class.forName("advisor.application.PreferencesServiceCaller")).isInstanceOf(
      ClassNotFoundException.class
    );
  }
}
