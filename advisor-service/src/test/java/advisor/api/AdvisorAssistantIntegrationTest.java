package advisor.api;

import static org.assertj.core.api.Assertions.assertThat;

import advisor.AdvisorTestBase;
import advisor.api.AdvisorEndpoint.AssistantRequest;
import advisor.api.AdvisorEndpoint.AssistantResponse;
import advisor.application.ContactAdvisorAgent;
import akka.http.javadsl.model.StatusCodes;
import akka.javasdk.testkit.TestKit;
import akka.javasdk.testkit.TestModelProvider;
import org.junit.jupiter.api.Test;

/**
 * The model is replaced, so these tests need no API key and no network. The TestKit has no mock for an
 * MCP endpoint, and the runtime cannot find preferences-service to list its tools. So the tests switch
 * the MCP tools off and do not cover the calls of the agent to preferences-service. They cover the route and
 * the agent with a model that answers directly. The MCP calls need a run of both services.
 */
public class AdvisorAssistantIntegrationTest extends AdvisorTestBase {

  private final TestModelProvider model = new TestModelProvider();

  @Override
  protected TestKit.Settings testKitSettings() {
    // The key is never used. The configuration only needs a value for the default provider.
    return super.testKitSettings()
      .withAdditionalConfig("akka.javasdk.agent.openai.api-key = n/a\nadvisor.assistant.mcp-tools = false")
      .withModelProvider(ContactAdvisorAgent.class, model);
  }

  @Test
  public void theRouteReturnsTheAnswerOfTheAgent() {
    model.fixedResponse("Email is fine for c-1. Do not call, the customer refuses phone.");

    var response = httpClient
      .POST("/customers/assistant")
      .withRequestBody(new AssistantRequest("How should I contact c-1?"))
      .responseBodyAs(AssistantResponse.class)
      .invoke();

    assertThat(response.status()).isEqualTo(StatusCodes.OK);
    assertThat(response.body().answer()).isEqualTo("Email is fine for c-1. Do not call, the customer refuses phone.");
  }

  @Test
  public void anEmptyQuestionIsA400() {
    var response = httpClient.POST("/customers/assistant").withRequestBody(new AssistantRequest("  ")).invoke();

    assertThat(response.status()).isEqualTo(StatusCodes.BAD_REQUEST);
  }
}
