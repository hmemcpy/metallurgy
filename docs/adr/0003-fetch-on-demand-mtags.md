# Fetch mtags on demand via IntelliJ background tasks

Metallurgy ships small (~5 MB) and downloads the Scala 3 presentation compiler (`mtags_3.x` + its dependencies) on first use per Scala 3 minor version, via IntelliJ's `Task.Backgroundable` + `ProgressManager` + the platform's built-in HTTP client. Downloaded artifacts are cached under `PathManager.getCachePath()/metallurgy/<scala-version>/`.

The alternative was bundling mtags for every supported Scala 3 minor directly in the plugin zip (~50 MB per minor version). We rejected that because:

- The plugin download size scales linearly with the number of supported Scala 3 minors; with fetch-on-demand, it stays constant.
- IntelliJ's platform already provides all the infrastructure we need (background tasks, progress UI, proxy/auth handling, the platform HTTP client). No coursier dependency, no extra weight.
- The cost (one-time first-use download of ~50 MB per Scala version) is paid once and amortised across every project on the user's machine that shares that Scala version.

The trade-off is offline support: users without cached artifacts and no network get a notification and a no-op plugin. We accept this — the bundled plugin keeps working offline regardless — and the cache makes the second-and-later opens instant.
