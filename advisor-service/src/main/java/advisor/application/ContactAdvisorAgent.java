package advisor.application;

import akka.http.javadsl.model.headers.Authorization;
import akka.javasdk.agent.Agent;
import akka.javasdk.agent.RemoteMcpTools;
import akka.javasdk.annotations.Component;
import com.typesafe.config.Config;

/**
 * Helps an advisor decide whether and how to contact a customer. It gets its facts from the MCP
 * endpoint of preferences-service, which it reaches by service name like the other calls in this
 * service. It sends the same bearer token as {@link PreferencesServiceCaller}, because the MCP endpoint
 * applies the token rule of the whole service. The agent can read preferences and cannot change them.
 */
@Component(id = "contact-advisor-agent")
public class ContactAdvisorAgent extends Agent {

  private static final String SYSTEM_MESSAGE =
    """
    You help advisors decide whether and how to contact a customer.
    Always check the contact preferences of the customer with your tools before you suggest a channel.
    Only suggest a channel that the customer accepts. If the customer accepts none, say so and
    suggest no contact. If the advisor gives no customer id, ask for it.
    """.stripIndent();

  private final String bearerToken;
  private final boolean useMcpTools;

  public ContactAdvisorAgent(Config config) {
    this.bearerToken = config.getString("advisor.preferences-token");
    this.useMcpTools = config.getBoolean("advisor.assistant.mcp-tools");
  }

  public Effect<String> ask(String question) {
    var builder = effects().systemMessage(SYSTEM_MESSAGE);
    // The runtime lists the remote tools before every model call, and the TestKit cannot reach another
    // service for that. The tests switch the tools off so that they can run with a model that answers directly.
    if (useMcpTools) {
      builder = builder.mcpTools(
        RemoteMcpTools.fromService(PreferencesServiceCaller.SERVICE_NAME)
          .addClientHeader(Authorization.oauth2(bearerToken))
          .withAllowedToolNames("get_contact_preferences", "can_contact")
      );
    }
    return builder.userMessage(question).thenReply();
  }
}
