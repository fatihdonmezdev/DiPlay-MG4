# OTA Updates

How the in-app updater works on the MG4 head unit, why the last step is manual,
and what to check when it misbehaves.

Code: [`common/src/main/java/com/shilapi/xcertplay/OtaUpdater.kt`](../common/src/main/java/com/shilapi/xcertplay/OtaUpdater.kt)

## What the user sees

Two entry points, both in `DiPlayActivity`:

- The **↻** button in the home header.
- **Check for updates** in the About page.

A check also runs once at startup. It is silent unless an update is waiting — an
"up to date" toast on every launch would be noise, and a failed check usually
just means the car has no network yet.

When an update exists the driver gets a confirmation dialog, then a progress
dialog for the download, then the Downloads app opens so they can tap the file
to install it.

## The flow

1. **Check** — `GET https://api.github.com/repos/andrew-snape/DiPlay-MG4/releases?per_page=20`.
   Drafts and prereleases are skipped. A release qualifies when its tag parses to a
   higher version than `versionName` and it carries an asset named `MG4CPlay-*.apk`
   whose GitHub `digest` field is a valid SHA-256.
2. **Download** — `DownloadManager` writes to public Downloads
   (`/storage/emulated/0/Download/`). Any previous copy of the same name is deleted
   first so the download does not land as `…-1.apk`.
3. **Verify** — two independent checks, both must pass:
   - SHA-256 of the file against the `digest` GitHub reports for the asset.
   - The archive's package name and signing certificate against the installed app.
     A platform-signed APK of a *different* app would pass a certificate check alone,
     so the package name is compared too.
4. **Install** — attempts `pm install -r -f -d -t`, then a `PackageInstaller`
   session. Both are refused on this head unit (see below), so in practice it
   falls through to step 5.
5. **Hand off** — opens the Downloads app (`DownloadManager.ACTION_VIEW_DOWNLOADS`)
   and shows a toast naming the file. The driver taps it; the system installer
   takes over.

## Why the install is not automatic

Three routes were tried on the device, all blocked by the platform:

| Route | Result |
| --- | --- |
| `pm install <path>` from the app cache | `system_server has no access to read file context u:object_r:app_data_file` |
| `pm install <path>` from public Downloads | same denial, context `u:object_r:sdcardfs` |
| Copy to `/data/local/tmp` first | `Permission denied` — the directory is `shell:shell`, mode `771`, and we are uid 1000 |
| `PackageInstaller` session | `Not allowed to install non-system apps on internal storage` (OEM check in `PackageHelper.resolveInstallVolume`) |

`pm install` runs inside `system_server`, and SELinux denies that domain every
directory the app can write to. The one directory it *can* read —
`/data/local/tmp` — the app cannot write to. That is exactly why the manual
`adb push /data/local/tmp` route works: adb runs as the shell user, which owns
that directory.

`-f` ("install on internal flash") does defeat the OEM volume policy, which is
why it is still in the command — it is the flag that makes the manual install
work. It just cannot help when the file itself is unreadable.

DiAuto-MG4 hit the same wall and settled on the same fallback.

> If a future firmware grants `system_server` read access to Downloads, step 4
> starts succeeding on its own and the fallback stops being reached. Nothing needs
> to change for that to happen.

## Publishing an update so OTA picks it up

See [`RELEASING.md`](./RELEASING.md). The parts OTA depends on:

- The release is on **`andrew-snape/DiPlay-MG4`**, not a prerelease, not a draft.
- The tag parses to a higher version than what is installed (`v0.2.9-mg4.54` → `0.2.9.54`).
- Exactly one asset named `MG4CPlay-v<version>.apk`.
- The APK is signed with the same platform key as the installed build, or the
  signature check rejects it after download.

GitHub computes the `digest` itself when the asset is uploaded; there is no
separate hash file to publish.

## Troubleshooting

Watch the device while testing:

```bash
adb logcat -c
adb logcat -v time | grep -E "DiPlay_OTA|Update rejected|Update verified"
```

| Symptom | Cause |
| --- | --- |
| "Update rejected: SHA-256 mismatch" | The release asset was replaced after GitHub recorded its digest. Re-upload with `--clobber` and re-check; verify `gh api …/releases/tags/<tag> --jq '.assets[0].digest'` matches `shasum -a 256` of the local file. |
| "Update rejected: Signing certificate mismatch" | APK signed with a different key. Re-sign with `tools/platform.pk8`. |
| "Update rejected: Package name mismatch" | Wrong APK attached to the release — check `aapt dump badging` output. |
| Check says up to date when a newer release exists | The release is a draft/prerelease, the asset name does not match `MG4CPlay-*.apk`, or the tag does not parse higher. Version comparison is numeric per component: `mg4.9` < `mg4.10`. |
| "Update rejected: ota_failed …" with no network activity | The car has no internet. The head unit reaches GitHub only through a tethered phone or the car's own connection. |
| Nothing happens after download | Expected on current firmware: the Downloads app should open. If it does not, the log shows `could not open Downloads` and the status line names the file to install by hand. |

## Testing an OTA end to end

OTA only triggers when the installed version is *older* than the release, so test
with two builds:

1. Publish the new version as a release (say `mg4.54`).
2. Build the previous version number from the **same code** and install that on the
   device, so the device has a working updater but an older version string:

   ```bash
   # temporarily set versionCode/versionName one step back in mobile/build.gradle.kts
   ./gradlew :mobile:assembleStandaloneDebug
   # sign, then:
   adb push MG4CPlay-v0.2.9-mg4.53.apk /data/local/tmp/update.apk
   adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
   # restore the version number afterwards
   ```
3. Clear stale downloads so the test starts clean:

   ```bash
   adb shell "rm -f /storage/emulated/0/Download/MG4CPlay*.apk"
   ```
4. Launch the app and press **↻**.

Testing with the *published* build installed will only ever report "up to date".
