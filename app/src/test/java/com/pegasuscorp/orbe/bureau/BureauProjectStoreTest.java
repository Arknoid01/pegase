package com.pegasuscorp.orbe.bureau;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import android.content.Context;

import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BureauProjectStoreTest {

    @Test
    public void saveLoad_roundTripAndMarkdown() {
        Context ctx = RuntimeEnvironment.getApplication();
        BureauProject p = sampleSport();
        assertTrue(BureauProjectStore.save(ctx, p));
        assertTrue(BureauProjectStore.exists(ctx, "sport"));

        BureauProject loaded = BureauProjectStore.load(ctx, "sport");
        assertNotNull(loaded);
        assertEquals("Sport", loaded.title);
        assertEquals(4, loaded.decisions.size());
        assertEquals(5, loaded.tasks.size());

        String md = BureauProjectStore.loadMarkdown(ctx, "sport");
        assertNotNull(md);
        assertTrue(md.contains("# Sport"));
        assertTrue(md.contains("## Vision"));
        assertTrue(md.contains("Jetpack Compose"));
        assertTrue(md.contains("- [ ] Concevoir la maquette"));
    }

    @Test
    public void slugFromFilename() {
        assertEquals("sport", BureauProjectStore.slugFromFilename("projects/sport.md"));
        assertTrue(BureauProjectStore.isStructuredProjectFile("projects/sport.md"));
        assertFalse(BureauProjectStore.isStructuredProjectFile("session-2026-07-20.md"));
    }

    static BureauProject sampleSport() {
        long now = 1_752_960_000_000L; // ~2025/2026 fixed
        BureauProject p = new BureauProject();
        p.id = "proj1";
        p.slug = "sport";
        p.title = "Sport";
        p.vision = "Créer une application de suivi de séances sportives avec des programmes personnalisés et des timers.";
        p.objectives.addAll(Arrays.asList(
                "Permettre de créer des programmes d'entraînement.",
                "Suivre les séances quotidiennes.",
                "Proposer une interface claire et agréable."));
        BureauProject.Decision d1 = new BureauProject.Decision();
        d1.id = "d1";
        d1.text = "Utiliser Jetpack Compose.";
        d1.confidence = BureauProject.Confidence.CONFIRMED;
        d1.createdAt = now;
        d1.updatedAt = now;
        BureauProject.Decision d2 = new BureauProject.Decision();
        d2.id = "d2";
        d2.text = "Stocker les données localement avec Room.";
        d2.confidence = BureauProject.Confidence.CONFIRMED;
        d2.createdAt = now;
        d2.updatedAt = now;
        BureauProject.Decision h = new BureauProject.Decision();
        h.id = "h1";
        h.text = "Ajouter des notifications de rappel pourrait améliorer la régularité.";
        h.confidence = BureauProject.Confidence.HYPOTHESIS;
        h.createdAt = now;
        h.updatedAt = now;
        BureauProject.Decision v = new BureauProject.Decision();
        v.id = "v1";
        v.text = "Vérifier la meilleure gestion d'un timer pendant la mise en arrière-plan.";
        v.confidence = BureauProject.Confidence.TO_VERIFY;
        v.createdAt = now;
        v.updatedAt = now;
        p.decisions.add(d1);
        p.decisions.add(d2);
        p.decisions.add(h);
        p.decisions.add(v);
        for (String t : Arrays.asList(
                "Concevoir la maquette.",
                "Implémenter le modèle Room.",
                "Développer le timer.",
                "Ajouter la création et la modification des séances.",
                "Écrire les tests.")) {
            BureauProject.Task task = new BureauProject.Task();
            task.id = BureauProject.newId();
            task.text = t;
            task.createdAt = now;
            task.updatedAt = now;
            p.tasks.add(task);
        }
        BureauProject.OpenQuestion q1 = new BureauProject.OpenQuestion();
        q1.id = "q1";
        q1.text = "L'application doit-elle fonctionner uniquement sur Android ?";
        q1.createdAt = now;
        BureauProject.OpenQuestion q2 = new BureauProject.OpenQuestion();
        q2.id = "q2";
        q2.text = "Faut-il prévoir une synchronisation cloud plus tard ?";
        q2.createdAt = now;
        p.openQuestions.add(q1);
        p.openQuestions.add(q2);
        BureauProject.HistoryEntry he = new BureauProject.HistoryEntry();
        he.id = "he1";
        he.text = "Projet créé après entretien avec Pégase.";
        he.createdAt = now;
        p.history.add(he);
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }
}
