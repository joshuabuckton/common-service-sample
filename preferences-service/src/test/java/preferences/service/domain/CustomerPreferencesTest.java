package preferences.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CustomerPreferencesTest {

  @Test
  void aCustomerWithNoRecordAcceptsNoChannel() {
    var preferences = CustomerPreferences.empty("customer-1");

    for (var channel : Channel.values()) {
      assertThat(preferences.allows(channel)).isFalse();
    }
  }

  @Test
  void withChannelsSetsAllThreeAndKeepsTheCustomer() {
    var preferences = CustomerPreferences.empty("customer-1").withChannels(true, false, true);

    assertThat(preferences.customerId()).isEqualTo("customer-1");
    assertThat(preferences.allows(Channel.EMAIL)).isTrue();
    assertThat(preferences.allows(Channel.SMS)).isFalse();
    assertThat(preferences.allows(Channel.PHONE)).isTrue();
  }

  @Test
  void channelNamesAreReadIgnoringCase() {
    assertThat(Channel.parse("sms")).contains(Channel.SMS);
    assertThat(Channel.parse("Email")).contains(Channel.EMAIL);
    assertThat(Channel.parse("fax")).isEmpty();
    assertThat(Channel.parse(null)).isEmpty();
  }
}
