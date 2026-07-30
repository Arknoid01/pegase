package com.pegasuscorp.orbe.memory;

/** Détection d'intentions lexicales (looksLike*) sans appeler le LLM. */
public final class IntentDetector {

    private IntentDetector() {}

    static String detectIntent(String fold, EntityResolver.Resolution entities) {
        if (looksLikeBrief(fold)) {
            return "brief";
        }
        if (looksLikeOrionCode(fold)) {
            return "orion_code";
        }
        if (looksLikeOrion(fold)) {
            return "orion";
        }
        if (looksLikeDiag(fold)) {
            return "diag";
        }
        if (fold.contains("spotify") || fold.contains("musique") || fold.contains("playlist")
                || fold.contains("chanson")) {
            return "music";
        }
        if (fold.contains("meteo") || fold.contains("quel temps") || fold.contains("actualite")
                || fold.contains("news") || fold.contains("match") || fold.contains("score")
                || needsFreshData(fold)) {
            return "fresh_data";
        }
        if (!entities.matches.isEmpty()) {
            String type = entities.matches.get(0).entity.type;
            if (Entity.TYPE_PERSON.equals(type)) return "person";
            if (Entity.TYPE_PROJECT.equals(type)) return "project";
            if (Entity.TYPE_PLACE.equals(type)) return "place";
            if (Entity.TYPE_DEVICE.equals(type)) return "device";
        }
        if (fold.contains("pegase") || fold.contains("orbe") || fold.contains("fableris")
                || fold.contains("projet") || fold.contains("city builder")) {
            return "project";
        }
        if (fold.contains("memoire") || fold.contains("retiens") || fold.contains("souvenir")) {
            return "memory";
        }
        if (fold.contains("rappel") || fold.contains("liste") || fold.contains("notepad")
                || fold.contains("bloc note") || fold.contains("alarme") || fold.contains("reveil")
                || fold.contains("minuteur") || fold.contains("timer")) {
            return "productivity";
        }
        if (looksLikeCreative(fold)) {
            if (fold.contains("histoire") || fold.contains("raconte")) return "story";
            return "creative";
        }
        if (looksLikePhilosophical(fold)) {
            return "philosophical";
        }
        if (looksLikeTechnical(fold)) {
            return "technical";
        }
        if (looksLikeExplanation(fold)) {
            return "explanation";
        }
        return "general";
    }

    /** Histoire, poème, invention — réponses plus longues. */
    public static boolean looksLikeCreative(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (fold.contains("raconte") || fold.contains("histoire") || fold.contains("imagine")
                || fold.contains("invente") || fold.contains("poeme") || fold.contains("conte")) {
            return true;
        }
        if (fold.contains("decris") || fold.contains("décris")) return true;
        // « écris » sans demande de note (sinon notepad)
        return fold.contains("ecris") && !fold.contains("note");
    }

    /** Avis, sens, pourquoi — réponses développées. */
    public static boolean looksLikePhilosophical(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("pourquoi")
                || fold.contains("tu penses quoi")
                || fold.contains("ton avis")
                || fold.contains("fin de l")
                || fold.contains("univers")
                || fold.contains("sens de")
                || fold.contains("selon toi");
    }

    public static boolean looksLikeTechnical(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("comment ca marche") || fold.contains("comment ça marche")
                || fold.contains("architecture") || fold.contains("technique")
                || fold.contains("explique le code") || fold.contains("debug");
    }

    public static boolean looksLikeExplanation(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (looksLikeCreative(fold) || looksLikePhilosophical(fold)) return false;
        return fold.contains("explique") || fold.contains("c est quoi")
                || fold.contains("qu est ce que") || fold.contains("quest ce que");
    }

    public static boolean looksLikeTool(String fold) {
        return fold.contains("ouvre ") || fold.contains("lance ") || fold.contains("mets ")
                || fold.contains("allume ") || fold.contains("itineraire")
                || fold.contains("cherche ") || fold.contains("youtube")
                || fold.contains("mail") || fold.contains("email") || fold.contains("partage")
                || fold.contains("volume") || fold.contains("silence") || fold.contains("muet")
                || fold.contains("mode avion") || fold.contains("hotspot")
                || fold.contains("presse papier") || fold.contains("clipboard")
                || fold.contains("contact")
                || looksLikeFiles(fold)
                || looksLikeCalc(fold)
                || looksLikeDiag(fold)
                || looksLikeBrief(fold)
                || looksLikeOrion(fold)
                || looksLikeOrionCode(fold);
    }

