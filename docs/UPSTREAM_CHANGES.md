# Upstream (Metrolist) files changed by abcd

Every Metrolist file that abcd modifies, with the reason. Keep this list current so upstream fixes can
be merged later. New DJ code lives in the `dj-*` modules and is not listed here.

| File | Change | Reason |
|---|---|---|
| `androidApp/build.gradle.kts` | `applicationId` → `in.aiqulabs.abcd`; app labels → `abcd` / `abcd Debug` | Rebrand so abcd installs beside Metrolist (brief §2.4) |
| `androidApp/build.gradle.kts` | `implementation(project(":dj-core"))` | App consumes DJ logic |
| `settings.gradle.kts` | `include(":dj-core")` | Register DJ modules |
| `shared/src/commonMain/composeResources/values/strings.xml` | `app_name` → `abcd` | Rebrand (brief §2.4) |
| `androidApp/src/main/res/mipmap-anydpi{,-v26}/ic_launcher.xml` | Point the default icon at new `abcd_launcher_*` drawables | Placeholder abcd icon; Metrolist's alternative icons in the picker are unchanged |
| `README.md` | abcd section added at the top; Metrolist's README kept below | Credits (brief §2.2) |
| `CLAUDE.md` | Replaced with the abcd agent guide | Brief §3.4 |
| `.gitignore` | Ignore `graphify-out/` | Local code graph is not committed |
