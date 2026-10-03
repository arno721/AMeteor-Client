# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

A fork of [Meteor Client](https://github.com/MeteorDevelopment/meteor-client) (Fabric utility mod for Minecraft, GPLv3) targeting Minecraft 26.2 / Java 25 (see `gradle/libs.versions.toml`), with an added Traditional Chinese (zh_tw) localization layer. The Java package is still `meteordevelopment.meteorclient`. There is no git repo and no test suite (`src/test` does not exist).

## Commands

- Build: `./gradlew build` (Windows: `gradlew.bat build`). Output jar is in `build/libs`. Version is `<minecraft>-<build_number|local>`.
- Dev client: `./gradlew runClient` (Fabric Loom).
- Error Prone + NullAway (off by default): `./gradlew build -Perrorprone`. NullAway runs as an ERROR for the `meteordevelopment.meteorclient` package.
- `BUILD_STATUS.txt` notes the Gradle distribution was unreachable in the original environment, so builds may not have been verified there.

## Architecture

- **Entry point**: `MeteorClient.onInitializeClient` drives startup order: `AddonManager.init` → register addon event handlers → `ReflectInit` (`@PreInit` / `@PostInit` hooks discovered by reflection) → `Categories.init` → `Systems.init` → addons' `onInitialize` → `Modules.sortModules` → `Systems.load` → `LanguageManager.setMode`. Order matters; keep new init code in the right phase.
- **Event bus**: Orbit (`MeteorClient.EVENT_BUS`). Handlers are `@EventHandler` methods discovered reflectively, so they look unused to static analysis. Event classes live in `events/`.
- **Systems** (`systems/`): persistent singletons extending `System` and registered in `Systems` (modules, config, accounts, friends, hud, macros, profiles, proxies, waypoints). They save/load to `FOLDER` (`<gameDir>/meteor-client`) and are saved in a JVM shutdown hook.
- **Modules** (`systems/modules/{combat,misc,movement,player,render,world}`): extend `Module`, configured through `settings/` (`SettingGroup` → `Setting<T>`). The GUI (`gui/`) is generated from settings via `DefaultSettingsWidgetFactory`.
- **Mixins** (`mixin/`, `mixininterface/`): registered in `src/main/resources/meteor-client*.mixins.json`. Separate mixin configs for Baritone, Sodium, Lithium, Iris/Indigo and ViaFabricPlus are loaded conditionally via `MixinPlugin`. Those mods are `compileOnly`. Field and method access to Minecraft internals goes through `meteor-client.classtweaker` (access widener).
- **Pathing** (`pathing/`): `PathManagers` selects `BaritonePathManager` or `NopPathManager` depending on whether Baritone is present.
- **Libraries** are bundled jar-in-jar via the `jij` configuration; Fabric API modules via `modInclude`. A custom block in `build.gradle.kts` recursively adds `jij` transitive deps (non-transitively). The `src/launcher` source set is compiled to Java 8 and included in the jar (for the `Main-Class` stub).
- **Addons**: external mods implement `MeteorAddon` (see `addons/`). `publishing` in `build.gradle.kts` targets `maven.meteordev.org/snapshots` (needs `MAVEN_METEOR_*` env vars).

## Localization (fork-specific)

`utils/i18n/LanguageManager` works with language packs. A pack is one JSON file: `{"_meta": {code, name, author, format}, "strings": {key: text}, "english": {...}}` (`english` is only a reference for translators and is ignored when loading).

- English lives in the code (`getEnglishTitle()` / `getEnglishDescription()` on `Module`, `Setting`, `Command`, and the fallback argument of `LanguageManager.translate(key, english)`), so English needs no pack.
- Built-in packs are in `src/main/resources/assets/meteor-client/languages/` (`zh_tw.json`; `en_us.json` is the complete English reference used by exports). Do NOT put packs in `assets/meteor-client/lang/`: Minecraft parses those files itself, they must stay flat and only hold `key.*` keybind names.
- Players' packs live in `<gameDir>/meteor-client/languages/*.json` and replace a built-in pack with the same code. The Config tab has export (current language / English template), import, open folder and reload buttons. `Config.language` is a `ProvidedStringSetting` holding `auto`, `en_us` or a pack code (old enum values are converted).
- Auto picks the pack matching the Minecraft language, then one with the same language prefix (zh_cn → zh_tw), else English. `isChinese()` really means "the active pack needs glyphs the bundled fonts lack" (switches to the vanilla font).
- Key formats: `module.<name>.name` / `.description`, `module.<name>.setting.<setting>.name` / `.description`, `module.<name>.group.<group>` and `setting-group.<group>` (lowercase, spaces and underscores become `-`), `config.setting.<name>.*` (also used by HUD element settings), `command.<name>.*`, `category.<name>.name`, `tab.<name>.name`, plus literal keys passed to `translate()`.
- When adding a module, setting or command, add the zh_tw text to `languages/zh_tw.json` (and the English to `languages/en_us.json`) or it silently shows English.

## Conventions (from README)

- The license header (`This file is part of the Meteor Client distribution…`) is required on all Java files. Note that `LanguageManager.java` currently lacks it.
- Match the surrounding code style; favor readability over compactness (Google Java Style as a reference). `.editorconfig` is present.
