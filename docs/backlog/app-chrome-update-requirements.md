# Kravspecifikation — App Chrome Update v1

Status: DONE

## Formål

Denne opgave skal **ikke** bygge den nye Home-side eller Learning Path-flowet.

Opgaven skal refaktorere appens fælles chrome til en **state-drevet shell**, som kan bruges uændret, mens appen gradvist vokser fra én til op til fem hoveddestinationer.

Chrome-fundamentet skal understøtte den første onboarding-oplevelse, hvor brugeren starter med kun Home, senere introduceres til profilområdet og derefter får Learning tilføjet som ny destination.

Målet er at gøre følgende muligt uden nye specialvarianter af header eller bundnavigation:

```text
Første launch:
[ Home ]

Efter progression:
[ Home ] [ Learning ]

Senere:
[ Home ] [ Learning ] [ ... ]
```

Home-indholdet, Sensei, dojo-baggrunden og Learning-flowet implementeres i en separat opgave.

---

# 1. Overordnet arkitektur

Chrome skal bestå af tre uafhængige områder:

```text
┌───────────────────────────────────────────┐
│             Android status area           │
├───────────────────────────────────────────┤
│ [Leading]       Title / subtitle [Profile]│
├───────────────────────────────────────────┤
│                                           │
│              Page content                 │
│                                           │
├───────────────────────────────────────────┤
│      Progressive bottom navigation        │
│                                           │
│        Android gesture/navigation area    │
└───────────────────────────────────────────┘
```

Chrome-komponenterne må ikke kende onboarding-, lærings- eller coachinglogik.

De modtager state og renderer det.

---

# 2. Tekniske grundprincipper

## 2.1 Bevar Android Views

Den eksisterende app er en Kotlin/Android Views-app.

Denne opgave må ikke samtidigt introducere Compose.

Chrome-refaktoreringen skal bygge videre på eksisterende:

- `MainPageHeader`
- `SubPageHeader`
- `ProfileAvatarButton`
- `AvatarView`
- `AppBottomNavigationView`
- eksisterende WindowInsets-håndtering
- eksisterende back-stack/navigation

## 2.2 State-driven rendering

Header og bundnavigation skal modtage state.

De må ikke selv afgøre:

- hvilken side brugeren er på,
- hvilke destinationer brugeren har unlocked,
- hvilken profil der er aktiv,
- om et onboarding-trin er gennemført,
- om en destination skal fremhæves.

---

# 3. Appnavn

Appens viste navn skal komme fra én fælles string/resource.

Arbejdstitel:

```text
Karate Analyzer
```

Eksempel:

```xml
<string name="app_display_name">Karate Analyzer</string>
```

Det endelige produktnavn er ikke en del af denne opgave.

Ingen screen må hardkode produktnavnet.

---

# 4. Header-kontrakt

Eksisterende Main/Sub-page headers samles omkring én fælles header-model.

Et muligt koncept:

```kotlin
data class AppHeaderState(
    val title: String,
    val subtitle: String?,
    val leadingAction: HeaderLeadingAction,
    val trailingAction: HeaderTrailingAction,
    val attentionTarget: HeaderAttentionTarget? = null,
)
```

De præcise klassenavne er ikke bindende.

## 4.1 Leading slot

Skal kunne være:

```text
None
Back
Page-specific action
```

Back skal bruge samme reelle back-stack som Android system Back.

Back må ikke implementeres som en særregel, der altid navigerer til Home.

## 4.2 Center/title area

Titelområdet skal have stabil geometri uafhængigt af leading/trailing controls.

Det skal ikke hoppe vandret, når:

- Back vises/skjules,
- profil vises/skjules,
- trailing action ændres.

Den konkrete visuelle alignment kan være centreret eller venstrejusteret afhængigt af endeligt design, men **sidekontroller må ikke ændre titelblokkens placering uforudsigeligt**.

## 4.3 Trailing slot

Skal kunne være:

```text
None
ActiveProfile
UnknownProfile
Page-specific action
```

På den nye Home-side er profil/avatar som udgangspunkt placeret i trailing/top-right området.

---

# 5. Profil-state

Profilområdet skal understøtte tre eksplicitte states.

## 5.1 Aktiv bruger med avatar

Eksisterende `AvatarView` og profilbinding genbruges.

## 5.2 Aktiv bruger uden avatar

Vis neutral fallback-avatar.

## 5.3 Ingen kendt/aktiv bruger

Dette er en **rigtig onboarding-state**, ikke kun error handling.

Vis en neutral “unknown profile”-avatar, fx:

- grå person-silhuet,
- eventuelt dashed ring,
- eventuelt lille `?`-indikator.

