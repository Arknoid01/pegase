package com.pegasuscorp.orbe.rag;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;

import static org.junit.Assert.*;

public class BertWordPieceTokenizerTest {

    private BertWordPieceTokenizer tokenizer;

    @Before
    public void setUp() throws Exception {
        File vocab = new File("src/main/assets/rag/vocab.txt");
        assertTrue("vocab.txt manquant — lancer le téléchargement assets/rag", vocab.exists());
        try (FileInputStream in = new FileInputStream(vocab)) {
            tokenizer = new BertWordPieceTokenizer(in);
        }
    }

    @Test
    public void encode_bonjour_matchesHuggingFace() {
        BertWordPieceTokenizer.Encoded enc = tokenizer.encode("bonjour", 32);
        assertEquals(101, enc.inputIds[0]); // CLS
        assertEquals(14753, enc.inputIds[1]); // bon
        assertEquals(23099, enc.inputIds[2]); // ##jou
        assertEquals(2099, enc.inputIds[3]); // ##r
        assertEquals(102, enc.inputIds[4]); // SEP
        assertEquals(1, enc.attentionMask[0]);
        assertEquals(1, enc.attentionMask[4]);
        assertEquals(0, enc.attentionMask[5]);
        assertEquals(0, enc.tokenTypeIds[0]);
    }
}
