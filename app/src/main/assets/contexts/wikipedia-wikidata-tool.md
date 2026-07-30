# Wikipedia + Wikidata — outils Pégase

## Vision

Réserver **Tavily** à l’actualité / données fraîches. Pour les faits établis
(définitions, inventeurs, concepts, histoire), utiliser **Wikipedia** et
**Wikidata** — gratuit, structuré, sans clé API, ~75 % d’économie sur le quota Tavily.

Évite aussi la pollution (posts Facebook, scores inventés) sur des questions
encyclopédiques.

## Statut

Priorité : 🟢 Haute  
État : ✅ Implémenté (`WikipediaTool`, `WikidataTool`, routage `needsFreshData`)  
Cible : économiser le quota Tavily sur les faits encyclopédiques

## Décision de routage

| Question | Source |
|----------|--------|
| Sans signal d’actualité | Wikipedia / Wikidata |
| Avec « aujourd’hui », « ce soir », « récemment », année courante, score… | Tavily |

### Exemples

| Phrase | Route |
|--------|--------|
| « C'est quoi le coefficient de restitution ? » | Wikipedia ✅ |
| « Quelles sont les hypothèses sur la fin de l'univers ? » | Wikipedia ✅ |
| « Qui a inventé le HTML ? » | Wikidata ✅ |
| « Quel est le résultat du match de ce soir ? » | Tavily ✅ |
| « Actualité F1 aujourd'hui ? » | Tavily ✅ |
| « C'est quoi la F1 ? » | Wikipedia ✅ |

### `needsFreshData(fold)` — déclencheurs Tavily

```java
private static boolean needsFreshData(String fold) {
    if (fold == null || fold.isEmpty()) return false;
    String year = String.valueOf(java.time.Year.now().getValue()); // pas hardcoder 2026
    return fold.contains("aujourd")
            || fold.contains("ce soir")
            || fold.contains("cette semaine")
            || fold.contains("en ce moment")
            || fold.contains("actuellement")
            || fold.contains("recemment")   // fold sans accents
            || fold.contains("derniere")
            || fold.contains(year)
            || fold.contains("resultat")
            || fold.contains("score")
            || fold.contains("meteo")       // déjà cache Open-Meteo en pratique
            || fold.contains("prix actuel")
            || fold.contains("actualite")
            || fold.contains("news");
}
```

Tout le reste → Wikipedia puis Wikidata (repli).

### Gains

| | Tavily | Wikipedia / Wikidata |
|--|--------|----------------------|
| Coût | Quota limité (clé) | Illimité, gratuit |
| Tokens injectés | ~2000 chars | ~500 chars |
| Fiabilité | Variable | Très élevée |
| Actualité | ✅ | ❌ |
| Faits précis | ⚠️ | ✅ |

## API — aucune clé

Même principe que NASA APOD : **GET HTTP + JSON**. Wikimedia demande seulement
un `User-Agent` poli (pas d’auth).

```java
conn.setRequestProperty("User-Agent",
        "Pegase/1.0 (Orbe Android Assistant; contact@pegasuscorp.fr)");
```

### Wikipedia — résumé d’article (REST)

```
GET https://fr.wikipedia.org/api/rest_v1/page/summary/{titre_encodé}
```

- Titre simple : `…/page/summary/Balle`
- Parenthèses / accents → **URL-encoder** (`Balle_%28physique%29`, `Fin_de_l%27univers`)
- En Java :

```java
String encoded = URLEncoder.encode(title, "UTF-8").replace("+", "%20");
// ou pour les titres wiki classiques : espaces → _
String path = URLEncoder.encode(title.replace(' ', '_'), "UTF-8");
String url = "https://fr.wikipedia.org/api/rest_v1/page/summary/" + path;
```

Champs utiles du JSON : `title`, `extract`, `description`, `thumbnail`,
`content_urls.desktop.page`.

### Wikipedia — recherche si le titre exact est inconnu

```
GET https://fr.wikipedia.org/w/rest.php/v1/search/page?q={query}&limit=1
```

→ récupérer le meilleur `title` / `key` → puis `/page/summary/{key}`.

### Wikidata — entités

```
GET https://www.wikidata.org/w/api.php
  ?action=wbsearchentities
  &search={query}
  &language=fr
  &format=json
```

Puis (détail optionnel) :

```
GET https://www.wikidata.org/w/api.php
  ?action=wbgetentities
  &ids={Qid}
  &languages=fr
  &props=labels|descriptions|claims
  &format=json
```

Utile pour « qui a inventé… », dates, identifiants stables (Q-ids).

## Outils envisagés

### `wikipedia`

```
wikipedia(query:str, lang?:str="fr")
```

1. Search page (`/w/rest.php/v1/search/page`)
2. Summary (`/api/rest_v1/page/summary/{key}`)
3. Retourne `title` + `extract` (tronqué ~500–800 chars) pour synthèse orale

### `wikidata`

```
wikidata(query:str, lang?:str="fr")
```

1. `wbsearchentities`
2. Optionnel : `wbgetentities` sur le top hit
3. Retourne label + description (+ 1–2 claims simples si utile)

### Routage côté app (pas seulement le LLM)

- `ContextAnalyzer` : si `!needsFreshData` et question factuelle
  (`c'est quoi`, `qui a`, `explique`, `définition`…) → exposer
  `WIKIPEDIA` / `WIKIDATA` plutôt que (ou avant) `TAVILY`
- `VoiceIntentRouter` : même heuristique pour court-circuit local
- Description outils : *NE PAS utiliser search/Tavily pour une définition
  encyclopédique — préférer wikipedia*

## Fichiers à créer (implémentation)

- `tools/WikipediaTool.java`
- `tools/WikidataTool.java`
- `tools/WikiHttp.java` (GET + User-Agent + timeouts, partagé)
- `ToolTag.WIKIPEDIA` / `WIKIDATA` (ou un seul `WIKI`)
- Branchement `ContextAnalyzer` + `ToolRegistry` + tests Robolectric
  (mock HTTP ou réponses fixture)

## Plan d’action

- [x] Spec validée (ce fichier)
- [x] `WikiHttp` + User-Agent
- [x] `WikipediaTool` (search → summary)
- [x] `WikidataTool` (wbsearchentities)
- [x] `needsFreshData` + routage ContextAnalyzer / VoiceIntentRouter / descriptions
- [x] Tests : faits → wiki ; « aujourd’hui » / « ce soir » → Tavily
- [x] Doc réglages : Wikipedia/Wikidata = 0 clé (indicateur « ✓ Wiki »)

## Notes

- Encoder **toujours** le path (404 fréquents avec `()`, accents, apostrophes).
- Respecter les timeouts courts (8–10 s), comme NASA / Tavily.
- Ne pas remplacer la météo Open-Meteo ni le brief cache.
- Tavily garde `exclude_domains` (Facebook, X, TikTok…) pour l’actualité.
