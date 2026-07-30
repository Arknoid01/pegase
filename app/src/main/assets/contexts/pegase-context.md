# Pégase — Contexte Pégase

## Vision
Assistant personnel vocal Android qui agit vraiment et reste local-first.

## Statut
Priorité : 🔴 Haute
État : 🔄 En cours — stabilisation + évolutions

## Stack technique
- Android Java — com.pegasuscorp.orbe
- PegaseSession + FC natif + boucle agentique multi-hop
- Groq gpt-oss-20b + fallback
- Tavily + RAG local (MiniLM) + contextes nommés

## Décisions prises
- FC natif uniquement sur gpt-oss-20b + canal TEXT/VOICE
- Mutex via PegaseSession
- exclude_domains Tavily : Facebook, Twitter, TikTok
- clé Tavily dans ApiKeyStore, timeout 12s
- Mémoire long terme (RAG) ≠ contextes nommés (chargés à la demande)

## Plan d'action
- [x] PegaseSession voix + texte
- [x] RAG local Phase 1–3
- [x] Contextes nommés Phase 1
- [x] Bureau Markdown
- [x] Orion (boucle Bureau → code → commit)
- [x] WikipediaTool + WikidataTool (routage vs Tavily) — voir wikipedia-wikidata-tool.md
- [x] Compagnon F1 (fiche, intentions, RSS, live, mémoire fan)
