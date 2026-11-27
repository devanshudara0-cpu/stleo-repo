# AGENTS.md — STxLEO OTT & Provider Ecosystem

> **Document Status**: Production Active  
> **Last Updated**: November 2026  
> **Target Platform**: Android ARM64 (Linux Kernel 6.1+, Native Userspace)  
> **Target Repositories**:  
> - `stleo-repo` (`/storage/emulated/0/12/stleo-repo`, branch `main`)  
> - `STxLEO_OTT_Source` (`/storage/emulated/0/12/STxLEO_OTT_Source`, branch `main`)  
> - `stleo-youtube-repo` (`/storage/emulated/0/12/stleo-youtube-repo`, branch `main`)  
> - `moviebox-tg-bot--main` (`/storage/emulated/0/12/moviebox-tg-bot--main`)

---

## 1. Project Overview & Ecosystem Architecture

STxLEO OTT is a high-performance, zero-trace Android OTT streaming application paired with a modular plugin provider network. The ecosystem consists of:

```
/storage/emulated/0/12/
├── AGENTS.md                         # Ecosystem master rules, history, and roadmap
├── STxLEO_OTT_Source/                # Android OTT Client (Kotlin, ExoPlayer, Jetpack)
│   ├── app/                          # Core UI, CS3IPlayer, PluginManager, ViewModels
│   └── build.gradle.kts              # Application build configuration
├── stleo-repo/                       # Provider Plugin Ecosystem (DEX .leo packages)
│   ├── .github/workflows/            # GitHub Actions CI/CD (Google D8 DEX compilation)
│   ├── builds/                       # Compiled .leo binary archives
│   ├── common/                       # LeovonixExtractors, DomainManager, shared utilities
│   ├── providers/                    # 10 Official Providers (Kotlin source)
│   │   ├── BollyFlix/
│   │   ├── FourKHDHub/
│   │   ├── HDhub4u/
│   │   ├── HDMovie2/
│   │   ├── HindMoviez/
│   │   ├── MoviesDrive/
│   │   ├── MoviesMod/
│   │   ├── MultiMovies/
│   │   ├── UHDMovies/
│   │   └── VegaMovies/
│   ├── domains.json                  # Dynamic remote mirror/domain resolution
│   ├── plugins.json                  # Plugin manifest, versioning & SHA256 hashes
│   └── repo.json                     # Repository metadata
├── stleo-youtube-repo/               # YouTube provider plugin repository
└── moviebox-tg-bot--main/            # Telegram companion bot
```

---

## 2. Platform & Agent Execution Rules (STRICT)

All AI agents working on this project **must strictly adhere** to these environment constraints:

1. **Native Android Environment (No Bubblewrap / Desktop Sandboxing)**:
   - The execution host is native Android ARM64 (`aarch64`).
   - Standard Linux desktop `bwrap` (bubblewrap) is **not supported** by the Android kernel and SELinux (`libloader.so: invalid argument`).
   - In terminal/agent tools, **always execute commands with sandbox bypass enabled (`BypassSandbox: true`)**.
2. **Shared Storage `noexec` Restriction**:
   - `/storage/emulated/0` is mounted with the `noexec` flag.
   - Executing binaries or scripts directly (`./build.sh`, `./script.py`) will fail with `Permission denied` (exit code 126).
   - **Always invoke scripts with explicit interpreters**:
     ```bash
     bash script.sh
     python3 script.py
     sh script.sh
     ```
3. **No Redundant Environment Exports**:
   - Antigravity preconfigures `$PATH` (`$PREFIX/bin:/system/bin`), `$HOME`, and `$PREFIX` for every shell invocation.
   - **Do not** prepend `export PATH=...` or `export HOME=...` to commands. Execute binaries (`git`, `python3`, `pkg`, `bash`) directly.
4. **Git Operations on Android Shared Storage**:
   - Git on Android shared storage will fail with `fatal: detected dubious ownership in repository`.
   - Always run Git commands with:
     ```bash
     export GIT_CONFIG_NOSYSTEM=1
     git config --global --add safe.directory "<path>"
     ```
5. **Zero-Branding & Privacy Standard**:
   - Absolutely **zero references** to legacy project names (`lagradost`, `cloudstream`, `phisher98`) are permitted in user-facing code, strings, DEX binaries, or repository JSONs.
   - All package names and namespaces must remain strictly under `com.leovonix` and `com.stleo`.
