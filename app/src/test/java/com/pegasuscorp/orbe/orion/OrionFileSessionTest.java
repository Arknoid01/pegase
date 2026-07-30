package com.pegasuscorp.orbe.orion;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class OrionFileSessionTest {

    @Test
    public void addFile_sameNameKeepsLastContent() {
        OrionFileSession s = new OrionFileSession("t");
        s.addFile("timer.js", "// base");
        s.addFile("other.js", "1");
        s.addFile("timer.js", "// final");
        assertEquals(2, s.size());
        assertEquals("timer.js", s.getFiles().get(0).path);
        assertEquals("// final", s.getFiles().get(0).content);
        assertEquals("other.js", s.getFiles().get(1).path);
    }

    @Test
    public void addAll_collapsesDuplicatesCaseInsensitive() {
        OrionFileSession s = new OrionFileSession("t");
        List<OrionFileParser.ParsedFile> parsed = Arrays.asList(
                new OrionFileParser.ParsedFile("Timer.js", "a"),
                new OrionFileParser.ParsedFile("other.js", "b"),
                new OrionFileParser.ParsedFile("timer.js", "c"));
        s.addAll(parsed);
        assertEquals(2, s.size());
        assertEquals("c", s.find("timer.js").content);
        assertEquals("b", s.find("other.js").content);
    }
}
