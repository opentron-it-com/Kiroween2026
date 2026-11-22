package org.opentron.backend.security.confidential.gateway.onnx;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class BertWordPieceTokenizer {
    private static final int MAX_WORD_CODE_POINTS = 100;

    private final Map<String, Integer> vocabulary;
    private final TrieNode wordPieceRoot;
    private final int unknownId;
    private final int classificationId;
    private final int separatorId;
    private final int paddingId;
    private final boolean lowercase;

    BertWordPieceTokenizer(
            Path vocabularyPath,
            Path specialTokensPath,
            boolean lowercase,
            ObjectMapper objectMapper) {
        Objects.requireNonNull(vocabularyPath, "vocabularyPath");
        Objects.requireNonNull(specialTokensPath, "specialTokensPath");
        Objects.requireNonNull(objectMapper, "objectMapper");
        Vocabulary loadedVocabulary = loadVocabulary(vocabularyPath);
        this.vocabulary = loadedVocabulary.ids();
        this.wordPieceRoot = loadedVocabulary.root();
        Map<String, String> specialTokens = loadSpecialTokens(specialTokensPath, objectMapper);
        this.unknownId = requireTokenId(specialTokens.get("unk_token"));
        this.classificationId = requireTokenId(specialTokens.get("cls_token"));
        this.separatorId = requireTokenId(specialTokens.get("sep_token"));
        this.paddingId = requireTokenId(specialTokens.get("pad_token"));
        this.lowercase = lowercase;
    }

    int classificationId() {
        return classificationId;
    }

    int separatorId() {
        return separatorId;
    }

    int paddingId() {
        return paddingId;
    }

    List<Token> tokenize(CharSequence text) {
        Objects.requireNonNull(text, "text");
        List<Token> tokens = new ArrayList<>();
        int wordStart = -1;
        for (int offset = 0; offset < text.length();) {
            int codePoint = Character.codePointAt(text, offset);
            int nextOffset = offset + Character.charCount(codePoint);
            if (isControl(codePoint) || isWhitespace(codePoint)) {
                if (wordStart >= 0) {
                    tokenizeWord(text, wordStart, offset, tokens);
                    wordStart = -1;
                }
            } else if (isPunctuation(codePoint) || isCjk(codePoint)) {
                if (wordStart >= 0) {
                    tokenizeWord(text, wordStart, offset, tokens);
                    wordStart = -1;
                }
                tokenizeWord(text, offset, nextOffset, tokens);
            } else if (wordStart < 0) {
                wordStart = offset;
            }
            offset = nextOffset;
        }
        if (wordStart >= 0) {
            tokenizeWord(text, wordStart, text.length(), tokens);
        }
        return List.copyOf(tokens);
    }

    private int requireTokenId(String token) {
        Integer id = vocabulary.get(token);
        if (token == null || id == null) {
            throw new IllegalStateException(
                    "BERT vocabulary does not contain a required special token.");
        }
        return id;
    }

    private void tokenizeWord(CharSequence text, int start, int end, List<Token> tokens) {
        NormalizedWord word = normalizeWord(text, start, end, lowercase);
        int[] normalized = word.codePoints();

        if (normalized.length == 0) {
            return;
        }
        if (normalized.length > MAX_WORD_CODE_POINTS) {
            tokens.add(new Token(unknownId, start, end));
            return;
        }

        int cursor = 0;
        List<Token> wordPieces = new ArrayList<>();
        while (cursor < normalized.length) {
            TrieNode node = wordPieceRoot;
            if (cursor > 0) {
                node = node.children().get((int) '#');
                if (node != null) {
                    node = node.children().get((int) '#');
                }
            }
            int matchEnd = cursor;
            Integer matchId = null;
            for (int offset = cursor; node != null && offset < normalized.length; offset++) {
                node = node.children().get(normalized[offset]);
                if (node != null && node.tokenId() != null) {
                    matchId = node.tokenId();
                    matchEnd = offset + 1;
                }
            }
            if (matchId == null || matchEnd == cursor) {
                tokens.add(new Token(unknownId, start, end));
                return;
            }
            wordPieces.add(new Token(
                    matchId,
                    word.starts()[cursor],
                    word.ends()[matchEnd - 1],
                    start,
                    end));
            cursor = matchEnd;
        }
        tokens.addAll(wordPieces);
    }

    private static NormalizedWord normalizeWord(
            CharSequence text, int start, int end, boolean lowercase) {
        int capacity = Math.max(1, end - start);
        int[] codePoints = new int[capacity];
        int[] starts = new int[capacity];
        int[] ends = new int[capacity];
        int size = 0;
        for (int offset = start; offset < end;) {
            int codePoint = Character.codePointAt(text, offset);
            int sourceEnd = offset + Character.charCount(codePoint);
            if (codePoint <= 0x7f) {
                int normalizedCodePoint = lowercase
                        ? Character.toLowerCase(codePoint)
                        : codePoint;
                if (size == codePoints.length) {
                    int newCapacity = Math.max(4, codePoints.length * 2);
                    codePoints = java.util.Arrays.copyOf(codePoints, newCapacity);
                    starts = java.util.Arrays.copyOf(starts, newCapacity);
                    ends = java.util.Arrays.copyOf(ends, newCapacity);
                }
                codePoints[size] = normalizedCodePoint;
                starts[size] = offset;
                ends[size] = sourceEnd;
                size++;
            } else {
                String normalizedCharacter = normalize(codePoint, lowercase);
                for (int normalizedOffset = 0;
                        normalizedOffset < normalizedCharacter.length();) {
                    int normalizedCodePoint = normalizedCharacter.codePointAt(normalizedOffset);
                    normalizedOffset += Character.charCount(normalizedCodePoint);
                    if (size == codePoints.length) {
                        int newCapacity = Math.max(4, codePoints.length * 2);
                        codePoints = java.util.Arrays.copyOf(codePoints, newCapacity);
                        starts = java.util.Arrays.copyOf(starts, newCapacity);
                        ends = java.util.Arrays.copyOf(ends, newCapacity);
                    }
                    codePoints[size] = normalizedCodePoint;
                    starts[size] = offset;
                    ends[size] = sourceEnd;
                    size++;
                }
            }
            offset = sourceEnd;
        }
        return new NormalizedWord(
                java.util.Arrays.copyOf(codePoints, size),
                java.util.Arrays.copyOf(starts, size),
                java.util.Arrays.copyOf(ends, size));
    }

    private static String normalize(int codePoint, boolean lowercase) {
        String value = new String(Character.toChars(codePoint));
        if (lowercase) {
            value = value.toLowerCase(Locale.ROOT);
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFD);
        StringBuilder result = new StringBuilder(decomposed.length());
        decomposed.codePoints()
                .filter(character -> Character.getType(character) != Character.NON_SPACING_MARK)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static Vocabulary loadVocabulary(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Configured BERT vocabulary is missing.");
        }
        Map<String, Integer> vocabulary = new HashMap<>();
        TrieNode root = new TrieNode();
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String token;
            int id = 0;
            while ((token = reader.readLine()) != null) {
                if (token.isEmpty() || vocabulary.putIfAbsent(token, id) != null) {
                    throw new IllegalStateException("BERT vocabulary contains an empty or duplicate token.");
                }
                TrieNode node = root;
                for (int codePoint : token.codePoints().toArray()) {
                    node = node.children().computeIfAbsent(
                            codePoint, ignored -> new TrieNode());
                }
                node.setTokenId(id);
                id++;
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read configured BERT vocabulary.", exception);
        }
        if (vocabulary.isEmpty()) {
            throw new IllegalStateException("Configured BERT vocabulary is empty.");
        }
        return new Vocabulary(Map.copyOf(vocabulary), root);
    }

    private static Map<String, String> loadSpecialTokens(Path path, ObjectMapper objectMapper) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Configured BERT special-token map is missing.");
        }
        try {
            JsonNode root = objectMapper.readTree(path.toFile());
            Map<String, String> tokens = new HashMap<>();
            for (String name : List.of("unk_token", "cls_token", "sep_token", "pad_token")) {
                JsonNode token = root.get(name);
                if (token == null || !token.isTextual()) {
                    throw new IllegalStateException(
                            "BERT special-token map is missing a required token.");
                }
                tokens.put(name, token.textValue());
            }
            return Map.copyOf(tokens);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read configured BERT special-token map.", exception);
        }
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isControl(int codePoint) {
        int type = Character.getType(codePoint);
        return (type == Character.CONTROL && codePoint != '\t' && codePoint != '\n' && codePoint != '\r')
                || type == Character.FORMAT;
    }

    private static boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.CONNECTOR_PUNCTUATION
                || type == Character.DASH_PUNCTUATION
                || type == Character.START_PUNCTUATION
                || type == Character.END_PUNCTUATION
                || type == Character.INITIAL_QUOTE_PUNCTUATION
                || type == Character.FINAL_QUOTE_PUNCTUATION
                || type == Character.OTHER_PUNCTUATION;
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x20000 && codePoint <= 0x2A6DF)
                || (codePoint >= 0x2A700 && codePoint <= 0x2B73F)
                || (codePoint >= 0x2B740 && codePoint <= 0x2B81F)
                || (codePoint >= 0x2B820 && codePoint <= 0x2CEAF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x2F800 && codePoint <= 0x2FA1F);
    }

    record Token(long id, int start, int end, int wordStart, int wordEnd) {
        Token(long id, int start, int end) {
            this(id, start, end, start, end);
        }
    }

    private record Vocabulary(Map<String, Integer> ids, TrieNode root) {
    }

    private record NormalizedWord(int[] codePoints, int[] starts, int[] ends) {
    }

    private static final class TrieNode {
        private final Map<Integer, TrieNode> children = new HashMap<>();
        private Integer tokenId;

        private Map<Integer, TrieNode> children() {
            return children;
        }

        private Integer tokenId() {
            return tokenId;
        }

        private void setTokenId(int tokenId) {
            this.tokenId = tokenId;
        }
    }
}