    /**
     * Intention calcul — exige au moins un chiffre ; refuse {@code ?} seul.
     * Le calculator ne doit jamais se déclencher sans chiffre dans la phrase.
     */
    public static boolean looksLikeCalc(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String t = fold.trim();
        if (t.equals("?") || t.matches("\\?+")) return false;
        boolean keyword = t.contains("calcule") || t.contains("calcul")
                || t.contains("combien font") || t.contains("combien fait")
                || t.contains("marge") || t.contains("pourcent") || t.contains("fois ");
        if (!keyword) return false;
        // Expression candidate : toute la phrase après détection mot-clé
        if (!hasDigit(t)) return false;
        return true;
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }

    /** Brief du matin / résumé journée (cache Prefetch). */
    public static boolean looksLikeBrief(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (fold.contains("brief du matin") || fold.contains("brief matin")
                || fold.contains("brief de matin")) {
            return true;
        }
        if (fold.contains("resume ma journee") || fold.contains("resumer ma journee")
                || fold.contains("resume la journee")) {
            return true;
        }
        if (fold.contains("qu est ce que j ai aujourd")
                || fold.contains("quest ce que j ai aujourd")
                || fold.contains("quoi aujourd hui")
                || (fold.contains("aujourd hui") && (fold.contains("quoi")
                || fold.contains("qu ai je") || fold.contains("programme")))) {
            return true;
        }
        return fold.contains("ajoute") && fold.contains("routine")
                && (fold.contains("matin") || fold.contains("ma routine"));
    }

    /** Fichiers téléphone : chercher / lister / déplacer / supprimer / ouvrir. */
    public static boolean looksLikeFiles(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (fold.contains("mes fichiers") || fold.contains("dans mes fichiers")
                || fold.contains("dans telechargements") || fold.contains("dans les telechargements")
                || fold.contains("dans documents") || fold.contains("explorateur")) {
            return true;
        }
        if (fold.contains("supprime") && (fold.contains("fichier") || fold.contains("photo")
                || fold.contains("pdf") || fold.contains(".jpg") || fold.contains(".png")
                || fold.contains(".pdf") || fold.contains("telecharg"))) {
            return true;
        }
        if (fold.contains("deplace") || fold.contains("déplace") || fold.contains("deplacer")
                || fold.contains("déplacer")) {
            return fold.contains("fichier") || fold.contains("photo") || fold.contains("pdf")
                    || fold.contains("dans document") || fold.contains("telecharg");
        }
        if ((fold.contains("ou est") || fold.contains("où est") || fold.contains("trouve")
                || fold.contains("cherche"))
                && (fold.contains("fichier") || fold.contains("pdf") || fold.contains("photo")
                || fold.contains("facture") || fold.contains("telecharg"))) {
            return true;
        }
        return fold.contains("ouvre le fichier") || fold.contains("ouvrir le fichier")
                || fold.contains("liste mes fichiers") || fold.contains("liste des fichiers");
    }

