package com.pegasuscorp.orbe.llm;

import android.content.Context;

import com.pegasuscorp.orbe.memory.UserProfileStore;
import com.pegasuscorp.orbe.tools.ToolOrchestrator;
import com.pegasuscorp.orbe.tools.ToolRegistry;
import com.pegasuscorp.orbe.tools.ToolTag;

import java.util.EnumSet;

import java.util.regex.Pattern;

/**
 * Personnalité permanente de Pégase et nettoyage pour la synthèse vocale.
 */
public final class PegasePrompt {

    // Prompt complet — Gemini gère ça sans problème de performance.
    private static final boolean DEBUG_MINIMAL_PROMPT = false;

    public static final String SYSTEM = buildSystemFallback();

    public static String buildSystem(Context context) {
        return buildSystem(context, EnumSet.allOf(ToolTag.class));
    }

    public static String buildSystem(Context context, EnumSet<ToolTag> allowedTools) {
        return buildSystem(context, allowedTools, false);
    }

    public static String buildSystem(Context context, EnumSet<ToolTag> allowedTools,
            boolean nativeFunctionCalling) {
        if (DEBUG_MINIMAL_PROMPT) {
            return "Tu es Pégase. Réponds en français en 1 phrase.";
        }
        UserProfileStore profile = UserProfileStore.getInstance(context);
        String name = profile.getUserName();
        String toolsSection = nativeFunctionCalling
                ? new ToolRegistry().buildNativeToolsHint()
                : ToolOrchestrator.promptSection(allowedTools);
        return "Tu es " + profile.getAssistantName() + ", l'assistant personnel de " + name + ".\n"
                + "Tu le tutoies toujours.\n"
                + "Personnalité : " + profile.getAssistantPersonality() + "\n"
                + workModeLine(context)
                + lifePatternsLine(context)
                + situationLine(context)
                + learningLine(context)
                + projectObjectsLine(context)
                + PersonalityGuide.promptBlock(context)
                + buildOperationalRules(name, nativeFunctionCalling)
                + toolsSection;
    }

    private static String situationLine(Context context) {
        if (context == null) return "";
        try {
            String block = com.pegasuscorp.orbe.learning.SituationPromptBuilder
                    .promptBlock(context);
            return block == null || block.isEmpty() ? "" : block + "\n";
        } catch (Exception e) {
            return "";
        }
    }

    private static String learningLine(Context context) {
        if (context == null) return "";
        try {
            String block = com.pegasuscorp.orbe.learning.LearningCandidateStore
                    .getInstance(context).promptBlock();
            return block == null || block.isEmpty() ? "" : block + "\n";
        } catch (Exception e) {
            return "";
        }
    }

    private static String projectObjectsLine(Context context) {
        if (context == null) return "";
        try {
            String block = com.pegasuscorp.orbe.objects.ProjectObjectStore
                    .getInstance(context).promptBlock();
            return block == null || block.isEmpty() ? "" : block + "\n";
        } catch (Exception e) {
            return "";
        }
    }

    private static String lifePatternsLine(Context context) {
        if (context == null) return "";
        try {
            String block = com.pegasuscorp.orbe.life.LifePatternStore
                    .getInstance(context).promptBlock();
            return block == null || block.isEmpty() ? "" : block + "\n";
        } catch (Exception e) {
            return "";
        }
    }

