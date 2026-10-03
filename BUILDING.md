# Building Applied Causality from release source

Corresponding source for each release is published on GitHub before its binary
is distributed through CurseForge. Follow the CurseForge project's Source link
and the version-specific source link in its file description. Download the
matching release tag or `applied_causality-<version>-sources.zip`, extract it,
and run the commands below in the directory containing `build.gradle`.
The playable MOD JAR does not contain a source archive.

## Requirements

- Java Development Kit **17**; set `JAVA_HOME` to it if needed.
- Internet access for the Gradle wrapper, Forge tooling, mappings, and declared
  dependencies. Downloaded dependencies retain their own licenses.
- Gradle **8.8**, provided by the included wrapper.

`gradle.properties` records Minecraft, Forge, AE2, and optional integration
versions. `build.gradle` and `settings.gradle` define the build, mappings,
repositories, and plugin versions/ranges. The source ZIP includes every source
set and its resources so the normal build and available tests do not need files
from the maintainer's working directory.

## Build and test

Windows PowerShell:

```powershell
.\gradlew.bat build --warning-mode all --console=plain
```

Linux/macOS:

```sh
sh ./gradlew build --warning-mode all --console=plain
```

The build runs the unit tests, creates the normal MOD JAR, reobfuscates it for
Forge, and creates a separate corresponding-source ZIP. Outputs:

- `build/libs/applied_causality-<version>.jar`: playable reobfuscated MOD.
- `build/distributions/applied_causality-<version>-sources.zip`: source archive
  to attach to the matching GitHub release.
- `build/reports/tests/test/index.html`: unit-test report.

Integration GameTests and development client/server launches are additional
checks; `build` does not substitute for testing the JAR in a clean Forge install.
Generated manifests contain build timestamps, so a rebuild need not have the
same whole-JAR hash. Compare source, compiled classes, resources, and license
contents when checking the release-source relationship.

## License and source delivery

Original Applied Causality material is LGPL-3.0-or-later, copyright 2026 yuuki015.
See `LICENSE.txt` for LGPLv3/GPLv3 and `NOTICE.txt` for third-party terms.
Preserve license/copyright notices and satisfy corresponding-source and other
applicable obligations when distributing the original or a modified MOD.
For this project's release process, publish the matching source/tag on GitHub
first, then link that exact source from the CurseForge project and file.
Keep the source accessible for the distributed versions; a moving branch alone
does not identify the source corresponding to an older binary.
When releasing modifications, provide their corresponding source too.
Do not include dependency MOD binaries, caches, or private local settings in
the source archive. The included Gradle wrapper JAR is a build tool.
