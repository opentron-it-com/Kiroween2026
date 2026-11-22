package org.opentron.backend.security.confidential.gateway.sdk;

import java.util.List;
import java.util.Objects;

/** Runs a role-preserving conversation through one sanitize/restore context. */
public final class ConfidentialConversationClient {
    private final ConfidentialGatewayClient gatewayClient;

    public ConfidentialConversationClient(ConfidentialGatewayClient gatewayClient) {
        this.gatewayClient = Objects.requireNonNull(gatewayClient, "gatewayClient");
    }

    public List<Message> complete(
            List<Message> conversation,
            SanitizedConversationModel model) {
        Objects.requireNonNull(model, "model");
        List<Message> input = validateMessages(conversation, "conversation");
        ConfidentialGatewayClient.BatchSanitizeResult sanitized =
                gatewayClient.sanitizeSegments(input.stream().map(Message::content).toList());
        List<Message> sanitizedConversation = withContents(input, sanitized.sanitizedSegments());

        List<Message> modelResponses = validateMessages(
                model.complete(sanitizedConversation), "model responses");
        List<String> restoredContents = gatewayClient.desanitizeSegments(
                modelResponses.stream().map(Message::content).toList(),
                sanitized.secureContext());
        return withContents(modelResponses, restoredContents);
    }

    private static List<Message> validateMessages(List<Message> messages, String name) {
        if (messages == null || messages.isEmpty() || messages.size() > 100) {
            throw new IllegalArgumentException(name + " must contain between 1 and 100 messages.");
        }
        return List.copyOf(messages);
    }

    private static List<Message> withContents(List<Message> messages, List<String> contents) {
        if (contents == null || contents.size() != messages.size()) {
            throw new IllegalStateException("Message content count did not match the conversation.");
        }
        return java.util.stream.IntStream.range(0, messages.size())
                .mapToObj(index -> new Message(messages.get(index).role(), contents.get(index)))
                .toList();
    }

    @FunctionalInterface
    public interface SanitizedConversationModel {
        List<Message> complete(List<Message> sanitizedConversation);
    }

    public record Message(String role, String content) {
        public Message {
            Objects.requireNonNull(role, "role");
            if (!List.of("system", "developer", "user", "assistant", "tool").contains(role)) {
                throw new IllegalArgumentException("Unsupported conversation role.");
            }
            Objects.requireNonNull(content, "content");
            if (content.length() > 1_000_000) {
                throw new IllegalArgumentException("Message exceeds the supported size.");
            }
        }
    }
}