Den skal være klikbar og åbne det eksisterende profilflow.

Accessibility-label skal være generisk, fx:

```text
Select profile
```

eller lokaliseret ækvivalent.

---

# 6. Welcome-subtitle

Home skal kunne rendere både:

```text
Karate Analyzer
Welcome
```

og:

```text
Karate Analyzer
Welcome Lasse
```

Der må ikke antages, at brugerens navn er kendt ved første launch.

Welcome-string skal være lokaliserbar.

Eksempel:

```xml
<string name="home_welcome">Welcome</string>
<string name="home_welcome_named">Welcome %1$s</string>
```

---

# 7. Progressive bundnavigation

Bundnavigationen skal refaktoreres fra hardkodet destination-liste til state-driven navigation.

Den skal understøtte **1–5 synlige destinationer**.

## 7.1 State-model

Et muligt koncept:

```kotlin
data class AppNavigationState(
    val visibleDestinations: List<AppDestination>,
    val selectedDestination: AppDestination,
    val newlyUnlockedDestinations: Set<AppDestination> = emptySet(),
    val attentionDestination: AppDestination? = null,
)
```

Den præcise API er ikke bindende.

## 7.2 Fast destinationsorden

Der skal være én kanonisk orden.

Unlock må ikke omarrangere allerede kendte destinationer.

Den første produktsekvens, som faktisk er besluttet, er:

```text
HOME
HOME + LEARNING
```

Senere destinationer skal understøttes arkitektonisk, men deres endelige rækkefølge/navne besluttes ikke i denne opgave.

## 7.3 Funktionelle krav

| ID | Krav |
|---|---|
| NAV-A | Komponenten accepterer 1–5 synlige destinationer. |
| NAV-B | Én destination står visuelt centreret. |
| NAV-C | Ved 2–5 destinationer fordeles bredden jævnt. |
| NAV-D | Destinationernes kanoniske rækkefølge ændres aldrig. |
| NAV-E | Hver destination har stabil ID, ikon, label og callback. |
| NAV-F | Selected-state er separat fra unlock/attention-state. |
| NAV-G | Destination kan tilføjes runtime uden at rekonstruere hele siden. |
| NAV-H | Hele baren kan skjules på immersive flows. |
| NAV-I | Når baren skjules, efterlades intet tomt chrome-område. |
| NAV-J | Touch targets er mindst 48 × 48 dp. |
| NAV-K | Android navigation inset er surface, ikke tappable content. |
| NAV-L | Hvis selected destination ikke længere er synlig, falder shell sikkert tilbage til Home. |

---

# 8. Separér fire navigationstilstande

Følgende må ikke repræsenteres af ét enkelt boolean:

```text
destinationUnlocked
destinationVisible
destinationSelected
unlockAnnouncementPending
```

De betyder forskellige ting.

Eksempel:

- Learning kan være unlocked,
- Learning kan være hidden under kamera-flow,
- Home kan være selected,
- Learning kan have pending unlock-animation.

---

# 9. Attention / discovery state

Chrome skal kunne fremhæve et UI-element uden at navigere til det.

Dette bruges til onboarding.

Eksempler:

- Sensei beder brugeren trykke på den ukendte profil-avatar.
- Learning er netop blevet tilføjet og skal opdages.

State-semantik:

```text
selected = hvor er jeg?
unlocked = hvad kan jeg bruge?
attention = hvad bliver jeg bedt om at finde?
```

Attention-state må være midlertidig og må ikke ændre valgt destination.

---

# 10. Unlock-animation

Ved transition:

```text
N destinationer → N + 1 destination
```

skal den nye destination føles som en udvidelse af appen.

## 10.1 Reflow

Eksisterende ikoner glider fra gamle til nye positioner.

Anbefalet varighed:

```text
ca. 220–280 ms
```

Ingen instant jump.

## 10.2 Entrance

Ny destination:

```text
alpha: 0 → 1
scale: ca. 0.85/0.90 → 1.0
```

under sidste del af reflowet.

## 10.3 Discovery pulse

Efter entrance får destinationen et kort attention-highlight.

Fx:

- halo/ring,
- 1–2 korte pulse,
- evt. mild scale/alpha-effekt.

Pulsen skal stoppe helt.

Der må ikke være permanent blinking.

## 10.4 Selected vs newly unlocked

Disse må ikke se ens ud.

```text
selected = current location
newlyUnlocked = newly discovered capability
```

Rød ikonfarve alene må ikke være eneste signal.

---

# 11. Animationer deaktiveret

Hvis systemanimationer er slået fra:

