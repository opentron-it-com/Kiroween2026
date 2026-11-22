package org.opentron.backend.security.confidential.gateway.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BertWordPieceTokenizerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void tokenizesWordPiecesAndPreservesOriginalUtf16Offsets() throws Exception {
        BertWordPieceTokenizer tokenizer = createTokenizer(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\nplay\n##ing\n!\n");

        List<BertWordPieceTokenizer.Token> tokens = tokenizer.tokenize("Playing!");

        assertEquals(List.of(
                new BertWordPieceTokenizer.Token(4, 0, 4, 0, 7),
                new BertWordPieceTokenizer.Token(5, 4, 7, 0, 7),
                new BertWordPieceTokenizer.Token(6, 7, 8, 7, 8)), tokens);
    }

    @Test
    void mapsUnrecognizedWordsToUnknownTokenWithoutLosingTheirOffsets() throws Exception {
        BertWordPieceTokenizer tokenizer = createTokenizer(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\n!\n");

        assertEquals(List.of(
                new BertWordPieceTokenizer.Token(1, 0, 5),
                new BertWordPieceTokenizer.Token(4, 5, 6)),
                tokenizer.tokenize("Hello!"));
    }

    @Test
    void normalizesUnicodeWhileKeepingOriginalOffsets() throws Exception {
        BertWordPieceTokenizer tokenizer = createTokenizer(
                "[PAD]\n[UNK]\n[CLS]\n[SEP]\ncafe\n");

        assertEquals(List.of(new BertWordPieceTokenizer.Token(4, 0, 4)),
                tokenizer.tokenize("Caf\u00e9"));
    }

    private BertWordPieceTokenizer createTokenizer(String vocabulary) throws Exception {
        Path vocabularyPath = temporaryDirectory.resolve("vocab.txt");
        Path specialTokensPath = temporaryDirectory.resolve("special_tokens_map.json");
        Files.writeString(vocabularyPath, vocabulary);
        Files.writeString(
                specialTokensPath,
                "{\"unk_token\":\"[UNK]\",\"cls_token\":\"[CLS]\","
                        + "\"sep_token\":\"[SEP]\",\"pad_token\":\"[PAD]\"}");
        return new BertWordPieceTokenizer(
                vocabularyPath, specialTokensPath, true, new ObjectMapper());
    }
}
