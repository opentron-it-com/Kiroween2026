package org.opentron.backend.agents;

public final class AgentPromptConstants {
    private AgentPromptConstants() {}

    // Was a flat 16000 for every model, regardless of AnthropicClient's
    // real per-model output ceiling (Claude Fable/Sonnet/Haiku 5.5: 128000 -
    // see AnthropicClient.MAX_OUTPUT_TOKENS). That
    // flat cap silently truncated large/complex specialist responses
    // (stop_reason=max_tokens) well before the model's actual limit, which
    // is exactly what produced the mid-function cutoffs seen on
    // architecture/code-complexity tasks routed to Claude. Set this
    // to the highest ceiling any supported model has; AnthropicClient
    // still applies Math.min(maxTokens, MAX_OUTPUT_TOKENS.get(model)) per
    // call, so smaller-ceiling models are still correctly
    // capped there - this constant just stops being the bottleneck.
    public static final int SPECIALIST_MAX_TOKENS = 128_000;

    public static final String INTERNET_SEARCH_PROMPT = "Act as an expert internet researcher.\r\n"
            + "\r\n"
            + "Conduct a deep web investigation on [TOPIC].\r\n"
            + "\r\n"
            + "Use only authoritative sources whenever possible.\r\n"
            + "\r\n"
            + "Methodology:\r\n"
            + "1. Search broadly.\r\n"
            + "2. Verify important claims with multiple sources.\r\n"
            + "3. Locate official documentation and primary sources.\r\n"
            + "4. Identify misinformation or unverified claims.\r\n"
            + "5. Compare competing perspectives objectively.\r\n"
            + "6. Present findings with citations and links.\r\n"
            + "\r\n"
            + "Deliver:\r\n"
            + "- Executive summary\r\n"
            + "- Detailed analysis\r\n"
            + "- Evidence table\r\n"
            + "- Key insights\r\n"
            + "- Risks and limitations\r\n"
            + "- Source list\r\n"
            + "\r\n"
            + "Do not make assumptions.\r\n"
            + "Clearly distinguish verified facts from opinions." 
            + "You have internet access and can fetch external content, perform google search, research topics, and integrate with APIs. ";
}