6. **Port Safety**:
   - Core API / Webview ports `45157` and `45158` are reserved. Dev servers must use `3000`, `5173`, `8000`, or `8080`.

---

## 3. History of Completed Fixes

### Fix 1: Legacy DEX Architecture Crash & GitHub Actions D8 Pipeline
- **Problem**: Legacy `.leo` plugin packages contained binary references to `com.lagradost.*` and `com.phisher98.*`. When loaded by `PluginManager.kt:682`, the app crashed with `NoClassDefFoundError` / `ErrorLoadingException`, rendering all 10 providers completely unusable.
- **Root Cause**: Old plugin builds depended on upstream Gradle plugins that injected legacy CloudStream classes into DEX bytecode.
- **Solution**:
  - Implemented `.github/workflows/build_plugins.yml` in `stleo-repo` using pure Google D8 bytecode compilation.
  - Linked all 10 providers against clean `leovonix-plugin-sdk.jar`.
  - Built an automated CI step that calculates SHA-256 checksums, validates that zero forbidden tokens (`lagradost`, `phisher98`) exist, and updates `plugins.json`.
  - Upgraded plugin builds through v102 -> v112 -> v113. All 10 providers load cleanly in the OTT client.

### Fix 2: Disable Repository URL Clipboard Copying on Long-Press
- **Problem**: User reported that holding down/long-pressing an extension repository inside the app copied the raw repository URL (`https://raw.githubusercontent.com/devanshudara0-cpu/stleo-repo/main/repo.json`) to the Android clipboard. The user required this behavior removed so external parties cannot copy the private endpoint.
- **Root Cause**: `PluginRepositoryFragment.kt` and `RepositoryViewModel.kt` had an active `setOnLongClickListener` attached to repository card views that copied `repo.url` into Android's `ClipboardManager`.
- **Solution**:
  - Disabled the clipboard copy handler on repository long-click in `PluginRepositoryFragment.kt`.
  - Verified and committed under `4a14cbf` (*fix(extensions): disable long-press repository clipboard copy*).

### Fix 3: Third-Party Donation Popups Blocked
- **Problem**: Annoying third-party donation popups ("physico" / external dialogs) were appearing during app startup and usage.
- **Root Cause**: Third-party dialog hooks in `MainActivity.kt` triggered donation reminders.
- **Solution**:
  - Stripped out and blocked external donation popups and unverified dialog callers in `MainActivity.kt`.
  - Bumped `versionCode` to 16 (commit `c4c0a05`).

### Fix 4: Full Zero-Branding Codebase Clean
- **Problem**: Residual legacy branding existed across drawables, class names, typealiases, TorrServer stubs, and SoundCloud NewPipe bindings.
- **Solution**:
  - Deleted legacy CloudStream drawables, typealiases, and SafeFile wrappers.
  - Replaced with clean Leovonix architecture (commits `7206c52` and `0835d88`).

### Fix 5: Dynamic Domain Mirror Synchronization
- **Problem**: Multiple streaming sites changed their domain names (e.g. `moviesmod.fi`, `hdhub4u.cl`, `moviesdrive.cyou`), leading to HTTP 404 / 521 connection timeouts.
- **Solution**:
  - Updated `domains.json` in `stleo-repo` to live mirror endpoints:
    - 4KHDHub: `https://4khdhub.one`
    - HDhub4u: `https://new17.hdhub4u.cl`
    - UHDMovies: `https://uhdmovies.ceo`
    - HDMovie2: `https://hdmovie2a.asia`
    - HindMoviez: `https://v5.hindmovie.im`
    - MoviesDrive: `https://new5.moviesdrive.cyou`
    - BollyFlix: `https://new1.bollyflix.tours`
    - MoviesMod: `https://moviesmod.free`
    - VegaMovies: `https://vegamovies.latino`
    - MultiMovies: `https://m1.multimovies.courses`
  - Integrated `DomainManager.getDomain(providerKey, fallbackUrl)` into all providers to fetch live domains dynamically without requiring APK updates.

### Fix 6: PlayDrive Stream Unsealing & HubCloud Resolvers
- **Problem**: Video players on VegaMovies and 4KHDHub were failing to play due to JS-based stream obfuscation.
- **Solution**:
  - Implemented `unsealPlayDriveStream` in `LeovonixExtractors.kt` using XOR decryption key `pa4u-stream-seal-v1` to extract `.m3u8` master playlists from `window.__PA_PLAY__`.
  - Added HubCloud multi-hop redirect resolution (`hubcloud.mov` -> `gamerxyt.com/hubcloud.php`).

