# Getting Nightbell into F-Droid, and keeping it there

Written after the first submission, which took four rounds of CI to land. Every
failure in here is one that actually happened, with the error text that appeared
and what fixed it. The point of writing it down is that none of the four were
guessable in advance and all four are cheap to check locally before publishing
anything.

Merge request: <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/45381>
Metadata lives at `metadata/me.river.nightbell.yml` in `fdroid/fdroiddata`.

---

## The decision that cannot be undone

F-Droid publishes an app one of two ways.

**They sign it.** They build from source and sign the result with the F-Droid key.
Simple, nothing required of the developer beyond a working build.

**You sign it.** They build from source, compare their output against the APK
attached to your GitHub release, and if the two match they publish *your* APK with
*your* signature on it. This is what lets a user move between the GitHub download
and the F-Droid listing without uninstalling and losing their monitors.

The second one is what this app does, and the reason it was worth the four rounds
is that the choice is one directional. From the F-Droid merge request template:

> Do note that if you don't enable reproducible build then the apk will be signed
> with our key so you can't enable it later.

So the work below is not optional polish. It is the price of the app remaining
installable from both places.

---

## The three release constraints

All three fail quietly. The app keeps working, the build keeps succeeding, and the
only symptom is that F-Droid stops being able to reproduce it, at which point the
fallback is their key and the door closes.

### 1. Build on the JDK their buildserver runs

Currently `openjdk 21.0.12`. Their container installs
`openjdk-21-jdk-headless 21.0.12+8-1~deb13u1` from Debian trixie. On Arch the same
upstream release is `jdk21-openjdk 21.0.12.u8`, and the two produce byte-identical
output, which was measured rather than assumed.

```bash
sudo archlinux-java set java-21-openjdk
java -version          # want 21.0.12
```

A different JDK gives R8 a different `classes.dex`. That alone would be enough, but
it also drags `assets/dexopt/baseline.prof` with it, because the baseline profile
is compiled out of `app/src/main/baselineProfiles/*.txt` into dex method indices.
`assets/dexopt/baseline.profm` holds no indices and stays identical, which is the
tell that the dex is the root cause and the profile is downstream of it.

To find their current JDK, read the `fdroid build` job log and grep for
`openjdk`. It is installed by `apt` at the top of the job, in the clear.

### 2. Commit the version bump before building the APK

AGP writes `META-INF/version-control-info.textproto` into the APK:

```
repositories {
  system: GIT
  local_root_path: "$PROJECT_DIR"
  revision: "bbb366584225f5318b743e62e94ead94fd8bedfe"
}
```

That revision is whatever `HEAD` was at build time. F-Droid checks out the commit
named in the metadata, so their copy records that commit. Build the APK before
committing the version bump and the two disagree on this one file while all 147
others match.

`local_root_path` is normalised to the literal `$PROJECT_DIR`, so the build
directory does not have to match. Only the revision does.

Three things therefore have to be the same hash: the commit the APK recorded, the
commit `metadata/me.river.nightbell.yml` names, and the commit the release tag
points at. The tag is not what F-Droid reads, since `commit` is a hash, but keeping
it aligned is what makes the release legible later. On 3.0.4 the artifacts APK was
committed before tagging, which put the tag one commit past the recorded revision,
and the tag had to be moved back.

### 3. Ship no signing block beyond the signature

AGP adds a Play dependency-metadata blob by default. F-Droid's scanner rejects it:

```
Problem: found extra signing block 'Dependency metadata'
CRITICAL: Found 1 problems in tmp/binaries/me.river.nightbell_25.binary.apk
```

Fixed in `app/build.gradle.kts`:

```kotlin
dependenciesInfo {
    includeInApk = false
    includeInBundle = false
}
```

Check it by parsing the signing block rather than trusting the setting. The block
IDs worth knowing:

| ID | Meaning |
| --- | --- |
| `0x7109871a` | APK Signature Scheme v2, expected |
| `0xf05368c0` | APK Signature Scheme v3 |
| `0x42726577` | padding, expected |
| `0x504b4453` | Play dependency metadata, must not be present |

`docs/fdroid-preflight.sh` prints them.

---

## The metadata file

Canonical form as submitted. Field order matters, and `fdroid rewritemeta` is the
authority on it, not the JSON schema's property order. They disagree:
`AllowedAPKSigningKeys` sits before `RepoType` in the schema and after `Builds` in
the canonical layout.

