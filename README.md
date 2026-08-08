This application was created with OpenAI Codex using GPT-5.6 Sol at Ultra reasoning effort.
I also performed an additional security review with Claude Code using the Sonnet 5 model. Among
other things, it reported:

> A note up front: this is an unusually thoroughly designed, defense-oriented codebase. In response
> to your four specific questions, I found no exploitable vulnerability, only a few minor findings
> that are mostly theoretical or defense-in-depth in nature. ... The codebase implements a
> substantially stricter level of protection than would normally be expected from an application
> with a similar purpose (a hardware-bound, per-use biometric key; AEAD; package-name, signature,
> and version pinning; a structure fingerprint; build-time enforced static denylists; a release key
> protected by ACLs and stored outside the project tree; and reproducible, hash-audited release
> artifacts). I found no specific, exploitable security flaw that would enable password theft, data
> theft, control of the device, or damage.

The remainder of this document was written by Codex.

***************************************************************************************************************

# Excel Password – Fingerprint

The project's final `vault` module stores the opening password of a single password-encrypted XLSX
file, protected by the phone's Android Keystore. A locked suggestion appears in Microsoft Excel's
native Autofill field; after the user taps it, the system's strong biometric authentication unlocks
and fills in the real password.

The final application ID is `hu.bordasm.excelfingerprintunlock`. The application has no internet,
storage, Accessibility, or clipboard permission. It verifies both the Excel package and the
Microsoft signing certificate measured on the phone. The release intentionally accepts only the
tested Excel version `16.0.20228.20090 (2005247675)` and the measured structure of its password
field. After an Excel update, the application fails closed and must itself be updated. The debug
build cannot handle real secrets. For the complete security model and its limitations, see
[VAULT_SECURITY.md](VAULT_SECURITY.md).

## Using the Final Application

1. Install `dist/ExcelFingerprintUnlock-1.0.1-release.apk`. When updating from version 1.0.0, do
   not uninstall the earlier version first.
2. Launch the **Excel jelszó – ujjlenyomat** application.
3. Select it as the Autofill service. Android allows only one primary Autofill provider to be active
   at a time, so this may replace Samsung Pass, for example.
4. Tap **XLSX-jelszó biztonságos beállítása** (“Set XLSX password securely”), enter the real file
   password twice, and then authenticate biometrically.
5. Open the encrypted file in Excel, tap the password field, select the
   **CSAK XLSX-megnyitás: jelszó ujjlenyomattal** (“XLSX opening only: password with fingerprint”)
   suggestion, and then use the fingerprint reader. Never select this suggestion in any other
   Excel password dialog.

After enrolling a new fingerprint, changing the screen lock, uninstalling the application, or
changing phones, the password must be entered again. If the application reports that the vault is
“unusable,” first use the application to delete the saved password and Keystore key, and then set
them up again. For this reason, keep the real XLSX password in a separate, secure location as well.

## Version 1.0.1 Fix

Android 16's modern Keystore2 implementation may report the authentication validity duration of a
per-operation key as `0`, while some earlier provider variants and API documentation use `-1`.
Version 1.0.1 accepts both representations of per-operation authentication, but still rejects any
positive authentication window. Key generation remains unchanged: a zero-second,
`BIOMETRIC_STRONG`-only operation bound to a `CryptoObject`.

If setup fails, the application displays only a short, secret-free error code. The code contains no
password, key material, provider message, or device identifier, and it is not written to a log,
file, or the clipboard.

## AndroidX Biometric Dependency