---

## 4. Deep Technical Analysis: Current User-Reported Bugs

The user reported four specific issues during playback and browsing:

### Issue A: HindMoviez plays a 2-3 minute tutorial video ("How to download from Hindmoviez") instead of the movie/series
- **Symptoms**: Selecting any movie or web series on HindMoviez plays an MP4 video explaining how to download from the site.
- **Root Cause**:
  1. Posts on `v5.hindmovie.im` contain an instructional anchor: `<a href="https://disk.yandex.com/i/f2t6k8d1iLQg6Q">How To Download</a>`.
  2. In `LeovonixExtractors.kt:100-109`, `isCandidateLink()` did not blacklist `disk.yandex.com` or `yadi.sk`, nor did it filter out links containing `how to download` / `tutorial`.
  3. In `HindMoviezPlugin.kt:174-179`, if no immediate stream buttons are matched, it called `LeovonixExtractors.collectCandidateLinks()`, which scraped the entire page body and selected the Yandex tutorial link.
  4. Furthermore, movie posts on HindMoviez host their download links behind `https://mvlink.blog/<id>`. In `HindMoviezPlugin.kt`, `mvlink.` pages were only fetched and parsed inside the TV series branch (`epGrouped.isEmpty()`), but were completely ignored in the movie branch!

### Issue B: 4KHDHub throws "Remote Error" on playback
- **Symptoms**: Tapping play on 4KHDHub movies immediately fails with "Remote error".
- **Root Cause**:
  1. `CS3IPlayer.kt:687-693` catches ExoPlayer `PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS (2004)` and connection failures and displays `R.string.remote_error`.
  2. 4KHDHub streams resolve via `greenmotors.club` -> `hubcloud.mov` -> `gamerxyt.com/hubcloud.php` to direct CDN links on Google User Content (`video-downloads.googleusercontent.com`) and Cloudflare R2 (`*.r2.cloudflarestorage.com`, `*.r2.dev`).
  3. In `LeovonixExtractors.kt:344-354`, `emitDirectLink` sets `this.referer = referer`. For 4KHDHub, `referer` was set to `https://gamerxyt.com/...`.
  4. When ExoPlayer requests the CDN file with `Referer: https://gamerxyt.com/...`, Google CDN and Cloudflare R2 immediately reject the request with **HTTP 403 Forbidden**. Google and R2 video streams require an **empty referer** or no referer header.

### Issue C: VegaMovies shows "Link not found"
- **Symptoms**: Opening VegaMovies posts fails to display any playable links ("Link not found" / "No Links Found").
- **Root Cause**:
  1. In `VegaMoviesPlugin.kt:143`, the content selector looked for `.entry-content, .page-body, main`. VegaMovies updated its layout to `<div class="page-body">` with download buttons wrapped in custom styling.
  2. VegaMovies download buttons point to `nexdrive.beer/genxfm...`, which leads to `fastdl.beer/embed.php?download=...` and `vcloud.my/...`.
  3. On `vcloud.my/...`, the actual download link is shielded by a double base64 script: `window.location.href = atob(atob('...'))`, redirecting to `https://vcloud.my/.../?token=...`. That tokenized page exposes the real Cloudflare R2 / PixelDrain download streams.
  4. Because `vcloud.my`'s double `atob` redirect was not unwrapped, no direct video link was returned to ExoPlayer, triggering `R.string.no_links_found_toast`.

### Issue D: Other servers stuck indefinitely on loading / spinner freeze
- **Symptoms**: When selecting other servers (HDMovie2, UHDMovies, MoviesMod, MoviesDrive), tapping a movie does not show an error, but stays permanently on the loading spinner.
- **Root Cause**:
  1. When a title is opened, `load()` parses the page and stores all candidate URLs into the payload.
  2. In `HDMovie2`, `UHDMovies`, `MoviesMod`, and `MoviesDrive`, `load()` was extracting all `<a>` tags from the entire page body — collecting between 100 to 240+ URLs (including category tags, actor profiles, related post links, footers).
  3. In `loadLinks()`, `LeovonixExtractors.resolveLinks` was called on each URL concurrently.
  4. Dozens of concurrent multi-hop network requests overwhelmed the OkHttp connection pool, hit cloud rate limiters (Cloudflare / DDoS-Guard), and caused coroutine timeouts. The player UI stayed locked waiting for responses.