```yaml
Categories:
  - Development
  - Network Analyzer
  - Notification
  - System
License: Apache-2.0
AuthorName: Lukas Hrehor
AuthorEmail: lukas.hrehor@gmail.com
WebSite: https://nightbell.app
SourceCode: https://github.com/riveerxd/nightbell
IssueTracker: https://github.com/riveerxd/nightbell/issues
Changelog: https://github.com/riveerxd/nightbell/releases

AutoName: Nightbell

RepoType: git
Repo: https://github.com/riveerxd/nightbell.git
Binaries: 
  https://github.com/riveerxd/nightbell/releases/download/v%v/Nightbell-%v-release.apk

Builds:
  - versionName: 3.0.4
    versionCode: 26
    commit: bbb366584225f5318b743e62e94ead94fd8bedfe
    subdir: app
    gradle:
      - yes
    scandelete:
      - artifacts

AllowedAPKSigningKeys: 20d8abdaa8416a9a751e3ea144ef1523d7ddbaaeee9ec6be01d63a65574a70de

AutoUpdateMode: Version
UpdateCheckMode: Tags ^v.+$
CurrentVersion: 3.0.4
CurrentVersionCode: 26
```

Notes on the parts that are not obvious:

**There is a trailing space after `Binaries:`.** The URL is too long for one line,
so `rewritemeta` wraps it, and its wrapped form is `Binaries: ` followed by the
value indented on the next line. A file without that space fails `rewritemeta` with
a one-character diff. Most editors strip trailing whitespace on save, so write this
file with a tool that does not, or commit it through the API with
`encoding: base64`.

**No `Summary` or `Description`.** The template asks that localized text live in
the app repo instead, and it does, at `fastlane/metadata/android/en-US/`. Adding
them here would duplicate it.

**`scandelete: artifacts`** exists because the repo keeps released APKs and gzipped
R8 mapping files under `artifacts/`, and the scanner reports `.gz` files as
archives:

```
ERROR: Found gzip file archive at artifacts/mapping/mapping-1.5.0.txt.gz
ERROR: Could not build app me.river.nightbell: Can't build due to 17 errors while scanning
```

Nothing in `build.gradle.kts`, `settings.gradle.kts` or `gradle.properties`
references `artifacts/`, so deleting it before the scan cannot affect the build.
`scanignore` would also work and leaves the files in place.

**`gradle: [- yes]`** is literally the string `yes`. Beware validating this file
with PyYAML, which follows YAML 1.1 and turns `yes` into boolean `true`, then fails
the schema. F-Droid's `check-jsonschema` reads it as a string. The preflight script
disables PyYAML's boolean resolver to match.

**`commit` is a full 40 character hash, never a tag or a branch.** The schema
accepts a tag and the pipeline passes with one, so this is a review rule rather
than a mechanical one. From linsui on the merge request:

> Please don't use tag or branch in commit. Use the full commit hash instead.

Which makes sense: a tag can be moved, and this project moved one during this very
release when the artifacts commit ended up tagged by mistake. A hash cannot drift.

The hash to use is the commit the tag points at, and it has to be the same one the
APK recorded in `META-INF/version-control-info.textproto`, or the reproducible
build comparison fails on that file:

```bash
git rev-parse v3.0.4^{commit}
unzip -p artifacts/Nightbell-3.0.4-release.apk META-INF/version-control-info.textproto
```

Those two must print the same hash before the metadata is written.

**`AllowedAPKSigningKeys`** is the lowercase hex SHA-256 of the signing
certificate:

```bash
apksigner verify --print-certs artifacts/Nightbell-<version>-release.apk
```

---

## The CI pipeline

Nine jobs. Roughly 520 seconds end to end, dominated by the build.

| Job | ~time | What it actually checks |
| --- | --- | --- |
| `schema validation` | 72s | The `.yml` against `schemas/metadata.json` |
| `fdroid lint` | 150s | Metadata sanity |
| `fdroid rewritemeta` | 110s | Canonical formatting. Prints the exact diff it wants |
| `fdroid build` | 390s | Builds from source, then compares to your `Binaries` APK |
| `check apk` | 150s | Scans the APK. Only runs if the build passed |
| `check source code` | 150s | Scans the source tree |
| `checkupdates` | 175s | Exercises `UpdateCheckMode` against real tags |
| `git redirect` | 68s | URL checks |
| `tools check scripts` | 120s | Their own tooling |

