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
