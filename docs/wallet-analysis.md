# Xiaomi Smart Card analysis

## Scope and evidence

This analysis targets `com.miui.tsmclient` version `26.08.28.4.f`, version code `4180`.
The connected device APK and the existing analysis APK have the same SHA-256:

```text
6fa01241399c90ba27e1f2bebcfe3e5aa0ef5748d611b070d4e3e33b1d0129ea
```

The APK requires Android 10 and targets Android 16.
The findings describe this APK. They do not establish compatibility with other wallet versions.
The analysis uses Android SDK `apkanalyzer` output, the manifest, resources, and Dalvik bytecode.
Obfuscated names in this document identify symbols from this APK only.

Private APKs, databases, screenshots, and images remain outside version control.
The document includes no card numbers, balances, account records, or root-manager records.
The synthetic unit fixtures use `example.com` addresses and invented card identities.

## The underlying problem

MiPay Card must identify the image that the wallet actually shows on its swiping screen.
A rectangular image in the cache does not establish its card identity or its screen.
The same card can use different images on its catalog, detail, and swiping screens.
Multiple physical cards can also share one image source.

The intended outcome is a small card-image editor with reliable enumeration, explicit replacement eligibility, and recoverable writes.
It changes image-cache bytes only. It does not change NFC credentials, secure-element data, card records, or balances.

## Application structure

The wallet uses card entities, content providers, card-list models, Android resources, and Glide image requests.
The swiping screen combines background art with separate text, logos, foregrounds, and other decorations.
A background replacement does not remove every visible element on that screen.

The relevant evidence forms this chain:

| Stage | APK evidence | Meaning |
| --- | --- | --- |
| Storage access | `TSMProvider`, `CacheContract`, `u5.a.d` | Read the cache provider with the `key:value` projection |
| Entity creation | `CardInfoFactory.makeCardInfo` | The provider container key selects the entity class |
| Group selection | `CardInfo.parse`, `CardInfoManager.parseList` | The JSON `groupId` can override the default group |
| Screen eligibility | `model.c1.t`, `SwipingCardList.addCard` | Apply swiping visibility and list predicates |
| Background selection | `SlideView.g` | Select art according to the entity class |
| Ordinary image request | `SlideView.c`, `n0.i`, custom Glide target | Load a custom URL as a drawable at original size |
| Theme image request | `SlideView.d` | Load an APK background and a separate foreground |
| Disk identity | `x1.a`, `h4`, Glide cache keys | Normalize the model and hash the cache-key bytes |

The manifest protects `com.miui.tsmclient.provider` with `com.miui.tsmclient.permission.CONTENT_PROVIDER`.
Its protection level is `0x3` (`signatureOrSystem`). An ordinary third-party app cannot rely on direct provider access.
The public and feature providers expose different interfaces. They do not replace the internal card-cache query.
Root access is necessary for this editor's provider query and private cache reads.

## Card enumeration

`u5.a.d` reads each cache row as one JSON object or an array of objects.
It passes the container key and each object to `CardInfoFactory.makeCardInfo`.
Non-card configuration rows also exist. A generic JSON object does not establish a swiping card.

The known entity families are:

| Container key | Entity family | Default group |
| --- | --- | --- |
| `SPTC`, `SPTC_NEW` | `SptcCardInfo` | `TRANSCARD`, 1 |
| Other container keys | `PayableCardInfo` | `TRANSCARD`, 1 |
| `BANKCARD` | `BankCardInfo` | `BANKCARD`, 2 |
| `MIFARE_ENTRANCE` | `MifareCardInfo` | `MIFARECARD`, 3 |
| `EID` | `EidCardInfo` | `EIDCARD`, 4 |
| `QRBANKCARD` | `QrBankCardInfo` | `QRBANKCARD`, 5 |
| `DCEPCARD` | Digital currency | `DCEP`, 6 |
| `DIGITALKEY` | `CarKeyCardInfo` | `CCC`, 7 |
| `TRADITIONALCARKEYCARD` | `ProprietaryCarKeyCardInfo` | `PROPRIETARYCARKEY`, 8 |
| `BLECAR` | Bluetooth car key | `BLECARKEY`, 9 |
| `ICCOADIGITALKEY` | `ICCOACarKeyCardInfo` | `ICCOACARKEY`, 10 |

