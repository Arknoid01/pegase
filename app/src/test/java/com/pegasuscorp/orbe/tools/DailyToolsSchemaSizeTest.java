package com.pegasuscorp.orbe.tools;

import com.pegasuscorp.orbe.memory.ContextAnalyzer;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Mesure : combien d'outils + chars par schema pour intent general (daily)
 * vs registre complet — pour décider si le levier est le nombre ou la verbosité.
 */
@RunWith(RobolectricTestRunner.class)
public class DailyToolsSchemaSizeTest {

    @Test
    public void reportDailyVsAllSchemaSizes() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        var intent = ContextAnalyzer.analyze(RuntimeEnvironment.getApplication(),
                "salut ça va ?");
        EnumSet<ToolTag> daily = intent.allowedTools;
        EnumSet<ToolTag> all = EnumSet.allOf(ToolTag.class);

        JSONArray dailyJson = OpenAiToolSchemaBuilder.build(reg, daily);
        JSONArray allJson = OpenAiToolSchemaBuilder.build(reg, all);

        List<Tool> dailyTools = reg.listTools(daily);
        List<Tool> allTools = reg.listTools(all);

        System.out.println("SCHEMA intent=" + intent.intent
                + " daily_tags=" + daily.size()
                + " daily_tools=" + dailyTools.size()
                + " daily_chars=" + dailyJson.toString().length()
                + " all_tools=" + allTools.size()
                + " all_chars=" + allJson.toString().length());

        List<int[]> ranked = new ArrayList<>(); // [chars, index] via parallel lists
        List<String> lines = new ArrayList<>();
        for (Tool t : dailyTools) {
            String schema = OpenAiToolSchemaBuilder.toOpenAiTool(t).toString();
            int chars = schema.length();
            int descLen = t.description() != null ? t.description().length() : 0;
            lines.add(String.format("%5d chars  desc=%4d  %s", chars, descLen, t.id()));
        }
        Collections.sort(lines, Comparator.reverseOrder());
        System.out.println("--- daily tools by schema size (desc) ---");
        for (String line : lines) {
            System.out.println(line);
        }

        // Sanity : general doit être daily, pas all
        assertEquals("general", intent.intent);
        assertTrue("daily should be smaller than all",
                dailyTools.size() < allTools.size());
        assertTrue("daily schema should be under all",
                dailyJson.toString().length() < allJson.toString().length());

        // Étape 2 : UI / F1 / life / project hors schema general
        for (Tool t : dailyTools) {
            assertFalse("ui_* hors daily: " + t.id(),
                    "ui_action".equals(t.id()) || "ui_loop".equals(t.id())
                            || "ui_explain".equals(t.id())
                            || "ui_search".equals(t.id()) || "copilot_action".equals(t.id())
                            || "screen_capture".equals(t.id()));
            assertFalse("f1 hors daily", "f1".equals(t.id()));
            assertFalse("life_pattern hors daily", "life_pattern".equals(t.id()));
            assertFalse("project_object hors daily", "project_object".equals(t.id()));
        }
    }
}