    /** « comment tu vas », bilan de traces, auto-diagnostic. */
    public static boolean looksLikeDiag(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("comment tu vas")
                || fold.contains("comment ca va")
                || fold.contains("comment vas tu")
                || fold.contains("tu vas bien")
                || fold.contains("analyse tes traces")
                || fold.contains("analyse ta trace")
                || fold.contains("bilan de session")
                || fold.contains("bilan de la session")
                || fold.contains("bilan d hier")
                || fold.contains("bilan hier")
                || fold.contains("tu as eu des problemes")
                || fold.contains("tu as eut des problemes")
                || fold.contains("tu as eu un probleme")
                || fold.contains("tu as eut un probleme")
                || fold.contains("as tu eut des problemes")
                || fold.contains("as tu eu des problemes")
                || fold.contains("probleme avec")
                || fold.contains("eu un probleme")
                || fold.contains("tu as quelque chose")
                || fold.contains("comment va tu")
                || fold.contains("comment vas tu")
                || fold.contains("cette semaine")
                || fold.contains("probleme cette semaine")
                || fold.contains("problemes cette semaine")
                || fold.contains("bilan de la semaine")
                || fold.contains("bilan semaine")
                || fold.contains("auto diagnostic")
                || fold.contains("autodiagnostic")
                || fold.contains("outil de diagnostic")
                || fold.contains("outil diag")
                || fold.contains("probleme de diagnostic")
                || fold.contains("problemes de diagnostic")
                || looksLikeDiagToolUsageQuestion(fold)
                || looksLikeDiagSearch(fold)
                || looksLikeDiagAnalyze(fold)
                || looksLikeDiagDetail(fold)
                || (fold.contains("diagnostic") && (fold.contains("toi")
                || fold.contains("pegase") || fold.contains("tes ") || fold.contains("ta ")
                || fold.contains("outil") || fold.contains("appel")))
                || (fold.contains("traces") && (fold.contains("lis")
                || fold.contains("montre") || fold.contains("analyse")
                || fold.contains("regarde")));
    }

    /**
     * Relance d'approfondissement (« dis m'en plus », « développe ») —
     * à combiner avec un diag summary récent pour router vers {@code detail}.
     */
    public static boolean looksLikeDiagDetailFollowUp(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ')
                .replaceAll("\\s+", " ").trim();
        if (f.isEmpty()) return false;
        // Relances courtes / orales
        if (f.equals("et encore") || f.equals("encore") || f.equals("et puis")
                || f.equals("continue") || f.equals("plus") || f.equals("davantage")
                || f.equals("developpe") || f.equals("detaille")
                || f.equals("c est tout") || f.equals("c est tout ?")
                || f.equals("et alors") || f.equals("ensuite")) {
            return true;
        }
        return f.contains("dis m en plus") || f.contains("dis moi en plus")
                || f.contains("dis m en davantage") || f.contains("dis moi davantage")
                || f.contains("m en dire plus") || f.contains("tu peux m en dire plus")
                || f.contains("peux tu m en dire plus") || f.contains("en dire plus")
                || f.contains("et encore") || f.contains("encore un peu")
                || f.contains("developpe") || f.contains("tu peux developper")
                || f.contains("plus de detail") || f.contains("plus de details")
                || f.contains("plus en detail") || f.contains("tu peux detailler")
                || f.contains("detailler") || f.contains("les details")
                || f.contains("plus d info") || f.contains("plus d infos")
                || f.contains("c est tout ?") || f.contains("c est tout")
                || f.contains("raconte plus") || f.contains("dis en plus");
    }

    /**
     * « qu'est-ce qui a merdé », « tu as eu des problèmes », « explique l'erreur »
     * → {@code diag detail} (récit des erreurs), pas le bilan storage summary.
     */
    public static boolean looksLikeDiagDetail(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ')
                .replaceAll("\\s+", " ").trim();
        if (f.contains("qu est ce qui a merde") || f.contains("qui a merde")
                || f.contains("ce qui a merde") || f.contains("ca a merde")
                || f.contains("a merde hier") || f.contains("a merde aujourd")) {
            return true;
        }
        if (f.contains("qu est ce qui a plante") || f.contains("qui a plante")
                || f.contains("ce qui a plante")) {
            return true;
        }
        // « tu as eu des problèmes (hier) ? » — demande le récit, pas le stockage
        if (f.contains("tu as eu des problemes") || f.contains("tu as eut des problemes")
                || f.contains("as tu eu des problemes") || f.contains("as tu eut des problemes")
                || f.contains("tu as eu un probleme") || f.contains("tu as eut un probleme")
                || f.contains("as tu eu un probleme") || f.contains("as tu eut un probleme")
                || f.contains("eu des problemes hier") || f.contains("eu un probleme hier")
                || f.contains("des problemes hier") || f.contains("un probleme hier")) {
            return true;
        }
        if (f.contains("detaille les problemes") || f.contains("details les problemes")
                || f.contains("detaille les erreurs") || f.contains("details les erreurs")
                || f.contains("raconte les erreurs") || f.contains("raconte les problemes")
                || f.contains("liste les erreurs") || f.contains("montre les erreurs")
                || f.contains("quels problemes") || f.contains("quel probleme")
                || f.contains("c est quoi le probleme") || f.contains("c est quoi les problemes")) {
            return true;
        }
        if (f.contains("explique l erreur") || f.contains("explique les erreurs")
                || f.contains("explique le probleme") || f.contains("explique les problemes")
                || f.contains("c est quoi l erreur") || f.contains("quelle erreur")
                || f.contains("quelles erreurs") || f.contains("quoi comme erreur")) {
            return true;
        }
        return f.contains("detaille") && (f.contains("probleme") || f.contains("erreur")
                || f.contains("merde") || f.contains("panne"));
    }