    private static String workModeLine(Context context) {
        if (context == null) return "";
        try {
            if (com.pegasuscorp.orbe.intentions.PegaseModeStore.isDrive(context)) {
                return "Mode actuel : CONDUITE — réponses très courtes, pas de blagues, "
                        + "priorise Spotify / navigation / SMS lus à voix haute si demandé.\n";
            }
            if (com.pegasuscorp.orbe.intentions.PegaseModeStore.isWork(context)) {
                return "Mode actuel : TRAVAIL / concentré — réponses plus brèves, "
                        + "moins de blagues, priorise l'utile.\n";
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String buildSystemFallback() {
        return "Tu es Pégase, l'assistant personnel de Yannick.\n"
                + "Tu le tutoies toujours.\n"
                + "Personnalité : " + UserProfileStore.DEFAULT_ASSISTANT_PERSONALITY + "\n"
                + buildOperationalRules("Yannick");
    }

    /**
     * Règles opérationnelles (format oral, outils, STT) — le ton est dans
     * {@link PersonalityGuide} / pegase-personality.md.
     */
    private static String buildOperationalRules(String userName, boolean nativeFunctionCalling) {
        String toolLine = nativeFunctionCalling
                ? "Pour une action sur le téléphone ou une donnée fraîche, appelle la fonction appropriée "
                + "— ne prétends jamais l'avoir fait sans l'appeler.\n"
                : "Pour une action sur le téléphone, utilise l'outil approprié (JSON) "
                + "— ne prétends jamais l'avoir fait sans l'outil.\n";
        return "Sur les commandes, outils et questions factuelles → 1 à 2 phrases, concis.\n"
                + "Exception : après search / wikipedia / news → partage ce que tu as trouvé "
                + "(1–3 phrases orales), ne dis pas juste « Ok » ou « Compris ».\n"
                + "Ne coupe jamais une histoire ou une explication au milieu — "
                + "si tu commences, termine.\n"
                + "Si tu n'es pas sûr, dis-le puis utilise search pour vérifier plutôt que d'inventer.\n"
                + "Si un souvenir est incertain, indique-le au lieu de l'inventer.\n"
                + "Réponds directement, sans raisonnement interne, sans balise <think>.\n"
                + "Pas d'emojis (réponses lues à voix haute). Pas de listes à puces.\n"
                + "Pas de markdown (pas de **, pas de #, pas de blocs ```).\n"
                + "Phrases 100 % françaises orales : espace correctement CHAQUE mot "
                + "(pas de mots collés : « tu vas », pas « tuvas » ; « c'est », pas « cest »).\n"
                + "Pas de tirets longs (—) ni de listes. Garde les traits d'union des nombres "
                + "composés (« soixante-et-unième », pas « soixanteetunième »). "
                + "Pas d'abréviations anglaises ; évite les s finaux inutiles "
                + "(préfère « appli » à « apps »).\n"
                + toolLine
                + "Si le résultat d'un outil indique un succès → "
                + "dis-le clairement sans douter.\n"
                + "ok=true = succès certain. Jamais « je n'ai pas réussi » dans ce cas.\n"
                + "Pour une simple conversation, réponds en français naturel sans JSON.\n"
                + buildVoiceInputRules(userName);
    }

    private static String buildVoiceInputRules(String userName) {
        return "\n=== ENTRÉE VOCALE (Speech-to-text) ===\n"
                + userName + " parle au micro : la transcription peut contenir des erreurs "
                + "(mots collés, homophones, noms mal orthographiés).\n"
                + "Corrige mentalement et déduis l'intention réelle avant de répondre.\n"
                + "Exemples : « metteo demain » = météo · « foot psg » = search (résultat sportif) · "
                + "« cherche prix bitcoin » = outil search · "
                + "« mets du draft punk » = spotify play query « Daft Punk » (corrige les noms d'artistes).\n"
                + "« playlist des meilleures chansons d'Orelsan » = spotify playlist query « Orelsan ».\n"
                + "« combien font 12 fois 4 » / « marge 36 % sur 50 € » = outil calculator "
                + "(ne calcule JAMAIS de tête).\n"
                + "Si la demande est ambiguë, pose UNE question courte de clarification.\n"
                + "Le routeur vocal local peut demander confirmation avant un outil — respecte ce flux.\n"
                + "Si l'intention correspond clairement à un outil, utilise-le sans demander confirmation.\n"
                + "Ne dis jamais « je n'ai pas accès » quand un outil peut faire la tâche.\n"
                + "Pour tout calcul, pourcentage, marge ou opération arithmétique : utilise calculator "
                + "— ne calcule JAMAIS toi-même, reformule seulement le résultat de l'outil.\n"
                + "Pour un prix, horaire, fait récent ou donnée incertaine : utilise search (pas d'invention).\n"
                + "Pour une définition / concept (« c'est quoi », « explique ») : wikipedia "
                + "(pas search). Pour « qui a inventé » : wikidata.\n"
                + "L'outil search récupère plusieurs sources web ; tu synthétises ensuite la réponse.\n"
                + "web_search ouvre seulement le navigateur ; search répond à voix haute après recoupement.\n";
    }

    private static final Pattern EMOJI = Pattern.compile(
            "[\\p{So}\\p{Sk}\\uFE0F\\u200D\\u2600-\\u27BF\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+");

    private PegasePrompt() {}

    /** Retire emojis, balises <think> et espaces superflus avant Piper / TTS. */
    public static String sanitizeForSpeech(String text) {
        if (text == null) return "";
        return PersonalityGuide.stripBannedPhrases(normalizeReplyText(text, true));
    }

    /** Même nettoyage pour l'affichage texte (Discussion, mémoire). */
    public static String sanitizeForDisplay(String text) {
        if (text == null) return "";
        return normalizeReplyText(text, false);
    }

    private static String normalizeReplyText(String text, boolean collapseNewlines) {
        String out = text.replaceAll("(?s)<think>.*?</think>", "");
        out = out.replaceAll("(?s)<think>.*", "");
        out = out.replace("/no_think", "");
        out = out.replaceAll("```[a-z]*", "").replace("```", "");
        out = EMOJI.matcher(out).replaceAll("");
        // Soft hyphens Wikipedia → tiret dur (évite soixanteetunième si le Cf est perdu)
        out = out.replace('\u00AD', '-');
        out = out.replaceAll("-{2,}", "-");
        out = fixFrenchOralSpacing(out);
        if (collapseNewlines) {
            out = out.replaceAll("\\s+", " ").trim();
        } else {
            out = out.replaceAll("[ \\t]+", " ");
            out = out.replaceAll(" *\\n *", "\n").trim();
        }
        return out;
    }

    /** Corrige les mots collés fréquents (LLM) pour une meilleure prononciation. */
    public static String fixFrenchOralSpacing(String text) {
        if (text == null || text.isEmpty()) return "";
        String out = text;
        // Apostrophes / questions courantes
        out = out.replaceAll("(?i)qu'estce", "qu'est-ce");
        out = out.replaceAll("(?i)qu'est ce", "qu'est-ce");
        out = out.replaceAll("(?i)estce", "est-ce");
        out = out.replaceAll("(?i)est ce", "est-ce");
        out = out.replaceAll("(?i)puisje", "puis-je");
        out = out.replaceAll("(?i)puis je", "puis-je");
        // Verbe + tu (Veuxtu, Peuxtu…)
        out = out.replaceAll("(?i)\\b(veux)(tu)\\b", "$1-$2");
        out = out.replaceAll("(?i)\\b(peux)(tu)\\b", "$1-$2");
        out = out.replaceAll("(?i)\\b(dois)(tu)\\b", "$1-$2");
        out = out.replaceAll("(?i)\\b(vas)(tu)\\b", "$1-$2");
        out = out.replaceAll("(?i)\\b(sais)(tu)\\b", "$1-$2");
        out = out.replaceAll("(?i)\\b(fais)(tu)\\b", "$1-$2");
        // Pronoms / adverbes courants collés
        out = out.replaceAll("(?i)\\b(je)(suis)\\b", "$1 $2");
        out = out.replaceAll("(?i)\\b(je)(vais)\\b", "$1 $2");
        out = out.replaceAll("(?i)\\b(il)(y)a\\b", "$1 $2 a");
        out = out.replaceAll("(?i)\\bpeutetre\\b", "peut-être");
        out = out.replaceAll("(?i)\\bpeut être\\b", "peut-être");
        out = out.replaceAll("(?i)\\bcest\\b", "c'est");
        out = out.replaceAll("(?i)\\bnest\\b", "n'est");
        out = out.replaceAll("(?i)\\bjai\\b", "j'ai");
        out = out.replaceAll("(?i)\\btas\\b", "t'as");
        out = out.replaceAll("(?i)\\bdaccord\\b", "d'accord");
        out = out.replaceAll("(?i)\\baujourdhui\\b", "aujourd'hui");
        out = out.replaceAll("(?i)\\bquelquechose\\b", "quelque chose");
        out = out.replaceAll("(?i)\\bparceque\\b", "parce que");
        out = out.replaceAll("(?i)\\bquandmeme\\b", "quand même");
        out = out.replaceAll("(?i)\\bcestadire\\b", "c'est-à-dire");
        out = out.replaceAll("(?i)\\bquestceque\\b", "qu'est-ce que");
        out = out.replaceAll("(?i)\\bquest ce que\\b", "qu'est-ce que");
        out = out.replaceAll("(?i)\\btuen\\b", "tu en");
        out = out.replaceAll("(?i)\\btuy\\b", "tu y");
        out = out.replaceAll("(?i)\\bjene\\b", "je ne");
        out = out.replaceAll("(?i)\\bjeny\\b", "je n'y");
        out = out.replaceAll("(?i)\\bilny\\b", "il n'y");
        out = out.replaceAll("(?i)\\bilya\\b", "il y a");
        out = out.replaceAll("(?i)\\bavectoi\\b", "avec toi");
        out = out.replaceAll("(?i)\\bpourtoi\\b", "pour toi");
        out = out.replaceAll("(?i)\\bchetoi\\b", "chez toi");
        out = out.replaceAll("(?i)\\bsilteplait\\b", "s'il te plaît");
        out = out.replaceAll("(?i)\\bsil vous plait\\b", "s'il vous plaît");
        out = out.replaceAll("(?i)\\bjenesaispas\\b", "je ne sais pas");
        out = out.replaceAll("(?i)\\btuvas\\b", "tu vas");
        out = out.replaceAll("(?i)\\btues\\b", "tu es");
        out = out.replaceAll("(?i)\\btuas\\b", "tu as");
        out = out.replaceAll("(?i)\\bjepeux\\b", "je peux");
        out = out.replaceAll("(?i)\\bjeveux\\b", "je veux");
        out = out.replaceAll("(?i)\\bjesuis\\b", "je suis");
        out = out.replaceAll("(?i)\\bjevais\\b", "je vais");
        out = out.replaceAll("(?i)\\bilest\\b", "il est");
        out = out.replaceAll("(?i)\\bonest\\b", "on est");
        out = out.replaceAll("(?i)\\bcestun\\b", "c'est un");
        out = out.replaceAll("(?i)\\bcestune\\b", "c'est une");
        out = out.replaceAll("(?i)\\bcestla\\b", "c'est la");
        out = out.replaceAll("(?i)\\bcestle\\b", "c'est le");
        out = out.replaceAll("(?i)\\bbeaucoupde\\b", "beaucoup de");
        out = out.replaceAll("(?i)\\bunpeu\\b", "un peu");
        out = out.replaceAll("(?i)\\bplutot\\b", "plutôt");
        out = out.replaceAll("(?i)\\bdeja\\b", "déjà");
        out = out.replaceAll("(?i)\\bca va\\b", "ça va");
        out = out.replaceAll("(?i)\\bcava\\b", "ça va");
        out = out.replaceAll("(?i)\\bavecmoi\\b", "avec moi");
        out = out.replaceAll("(?i)\\bpourmoi\\b", "pour moi");
        out = out.replaceAll("(?i)\\bcommeca\\b", "comme ça");
        out = out.replaceAll("(?i)\\btoutde suite\\b", "tout de suite");
        out = out.replaceAll("(?i)\\btoutdesuite\\b", "tout de suite");
        out = out.replaceAll("(?i)\\bcesttout\\b", "c'est tout");
        out = out.replaceAll("(?i)\\bcestbien\\b", "c'est bien");
        out = out.replaceAll("(?i)\\bcestbon\\b", "c'est bon");
        // Nombres composés FR collés (LLM / Wikipedia sans traits d'union)
        out = fixFrenchCompoundNumbers(out);
        // Ponctuation collée — ne pas casser les décimales FR (6,545)
        out = out.replaceAll("([.!?;:])(?=[A-Za-zÀ-ÖØ-öø-ÿ])", "$1 ");
        out = out.replaceAll("(\\d),\\s+(\\d)", "$1,$2");
        out = out.replaceAll(",(?!\\d)(?=\\S)", ", ");
        out = out.replaceAll("(?<=\\S)\\.(?=\\S)", ". ");
        // Espace avant une majuscule collée (VeuxTu → Veux Tu) en secours
        out = out.replaceAll("([a-zàâäéèêëïîôùûüç])([A-ZÀÂÄÉÈÊËÏÎÔÙÛÜÇ])", "$1 $2");
        return out;
    }

    /** Remet les traits d'union des nombres composés français collés. */
    static String fixFrenchCompoundNumbers(String text) {
        if (text == null || text.isEmpty()) return "";
        String out = text;
        out = out.replaceAll("(?i)\\bquatrevingts\\b", "quatre-vingts");
        out = out.replaceAll("(?i)\\bquatrevingt\\b", "quatre-vingt");
        out = out.replaceAll(
                "(?i)\\b(vingt|trente|quarante|cinquante|soixante)et(un|une|uni[eè]me)\\b",
                "$1-et-$2");
        out = out.replaceAll(
                "(?i)\\b(dix)(sept|huit|neuf|septi[eè]me|huiti[eè]me|neuvi[eè]me)\\b",
                "$1-$2");
        out = out.replaceAll(
                "(?i)\\b(vingt|trente|quarante|cinquante|soixante)"
                        + "(deux|trois|quatre|cinq|six|sept|huit|neuf"
                        + "|deuxi[eè]me|troisi[eè]me|quatri[eè]me|cinqui[eè]me"
                        + "|sixi[eè]me|septi[eè]me|huiti[eè]me|neuvi[eè]me)\\b",
                "$1-$2");
        out = out.replaceAll(
                "(?i)\\b(soixante)(dix|onze|douze|treize|quatorze|quinze|seize"
                        + "|dixi[eè]me|onzi[eè]me|douzi[eè]me|treizi[eè]me"
                        + "|quatorzi[eè]me|quinzi[eè]me|seizi[eè]me)\\b",
                "$1-$2");
        out = out.replaceAll(
                "(?i)\\b(quatre-vingt)(un|une|deux|trois|quatre|cinq|six|sept|huit|neuf"
                        + "|uni[eè]me|deuxi[eè]me|troisi[eè]me)\\b",
                "$1-$2");
        return out;
    }
}
