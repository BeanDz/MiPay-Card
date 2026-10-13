# MiPay Card

A small Android app that replaces Xiaomi Smart Card backgrounds with your photos.
The app uses Kotlin, Compose Material 3, and libsu. Its interface uses Chinese.

The app reads wallet-provider metadata and lists cards that qualify for the swiping screen.
When their images are unavailable, cards remain visible.
It does not show card numbers or balances.

The verified wallet profile is `com.miui.tsmclient` version `26.08.28.4.f`, version code `4180`.
Other versions can show card records, but the app disables replacement until their image paths receive verification.
The [wallet analysis](docs/wallet-analysis.md) describes every card family's entry routes, image requests, cache identities, and rebuild design.

## Device requirements

The app requires:

- Android 10 or later with Xiaomi Smart Card / Mi Pay.
- A root manager that provides `su`, such as KernelSU or Magisk.
- Root permission for `dev.doji.mipaycard` in the root manager.

`adb root` grants root to the ADB shell. The installed app requires its own `su` permission.
The app uses [libsu](https://github.com/topjohnwu/libsu) with the global mount mode for private wallet-cache access.
[KernelSU supports this mode](https://github.com/tiann/KernelSU/blob/main/userspace/ksud/src/su.rs).

## Use the app

1. Open each desired card on the Mi Pay swiping screen to populate its cache.
2. Open MiPay Card and grant root permission.
3. Select a card with an enabled change button.
4. Select a photo.
5. Examine the crop preview.
6. Apply the image.
7. Reopen the Mi Pay swiping screen.

The app changes the background of the swiping screen. Other screens can use different images.
Wallet text, logos, and foreground decorations remain separate from the background.
Native resources, theme backgrounds, animated art, shared sources, and ambiguous identities do not permit independent replacement.
A cache from another screen can supply a preview without authorizing replacement.
Bank thumbnails require the swiping activity's actual resource density, which can differ from the system density.
Open the wallet home or card list, then refresh the editor once to capture that configuration.
The verified configuration survives wallet shutdown and editor restart.
A device reboot, wallet update, or relevant display change requires a fresh reading from the wallet.

The first replacement saves the original image in private app storage.
Later replacements preserve that original. Existing originals from the previous app implementation remain valid.
The app preserves dimensions, owner, mode, and SELinux context during writes.
Before each write, it validates the current card, cache target, and image fingerprint.
A failed write triggers rollback in the same root job.

The card's overflow menu contains the restore action.
After restore, the original backup remains available.
The wallet can download its source again after cache eviction or an update.
The app does not change the server image or NFC credentials.

Before you clear app data or uninstall the app, restore the original images.
Clearing app data or uninstalling the app deletes its private backups.

## Build and install

Install [mise](https://mise.jdx.dev/getting-started.html) and the [Android SDK command-line tools](https://developer.android.com/tools).
Set `ANDROID_HOME` to your SDK directory.
Accept the Android SDK licenses with `sdkmanager --licenses`.

Run these commands from the repository root:

```sh
mise trust
mise install
mise run setup
mise run build
```

The setup task installs the required SDK packages and writes the local SDK path.
The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

Connect one Android device with USB debugging enabled. Then run:

```sh
mise run install
```

The pinned tools and tasks live in [mise.toml](mise.toml).
The app requires no network permission and downloads no images.

## Development

Run the project checks:

```sh
mise run format
mise run check
mise run alint
```

The check task runs ktlint, Android Lint, and JVM tests.
The tests use synthetic metadata, URLs, and temporary cache files.
They cover provider output, swiping eligibility, source selection, cache keys, and root-write transactions.

The pinned `alint` 0.18.0 tool and [alint configuration](.alint.yml) check repository hygiene without an AI model.

Private device artifacts belong in ignored `artifacts/` or temporary storage.
They must remain outside version control.
