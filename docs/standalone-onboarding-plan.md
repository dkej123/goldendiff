# Plan: pierwszy-uruchomieniowy walkthrough + kontrolka projektu na toolbarze

## Context

Standalone app (`:app`, Compose Desktop, macOS) ma dwie luki w onboardingu:

1. **Brak przewodnika konfiguracji przy pierwszym uruchomieniu.** Nowy user dostaje pusty ekran
   „No project open", a gdy otworzy projekt bez skonfigurowanych golden dirs — „Nothing to compare
   yet → Open Settings". Konfiguracja (golden directories, tryb dopasowania itd.) jest odkrywalna
   tylko przez samodzielne wejście w Settings. Nic nie prowadzi usera krok po kroku.
2. **Nie da się zmienić projektu, gdy jakiś jest już otwarty.** Przycisk „Open project…" na
   toolbarze jest w bloku `if (!hasProject)` (`GoldenDiffWindow.kt:115`) i znika po otwarciu
   projektu. Menu natywnego nie ma. Przy starcie apka zawsze przywraca ostatni projekt
   (`AppConfig.lastProject()`), więc user utyka na jednym projekcie.

Cel: (A) **pełny kreator** pierwszego uruchomienia z krokiem per sekcja Settings, prowadzący usera
od wyboru projektu do gotowej konfiguracji; (B) **stała kontrolka projektu** na toolbarze — dropdown
z nazwą bieżącego projektu, listą ostatnich i „Open other project…".

### Założenie o design → implementacja
Warstwa wizualna kreatora **nie jest projektowana od zera w Compose**. Najpierw Claude Design
dostarcza **makiety HTML** (osobny plik/ekran na każdy krok i stan, light + dark) na podstawie
briefu z załącznika. Implementacja w Compose **odwzorowuje te HTML-e 1:1** — layout, odstępy,
hierarchia, stany — mapując je na istniejący design system (`ui/Theme.kt`, `ui/Widgets.kt`). Krok 0
implementacji: odebrać HTML-e, dopiero potem A2. HTML jest referencją wizualną, nie kodem do portu.

Konwencje ustalone w Phase 1 (referencje z kodu):
- Wtórne okna: wzorzec `if (state.xxxVisible) SomeWindow(state) { state.xxxVisible = false }` w
  `Main.kt` + `DeferredWindowCloseEffect` (`WindowLifecycle.kt:14`). Wzorzec do naśladowania:
  `TelemetryConsentWindow` (osobne `Window`, brand ✣, `PrimaryButton`/tekst-link „Not now").
- „First run" nie ma dziś flagi — pustość configu to `GoldenDiffConfig.isConfigured` (goldenPaths
  niepuste). Brak golden dirs → `Blocker.NoGoldenDirectories` (`AppState.kt:477`).
- Wybór projektu: `chooseDirectory(title): File?` (natywny `JFileChooser`, `GoldenDiffWindow.kt:472`)
  → `AppState.openProject(root, restored)` (ładuje config, `rememberProject`, refresh, indeksuje).
- Zmiana configu: `AppState.updateConfig(GoldenDiffConfig)` (`AppState.kt:353`) — zapis przez
  `AppConfig.save`, refresh. Wizard = guided front-end nad tymi samymi wywołaniami co Settings.
- Building-blocki configu (`FieldLabel`, `RadioRow`, `Toggle`, `MonoField`) oraz sekcje
  (`DirectoriesSection`, `OutputMatchingSection`, `MatchingSection`, `FilteringSection`,
  `DisplaySection`, `PrivacySection`) są **`private` w `SettingsWindow.kt`** → do reużycia trzeba je
  wypromować.
- Design system: `ui/Theme.kt` (`Tokens`, `tokens`, `Type`, `Dimens`), `ui/Widgets.kt`
  (`PrimaryButton`, `GhostButton`, `ToolButton`, `DropdownButton<T>`, `Pane`, `EmptyState`,
  `IconButton`, `FolderIcon`, `HairLine`, `hoverWash`).

---

## Część A — Pełny kreator pierwszego uruchomienia

### A1. Refaktor: wypromuj współdzielone komponenty (DRY)
Aby kroki kreatora były **dokładnie tymi samymi** sekcjami co Settings (bez duplikacji):

