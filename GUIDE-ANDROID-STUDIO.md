# Faire tourner Orbe sur ton téléphone — guide pas-à-pas

Objectif : voir l'orbe s'afficher en vrai sur ton écran. On ne touche pas
encore à la voix ni au chat — juste la base (orbe + éventail + tiroir).

La méthode la plus fiable : **créer un projet neuf dans Android Studio**
(il génère toute la plomberie Gradle correcte pour ta version), puis y **déposer
nos fichiers**. On évite ainsi les galères de versions Gradle.

---

## 1. Installer Android Studio

Si ce n'est pas déjà fait : télécharge Android Studio (gratuit) et installe-le
avec les composants par défaut (SDK Android inclus). Au premier lancement, laisse-le
finir de télécharger le SDK.

## 2. Créer le projet

`File > New > New Project` puis :

- Modèle : **Empty Views Activity** (⚠️ *Views*, pas *Compose*)
- **Name** : `Orbe`
- **Package name** : `com.pegasuscorp.orbe`
- **Language** : **Java**
- **Minimum SDK** : **API 24** (Android 7.0)

Valide. Laisse Android Studio synchroniser (barre de progression en bas).

> En nommant l'app `Orbe`, Android Studio génère automatiquement un thème
> `Theme.Orbe` — c'est exactement celui que le manifeste attend. Rien à créer.

## 3. Déposer nos fichiers

Dans le panneau de gauche, passe la vue en **Project** (menu déroulant en haut
du panneau) pour voir la vraie arborescence des dossiers.

**a) Les fichiers Java** — va dans
`app/src/main/java/com/pegasuscorp/orbe/`. Tu y trouves le `MainActivity.java`
généré. Remplace-le par le nôtre, et ajoute tous les autres :

```
com/pegasuscorp/orbe/
├── MainActivity.java        (remplace celui généré)
├── OrbView.java
├── AppDrawerActivity.java
├── voice/
│   ├── VoiceManager.java
│   ├── IntentParser.java
│   └── LocalKeywordParser.java
└── chat/
    ├── ChatBackend.java
    ├── ConversationManager.java
    └── GeminiChatBackend.java
```

Pour les sous-dossiers `voice/` et `chat/` : clic droit sur le package
`com.pegasuscorp.orbe` > `New > Package`, nomme-le `voice` (puis `chat`), et
glisse les fichiers dedans. Garde bien la ligne `package ...` en haut de chaque
fichier telle quelle.

**b) Le manifeste** — ouvre `app/src/main/AndroidManifest.xml` et remplace tout
son contenu par le nôtre.

## 4. Vérifier deux détails

- **minSdk** : ouvre `app/build.gradle` (celui du module, pas le racine).
  Vérifie `minSdk 24`. Les dépendances `appcompat` et `core` sont déjà là dans
  un projet neuf — rien à ajouter pour la base.
- Le fichier `activity_main.xml` généré peut rester, on ne s'en sert pas
  (`MainActivity` construit son écran en code).

Puis clique sur **Sync Now** si la barre jaune apparaît en haut.

## 5. Brancher le téléphone

1. Sur ton tel : `Réglages > À propos` → tape 7 fois sur *Numéro de build*
   pour débloquer le mode développeur.
2. `Réglages > Options pour développeurs` → active **Débogage USB**.
3. Branche le tél en USB, accepte la demande d'autorisation qui s'affiche.
4. Le nom de ton téléphone doit apparaître dans la barre du haut d'Android Studio.

## 6. Lancer

Clique sur le triangle vert **Run** (ou `Maj+F10`). Android Studio compile,
installe, et lance l'app. **L'orbe cyan doit apparaître** 🎉

Teste :
- **Tap sur l'orbe** → l'éventail de raccourcis s'ouvre
- **Swipe vers le haut** → le tiroir d'apps s'ouvre (liste de tes vraies apps)
- **Appui long sur l'orbe** → demande la permission micro puis écoute (voix niveau 1)

## 7. En faire ton launcher (optionnel)

Appuie sur le bouton **Accueil** de ton tél : Android te demande quelle app
utiliser comme launcher → choisis **Orbe**.

> **Pour revenir en arrière** : `Réglages > Applications > Applications par
> défaut > App d'accueil` → resélectionne ton launcher habituel. Tu n'es jamais
> coincé.

---

## Si ça coince

- **« You need to use a Theme.AppCompat theme »** au lancement → le manifeste
  ne pointe pas sur `@style/Theme.Orbe`. Vérifie la ligne `android:theme` dans
  `<application>`. (Notre manifeste est déjà correct sur ce point.)
- **Le tiroir est vide** → normal sur l'émulateur (peu d'apps). Teste sur un
  vrai téléphone.
- **Rouge partout dans un fichier** → vérifie que la 1re ligne `package ...`
  correspond bien au dossier où il est rangé.
- **La voix ne marche pas sur l'émulateur** → normal, il faut un vrai micro.

## Une fois que ça tourne

Là on pourra reprendre les couches suivantes, dans l'ordre :
1. la teinte **cyan + le halo + les orbites** dans `OrbView` (le rendu de l'aperçu)
2. le **tracé de lettre** (ML Kit Digital Ink) pour le tiroir
3. le **mode discussion** (clé Gemini) et le reste du Jarvis