The swiping list requires a nonempty AID.
Transit entities require `has_issue=true`, including the factory's `PayableCardInfo` fallback.
The JSON `groupId` changes grouping, not the entity class or this eligibility rule.
Door cards with a nonempty `apply_status` other than `ISSUED` are dummy cards and do not qualify.
Door cards with `card_vc_status=10` are temporary readers and do not qualify.
BLE car keys are never visible on this swiping screen.
When `uiConfig.tag` exists, ICCOA cards require the tag `NFC` or a nonempty `product.rkeFunctions` array.

`getLocalCards`, `getIssuedCards`, and the swiping model use different filters.
The editor must use the swiping model rather than copy the issued-card list.
When their container establishes a card type, malformed records remain visible with a reason.
An unavailable image must not remove an otherwise visible card.

### Identity and display rows

A card instance, an image source, and a displayed row are separate identities.
Bank reference IDs and door UID/CID fields identify instances more precisely than a shared product AID.
The editor hashes identity fields and does not retain card numbers or balances in its public model.

Duplicate identities cannot safely authorize independent writes.
Each duplicate row must have a unique display key, retain its own image source, and show an ambiguity reason.
The editor must not store card sources in a map that silently replaces duplicate IDs.

## Background selection

### Entry routes and screen ownership

The modern card holder and the older category lists use different renderers.
`MainActivity` dispatches intents and URIs. Starting it without its expected input does not establish the card-holder entry.
`CardHolderEntranceActivity` hosts a selected fragment through `fragment_name`.
`MainActivity.S0` can select `g8.b` or `d2`, depending on its branch.
`g8.b` is `CardHolderOpenServiceFragment`; `d2` is `CardHolderEntranceFragment`, which extends `CardHolderBaseFragment`.

The modern home callback is `CardHolderBaseFragment$b.d`.
Its row action has click type `1`; its subtitle action has type `2`, or `4` when unavailable.
The transit balance action has type `3`.
The callback constructs a swiping intent through `DoubleClickActivity.I0(context, card.getCardId(), "cardPackage", false)`.
It selects another activity for the category actions listed below.
It also supplies `card_info`, except for the separate `ID_CARD` entry.

| Provider family | Home row/category entry | Available subtitle or swiping entry | List or detail renderer |
| --- | --- | --- | --- |
| Transit, including `SPTC`, `SPTC_NEW`, and the payable fallback | `IssuedTransCardListActivity` → `g6` | `DoubleClickActivity`; balance action → `RechargeActivity` | `g6` → `o5` (`IssuedTransCardListAdapter`) |
| `BANKCARD` | `MiPayEntranceActivity` → `p6` | `DoubleClickActivity` | `p6` → `m6` → `MiPayCardItemView` |
| `QRBANKCARD` | Same bank-list entry; `QrBankCardInfo` extends `BankCardInfo` | `DoubleClickActivity` | Same item view, with a separate QR background branch |
| `MIFARE_ENTRANCE` | `MifareCardListActivity` → `m7` | `DoubleClickActivity` | `m7` → `c7`; preferences use `MifareCardPreferenceActivity` |
| `EID` | `DoubleClickActivity` | `DoubleClickActivity` | Common `SlideView`; the home dispatcher has no separate EID detail route |
| `DCEPCARD` | `DoubleClickActivity` | `DoubleClickActivity` | Common `SlideView`; the home dispatcher has no separate DCEP detail route |
| `DIGITALKEY` | `CarKeyListActivity` → `w1` → `o1` | `DoubleClickActivity` | `CarKeyNewDetailActivity` → `ui.digitalkey.m0` |
| `TRADITIONALCARKEYCARD` | Same car-key list | `DoubleClickActivity` | `CarKeyNewDetailActivity` → `m8.l` |
| `BLECAR` | Same car-key list for every home action | No NFC swiping entry; its entity disables swiping visibility | `CarKeyNewDetailActivity` → `x7.d` |
| `ICCOADIGITALKEY` | Same car-key list | `DoubleClickActivity`, subject to its visibility rules | `CarKeyNewDetailActivity` → `ui.digitalkey.m0` |

