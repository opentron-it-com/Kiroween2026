package org.opentron.backend.llm.anthropic;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/*** Anthropic Messages API Response Body DTO
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicResponse {

    public String id;
    public String type;
    public String role;
    public List<AnthropicContent> content;
    public String model;
    public String stop_reason;

    @JsonProperty("usage")
    public AnthropicUsage usage;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnthropicContent {
        public String type;
        public String text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnthropicUsage {
        @JsonProperty("input_tokens")
        public int input_tokens;

        @JsonProperty("output_tokens")
        public int output_tokens;

        @JsonProperty("cache_creation_input_tokens")
        public int cache_creation_input_tokens = 0;

        @JsonProperty("cache_read_input_tokens")
        public int cache_read_input_tokens = 0;

        public int getTotalInputTokens() {
            return input_tokens + cache_creation_input_tokens + cache_read_input_tokens;
        }

        public int getTotalTokens() {
            return getTotalInputTokens() + output_tokens;
        }
    }
}