package preferences.service.domain;

import java.util.Optional;
import java.util.stream.Stream;

/** A way to contact a customer. */
public enum Channel {
  EMAIL,
  SMS,
  PHONE;

  /** Reads a channel name from a caller, ignoring case. */
  public static Optional<Channel> parse(String name) {
    return Stream.of(values())
      .filter(channel -> channel.name().equalsIgnoreCase(name))
      .findFirst();
  }
}