Two things worth knowing about reading these:

**A pipeline with zero jobs that says "failed" is not about your file.** The first
push to a fork can produce one of these. `yaml_errors: null`, `started_at: null`,
and `finished_at` equal to `created_at` means it died at creation, usually runners.
Check job count before debugging anything.

**`check apk` is skipped when `fdroid build` fails**, so a green-looking run can
still be hiding a scan failure. It only appeared on the fourth round for that
reason.

Poll it without clicking:

```bash
source ~/.config/gitlab/nightbell.env
P=<pipeline id>
curl -sS -H "PRIVATE-TOKEN: $GITLAB_TOKEN" \
  "https://gitlab.com/api/v4/projects/riveerxd%2Ffdroiddata/pipelines/$P/jobs" |
  python3 -c "import sys,json;[print('%-24s %s' % (j['name'],j['status'])) for j in json.load(sys.stdin)]"
```

Job logs need `-L`, they redirect to a CDN:

```bash
curl -sSL "https://gitlab.com/riveerxd/fdroiddata/-/jobs/<job id>/raw" |
  sed 's/\x1b\[[0-9;]*[A-Za-z]//g'
```

---

## The trick that makes verification debuggable

**A failed `fdroid build` uploads its own APK as a job artifact.** That is the
whole game, because it turns "publish and hope" into a local diff.

```bash
curl -sSL -o art.zip "https://gitlab.com/riveerxd/fdroiddata/-/jobs/<job id>/artifacts/download"
unzip -q art.zip -d art          # art/tmp/me.river.nightbell_<versionCode>.apk

mkdir fd mine
unzip -q art/tmp/me.river.nightbell_26.apk -d fd
unzip -q artifacts/Nightbell-3.0.4-release.apk -d mine
diff -r --brief fd mine | grep -viE "META-INF/(CERT|.*\.(RSA|SF|DSA|EC))|MANIFEST.MF"
```

An empty diff means the next run verifies. The APK has 148 files; both sides should
report 148.

Do not compare whole-APK hashes. Zip metadata differs between any two builds even
when every file inside matches, which is exactly why their check is per-file.

---

## Releasing a new version

Order matters. Steps 2 and 3 are the ones that are easy to get backwards.

1. **Set the JDK.** `sudo archlinux-java set java-21-openjdk`, confirm `21.0.12`.
2. **Bump `versionCode` and `versionName`** in `app/build.gradle.kts`, write
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`, and **commit**.
3. **Build.** `./gradlew clean :app:assembleRelease`
4. **Preflight.** `docs/fdroid-preflight.sh` checks all three constraints.
5. **Copy to `artifacts/`**, tag the *version bump* commit, push both.
6. **Create the GitHub release** with the APK attached. The `Binaries` URL pattern
   is `.../download/v%v/Nightbell-%v-release.apk`, so the tag must be `v<version>`
   and the asset `Nightbell-<version>-release.apk`.
7. **Commit the artifacts APK** afterwards. It lands past the tag, which is correct:
   the tag must stay on the commit the APK recorded.
8. **Update the fdroiddata metadata**: `versionName`, `versionCode`, `commit` as a
   full hash, `CurrentVersion`, `CurrentVersionCode`.

Because `AutoUpdateMode: Version` and `UpdateCheckMode: Tags ^v.+$` are set,
F-Droid picks up later tags on its own once the first build lands, so step 8 is
mostly a first-submission concern.

### Updating the metadata without a browser

```bash
source ~/.config/gitlab/nightbell.env
B64=$(base64 -w0 metadata.yml)
python3 - "$B64" > payload.json <<'PY'
import json,sys
json.dump({"branch":"master","content":sys.argv[1],"encoding":"base64",
           "commit_message":"Nightbell: <version>"}, sys.stdout)
PY
curl -sS -X PUT -H "PRIVATE-TOKEN: $GITLAB_TOKEN" -H "Content-Type: application/json" \
  --data @payload.json \
  "https://gitlab.com/api/v4/projects/riveerxd%2Ffdroiddata/repository/files/metadata%2Fme.river.nightbell.yml"
