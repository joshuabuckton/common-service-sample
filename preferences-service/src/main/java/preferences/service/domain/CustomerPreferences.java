package preferences.service.domain;

/** Which channels a customer accepts. A customer with no record has every channel off. */
public record CustomerPreferences(String customerId, boolean email, boolean sms, boolean phone) {
  public static CustomerPreferences empty(String customerId) {
    return new CustomerPreferences(customerId, false, false, false);
  }

  public CustomerPreferences withChannels(boolean newEmail, boolean newSms, boolean newPhone) {
    return new CustomerPreferences(customerId, newEmail, newSms, newPhone);
  }

  public boolean allows(Channel channel) {
    return switch (channel) {
      case EMAIL -> email;
      case SMS -> sms;
      case PHONE -> phone;
    };
  }
}