For transit and door cards, unavailable subtitle actions return to their category lists.
For digital, traditional, and ICCOA car keys, click types `1` and `4` open the car-key list.
Their other actions open the swiper. BLE actions always open the car-key list.
`CarKeyDetailActivity` redirects to the list; `CarKeyNewDetailActivity` hosts the actual detail fragments.
The bank-list card click expands an inline detail view through `p6.S4 → P4`; it does not launch the swiper.

The swiping activity accepts action `com.miui.intent.action.DOUBLE_CLICK`.
Its intent processing reads `aid`, `card_group_id`, `auto_finish`, `event_source`, and `event_source_type`.
`https://tsmclient.mi.com/swiping` is its swiping URI.
Event sources include `double_click_power`, `key_rf_on`, `volume_down`, `com_miui_home`, and `island_notification`.
Grouping and mode can limit which cards the entry shows.
The NFC fragment is `l7.l0` (`SwitchCardFragment`); the non-NFC fragment is `quick.nonenfc.c`.
Both use `CardStackAdapter`, which binds cards through `SlideView.g`.

The modern home also contains a separate `ID_CARD` entry.
It launches the external wallet URI `mipay://api.jr.airstarfinance.net/idcard-display`, with a staging-host variant.
This entry is distinct from the provider's `EID` entity. Its external renderer remains outside this APK analysis.

### Per-entry image requests

All wallet Glide requests below use the wallet cache directory described in the next section.
They do not receive separate directories for bank, transit, door, or car cards.
The request model, source, target, and options determine their entries.

| Screen | Source selection | Model | Target and disk strategy |
| --- | --- | --- | --- |
| Modern home, all families | `CardHolderDeviceCardInfo.cardArt`; independent of each provider entity's background field | Custom | `h2$a$b.P`: ImageView, layout `65dp × 42dp`, default `AUTOMATIC` |
| Issued transit list | `personalCardFace`, otherwise `issuedListBgHd`; this adapter does not select `gifUrl` | Custom | `o5`: measured ImageView, `AUTOMATIC` |
| Transit card introduction | `preIssuedDetailBg`, otherwise a native resource | Custom for online art | `CardIntroActivity → a3`: measured ImageView, `AUTOMATIC` |
| Ordinary bank list and inline detail | `card_art` through `q1`; width is display pixels minus twice `item_card_margin_horizontal`; height is `trade_alert_card_image_height` | Plain | `MiPayCardItemView.a`: measured ImageView, `AUTOMATIC` |
| QR bank list and inline detail | `bg_image` | Custom | Same item view: measured ImageView, `AUTOMATIC` |
| Issued door list | `MifareCardInfo.getImageUrl()`; native placeholders are separate | Custom | `c7`: measured ImageView, `ALL`, animation decoding disabled |
| Door preferences | `mCardArt` for the card preview; a separate custom URL can populate the supplementary image | Custom | `MifareCardPreferenceActivity.B1/q1`: measured ImageViews, `AUTOMATIC` |
| Door product catalog | `DoorCardProduct` image; this is a product entry, not an issued card | Custom | `DoorCardItemView`: ImageView; do not identify an issued-card target from this catalog image |
| Digital and ICCOA car-key list/detail | `product.cardArt` through `IUICarKeyInfo.getArt()` | Plain | `o1` and `CarKeyCardDetailItemView.setLogo`: ImageView, `AUTOMATIC` |
| Traditional car-key list/detail | Base `cardArt` | Plain | Same car list/detail loaders |
| BLE car-key list/detail | JSON `carKeyArt` through `getCarKeyFace()`, backed by `mCarKeyFace` | Plain | Same car list/detail loaders; no `SlideView` background |
| EID, DCEP, and eligible car-key swiping | Each entity's source from the table below | Custom | `SlideView.c`: original-size custom target, `ALL` |
| Transit, bank, QR bank, and door swiping | Each entity's source from the table below | Custom for online art | Same original-size target and `ALL`; native/theme branches differ |