- Nowy plik `app/.../SettingsSections.kt`: przenieś z `SettingsWindow.kt` (jako `internal`):
  `SectionHeader`, `FieldLabel`, `RadioRow`, `Toggle`, `MonoField`, `DirectorySection`,
  i sześć sekcji: `DirectoriesSection`, `OutputMatchingSection`, `MatchingSection`,
  `FilteringSection`, `DisplaySection`, `PrivacySection`. Sygnatury każdej `(state: AppState)`
  (poza `PrivacySection()`), więc pasują 1:1 do obu hostów.
- `SettingsWindow.kt` renderuje je bez zmian (import z nowego pliku).
- (Ogólne, wielokrotnego użytku `FieldLabel`/`RadioRow`/`Toggle`/`MonoField` mogą też trafić do
  `ui/Widgets.kt` — decyzja implementacyjna; ważne, że przestają być `private`.)

### A2. Nowe okno kreatora — `app/.../OnboardingWindow.kt`
Osobne `Window` (jak `TelemetryConsentWindow`), rozmiar ~ `1040×640` (jak SettingsWindow), zamykane
przez `DeferredWindowCloseEffect`. Layout/wizualia **wg dostarczonych makiet HTML od Claude Design**
(odwzorowanie 1:1 na `ui/Widgets.kt`/`Theme.kt`). Punkt wyjścia z briefu: **lewy rail ze stepperem**
(lista kroków z zaznaczeniem bieżącego + „done", wzorowany na `NavItem` z SettingsWindow) + treść
kroku + **stopka: Back / Skip (dla kroków opcjonalnych) / Next|Finish**.

Kroki (pełny kreator — krok per sekcja Settings + kroki ramowe):

1. **Welcome** — brand ✣, jedno zdanie „co robi Golden Diff", przycisk „Get started".
2. **Project** *(wymagany; krok spoza Settings)* — `chooseDirectory("Select project directory")`;
   po wyborze wywołaj `openProject(root)`; pokaż ścieżkę + status walidacji git (zielony „git
   repository" / czerwony komunikat dla `Blocker.NoGit`, `Blocker.NotARepository` — reużyj logiki z
   `AppState`). Next zablokowany dopóki projekt nie jest poprawnym repo.
3. **Golden directories** *(wymagany)* — `DirectoriesSection(state)` (część golden). ≥1 katalog do
   przejścia dalej. To jedyny krok krytyczny dla działania (`isConfigured`).
4. **Test output** *(opcjonalny, Skip)* — generated output dirs + `OutputMatchingSection(state)`
   (regex, domyślnie `^(.+)_actual\.png$`).
5. **Golden matching** *(opcjonalny, Skip)* — `MatchingSection(state)`: radio „annotated method"
   (domyślnie, regex `.*Preview.*|Test`) vs „file/class regex" (`{file_name}`/`{class_name}`).
6. **Filtering** *(opcjonalny, Skip)* — `FilteringSection(state)`: excluded suffixes
   (`_compare, _actual`), trim transparent padding.
7. **Display** *(opcjonalny, Skip)* — `DisplaySection(state)`: appearance Light/Dark/System itd.
8. **Privacy** *(opcjonalny, Skip)* — `PrivacySection()`: zgody telemetryczne. **Zastępuje** osobne
   `TelemetryConsentWindow` na pierwszym uruchomieniu (patrz A4), żeby nie pokazywać dwóch okien.
9. **Finish** — podsumowanie (projekt + liczba golden dirs + tryb dopasowania); „Start comparing".
   Na wyjściu: `AppConfig.markOnboardingCompleted()`, zamknij okno → główne okno pokazuje porównania.

Edycje w krokach idą **na żywo** przez `updateConfig(...)` (dokładnie jak w Settings) — po otwarciu
projektu w kroku 2 golden paths zapisują się relatywnie do roota. Brak osobnego „draft".

### A3. Stan + trigger
- `AppState`: dodaj `var onboardingVisible by mutableStateOf(false)` (public, jak `settingsVisible`).
- `AppConfig` (globalne, obok `last-project`): `onboardingCompleted(): Boolean` /
  `markOnboardingCompleted()` — plik `~/.config/golden-diff/onboarding-done`.
- `Main.kt` `LaunchedEffect`: jeśli `!AppConfig.onboardingCompleted()` → `state.onboardingVisible =
  true` (na true-first-run nie ma `lastProject`, więc wizard przejmuje otwarcie projektu; przy
  kolejnych startach wizard się nie pokazuje, `lastProject` przywracany jak dziś).
- `Main.kt`: `if (state.onboardingVisible) OnboardingWindow(state) { state.onboardingVisible = false }`.
- **Re-run**: w `SettingsWindow` (stopka lub sekcja Display) przycisk „Run setup walkthrough again"
  → `state.onboardingVisible = true`. Umożliwia powrót do kreatora.

### A4. Kolejność vs zgoda telemetryczna
Dziś `AppTelemetry.consentPromptVisible` steruje osobnym `TelemetryConsentWindow` (`Main.kt:94`).
Na pierwszym uruchomieniu zgodę zbieramy w kroku 8 kreatora (`PrivacySection`), więc:
- gdy `onboardingVisible` (pierwsze uruchomienie) — **nie** pokazuj `TelemetryConsentWindow`;
  decyzję zapisz z kreatora przez `AppTelemetry.decideConsent(...)`.
- ścieżka bez kreatora (istniejący userzy) — bez zmian.

---

## Część B — Kontrolka projektu na toolbarze (dropdown z recentami)

### B1. Recent projects w `AppConfig`
- `recentProjects(): List<File>` — czyta `~/.config/golden-diff/recent-projects` (newline, abs
  paths, najnowszy pierwszy), odfiltrowuje nieistniejące katalogi.
- Rozszerz `rememberProject(root)`: oprócz `last-project` dopisz root na początek listy recent,
  deduplikacja po `absolutePath`, cap ~8.

### B2. Toolbar
W `GoldenDiffWindow.kt` `Toolbar` (~`107`): zamiast `if (!hasProject) ToolButton("Open project…")`
dodaj **zawsze widoczną** kontrolkę na lewym skraju:
- Label = nazwa bieżącego projektu (lub „Open project…" gdy `projectRoot == null`).
- Menu: pozycje z `recentProjects()` → `openProject(it)`; separator; „Open other project…" →
  `chooseDirectory("Select project directory")?.let(state::openProject)`.
- Implementacja: nowy `ProjectMenuButton` wzorowany na `DropdownButton<T>` (`Widgets.kt:137`) albo
  jego bezpośrednie użycie z listą `sealed`/`File` + sentinel. Bieżący projekt wyróżniony.
- Empty-state „Open Project…" w `Body` (`GoldenDiffWindow.kt:366`) zostaje — toolbar to druga,
  zawsze dostępna ścieżka.

---

## Pliki do zmiany
- **NOWY** `app/.../OnboardingWindow.kt` — kreator (rail + kroki + stopka).
- **NOWY** `app/.../SettingsSections.kt` — wypromowane sekcje + building-blocki (z `SettingsWindow.kt`).
- `app/.../SettingsWindow.kt` — import sekcji z nowego pliku; przycisk „Run setup walkthrough again".
- `app/.../Main.kt` — trigger + okno kreatora; supresja `TelemetryConsentWindow` podczas onboardingu.
- `app/.../AppState.kt` — `onboardingVisible`.
- `app/.../AppConfig.kt` — `onboardingCompleted`/`markOnboardingCompleted`; `recentProjects` +
  rozszerzony `rememberProject`.
- `app/.../GoldenDiffWindow.kt` — `ProjectMenuButton` na toolbarze (reużycie `chooseDirectory`).
- (opc.) `app/.../ui/Widgets.kt` — jeśli tam trafią wspólne `FieldLabel`/`RadioRow`/`Toggle`/`MonoField`.

## Reużycie (bez pisania od nowa)
`chooseDirectory`, `openProject`, `updateConfig`, `EmptyState`, `PrimaryButton`/`GhostButton`/
`ToolButton`/`DropdownButton`, `Pane`, `tokens`/`Type`/`Dimens`, `DeferredWindowCloseEffect`,
sześć sekcji Settings (po wypromowaniu), git-checki produkujące `Blocker`.

## Testy
- `app/src/test/.../AppConfigTest.kt` (nowy, wzór: `ProjectTreeTest`): recent projects —
  dedup/kolejność/cap/odfiltrowanie nieistniejących; flaga `onboarding-done`.
- Kreator/UI — weryfikacja manualna (Compose UI bez test-harnessu w repo).

## Weryfikacja end-to-end
1. `./gradlew :app:run`. Symuluj pierwsze uruchomienie: usuń
   `~/.config/golden-diff/onboarding-done` i `~/.config/golden-diff/last-project`.
2. Kreator pojawia się: przejdź kroki z repo `/Users/dkwasniak/Workspace/adhoc-ios` — projekt
   (git OK), golden dirs `Shared/Tests` + `UIComponents/Tests`, resztę Skip/domyślnie, Finish.
3. Sprawdź zapis configu w `~/.config/golden-diff/adhoc-ios-<hash>.properties`; główne okno pokazuje
   porównania (bez blockera).
4. Toolbar: dropdown projektu → „Open other project…", potem przełącz z powrotem z listy recent.
5. Settings → „Run setup walkthrough again" ponownie otwiera kreator.
6. `./gradlew :app:test` oraz `./gradlew :core:test`. **Czytaj log Gradle na `BUILD SUCCESSFUL`**
   (nie ufaj exit code — gotcha z CLAUDE.md).

---

## Załącznik — prompt do „Claude Design" (brief zawartości przewodnika)

> Kontekst: to standalone desktopowa apka Golden Diff (macOS), którą projektowałeś — znasz jej
> design system (tokeny light/dark, typografię, komponenty, brand ✣). Zaprojektuj **kreator
> pierwszego uruchomienia (onboarding walkthrough)** w tym samym języku wizualnym. Nie zmieniaj
> stylu — dopasuj się do istniejących ekranów (Settings, TelemetryConsent).
>
> **Deliverable: makiety HTML** (self-contained, inline CSS, bez zewnętrznych zależności) —
> po jednym ekranie na każdy krok, oraz osobne ekrany dla kluczowych stanów (błąd/empty w kroku
> Project, aktywny każdy tryb radia w Golden matching). Każdy ekran w **wersji light i dark**. To
> jest referencja wizualna do 1:1 odwzorowania w Compose Desktop — więc trzymaj realne proporcje
> okna i odstępy.
>
> **Format okna:** ~1040×640. Layout: lewy rail ze stepperem (lista kroków, stan: aktywny /
> ukończony / dalszy) + panel treści + stopka z akcjami **Back / Skip / Next** (na ostatnim kroku
> **Finish**). Kroki opcjonalne mają „Skip".
>
> **Kroki i WSZYSTKIE opcje w każdym z nich:**
> 1. **Welcome** — brand ✣, tytuł, jedno zdanie co robi narzędzie (porównuje golden screenshoty z
>    git HEAD), przycisk „Get started".
> 2. **Project** *(wymagany)* — przycisk wyboru katalogu projektu; po wyborze: pokazana ścieżka +
>    status walidacji git (stan OK „git repository" oraz stany błędu: „git not found",
>    „not a git repository"). Next zablokowany gdy projekt niepoprawny.
> 3. **Golden directories** *(wymagany)* — lista katalogów golden (dodaj przez picker, usuń z listy),
>    ścieżki relatywne do projektu, hint objaśniający. Min. 1, inaczej Next zablokowany.
> 4. **Test output** *(opcjonalny)* — lista „generated output" katalogów + pole regex nazwy pliku
>    (wartość domyślna `^(.+)_actual\.png$`), krótkie objaśnienie „tylko gdy porównujesz z output
>    testów, nie z working copy".
> 5. **Golden matching** *(opcjonalny)* — radio: „Match by annotated method" (domyślny) z polem
>    regex nazwy adnotacji (domyślnie `.*Preview.*|Test`) ORAZ „Match by file/class regex" z polem
>    wieloliniowym wzorców (placeholdery `{file_name}`, `{class_name}`). Pokaż który tryb aktywny.
> 6. **Filtering** *(opcjonalny)* — pole „excluded suffixes" (domyślnie `_compare, _actual`) + toggle
>    „trim transparent padding".
> 7. **Display** *(opcjonalny)* — wybór wyglądu Light / Dark / System (+ ewentualnie rozmiar
>    miniatur).
> 8. **Privacy** *(opcjonalny)* — dwie zgody z togglami: „anonymous usage analytics" i
>    „crash reporting", z krótkimi opisami; można zmienić później w Settings.
> 9. **Finish** — podsumowanie (wybrany projekt, liczba golden dirs, tryb dopasowania), przycisk
>    „Start comparing".
>
> **Stany do pokazania:** walidacja (zablokowany Next), krok opcjonalny vs wymagany, wybrany/pusty
> katalog, aktywny tryb radia, error/empty w kroku Project. Pokaż wersje light i dark.