- destinationen vises straks i korrekt position,
- stateændringen gennemføres,
- ingen pulse er nødvendig.

Unlock-funktionalitet må aldrig afhænge af, at animation faktisk kører.

Projektets `minSdk 26` gør det muligt at bruge:

```kotlin
ValueAnimator.areAnimatorsEnabled()
```

hvis relevant.

---

# 12. Første onboarding-use case

Chrome-opgaven skal kunne demonstrere den faktiske v0.1-flow-kontrakt.

## State A — første launch

```text
Header:
Karate Analyzer
Welcome

Trailing:
Unknown profile avatar

Bottom:
[ Home ]
```

Home er selected.

## State B — Learning unlockes

Efter progression ændres navigation state til:

```text
[ Home ] [ Learning ]
```

Krav:

1. Home reflower til ny position.
2. Learning kommer ind med unlock-animation.
3. Learning får kort attention-highlight.
4. Home forbliver selected.
5. Appen navigerer ikke automatisk til Learning.

Brugeren skal selv trykke på Learning.

Det er en central onboarding-regel:

> **Start kan ændre progression/navigation-state, men må ikke automatisk føre brugeren til Learning.**

---

# 13. Android system-integration

## 13.1 Edge-to-edge

Eksisterende WindowInsets-adfærd skal bevares.

Bundnavigationens surface skal fortsætte visuelt helt til den fysiske bund af skærmen.

Kun tappable content flyttes oven over Androids navigation/gesture area.

Ønsket princip:

```text
┌───────────────────────┐
│  Home       Learning  │ ← tappable content
│                       │
│      ─────────        │ ← Android gesture handle
└───────────────────────┘
       same surface
```

Ikke:

```text
┌───────────────────────┐
│  Home       Learning  │
└───────────────────────┘
████ unrelated color ████
```

Inset-håndtering skal fortsat være centraliseret i chrome-komponenten.

Der må ikke tilføjes side-specifikke navigation-bar workarounds.

---

# 14. Back navigation

Header Back og Android system Back skal give samme logiske resultat.

Krav:

```text
tap header Back
=
Android Back gesture/button
```

Begge skal følge den aktuelle navigation/back-stack.

Predictive Back-kompatibilitet skal bevares.

Eksisterende AndroidX back-infrastruktur genbruges.

---

# 15. Accessibility

Alle interaktive chrome-elementer skal:

- have minimum 48 × 48 dp touch target,
- have meningsfuld `contentDescription`,
- kommunikere selected-state semantisk,
- ikke være afhængige af farve alene,
- ikke flytte accessibility focus uventet ved unlock-animation,
- give fallback-avatar en meningsfuld label,
- forblive anvendelige med animationer slået fra.

---

# 16. Chrome visibility

Shellen skal kunne styre header og bundnavigation eksplicit.

Et muligt koncept:

```kotlin
data class AppChromeVisibility(
    val showHeader: Boolean = true,
    val showBottomNavigation: Boolean = true,
)
```

Headerens konkrete leading/trailing state håndteres separat via `AppHeaderState`.

Chrome skal kunne skjules helt på fx:

- kamera,
- recording,
- immersive guided practice,
- andre flows hvor permanent navigation ikke er hensigtsmæssig.

Når chrome skjules, må der ikke være tom reserveret plads.

---

# 17. Scope — implementeres nu

## Header

- Saml eksisterende main/sub-header-koncept.
- Tilføj leading-, title- og trailing-slots.
- Sørg for stabil title geometry.
- Understøt unknown/known profile state.
- Bevar statusbar/cutout insets.
- Appnavn flyttes til fælles resource.

## Profil

- Genbrug eksisterende ProfileRepository / AvatarView.
- Tilføj eksplicit unknown/fallback state.
- Bevar live opdatering ved profilskift.

## Bundnavigation

- Fjern hardkodet 4-item liste.
- Understøt 1–5 destinations-state.
- Bevar selected-state, labels og accessibility.
- Bevar Android navigation-bar inset handling.
- Tilføj runtime reflow.
- Tilføj unlock entrance.
- Tilføj attention/discovery pulse.
- Tilføj safe fallback til Home.

## Test/dev-state

Det skal være muligt at demonstrere:

```text
[ Home ]
```

og derefter runtime:

```text
[ Home ] [ Learning ]
```

uden at ændre komponentkode.

Arkitekturen skal desuden understøtte 3, 4 og 5 destinationer.

---

# 18. Bevidst uden for scope

Denne opgave må ikke:

- bygge nyt Home-indhold,
- implementere Sensei,
- implementere dojo-baggrund,
- implementere speech bubble,
- implementere Learning Path,
- beslutte hele fremtidige navigation taxonomy,
- implementere coaching engine,
- implementere dojo schedule,
- bygge Practice/Train/Progress-sider,
- redesigne profile editor,
- migrere appen til Compose,
- erstatte navigation/back-stack arkitekturen,
- ændre kamera- eller analyse-pipeline,
- beslutte endeligt produktnavn.

---

# 19. Sandsynlige berørte filer

Forventet hovedarbejde i eller omkring:

```text
PageHeaders.kt
AppBottomNavigationView.kt
HomeScreenView.kt
ProfileAvatarButton.kt
AvatarView.kt
strings.xml
styles.xml / relevante resources
tests
```

De konkrete filer skal verificeres mod repoets aktuelle struktur.

---

# 20. Acceptkriterier — header

| Scenario | Forventet resultat |
|---|---|
| Ingen profil | Unknown avatar vises og kan trykkes. |
| Aktiv profil | Korrekt avatar vises. |
| Profil skiftes | Avatar og Welcome opdateres live. |
| Back vises | Titelgeometry forbliver stabil. |
| Back skjules | Titelgeometry forbliver stabil. |
| Trailing ændres | Titelgeometry forbliver stabil. |
| Ingen subtitle | Layout kollapser korrekt. |
| Langt brugernavn | Tekst wrapper/ellipsizes kontrolleret. |
| Cutout/statusbar | Controls overlapper ikke system UI. |
| Touch target | Avatar/Back er mindst 48 × 48 dp. |

---

# 21. Acceptkriterier — bundnavigation

Komponenten skal kunne rendere:

```text
1 destination
2 destinationer
3 destinationer
4 destinationer
5 destinationer
```

Transitions:

```text
1 → 2
2 → 3
3 → 4
4 → 5
```

For hver transition:

1. eksisterende destinationer reflower,
2. rækkefølgen ændres ikke,
3. ny destination fade/scale-animeres ind,
4. ny destination kan få discovery-highlight,
5. selected-state forbliver korrekt,
6. animationen stopper i stabil statisk state,
7. acknowledged unlock afspilles ikke igen ved normal rerender,
8. animations-disabled mode viser korrekt slutstate straks.

---

# 22. Acceptkriterier — Android navigation

Test mindst:

| Miljø | Krav |
|---|---|
| Gesture navigation | Bottom content overlapper ikke gesture area. |
| 3-button navigation | Bottom content overlapper ikke systemknapper. |
| Portrait | 1–5 items fungerer. |
| Landscape | Insets/cutout fungerer. |
| API 26 | Ingen minSdk-regression. |
| Android 15 / API 35 | Edge-to-edge fungerer. |
| Android 16 / API 36 | Edge-to-edge + Back-flow fungerer. |

---

# 23. Acceptkriterier — Back

På subpage:

```text
header Back
```

og:

```text
Android Back gesture/button
```

skal returnere til samme tidligere destination/state.

Ingen særskilt “go Home”-logik i headeren.

---

# 24. Definition of Done

Opgaven er færdig, når følgende kan demonstreres uden specialkode pr. screen:

## State A

```text
┌────────────────────────────┐
│       Karate Analyzer      │
│           Welcome          │  [? profile]
│                            │
│        page content        │
│                            │
│          [ Home ]          │
└────────────────────────────┘
```

## State B

```text
┌────────────────────────────┐
│       Karate Analyzer      │
│           Welcome          │  [? profile]
│                            │
│        page content        │
│                            │
│    [ Home ] [ Learning ]   │
│                 ✦          │
└────────────────────────────┘
```

og komponenten kan konfigureres videre op til fem destinationer uden ny implementeringsgren.

---

# 25. Efterfølgende opgave

Når chrome-opgaven er færdig, kan næste feature bygges oven på den:

**First-Run Home + Learning Entry v0.1**

Den feature vil bruge chrome-fundamentet til flowet:

```text
Home only
→ Sensei welcome
→ Start
→ profile discovery/setup
→ Learning unlockes
→ Sensei beder brugeren åbne Learning
→ brugeren trykker selv på Learning
→ Learning Path begynder
```

Chrome-opgaven må understøtte dette flow, men må ikke implementere selve flowet.

---

# 26. Separat release-blocker

Repoets nuværende `targetSdk` bør verificeres.

Hvis projektet fortsat targeter API 35, skal der oprettes en separat release-blocker for:

```text
targetSdk 35 → 36
API 36 regression test
edge-to-edge test
predictive-back test
```

Dette bør ikke udvide chrome-PR'en unødigt, men skal håndteres før relevant Play Store-release.