---

## 5. Concrete Action Plan & Pending Implementation Steps

### Step 1: Update `LeovonixExtractors.kt` in `stleo-repo`
1. **Tutorial & Garbage Blacklist**:
   - Add `disk.yandex.com`, `yadi.sk`, `yandex.ru`, and text patterns `how to download`, `kaise download kare`, `tutorial` to `isCandidateLink()`.
2. **Referer Stripping for CDN Streams (Fix for 4KHDHub Remote Error)**:
   - In `emitDirectLink()`, check if the stream URL belongs to:
     - `video-downloads.googleusercontent.com`
     - `*.r2.cloudflarestorage.com`
     - `*.r2.dev`
     - `*.workers.dev`
     - `pixeldrain.com/api/file/`
   - If true, force `referer = ""` so ExoPlayer does not transmit the intermediary referer header that causes HTTP 403 Forbidden.
3. **Double Base64 (`atob(atob)`) Unwrapping for `vcloud.my`**:
   - In `resolveRedirect()`, detect `vcloud.my` pages containing `atob(atob('...'))` and evaluate/decode the redirect target to follow the tokenized page.

### Step 2: Update `HindMoviezPlugin.kt` in `stleo-repo`
1. In `load()`, explicitly ignore all tutorial anchors matching `how to download` or `yandex.com`.
2. Expand movie handling: if a movie post contains `mvlink.blog/<id>`, fetch and parse the `mvlink` page to extract direct FastCloud / HubCloud / GDFlix buttons, exactly as done for TV series.

### Step 3: Update `VegaMoviesPlugin.kt` in `stleo-repo`
1. Fix entry content selectors to accurately target `<div class="page-body">`.
2. Extract all `nexdrive.beer` and `playdrive.vip` links.
3. Unwrap `nexdrive.beer` and feed `vcloud.my` / `fastdl.beer` URLs into `LeovonixExtractors`.

### Step 4: Link Payload Optimization across All Providers (Fix for Loading Freeze)
In `FourKHDHubPlugin.kt`, `HDhub4uPlugin.kt`, `MoviesDrivePlugin.kt`, `MoviesModPlugin.kt`, `UHDMoviesPlugin.kt`, and `HDMovie2Plugin.kt`:
1. Restrict candidate link harvesting strictly to download sections (e.g. `.download-links`, `p:has(a[href*="hubcloud"])`, `a.btn`).
2. Cap candidate URLs to a maximum of 10 links per movie/episode instead of dumping 100+ URLs.

### Step 5: Version Bump, CI Build & Verification
1. Increment plugin version from `113` to `114` in `.github/workflows/build_plugins.yml` and `plugins.json`.
2. Commit all changes to `main` branch of `stleo-repo` and push to GitHub.
3. Monitor GitHub Actions build run to ensure all 10 `.leo` packages compile cleanly with zero forbidden tokens.
4. Notify user to refresh/update plugins in the STxLEO OTT app.

---

## 6. Verification Checklist

- [x] All 10 providers compiled with Google D8 into clean `.leo` packages (v113 active).
- [x] Repository URL copy disabled on long press (`STxLEO_OTT_Source`).
- [x] Third-party Physico donation dialogs suppressed (`STxLEO_OTT_Source`).
- [x] `domains.json` updated with live mirror endpoints.
- [x] Tutorial links (`disk.yandex.com`) blacklisted in `LeovonixExtractors.kt`.
- [x] Referer stripped on Google CDN & Cloudflare R2 links in `emitDirectLink`.
- [x] `vcloud.my` double atob decoded in `LeovonixExtractors.kt`.
- [x] `HindMoviezPlugin.kt` movie `mvlink` extraction enabled.
- [x] `VegaMoviesPlugin.kt` `.page-body` and `nexdrive.beer` parser active.
- [x] Candidate links capped at 10 per provider to stop player loading freezes.
- [x] `DomainManager` auto-discovery & live mirror failover + DoH fallback in `RequestsHelper.kt`.
- [x] Zero-delay instant playback + cross-server fallback (`ServerFallbackHelper.kt`) in `STxLEO_OTT_Source`.
- [x] Added 3 new fast Hindi/HD providers (`RogMovies`, `Movies4u`, `ZinkMovies`).
- [ ] v114 compiled and published on GitHub Actions CI.
