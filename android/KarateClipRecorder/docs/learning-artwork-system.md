# Learning artwork system

Learning-path visuals are built from two independent vector layers:

1. A foreground identifies what is being learned.
2. An Ensō treatment identifies the semantic activity type.

`LearningPathArtworkView` owns this composition. It renders `EnsoBackgroundView` first and a centered monochrome foreground above it. The default proportions are 90% for the Ensō and 54% for the foreground, preserving both SVG aspect ratios.

## Activity semantics

`LearningActivityType` is explicit UI/domain metadata with `PRACTICE` and `TEST` values. `LearningArtworkStyleResolver` maps those values to centralized tokens:

- `EnsoThemeTokens.ensoPracticeBaseColor`: warm neutral/sand for learning and practice.
- `EnsoThemeTokens.ensoTestBaseColor`: muted iron red for tests and assessments.

Both colors still pass through the shared 15-tone Ensō palette. The foreground always remains black. Activity names and labels are never inspected to choose a palette.

## Japanese Counting

The supplied vector is stored once as `app/src/main/res/raw/japanese_counting.svg` and exposed as `LearningArtworkForeground.JAPANESE_COUNTING`. Practice and Test both reference this same asset; only the Ensō palette changes.

An artwork view selects one Ensō when first bound and retains it for that view instance. Screens showing several activities should allocate variants with `EnsoLibrary.newShuffleBag()` and pass them to each view, as the Japanese Counting entries do.

## Continue card

The Home Continue card selects one Ensō when `HomeScreenView` is created and retains it for that Home instance. Its artwork area is already a layered container, but intentionally contains no foreground figure yet.

In debug builds, Home → Settings shows all 20 Ensō variants with the Japanese Counting foreground in Practice and Test columns for palette, alignment, and legibility checks.