Car-key detail headers use a `64dp × 40dp` icon with `5dp × 4dp` padding.
These layout values do not authorize an original-size resource-cache replacement.
The same applies to every ImageView row above: its runtime target and transformations require separate resource-key verification.

### NextPay and external web entries

The swiper's successful scan result uses request code `101` and result `OK`.
It sends `com.miui.nextpay.action.SCAN_PAY` to `com.miui.nextpay`, with the scan result in `payString`.
NextPay resolves this action to `ScanPayActivity → d2.a` (`ScanPayFragment`), which hosts web content.
`q4` also dispatches `HYBRID`, `WEBVIEW`, and `OUT_APP_WEBVIEW` entries with URL and title extras.
These paths cross a package boundary and use their destination context.
They do not establish an alternate renderer for the bank card background on `DoubleClickActivity`.
No NextPay image-cache target is authorized by the current editor.

### Swiping source fields

`SlideView.g` selects the following sources:

| Entity family | Background source | Boundary |
| --- | --- | --- |
| Transit or generic UI art | `personalCardFace`, then `gifUrl`, then `issuedListBgHd` | GIF art needs separate animation support |
| Door | `card_art`, otherwise `card_native_drawable` | The fallback is an APK drawable |
| Bank | `card_art` through the CDN thumbnail builder | The art value is an identifier |
| QR bank | `bg_image` | Separate from ordinary bank art |
| Electronic ID | `card_art` | Ordinary URL path |
| Digital currency | Base `cardArt` | Ordinary URL path |
| Traditional car key | Base `cardArt` | Ordinary URL path |
| Digital or ICCOA car key | `product.cardArt` | Product metadata supplies the art |

The bank URL has this form:

```text
https://did-cdn.pay.xiaomi.com/mfc/thumbnail/webp/w{widthPixels}h{heightPixels}q80/{card_art}
```

The dimensions come from `trade_alert_card_image_width` and `trade_alert_card_image_height`, with defaults of `349dp` and `220dp`.
Resource density and overlays can change the resulting pixel dimensions.
These dimensions belong to the URL. They are not the Glide target dimensions.
A complete URL in the bank identifier field is outside this editor's supported contract.

### Bank density failure and its correction

The wallet enables `miuix.autodensity.AutoDensityConfig` in `App.onCreate`.
Its adaptation gate returns true. The adjusted density belongs to the wallet process.
`PackageManager.getResourcesForApplication` and `wm density` do not establish that adjusted configuration.
MIUI's calculation includes physical display metrics, system properties, accessibility settings, and device-specific branches.
The editor does not duplicate that calculation.

The connected device provided these independent observations:

| Evidence | Value | Consequence |
| --- | --- | --- |
| System density and wallet raw activity configuration | `520dpi` | External resource conversion produces `1134 × 715` |
| `DoubleClickActivity.mLastConfigurationFromResources`, display `0` | `497dpi` | Swiping resource conversion produces `1084 × 683` |
| Exact custom source keys for the active bank art at `1084 × 683` | Resource `BitmapDrawable` and DATA entries exist | The bank has a matching swiping image source |
| Exact plain source key for the same identifier at `1134 × 715` | DATA entry exists | This entry cannot substitute for the custom swiping request |

The earlier editor used external resource density and computed the wrong bank URL.
Its disabled bank button did not establish a missing swiping cache or a native bank background.
Matching the corrected request proves that this bank has an online swiping background.
No private card identifier or cache hash is included here.

The correction reads `dumpsys activity com.miui.tsmclient/` through root, outside the UI thread.
It selects `mLastConfigurationFromResources` on display `0` for three verified activities:

- `DoubleClickActivity`
- `CardListActivity`
- `CardHolderEntranceActivity`

These activities share the display's AutoDensity configuration. Their classes and inherited interfaces have no adaptation opt-out.
The NFC swiping fragment closes its activity about `500 ms` after pause, independently of `auto_finish`.
Its `onPause` schedules handler message `2`; `j3` handles that message through `d7 → Activity.finish()`.
The retained card-holder activity provides the same adjusted resource density after the editor enters the foreground.
The reader distinguishes missing activities from malformed or conflicting evidence.
It then creates a wallet package configuration context with that density and reads the bank dimension resources.
[Android's configuration context API](https://developer.android.com/reference/android/content/Context#createConfigurationContext(android.content.res.Configuration)) defines this resource override.
The editor rechecks this evidence before a mutation.
When no verified configuration exists, bank art requiring these dimensions remains disabled, with instructions to open the wallet home or list.
The observed `497dpi` is not a device-independent constant.

### Reading bank art after replacement

The writer stops the wallet before changing its cache. This removes the activities that supplied the adjusted resource density.
The previous reader required a live activity on every scan, including the automatic scan after replacement.
That scan lost the bank thumbnail dimensions and marked the card unavailable, even when its image remained valid.
Opening a photo picker could also remove the swiping activity before the write.

The editor now saves verified density with a configuration signature before any image mutation.
The signature includes the wallet version, update time, data directory, system build, boot count, and relevant display metrics and settings.
It also includes `persist.sys.miui_resolution`, which AutoDensity reads when `screen_resolution_supported` offers multiple resolutions.
It excludes activity sequence numbers, window bounds, and activity type, which can change during an ordinary editor restart.
Missing activities retain saved evidence only when this signature matches.
New verified evidence replaces the saved value. Malformed, incomplete, or conflicting evidence clears it.
A changed signature also prevents reuse, so a wallet update, reboot, or relevant display change requires fresh verification.

The small calibration record uses private preferences. Its synchronous save runs outside the UI thread and reports storage failures.
[Android's preferences guide](https://developer.android.com/training/data-storage/shared-preferences) describes private key-value storage and the threading requirements for synchronous writes.
The record survives editor process restart. It does not contain card identifiers or image data.
Original image backups remain separate from calibration.

### Theme background branch

The wallet reads `bool/use_theme_card_art` and `string/use_theme_card_logo_type`.
`CardUIInfo.getThemeFgArt` selects a foreground from the serialized `themeFgRules` map.
When theme mode is active and a foreground exists, `SlideView.d` loads `ic_transport_theme_art` as the background.
That background is an APK resource. The ordinary background URL is not the active background in this branch.
The editor must disable background replacement for this branch.

The unmodified APK sets theme mode to false and the selector to `light`.
Runtime resources still require inspection because overlays can change resource values.

## Image models and disk cache

`TSMClientGlideModule` registers `l1` (`HttpsModelLoader`) for HTTP String models.
It does not replace the default disk-cache factory.
The analyzed factory `w3.f` selects `context.getCacheDir()/image_manager_disk_cache`, with a `250 MiB` limit.
For Android user `0`, the wallet path is:

```text
/data/user/0/com.miui.tsmclient/cache/image_manager_disk_cache/<sha256>.0
```

`/data/data/com.miui.tsmclient` is the usual alias for this user-0 application directory.
Other Android users have their own application data directory. The editor reads the installed application's `dataDir`.
The parent `cache/` also contains unrelated two-file cache entries and a journal.
Those entries are outside the verified Glide image directory.

Provider card JSON, online image DATA, decoded image resources, native drawables, and web caches are different storage boundaries.
There is no verified `bank/` or `transit/` image-cache subdirectory.

The ordinary swiping path uses `CustomGlideUrl`, `DiskCacheStrategy.ALL`, and a drawable `CustomTarget`.
The target requests `SIZE_ORIGINAL`, represented by `Int.MIN_VALUE` for both dimensions.
The URL loader first upgrades HTTP to HTTPS.
When the query contains the exact encoded key `GalaxyAccessKeyId`, the custom identity removes the entire query.
Other query parameters remain part of the identity.
The fragment remains part of the identity.

`x1.a` hashes the resulting URL with MD5.
It interprets the digest as a signed `BigInteger`, takes its absolute value, and converts it to base36.
An unsigned MD5 interpretation produces different identities for many URLs.

The plain URL model retains the normalized URL and its query.
Its cache entries do not establish the custom swiping target.
They can supply a preview only.

For the proven ordinary target, the editor considers these entries in order:

1. The original-size resource entry for `android.graphics.drawable.BitmapDrawable`.
2. The original-size resource entry for `android.graphics.Bitmap`.
3. The source-data entry for the custom model.

The earlier analysis incorrectly excluded Bitmap entries from a Drawable request.
Glide's decoder registry tries Animation, Bitmap, and BitmapDrawable buckets in that order.
`DecodeJob` records the decoded object's class before the Bitmap-to-Drawable transcode.
Both decoded classes can therefore occur in this request's resource key.
The source, target dimensions, transformation, and options establish compatibility; the decoded class alone does not identify the screen.
The resource entry incorporates the source identity, signature, dimensions, transformation, options, and decoded class.
The source-data entry incorporates the source identity and signature.
The default signature, transformation, and disk options contribute no extra bytes in this request.
SHA-256 produces the disk key. The clean cache file has the suffix `.0`.

These relationships agree with the [Glide cache documentation](https://bumptech.github.io/glide/doc/caching.html).
The exact byte sequence comes from [ResourceCacheKey](https://github.com/bumptech/glide/blob/v4.16.0/library/src/main/java/com/bumptech/glide/load/engine/ResourceCacheKey.java) and the analyzed bytecode.
The upstream source explains the algorithm. Its version tag does not establish the bundled wallet Glide version.

Two cards with one custom source identity share a replacement boundary. Different signed URLs can still have the same identity.
The editor disables independent replacement for such cards.
Filename shape, image dimensions, colors, and visual similarity do not establish ownership.
An exact DATA entry identifies its source, not its originating page.
Home, QR-list, door-list, and swiping requests can share custom DATA when their source identities match.
Replacing shared DATA can affect another screen that reads that same source.
`AUTOMATIC` normally stores remote source DATA; `ALL` also permits decoded resource storage.
The [Glide cache documentation](https://bumptech.github.io/glide/doc/caching.html) explains these strategy differences.

## Why the previous implementation needs replacement

The previous implementation combines enumeration, source selection, image decoding, backup management, and root mutations in one repository class.
The review found concrete behavior errors:

| Problem | Consequence | Required correction |
| --- | --- | --- |
| Duplicate card IDs and `associate` by ID | A source overwrites another source, and Compose list keys collide | Unique display keys and explicit identity ambiguity |
| Full bank URLs pass identifier validation | The repository constructs a URL with two prefixes | Validate the identifier at the parser boundary |
| Incomplete factory and visibility rules | The list can differ from the swiping screen | Use the actual predicates |
| No theme branch or version boundary | An unrelated background cache can appear editable | Read runtime resources and disable unverified profiles |
| Dimension-only freshness check | Different bytes of the same size pass validation | Compare a snapshot fingerprint before mutation |
| Backup validation checks only file length | A corrupted original survives another write | Decode and validate the original before a write |
| Kotlin performs rollback after a failed root command | App interruption can separate the write from rollback | Keep validation, write, and rollback in one root job |
| Replacement uses a sampled preview as its initial alpha mask | The first crop can lose original edge detail | Read the full original mask |

## Rebuild design

The rebuild retains Kotlin, Compose Material 3, and [libsu](https://github.com/topjohnwu/libsu).
It introduces no new dependency or compatibility layer.
The existing first-backup filenames remain valid because they preserve the user's recovery data.

Each component owns one confirmed boundary:

| Component | Responsibility |
| --- | --- |
| Wallet parser and image keys | Pure metadata, visibility, identities, and exact cache-key computation |
| Wallet profile | Verified version and runtime resource values |
| Root cache access | Root commands, canonical paths, temporary copies, and one-job write verification and rollback |
| Image processing | Bounded decoding, full original alpha mask, crop, and PNG output |
| Wallet repository | Serialize scans and mutations, revalidate eligibility, and preserve the first original |
| ViewModel and Compose screen | Explicit operation state, preview selection, and Chinese user messages |

The repository owns mutation authorization under one mutex.
Before mutation, it reads fresh provider metadata and validates the selected source, key, dimensions, and content fingerprint.
It rejects shared sources, ambiguous identities, unsupported versions, native art, animation, and unproven targets.

Each libsu job explicitly collects standard output for provider records and cache keys.
`Shell.newJob()` does not collect output without `Job.to`, unlike `Shell.cmd`.
The [libsu shell contract](https://github.com/topjohnwu/libsu/blob/6.0.0/core/src/main/java/com/topjohnwu/superuser/Shell.java) defines this distinction.
Root diagnostics remain private. User messages contain the failed operation and exit code.

The writer stops the wallet before it modifies the cache.
It writes into the existing inode to preserve owner, mode, and SELinux context.
It validates canonical containment and rejects symbolic-link targets.
It compares the new bytes and metadata after the write.
On a command error, the same root job restores the operation's previous bytes and reports the outcome.

This approach does not claim atomic recovery from power loss or an uncatchable process termination.
The immutable original remains available for explicit restore.
The wallet can also download its source again after cache eviction.
Changing one selected cache entry does not permanently change the wallet's server source or every derived cache entry.

## Verification scope

Synthetic JVM tests cover provider framing, visibility, distinct and ambiguous identities, source precedence, signed URLs, and Glide digest fixtures.
Root transaction tests use temporary synthetic files. They verify normal writes, stale snapshots, links, rollback, and metadata preservation.
`mise run format` and `mise run check` validate the Kotlin source and Android build.
Device checks validate launch, card enumeration, eligibility messages, and absence of crashes.
Private wallet images require a separate deliberate user flow for visual replacement verification.

`alint` requires version `0.18.0`. Its installation remains pending approval, so unavailable installations do not trigger a dependency workaround.

### Verified rebuild results

The rebuild passed `mise run format`, `mise run check`, and the debug APK build.
All 47 JVM tests passed. Android Lint reported no errors or warnings.
The debug APK installed on the connected device and displayed the card list with previews and eligibility messages.
An Android synthetic-cache check passed normal writes, stale-content rejection, rollback, and owner, mode, inode, and SELinux-context preservation.
The check used temporary files and a stub wallet-stop command. It did not replace a private wallet image.
Photo selection, apply, and restore against real wallet images remain outside this verification scope.

### Entry-analysis verification limits

The per-entry tables come from the exact APK's manifest, host activities, callbacks, adapters, entities, and image-request bytecode.
The connected device verified transit and ordinary bank swiping, the bank's adjusted resource density, and exact cache-key matches.
After correction, the installed editor reported two cards with two enabled replacement controls.
The bank control was verified after opening the modern card holder, which remained available behind the editor.
After the persistence fix, stopping the wallet and refreshing still produced two cards with two enabled replacement controls.
Restarting the editor with no wallet activity also retained both controls and the bank preview.
The bank's existing original backup still enabled its restore menu action. The check did not execute that action or replace private images.
It did not provide issued QR, door, EID, DCEP, or car keys for live entry verification.
Those families have static evidence; their existence on a real device still depends on issuance and feature availability.
No fingerprint authentication, payment, card issuance, or private wallet-image mutation was performed for this analysis.

The Gradle configuration emits a deprecation warning for `Configuration.setVisible(boolean)` before application tasks run.
The pinned build still succeeds. The rebuild does not patch or replace the build plugins to suppress this warning.
`alint` was unavailable, so its separate check did not run.
