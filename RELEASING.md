# Releasing

Git tags are validated by `.github/workflows/release.yml`, and JitPack builds
the public artifacts directly from those tags. No registry account, publishing
token, namespace registration, or GPG key is required.

## One-Time Setup

1. Create the public `MetrolistGroup/innertubex` GitHub repository.
2. Enable GitHub private vulnerability reporting.
3. Protect the `main` branch and require the CI workflow.
4. Confirm the repository is visible at https://jitpack.io/#MetrolistGroup/innertubex.

## Release Checklist

1. Move entries from `Unreleased` in `CHANGELOG.md` into a versioned section.
2. If the release intentionally changes public API, regenerate its baseline:

   ```bash
   ./gradlew desktopApiDump
   git diff -- api/desktop/innertubex.api
   ```

   Review the generated signatures and commit the API baseline with the source
   change. Do not edit the baseline manually. If no public API change is
   intended, any baseline diff must be investigated rather than accepted.
3. Run `./gradlew ktlintFormat allTests ktlintCheck apiCheck assemble publishToMavenLocal`.
4. Simulate the tagged JitPack publication locally:

   ```bash
   ./gradlew clean allTests ktlintCheck apiCheck assemble publishToMavenLocal \
       -PGROUP=com.github.MetrolistGroup \
       -PPOM_ARTIFACT_ID=innertubex \
       -PVERSION_NAME=vMAJOR.MINOR.PATCH \
       --no-build-cache --no-configuration-cache
   ```

5. Commit the release changes through normal review.
6. Create and push an annotated `vMAJOR.MINOR.PATCH` tag.
7. Wait for the GitHub release workflow to finish.
8. Request the tag from JitPack and verify its build log, POM, Android AAR,
   desktop JAR, sources, documentation, and Gradle module metadata.
9. Test the published coordinate in a clean consumer before announcing it.

JitPack exposes `GROUP`, `ARTIFACT`, and `VERSION` to `jitpack.yml`. The build
maps those values to Gradle publication properties so generated publications
are exposed by JitPack as
`com.github.MetrolistGroup.innertubex:innertubex:<tag>` for KMP consumers.
