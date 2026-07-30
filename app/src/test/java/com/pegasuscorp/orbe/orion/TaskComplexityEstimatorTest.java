package com.pegasuscorp.orbe.orion;

import com.pegasuscorp.orbe.orion.prompt.ResolvedTask;
import com.pegasuscorp.orbe.orion.search.FileLocation;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class TaskComplexityEstimatorTest {

    private final TaskComplexityEstimator estimator = new TaskComplexityEstimator();

    @Test
    public void particulesWithKeyword_isSimpleLow() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch particules")
                .keyword("particleCount")
                .action("Augmenter particleCount")
                .rawInput("plus de particules")
                .build();
        TaskComplexity complexity = estimator.estimate(task);
        TaskRisk risk = estimator.assessRisk(task, null, complexity, "plus de particules");
        assertEquals(TaskComplexity.SIMPLE, complexity);
        assertEquals(TaskRisk.LOW, risk);
    }

    @Test
    public void trapPhrases_areSimpleNotMassive() {
        assertEquals(TaskComplexity.SIMPLE,
                estimator.estimate(task("sur toute la largeur")));
        assertEquals(TaskComplexity.SIMPLE,
                estimator.estimate(task("corrige tout le texte")));
        assertEquals(TaskComplexity.SIMPLE,
                estimator.estimate(task("change complètement la couleur")));
    }

    @Test
    public void explicitRewriteProject_isMassiveHigh() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("refaire le projet")
                .action("Header")
                .action("Navigation")
                .rawInput("refais tout le projet")
                .build();
        TaskComplexity complexity = estimator.estimate(task);
        TaskRisk risk = estimator.assessRisk(task, null, complexity, task.rawInput);
        assertEquals(TaskComplexity.MASSIVE, complexity);
        assertEquals(TaskRisk.HIGH, risk);
    }

    @Test
    public void enrichedContext_doesNotInflateComplexity() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("patch")
                .context("Fichier ciblé : a.js\nFichier ciblé : b.js\nFichier ciblé : c.js")
                .action("touch a.js")
                .action("touch b.js")
                .action("touch c.js")
                .rawInput("change complètement la couleur")
                .build();
        // Même si on passe un blob enrichi en 2ᵉ arg, la demande gagne.
        assertEquals(TaskComplexity.SIMPLE,
                estimator.estimate(task, task.context + " " + task.rawInput));
    }

    @Test
    public void pegaseSessionFile_isCritical() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("corriger session")
                .keyword("rewriteOrionPrompt")
                .rawInput("corriger rewriteOrionPrompt")
                .build();
        FileLocation loc = new FileLocation("PegaseSession.java", 120, "void rewriteOrionPrompt");
        TaskComplexity complexity = estimator.estimate(task);
        TaskRisk risk = estimator.assessRisk(task, loc, complexity, task.rawInput);
        assertEquals(TaskRisk.CRITICAL, risk);
    }

    @Test
    public void conversationManagerMention_isCriticalEvenWithoutLocation() {
        ResolvedTask task = ResolvedTask.builder()
                .mission("ajouter méthode")
                .objective("Ajouter une méthode dans ConversationManager")
                .action("Nouvelle méthode utilitaire")
                .action("Tests associés")
                .action("Brancher dans le routeur")
                .action("Documenter")
                .rawInput("ajoute une méthode dans ConversationManager")
                .build();
        TaskComplexity complexity = estimator.estimate(task);
        TaskRisk risk = estimator.assessRisk(task, null, complexity, task.rawInput);
        assertEquals(TaskComplexity.SIMPLE, complexity);
        assertEquals(TaskRisk.CRITICAL, risk);
    }

    private static ResolvedTask task(String raw) {
        return ResolvedTask.builder().mission(raw).rawInput(raw).build();
    }
}
