package com.pegasuscorp.orbe.git;

import org.junit.Test;

import static org.junit.Assert.*;

public class GitHubApiClientTest {

    @Test
    public void parseRepo_acceptsOwnerRepoAndUrl() {
        GitHubApiClient.ParsedRepo a = GitHubApiClient.ParsedRepo.parse("yanno/orbe");
        assertTrue(a.valid);
        assertEquals("yanno", a.owner);
        assertEquals("orbe", a.name);

        GitHubApiClient.ParsedRepo b = GitHubApiClient.ParsedRepo.parse(
                "https://github.com/yanno/orbe.git");
        assertTrue(b.valid);
        assertEquals("yanno", b.owner);
        assertEquals("orbe", b.name);
    }

    @Test
    public void sanitizePath_rejectsTraversal() {
        assertEquals("", GitHubApiClient.sanitizePath("../secret"));
        assertEquals("docs/readme.md", GitHubApiClient.sanitizePath("/docs/readme.md"));
    }

    @Test
    public void sanitizeRepoName_fromFilename() {
        assertEquals("hello-world", GitHubApiClient.sanitizeRepoName("Hello World.java"));
        assertEquals("foo_bar", GitHubApiClient.sanitizeRepoName("path/Foo_Bar.md"));
        assertEquals("", GitHubApiClient.sanitizeRepoName("!!!"));
    }
}