The direct external dependency remains `androidx.biometric:biometric:1.1.0`. Google's August 2026
release table lists it as the only stable Biometric version. The 1.2.x line reached only alpha
status, and the current 1.4.x line is also alpha, so neither is used for this production release.
The current status can be verified on the
[official AndroidX Biometric release page](https://developer.android.com/jetpack/androidx/releases/biometric).

## Building the Final Release

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/build-release.ps1
```

The script requests the release-key password interactively in a separate PowerShell window. On its
first run, it creates a dedicated RSA-4096 release key in
`ExcelFingerprintUnlock-private/release-signing.p12`, next to but outside the project directory.
The `.p12` file and its password require separate offline backups. If either is lost, the
application can no longer be updated under the same application ID.

The pinned SHA-256 fingerprint of the public release certificate is stored in
`release-signing-cert.sha256`. The build rejects any different key. The release build disables the
Gradle configuration and build caches, and restricts the key file's ACL to the current user,
SYSTEM, and the local Administrators group.

The script runs lint, source and APK audits, and signature verification, then copies the release
APK, its SHA-256 audit record, and the R8 mapping file to the `dist` directory.

For a development check that does not handle secrets:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/build-vault-debug.ps1
```

## Compatibility Probe (`app` Module)

This small Android application, which has no internet permission, verifies whether the
**password-encrypted XLSX opening field** in the Android version of Microsoft Excel can be used
through the Android Autofill service.

This is not yet the biometric password vault. The probe:

- does not request, read, or store a real XLSX password;
- does not open or modify any file;
- does not read the clipboard or use an Accessibility service;
- requests no network or storage permission;
- examines only Autofill requests originating from the `com.microsoft.office.excel` package;
- can offer only the harmless `XLSX_AUTOFILL_PROBE_7F3A` test string.

## What Counts as Success?

When the Excel password field is tapped, the “Excel probe – harmless test text” Autofill suggestion
appears. Tapping it inserts the following text into the field:

```text
XLSX_AUTOFILL_PROBE_7F3A
```

**Do not press Excel's Open/OK button afterward**, because this text is intentionally not a real
password. If the text is inserted, the Autofill route works and it is worthwhile to build the next
version using Android Keystore and biometric authentication.

## On-Device Test

1. Install the APK.
2. Launch the **Excel Autofill próba** application.
3. Tap **Autofill szolgáltatás kiválasztása** (“Select Autofill service”) and select this service.
   This may temporarily replace Samsung Pass or another password manager.
4. Tap **Próba egyszeri élesítése (2 perc)** (“Arm probe once (2 minutes)”).
5. Open Excel and the password-protected XLSX file, then tap the password field.
6. If the probe suggestion appears, tap it and verify the inserted test text.
7. Return to the probe application, refresh the report, and record the result.
8. Restore the previous Autofill provider after the test.

For detailed result interpretation, see [docs/TEST_CHECKLIST.md](docs/TEST_CHECKLIST.md).

## Local Build

The pinned toolchain is:

- Microsoft OpenJDK 17.0.20
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- Android SDK Platform 36 and Build Tools 36.0.0

The project does not modify system-wide `PATH` or Java settings; the tools are installed in the
`.tools` directory. The local SDK path is stored in the Git-ignored `local.properties` file.

Build and verify with:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/build-local.ps1
```

The script configures the project-local JDK, SDK, and cache paths only for its own process, verifies
the official SHA-256 fingerprint of the Gradle Wrapper JAR, runs lint, builds the APK, and performs
a permission audit of the packaged manifest.

The installable development APK is located at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it using USB debugging:

```powershell
.tools/android-sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

The debug APK is signed with a development key. This is appropriate for the harmless compatibility
probe, but the later application that handles a real password must be signed with a separate,
long-term release key.

## Privacy

The local report contains only event codes and counters: whether an Excel request arrived, whether
native or compatibility mode was used, how many structural UI nodes were present, and whether a
focused Autofill text field was found. It contains no field value, password, filename, hint, or
resource ID. The report remains in `noBackupFilesDir`; sharing requires a separate user action.

The security boundaries and requirements for the next phase are documented in
[SECURITY.md](SECURITY.md).

***************************************************************************************************************
************** Magyar fordítás ********************************************************************************
***************************************************************************************************************

Ez az alkalmazás az OPENAI Codex-el készült, a GPT-5.6 Sol-al, Ultra erőforrás felhasználással.
Plusz security check-et a Claude Code-al végeztem, a Sonnet 5-ös modellel. Többek között ezt írta:
"Előre jelzem: ez egy szokatlanul alaposan megtervezett, védekező szemléletű kódbázis. A négy konkrét kérdésedre a válasz nem találtam kihasználható sebezhetőséget, csak néhány kisebb, inkább elméleti/megerősítő jellegű észrevételt. ... A kódbázis lényegesen szigorúbb védelmi szintet valósít meg, mint amit egy hasonló célú alkalmazástól alapból elvárnánk (hardver-kötött, per-use biometrikus kulcs, AEAD, csomagnév+aláírás+verzió pinnelés, struktúra-fingerprint, build-időben kikényszerített statikus tiltólisták, ACL-lel védett és a projektfán kívül tartott kiadási kulcs, reprodukálható és hash-auditált release artifact). Konkrét, kihasználható biztonsági rést sem a jelszó ellopására, sem adatlopásra, sem eszközfeletti irányításra, sem károkozásra nem találtam."

Az innentől jövő részeket már a Codex írta.

***************************************************************************************************************

# Excel jelszó – ujjlenyomat

A projekt végleges `vault` modulja egyetlen, jelszóval titkosított XLSX megnyitási jelszavát
tárolja a telefon Android Keystore rendszerével védve. A Microsoft Excel natív Autofill mezőjében
egy zárolt javaslat jelenik meg; megérintése után a rendszer erős biometrikus azonosítása oldja fel
és tölti ki a valódi jelszót.

A végleges alkalmazásazonosító `hu.bordasm.excelfingerprintunlock`. Nincs internet-, tárhely-,
Accessibility- vagy vágólapengedélye. Az Excel csomagját és a telefonon mért Microsoft-aláírást
is ellenőrzi. A kiadás szándékosan csak a kipróbált Excel `16.0.20228.20090 (2005247675)`
verziót és annak mért jelszómező-struktúráját fogadja el; Excel-frissítés után fail-closed módon
appfrissítés szükséges. A debug build nem enged valódi titkot kezelni. A teljes modell és korlátai:
[VAULT_SECURITY.md](VAULT_SECURITY.md).

## Végleges alkalmazás használata

1. Telepítse a `dist/ExcelFingerprintUnlock-1.0.1-release.apk` fájlt. Az 1.0.0-ról történő
   frissítés előtt ne távolítsa el a korábbi verziót.
2. Indítsa el az **Excel jelszó – ujjlenyomat** appot.
3. Válassza ki Autofill szolgáltatásként. Androidon egyszerre csak egy elsődleges Autofill
   szolgáltató lehet aktív, ezért ez leválthatja például a Samsung Passt.
4. Nyomja meg az **XLSX-jelszó biztonságos beállítása** gombot, írja be kétszer a valódi
   fájljelszót, majd azonosítsa magát biometrikusan.
5. Nyissa meg a titkosított fájlt az Excelben, érintse meg a jelszómezőt, válassza az
   **CSAK XLSX-megnyitás: jelszó ujjlenyomattal** ajánlatot, majd használja az
   ujjlenyomat-olvasót. Más Excel-jelszóablakban soha ne válassza ezt az ajánlatot.

Új ujjlenyomat felvétele, a képernyőzár módosítása, az app eltávolítása vagy telefoncsere után a
jelszót újra meg kell adni. Ha az app „nem használható” vaultot jelez, előbb törölje benne a
mentett jelszót és Keystore-kulcsot, majd állítsa be újra. A valódi XLSX-jelszót ezért külön,
biztonságos helyen is őrizze meg.

## Az 1.0.1 javítás

Az Android 16 modern Keystore2 megvalósítása a műveletenként hitelesítendő kulcs időtartamát
`0` értékkel is jelenti, míg egyes korábbi provider-változatok és API-leírások `-1` értéket
használnak. Az 1.0.1 mindkét, műveletenkénti jelentést elfogadja, de pozitív időablakot továbbra
sem enged. A kulcsgenerálás változatlanul `0` másodperces, kizárólag `BIOMETRIC_STRONG`,
`CryptoObject`-hoz kötött művelet marad.

Beállítási hiba esetén az alkalmazás csak rövid, titokmentes hibakódot jelenít meg. A kód nem
tartalmaz jelszót, kulcsanyagot, provider-üzenetet vagy eszközazonosítót, és nem kerül naplóba,
fájlba vagy vágólapra.

## AndroidX Biometric függőség

A közvetlen külső függőség továbbra is az `androidx.biometric:biometric:1.1.0`. A Google 2026.
augusztusi kiadási táblája ezt jelöli az egyetlen stabil Biometric-verziónak. Az 1.2.x kizárólag
alpha állapotig jutott, az aktuális 1.4.x ág szintén alpha; production kiadásban ezért egyikre sem
frissítünk. Az állapot a
[hivatalos AndroidX Biometric kiadási oldalon](https://developer.android.com/jetpack/androidx/releases/biometric)
ellenőrizhető.

## Végleges release összeállítása

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/build-release.ps1
```

A script egy külön PowerShell-ablakban/interaktívan kéri a kiadási kulcs jelszavát. Első futáskor
a projekt melletti, de azon kívüli `ExcelFingerprintUnlock-private/release-signing.p12` fájlban
létrehozza a külön RSA-4096 kiadási kulcsot. A `.p12` fájlról és jelszaváról egymástól elkülönített,
offline biztonsági másolat szükséges; elvesztésük esetén az app többé nem frissíthető ugyanazzal az
azonosítóval.

A nyilvános kiadási tanúsítvány rögzített SHA-256 lenyomatát a
`release-signing-cert.sha256` tartalmazza. A build eltérő kulcsot nem fogad el. A release build
letiltja a Gradle konfigurációs és build cache-ét, a kulcsfájl ACL-jét pedig az aktuális
felhasználóra, SYSTEM-re és a helyi rendszergazdákra szűkíti.

A script lintet, forrás- és APK-auditot, aláírás-ellenőrzést futtat, majd a release APK-t, annak
SHA-256 auditrekordját és az R8 mappinget a `dist` könyvtárba másolja.

A titkot nem kezelő fejlesztői ellenőrzés:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/build-vault-debug.ps1
```

## Kompatibilitási próba (`app` modul)

Ez a kis, internetengedély nélküli Android-alkalmazás azt ellenőrzi, hogy a Microsoft Excel
Android-verziójának **jelszóval titkosított XLSX megnyitási mezője** használható-e az Android
Autofill szolgáltatáson keresztül.

Ez még nem a biometrikus jelszótároló. A próba:

- nem kér, nem olvas és nem tárol valódi XLSX-jelszót;
- nem nyit meg és nem módosít fájlt;
- nem olvas vágólapot, és nem használ Accessibility szolgáltatást;
- nem kér hálózati vagy tárhelyengedélyt;
- csak a `com.microsoft.office.excel` csomagtól érkező Autofill-kérést vizsgálja;
- kizárólag az `XLSX_AUTOFILL_PROBE_7F3A` ártalmatlan tesztszöveget tudja felajánlani.

## Mi számít sikernek?

Az Excel jelszómezőjének megérintésekor megjelenik az „Excel-próba – ártalmatlan
tesztszöveg” Autofill-javaslat, és rákoppintva a mezőbe bekerül:

```text
XLSX_AUTOFILL_PROBE_7F3A
```

Az Excel **Megnyitás/OK gombját ezután ne nyomja meg**, mert a szöveg szándékosan nem valódi
jelszó. Ha a beírás megtörténik, az Autofill útvonal működik, és érdemes elkészíteni a következő,
Android Keystore + biometrikus hitelesítésű változatot.

## Telefonos próba

1. Telepítse az APK-t.
2. Indítsa el az **Excel Autofill próba** appot.
3. Nyomja meg az **Autofill szolgáltatás kiválasztása** gombot, és válassza ezt a szolgáltatást.
   Ez ideiglenesen leválthatja a Samsung Passt vagy más jelszókezelőt.
4. Nyomja meg a **Próba egyszeri élesítése (2 perc)** gombot.
5. Nyissa meg az Excelt és a jelszóval védett XLSX-et, majd érintse meg a jelszómezőt.
6. Ha megjelenik a próbajavaslat, koppintson rá, és ellenőrizze a beírt tesztszöveget.
7. Térjen vissza a próbaappba, frissítse a jelentést, és jegyezze fel az eredményt.
8. A teszt után állítsa vissza a korábbi Autofill szolgáltatót.

A részletes eredményértelmezés: [docs/TEST_CHECKLIST.md](docs/TEST_CHECKLIST.md).

## Helyi összeállítás

A rögzített eszközlánc:

- Microsoft OpenJDK 17.0.20
- Android Gradle Plugin 9.2.0
- Gradle 9.4.1
- Android SDK Platform 36 és Build Tools 36.0.0

A projekt nem módosít rendszerszintű `PATH` vagy Java-beállítást; az eszközök a `.tools`
könyvtárba kerülnek. A helyi SDK-útvonalat a gitből kizárt `local.properties` tartalmazza.

Összeállítás és ellenőrzés:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./scripts/build-local.ps1
```

A script csak az adott folyamatban állítja be a projektlokális JDK/SDK/cache útvonalakat,
ellenőrzi a Gradle Wrapper JAR hivatalos SHA-256 lenyomatát, futtatja a lintet, elkészíti az
APK-t, majd a csomagolt manifestet is permission-auditnak veti alá.

Az installálható fejlesztői APK helye:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Telepítés USB-hibakereséssel:

```powershell
.tools/android-sdk/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

A debug APK fejlesztői kulccsal van aláírva. Ez megfelelő az ártalmatlan kompatibilitási
próbához, de a későbbi, valódi jelszót kezelő alkalmazást külön, hosszú távon őrzött kiadási
kulccsal kell aláírni.

## Adatvédelem

A helyi jelentés csak eseménykódokat és számlálókat tartalmaz: érkezett-e Excel-kérés,
natív vagy kompatibilitási mód volt-e, hány strukturális UI-node volt, és talált-e fókuszált
Autofill szövegmezőt. Nem tartalmaz mezőértéket, jelszót, fájlnevet, hintet vagy resource ID-t.
A jelentés a `noBackupFilesDir` területen marad; megosztás csak külön felhasználói művelettel
történik.

A biztonsági határokat és a következő fázis követelményeit a [SECURITY.md](SECURITY.md) írja le.