    /**
     * « Tu as fait appel à l'outil de diagnostic ? » — question méta, pas un bilan.
     */
    public static boolean looksLikeDiagToolUsageQuestion(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ')
                .replaceAll("\\s+", " ").trim();
        boolean mentionsDiag = f.contains("diagnostic") || f.contains(" diag")
                || f.contains("outil diag") || f.endsWith("diag");
        if (!mentionsDiag) return false;
        return f.contains("fait appel")
                || f.contains("as tu appel")
                || f.contains("as tu fait")
                || f.contains("tu as appel")
                || f.contains("tu as utilise")
                || f.contains("as tu utilise")
                || f.contains("utilise l outil")
                || f.contains("utilise outil")
                || f.contains("appel a l outil")
                || f.contains("appel a outil")
                || f.contains("lance l outil")
                || f.contains("appele diag")
                || f.contains("appele le diag");
    }

    /** « analyse tes problèmes », « propose des corrections », etc. */
    public static boolean looksLikeDiagAnalyze(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String f = fold.replace('\'', ' ').replace('’', ' ')
                .replaceAll("\\s+", " ").trim();
        if (f.contains("corrections.md") || f.contains("fichier corrections")) return true;
        if (f.contains("analyse tes problemes")) return true;
        if (f.contains("ne va pas") || f.contains("qui ne va")) return true;
        if (f.contains("propose des corrections") || f.contains("propose une correction")) {
            return true;
        }
        if ((f.contains("comment tu") && f.contains("amelior"))
                || f.contains("comment tu t ameliores")
                || f.contains("comment tu tamelio")) {
            return true;
        }
        if (f.contains("analyse") && (f.contains("trace") || f.contains("correction")
                || f.contains("probleme") || f.contains("anomalie"))) {
            return true;
        }
        return f.contains("genere") && f.contains("correction");
    }

    /** true si la demande vise plutôt le bilan hebdo / archives. */
    public static boolean looksLikeWeeklyDiag(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("cette semaine")
                || fold.contains("bilan de la semaine")
                || fold.contains("bilan semaine")
                || fold.contains("sur 7 jours")
                || fold.contains("ces derniers jours")
                || fold.contains("la semaine derniere")
                || (fold.contains("archives") && fold.contains("trace"));
    }

    /**
     * Recherche sémantique dans l'historique de comportement
     * ({@code diag action=search}).
     */
    public static boolean looksLikeDiagSearch(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("deja eu ce probleme")
                || fold.contains("deja eu ce souci")
                || fold.contains("deja eu ce cas")
                || fold.contains("tu as deja")
                || fold.contains("as tu deja")
                || fold.contains("premiere fois")
                || fold.contains("c est la premiere")
                || fold.contains("ca arrive souvent")
                || fold.contains("arrive souvent")
                || fold.contains("c est frequent")
                || fold.contains("ca t arrive souvent")
                || (fold.contains("souvent") && (fold.contains("hesit")
                || fold.contains("echec") || fold.contains("erreur")
                || fold.contains("probleme")));
    }

