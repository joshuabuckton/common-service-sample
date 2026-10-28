package preferences.service.api;

import akka.grpc.GrpcServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import java.util.Base64;
import java.util.Map;

/** Unsigned tokens for the tests. The TestKit does not check the signature, only the claims. */
final class TestTokens {

  static final String ISSUER = "preferences-callers";
  private static final ObjectMapper JSON = new ObjectMapper();

  private TestTokens() {}

  static String bearer(Map<String, String> claims) {
    try {
      var header = Base64.getEncoder().encodeToString("{\"alg\":\"none\"}".getBytes());
      var payload = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(claims));
      return "Bearer " + header + "." + payload;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  static String bearerFor(String subject) {
    return bearer(Map.of("iss", ISSUER, "sub", subject));
  }

  static String bearerFor(String subject, String role) {
    return bearer(Map.of("iss", ISSUER, "sub", subject, "role", role));
  }

  static Status.Code codeOf(Throwable error) {
    if (error instanceof GrpcServiceException e) return e.status().getCode();
    if (error instanceof StatusRuntimeException e) return e.getStatus().getCode();
    throw new AssertionError("Unexpected exception type " + error.getClass(), error);
  }
}
