package com.pegasuscorp.orbe.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class SkillStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File writeSkill(File root, String id, String content) throws IOException {
        File dir = new File(root, id);
        assertTrue(dir.mkdirs());
        Files.write(new File(dir, "SKILL.md").toPath(), content.getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static byte[] zip(String... nameContent) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (int i = 0; i < nameContent.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameContent[i]));
                z.write(nameContent[i + 1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    public void list_parseFrontmatter_etIgnoreLesSkillsInvalides() throws Exception {
        File root = tmp.newFolder("skills");
        writeSkill(root, "rapport", "---\nname: Rapport hebdo\ndescription: \"Prépare le rapport du lundi\"\n---\n# Étapes\n1. Lire l'agenda\n");
        writeSkill(root, "sans-desc", "---\nname: X\n---\ncorps");
        writeSkill(root, "sans-frontmatter", "juste du texte");
        SkillStore store = new SkillStore(Collections.singletonList(root));

        List<SkillStore.Skill> skills = store.list();
        assertEquals(1, skills.size());
        assertEquals("rapport", skills.get(0).id);
        assertEquals("Rapport hebdo", skills.get(0).name);
        assertEquals("Prépare le rapport du lundi", skills.get(0).description);
        assertEquals("# Étapes\n1. Lire l'agenda", store.readBody(skills.get(0)));
    }

    @Test
    public void promptIndex_videSansSkill_etListeSinon() throws Exception {
        File root = tmp.newFolder("skills");
        SkillStore store = new SkillStore(Collections.singletonList(root));
        assertEquals("", store.promptIndex());
        writeSkill(root, "rapport", "---\nname: R\ndescription: Rapport du lundi\n---\ncorps");
        String index = store.promptIndex();
        assertTrue(index.contains("=== SKILLS ==="));
        assertTrue(index.contains("rapport — Rapport du lundi"));
    }

    @Test
    public void interne_masqueExterne_memeId() throws Exception {
        File a = tmp.newFolder("a");
        File b = tmp.newFolder("b");
        writeSkill(a, "x", "---\nname: X\ndescription: interne\n---\n");
        writeSkill(b, "x", "---\nname: X\ndescription: externe\n---\n");
        SkillStore store = new SkillStore(java.util.Arrays.asList(a, b));
        assertEquals(1, store.list().size());
        assertEquals("interne", store.find("X").description);
    }

    @Test
    public void readResource_confinéAuDossierDuSkill() throws Exception {
        File root = tmp.newFolder("skills");
        File dir = writeSkill(root, "s", "---\nname: S\ndescription: d\n---\nc");
        Files.write(new File(root, "secret.txt").toPath(), "secret".getBytes());
        new File(dir, "references").mkdirs();
        Files.write(new File(dir, "references/a.md").toPath(), "ref".getBytes());
        SkillStore store = new SkillStore(Collections.singletonList(root));
        SkillStore.Skill skill = store.find("s");

        assertEquals("ref", store.readResource(skill, "references/a.md"));
        assertEquals(Collections.singletonList("references/a.md"), store.listResources(skill));
        try {
            store.readResource(skill, "../secret.txt");
            fail("traversée de chemin acceptée");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void importZip_installeEtRefuseZipSlip() throws Exception {
        File root = tmp.newFolder("skills");
        SkillStore store = new SkillStore(Collections.singletonList(root));

        List<String> ids = store.importZip(new ByteArrayInputStream(zip(
                "meteo/SKILL.md", "---\nname: M\ndescription: Météo détaillée\n---\ncorps",
                "meteo/references/a.md", "ref")), "x");
        assertEquals(Collections.singletonList("meteo"), ids);
        assertNotNull(store.find("meteo"));

        // SKILL.md à la racine → id de repli
        List<String> ids2 = store.importZip(new ByteArrayInputStream(zip(
                "SKILL.md", "---\nname: Z\ndescription: racine\n---\n")), "mon-skill");
        assertEquals(Collections.singletonList("mon-skill"), ids2);

        // entrée dangereuse ignorée : rien n'est écrit hors de la racine
        List<String> ids3 = store.importZip(new ByteArrayInputStream(zip(
                "../evil/SKILL.md", "---\nname: E\ndescription: mal\n---\n")), "x");
        assertTrue(ids3.isEmpty());
        assertFalse(new File(root.getParentFile(), "evil").exists());
        assertNull(store.find("evil"));
    }
}