    /**
     * Gestion pod RunPod Orion (pas le contexte nommé seul).
     */
    public static boolean looksLikeOrion(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (looksLikeComfy(fold)) return true;
        boolean mentionsOrion = fold.contains("orion");
        boolean manage = fold.contains("lance") || fold.contains("demarre")
                || fold.contains("demarrer") || fold.contains("eteins")
                || fold.contains("arrete") || fold.contains("stop")
                || fold.contains("allume") || fold.contains("en ligne")
                || fold.contains("hors ligne") || fold.contains("coute")
                || fold.contains("cout") || fold.contains("status")
                || fold.contains("etat") || fold.contains("pod")
                || fold.contains("gpu") || fold.contains("runpod");
        if (mentionsOrion && manage) return true;
        if (fold.contains("runpod") && (fold.contains("lance") || fold.contains("arrete")
                || fold.contains("status") || fold.contains("pod"))) return true;
        if (mentionsOrion && (fold.contains("combien") || fold.contains("coute"))) return true;
        return fold.contains("lance orion") || fold.contains("demarre orion")
                || fold.contains("eteins orion") || fold.contains("arrete orion");
    }

    /** Lance / gère un pod ComfyUI (séparé d'Orion). */
    public static boolean looksLikeComfy(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        boolean mentions = fold.contains("comfy") || fold.contains("comfyui");
        if (!mentions) return false;
        return fold.contains("lance") || fold.contains("demarre") || fold.contains("demarrer")
                || fold.contains("allume") || fold.contains("start")
                || fold.contains("eteins") || fold.contains("arrete") || fold.contains("stop");
    }

    /** Commit GitHub / déploiement Hostinger. */
    public static boolean looksLikeGitCommit(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("commit") || fold.contains("github")
                || fold.contains("hostinger") || fold.contains("hostiger")
                || fold.contains("pousse sur git") || fold.contains("push git")
                || fold.contains("pousse le fichier") || fold.contains("pousser le fichier")
                || fold.contains("envoie sur github") || fold.contains("envoyer sur github")
                || fold.contains("mets sur hostinger") || fold.contains("met sur hostinger")
                || fold.contains("upload") || fold.contains("pousse sur github")
                || fold.contains("committe tout") || fold.contains("commite tout")
                || fold.contains("deploie") || fold.contains("déploie")
                || (fold.contains("pousse") && (fold.contains("repo") || fold.contains("depot")
                || fold.contains("dépôt") || fold.contains("fichier")
                || fold.contains("github") || fold.contains("hostinger")));
    }

    /** Événement calendrier (≠ alarme). */
    public static boolean looksLikeAgenda(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("agenda")
                || fold.contains("calendrier")
                || fold.contains("rendez-vous")
                || fold.contains("rendez vous")
                || fold.contains("rdv")
                || fold.contains("reunion")
                || fold.contains("réunion")
                || fold.contains("evenement")
                || fold.contains("événement")
                || (fold.contains("mets dans") && fold.contains("agenda"))
                || (fold.contains("ajoute") && (fold.contains("calendrier")
                || fold.contains("agenda")));
    }

    /** Lecture agenda : « qu'est-ce que j'ai demain », « mon planning », etc. */
    public static boolean looksLikeAgendaQuery(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (looksLikeAgendaCreate(fold)) return false;
        boolean query = fold.contains("quoi")
                || fold.contains("qu est ce")
                || fold.contains("qu'est ce")
                || fold.contains("quelles")
                || fold.contains("quels")
                || fold.contains("liste")
                || fold.contains("montre")
                || fold.contains("affiche")
                || fold.contains("j ai")
                || fold.contains("ai je")
                || fold.contains("mon planning")
                || fold.contains("ma journee")
                || fold.contains("ma journée")
                || fold.contains("programme");
        boolean cal = fold.contains("agenda")
                || fold.contains("calendrier")
                || fold.contains("rendez")
                || fold.contains("rdv")
                || fold.contains("planning")
                || fold.contains("journee")
                || fold.contains("journée");
        return query && cal;
    }

    private static boolean looksLikeAgendaCreate(String fold) {
        return fold.contains("ajoute") || fold.contains("ajout ")
                || fold.contains("mets ") || fold.contains("met ")
                || fold.contains("cree") || fold.contains("crée")
                || fold.contains("programme un") || fold.contains("nouveau");
    }

    /** Session fichiers Orion : valider / lister / vider. */
    public static boolean looksLikeOrionFiles(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("valide tout") || fold.contains("valider tout")
                || fold.contains("montre les fichiers orion")
                || fold.contains("fichiers orion")
                || fold.contains("vide la session") || fold.contains("vider la session")
                || fold.contains("session orion")
                || fold.contains("committe tout") || fold.contains("commite tout")
                || fold.contains("committe les fichiers")
                || fold.contains("genere un message de commit")
                || fold.contains("génère un message de commit");
    }

