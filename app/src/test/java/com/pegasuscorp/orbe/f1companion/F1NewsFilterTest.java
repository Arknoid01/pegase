package com.pegasuscorp.orbe.f1companion;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class F1NewsFilterTest {

    @Test
    public void matchesFerrariInTitle() {
        F1RssItem item = new F1RssItem("1",
                "Ferrari criticises mega harsh penalty for Hamilton",
                "https://example.com/1",
                "Both Lewis Hamilton and Ferrari disagree.",
                "Autosport");
        FavoriteTeamsStore.TeamDef ferrari = FavoriteTeamsStore.find("ferrari");
        List<F1NewsFilter.Match> matches = F1NewsFilter.filter(
                Collections.singletonList(item),
                Collections.singletonList(ferrari));
        assertEquals(1, matches.size());
        assertEquals("Ferrari", matches.get(0).primaryTeamLabel());
        assertTrue(matches.get(0).score > 10);
    }

    @Test
    public void ignoresUnrelatedArticle() {
        F1RssItem item = new F1RssItem("2",
                "FIA announces calendar tweak",
                "https://example.com/2",
                "No team names here.",
                "Autosport");
        List<F1NewsFilter.Match> matches = F1NewsFilter.filter(
                Collections.singletonList(item),
                Arrays.asList(
                        FavoriteTeamsStore.find("ferrari"),
                        FavoriteTeamsStore.find("mclaren")));
        assertTrue(matches.isEmpty());
    }

    @Test
    public void boostsPenaltyKeyword() {
        F1RssItem plain = new F1RssItem("a", "Ferrari team photo day", "", "small note", "x");
        F1RssItem hot = new F1RssItem("b", "Ferrari hit with harsh penalty", "", "stewards", "x");
        FavoriteTeamsStore.TeamDef ferrari = FavoriteTeamsStore.find("ferrari");
        List<FavoriteTeamsStore.TeamDef> fav = Collections.singletonList(ferrari);
        int sPlain = F1NewsFilter.filter(Collections.singletonList(plain), fav).get(0).score;
        int sHot = F1NewsFilter.filter(Collections.singletonList(hot), fav).get(0).score;
        assertTrue(sHot > sPlain);
    }
}