```

`encoding: base64` is what preserves the trailing space after `Binaries:`.

A GitLab token needs the `api` scope to create merge requests. The fine-grained
token UI does not obviously express that, so a classic token is less work. Keep the
expiry short and revoke it when the merge request is done.

---

## The four failures, in order

Kept as a lookup table, since the error strings are searchable.

| Error | Cause | Fix |
| --- | --- | --- |
| `Can't build due to 17 errors while scanning`, `Found gzip file archive at artifacts/mapping/*.txt.gz` | Committed R8 mapping files read as archives | `scandelete: [artifacts]` |
| `rewritemeta` diff moving `scandelete` below `gradle`, then `AllowedAPKSigningKeys` below `Builds` | Canonical field order | Paste what the diff prints |
| `rewritemeta` diff of `-Binaries:` / `+Binaries: ` | Missing trailing space on a wrapped value | Commit via API with base64 |
| `compared built binary to supplied reference binary but failed`, `classes.dex` and `baseline.prof` differ | JDK mismatch, 21.0.11 against their 21.0.12 | Match the JDK, re-release |
| Same error, only `version-control-info.textproto` differs | APK built before the release commit existed | Rebuild at the tagged commit |
| `found extra signing block 'Dependency metadata'` | AGP default for Play reporting | `dependenciesInfo { includeInApk = false }` |
| Review comment: `Please don't use tag or branch in commit` | A tag in `commit`. Passes CI, fails review | Use the full 40 character hash |

---

## IzzyOnDroid

Faster than F-Droid by a wide margin, days rather than months, and it serves the
published APK directly so there is no reproducibility requirement and no signing
divergence. Worth having regardless of F-Droid's timeline.

**Status: not submitted.** Issue
[#449](https://codeberg.org/IzzyOnDroid/repodata/issues/449) was filed as a plain
issue and closed within nine minutes:

> Please file a new issue and select the App Inclusion request template to properly
> request your app. Do fill out AI usage there as well.

### How to refile

Use the template picker, not a blank issue:
<https://codeberg.org/IzzyOnDroid/repodata/issues/new/choose> and choose **App
Inclusion Request**. The template is a Forgejo issue form, so it is a set of fields
rather than one body to paste. Its source, if you want to read it first, is
`.forgejo/issue_template/app-inclusion-request.yaml` in that repo.

Title format is `[AppRequest] <App Name>`, so `[AppRequest] Nightbell`.

Fields, with what applies here:

- **Guidelines checkboxes.** All four apply: author, complies with the
  [inclusion policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/), not
  already listed, Fastlane folder present.
- **Source code**, required: `https://github.com/riveerxd/nightbell`
- **Another app store**, optional: leave blank until the F-Droid MR merges, then it
  can point there.
- **License**, required: `Apache-2.0`
- **Categories**, required, their own list rather than F-Droid's: `System` and
  `Development` both exist there.
- **Summary** and **Description**, both required, even though the repo has Fastlane
  metadata. Take them from `fastlane/metadata/android/en-US/`.
- **Build instructions**, required. Command line, from a clean checkout:
  ```
  git clone https://github.com/riveerxd/nightbell
  cd nightbell && git checkout v3.0.4
  ./gradlew :app:assembleRelease
  ```
  Worth adding that a tree with no signing material produces
  `app-release-unsigned.apk` rather than failing, because both halves of the
  signing config are guarded behind `if (keystoreProps.isNotEmpty())`.
- **Further notices**: mention that #449 was yours and that this replaces it.

### The AI usage section

Required, and the reviewer asked for it explicitly. The dropdown is
`None / Minimal / Moderate / Substantial / Dominant`, with optional free-text for
which tools and what they did, plus two accountability checkboxes about reviewing
outputs and running manual tests.

This one is a statement about how the app was built and only the author can answer
it. Answer it straight. Understating it is the kind of thing that gets a listing
pulled later, which is a far worse outcome than any honest answer to a dropdown.

### After it lands

Several drafts carry deliberate placeholders that go stale the moment a listing
exists, and they are wrong rather than merely dated once it does:

- `docs/growth-prep/content/reddit/r-selfhosted.md`, the bracketed F-Droid status
- `docs/growth-prep/content/youtube/promo-video-metadata.md`, same
- `docs/growth-prep/content/README.md` item 1, and `08-submission-matrix.md`
- `README.md` wants an install badge
- `02-fdroid-metadata.yml`'s sibling drafts under `docs/growth-prep/distribution/`

`docs/GROWTH.md` also holds the launch order to a store listing being live before
the Show HN and Reddit posts go out, so this is the gate on that whole phase.