    /** Projets Orion locaux (workspace). */
    public static boolean looksLikeOrionProject(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("nouveau projet") || fold.contains("cree un projet")
                || fold.contains("crée un projet")
                || fold.contains("passe sur le projet") || fold.contains("passe sur projet")
                || fold.contains("projet orion") || fold.contains("fichiers du projet")
                || fold.contains("montre les fichiers du projet")
                || fold.contains("push le projet") || fold.contains("push projet")
                || fold.contains("pousse le projet") || fold.contains("push sur github")
                || (fold.contains("projet") && (fold.contains("orion")
                || fold.contains("github") || fold.contains("push")
                || fold.contains("fichier") || fold.contains("switch")
                || fold.contains("nouveau")));
    }

    /**
     * Demande de génération / explication de code via Orion.
     * Prioritaire sur {@link #looksLikeOrion} pour le routing intent.
     */
    public static boolean looksLikeOrionCode(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (fold.contains("demande a orion") || fold.contains("demande à orion")
                || fold.contains("demande orion") || fold.contains("via orion")
                || fold.contains("par orion") || fold.contains("avec orion")) {
            return true;
        }
        if (fold.contains("code moi") || fold.contains("code-moi")) return true;
        if (fold.contains("explique ce code") || fold.contains("explique le code")) return true;
        if (fold.contains("ecris une fonction") || fold.contains("écris une fonction")
                || fold.contains("ecrire une fonction")) return true;
        // « génère » / « génère du code » / « génère une classe »
        if ((fold.contains("genere") || fold.contains("génère") || fold.contains("generer"))
                && (fold.contains("code") || fold.contains("fonction")
                || fold.contains("classe") || fold.contains("methode")
                || fold.contains("orion") || fold.contains("script"))) {
            return true;
        }
        if (fold.contains("orion") && (fold.contains("code") || fold.contains("genere")
                || fold.contains("ecris") || fold.contains("explique"))) {
            return true;
        }
        return false;
    }

    static boolean looksLikeFreshData(String fold) {
        return needsFreshData(fold);
    }

    /**
     * Signaux d'actualité → Tavily. Sans ces mots → Wikipedia/Wikidata pour les faits.
     */
    public static boolean needsFreshData(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        String year = String.valueOf(java.time.Year.now().getValue());
        return fold.contains("aujourd")
                || fold.contains("ce soir")
                || fold.contains("cette semaine")
                || fold.contains("en ce moment")
                || fold.contains("actuellement")
                || fold.contains("recemment")
                || fold.contains("derniere")
                || fold.contains("dernier")
                || fold.contains(year)
                || fold.contains("resultat")
                || fold.contains("score")
                || fold.contains("meteo")
                || fold.contains("quel temps")
                || fold.contains("prix")
                || fold.contains("combien coute")
                || fold.contains("combien ca coute")
                || fold.contains("prix actuel")
                || fold.contains("actualite")
                || fold.contains("news")
                || fold.contains("match");
    }

    /** Question encyclopédique / définition → Wikipedia. */
    public static boolean looksLikeEncyclopedic(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        if (needsFreshData(fold)) return false;
        return fold.contains("c est quoi")
                || fold.contains("qu est ce")
                || fold.contains("quest ce")
                || fold.contains("explique")
                || fold.contains("definition")
                || fold.contains("defini ")
                || fold.contains("hypothese")
                || fold.contains("comment ca marche")
                || fold.contains("comment fonctionne")
                || looksLikeWikidata(fold)
                || fold.contains("qui est ");
    }

    /** Entité / inventeur → Wikidata (souvent avec Wikipedia). */
    public static boolean looksLikeWikidata(String fold) {
        if (fold == null || fold.isEmpty()) return false;
        return fold.contains("qui a invente")
                || fold.contains("qui a cree")
                || fold.contains("qui a decouvert")
                || fold.contains("qui a fonde")
                || fold.contains("inventeur")
                || fold.contains("createur de");
    }
}
