# Development

Everything lives in one `patches` module, so Morphe Desktop / Manager only needs one source.

## Layout

```
patches/src/main/kotlin/app/ra1nify/f4t/
├── shared/Compatibilities.kt         # one Compatibility per host
├── shared/AntiResaleInjector.kt      # Application.onCreate() detection + injection
├── bilibili/{GuardSelfDestructPatch,GateBypassPatch,AntiResalePatch}.kt
└── virtualtablet/{ProEntitlementPatch,DiagnosticsPatch,AntiResalePatch}.kt
extensions/extension/
├── src/main/java/app/ra1nify/f4t/extension/{AntiResaleAlert,MemeView}.java
└── proguard-rules.pro                # keeps the R8-minified extension entry point
docs/
├── anti-resale-alert.md              # notice behaviour and test checklist
├── bi1i-morphe-patch.md              # how the Bilibili guard was reverse engineered
├── virtualtablet-pro-patch.md        # Flutter + RevenueCat wiring, injection point
├── morphe-patch-authoring.md         # host-agnostic Morphe authoring guide
└── morphe-source-publishing.md       # how the release source is laid out
```

## Version scheme

`yy.MM.dd.<sequence>`

- `<sequence>` is `1 +` the highest last segment of the existing release tags, so it keeps counting
  up across days (`26.11.16.7`, `26.11.17.8`, ...);
- CI passes the exact version in (`-Pf4t.version=` or `F4T_VERSION`), so the bundle, the source
  manifest and the tag always agree;
- CI builds are pre-releases of the next version: `<next>-dev.<short-sha>`.

Morphe compares multi-part numeric versions component-wise, so this ordering is safe, and the
`-dev.<sha>` suffix is treated as a pre-release, i.e. it never outranks the release it precedes.

## Requirements

- JDK 21
- Android SDK (`compileSdk 36`); the `extensions` module is an Android library, so `sdk.dir` has to be
  set in the git-ignored `local.properties`

  ```properties
  sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
  ```

- A GitHub PAT with `read:packages`, because the Morphe Gradle plugin and `morphe-patcher` are
  published to GitHub Packages only

  ```properties
  # ~/.gradle/gradle.properties
  gpr.user=<github user>
  gpr.key=<PAT with read:packages>
  ```

The extension is minified with R8 using `extensions/extension/proguard-rules.pro`. Without it the
extension DEX would carry the whole Kotlin standard library (~2 MB) into every patched APK.

## Building

```bash
./gradlew buildAndroid
# -> patches/build/libs/patches-<version>.mpp
```

## Convenience tasks

Two Gradle tasks build the bundle and patch an APK in one step:

| Task | Applies |
| --- | --- |
| `:patches:patchBilibili` | all Bilibili patches |
| `:patches:patchVirtualTablet` | all VirtualTablet patches |

They read machine-specific paths from the git-ignored `local.properties`:

```properties
morphe.cli=C:/path/to/morphe-desktop-2.2.0-dev.14-all.jar
morphe.apk.bilibili=C:/path/to/bili/base.apk
morphe.apk.virtualtablet=C:/path/to/virtualtablet/base.apk
morphe.out.dir=C:/path/to/output
morphe.keystore=C:/Users/<you>/.android/debug.keystore
morphe.keystore.alias=androiddebugkey
morphe.keystore.password=android
```

Three run configurations are committed under `.idea/runConfigurations/`; `.gitignore` ignores the
rest of `.idea/`.

## Applying

```bash
java -jar morphe-desktop-*.jar patch <input.apk> --patches=patches/build/libs/patches-*.mpp \
  -e="No Limit" \
  -o <out.apk> --keystore=<ks> --keystore-entry-alias=<alias> \
  --keystore-password=<pw> --keystore-entry-password=<pw>
```

`--patches` takes the bundle; `-p` is `--with-packages`, not `--patches`.

The VirtualTablet host is a split install; Morphe Manager handles split packages directly. On the
desktop CLI a split host needs the patched base plus re-signed splits (`adb install-multiple`).

## Continuous integration

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `build-mpp.yml` | called by the other two | builds one bundle (release or debug), verifies it, uploads it as an artifact |
| `ci.yml` | every push to `main`, manual dispatch | builds `release` and `debug` as `<next>-dev.<sha>`, artifacts kept 90 days |
| `publish.yml` | manual dispatch only, from `main` | builds, signs, creates the `v<version>` release and commits the updated manifest |

- `debug` is the same bundle with the extension left unminified (`-PnoMinify=true`), so stack traces
  of the extension stay readable; it is roughly ten times larger.
- Signing uses the Ed25519 key from the `MORPHE_SIGNING_KEY` secret; `morphe-signing.pub` is
  committed and the publish workflow writes it into the manifest as `public_key`.
- The publish workflow commits `patches-bundle.json` with `[skip ci]`, and `ci.yml` ignores commits
  made by the bot, so a release does not trigger another build.

