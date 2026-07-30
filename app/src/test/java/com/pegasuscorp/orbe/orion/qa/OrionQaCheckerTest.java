package com.pegasuscorp.orbe.orion.qa;

import com.pegasuscorp.orbe.orion.prompt.OrionMode;
import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.search.FileLocation;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class OrionQaCheckerTest {

    @Test
    public void structural_flagsStyleChangeOnParticleMission() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch particules")
                .objective("augmenter particleCount")
                .action("passer particleCount de 50 à 150")
                .exclusion("aucun refactor")
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("main.js", "const particleCount = 50;\nconst background = '#000';\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("main.js", "const particleCount = 150;\nconst background = '#111';\n");

        OrionQaReport report = OrionQaChecker.checkStructural(task, before, after,
                OrionQaChecker.buildDiffSummary(before, after));
        assertFalse(report.isCompliant());
        assertTrue(report.reason.toLowerCase().contains("css")
                || report.reason.toLowerCase().contains("style"));
        assertFalse(report.extraExclusions.isEmpty());
    }

    @Test
    public void structural_okWhenOnlyCountChanges() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch particules")
                .objective("augmenter particleCount")
                .action("particleCount")
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("main.js", "const particleCount = 50;\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("main.js", "const particleCount = 150;\n");

        OrionQaReport report = OrionQaChecker.checkStructural(task, before, after,
                OrionQaChecker.buildDiffSummary(before, after));
        assertTrue(report.isCompliant());
    }

    @Test
    public void structural_greenfieldAllowsMultipleNewFiles() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("MODE GREENFIELD (création depuis Bureau)")
                .objective("première tâche : scaffold")
                .action("un slice minimal")
                .rawInput("MODE GREENFIELD — première tâche utile")
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        Map<String, String> after = new LinkedHashMap<>();
        after.put("MainActivity.java", "public class MainActivity {}");
        after.put("activity_main.xml", "<FrameLayout/>");
        after.put("AndroidManifest.xml", "<manifest/>");

        OrionQaReport report = OrionQaChecker.checkStructural(task, before, after,
                OrionQaChecker.buildDiffSummary(before, after));
        assertTrue(report.isCompliant());
    }

    @Test
    public void structural_featureAllowsJsHtmlCssMultiFile() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("ajouter un panneau particules")
                .objective("feature UI particules")
                .action("compteur + markup + style")
                .rawInput("feature panneau particules")
                .mode(OrionMode.FEATURE)
                .fileLocation(new FileLocation("app.js", 10, "let particleCount = 50;"))
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("app.js", "let particleCount = 50;\n");
        before.put("index.html", "<div id=\"app\"></div>\n");
        before.put("styles.css", "body { color: #fff; }\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("app.js", "let particleCount = 150;\nfunction renderPanel() {}\n");
        after.put("index.html", "<div id=\"app\"><div class=\"panel\"></div></div>\n");
        after.put("styles.css", "body { color: #fff; }\n.panel { background: #111; }\n");

        OrionQaReport report = OrionQaChecker.checkStructural(task, before, after,
                OrionQaChecker.buildDiffSummary(before, after));
        assertTrue(report.isCompliant());
    }

    @Test
    public void structural_patchRejectsSameMultiFileAsFeature() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("ajouter un panneau particules")
                .objective("patch UI particules")
                .action("compteur + markup + style")
                .rawInput("patch panneau particules")
                .mode(OrionMode.PATCH)
                .fileLocation(new FileLocation("app.js", 10, "let particleCount = 50;"))
                .build();
        Map<String, String> before = new LinkedHashMap<>();
        before.put("app.js", "let particleCount = 50;\n");
        before.put("index.html", "<div id=\"app\"></div>\n");
        before.put("styles.css", "body { color: #fff; }\n");
        Map<String, String> after = new LinkedHashMap<>();
        after.put("app.js", "let particleCount = 150;\nfunction renderPanel() {}\n");
        after.put("index.html", "<div id=\"app\"><div class=\"panel\"></div></div>\n");
        after.put("styles.css", "body { color: #fff; }\n.panel { background: #111; }\n");

        OrionQaReport report = OrionQaChecker.checkStructural(task, before, after,
                OrionQaChecker.buildDiffSummary(before, after));
        assertFalse(report.isCompliant());
        assertTrue(report.reason.toLowerCase().contains("hors scope")
                || report.reason.contains("fichiers")
                || report.reason.toLowerCase().contains("css")
                || report.reason.toLowerCase().contains("style"));
    }

    @Test
    public void semantic_parseNonCompliant() {
        OrionQaReport r = OrionQaChecker.parseSemantic(
                "NON_CONFORME : fond modifié\nNe pas toucher : background",
                "diff");
        assertFalse(r.isCompliant());
        assertTrue(r.extraExclusions.stream().anyMatch(s -> s.contains("background")
                || s.toLowerCase().contains("toucher")));
    }

    @Test
    public void augmentMission_addsConstraints() {
        OrionQaReport r = OrionQaReport.nonCompliant("fond",
                Collections.singletonList("Ne pas toucher : background"),
                "diff", true, false);
        String out = OrionQaChecker.augmentMission("Mission : patch\n", r);
        assertTrue(out.contains("Correction QA"));
        assertTrue(out.contains("background"));
    }
}
