# Biztonsági modell – biometrikus XLSX-jelszókitöltő

## Mit csinál az alkalmazás?

A `vault` modul egyetlen XLSX-megnyitási jelszót tárol, és csak a hiteles Microsoft Excel
Android-alkalmazás fókuszált Autofill szövegmezőjébe ajánlja fel. A jelszó kitöltéséhez minden
alkalommal a rendszer erős biometrikus azonosítása szükséges.

Az alkalmazás nem nyitja meg és nem fejti vissza az XLSX-fájlt. Az Excel továbbra is a valódi
fájljelszót kapja meg; az ujjlenyomat a telefonon tárolt jelszó biztonságos elővételét engedélyezi.

## Biztonsági határok

- Az appnak nincs internet-, hálózati, tárhely-, Accessibility- vagy vágólapengedélye.
- A végleges manifest egyetlen normál engedélye az `android.permission.USE_BIOMETRIC`.
- A jelszó egy AES-256-GCM-mel hitelesítetten titkosított bináris rekordként kerül a privát
  `noBackupFilesDir` területre. Felhőmentésből és eszközátvitelből is ki van zárva.
- Az AES-kulcs nem exportálható Android Keystore-kulcs. Csak TEE- vagy StrongBox-szintű,
  hardveresen kikényszerített, műveletenkénti `BIOMETRIC_STRONG` kulcs fogadható el.
- A műveletenkénti hitelesítési időtartam ellenőrzése kizárólag a Keystore2 által használt `0` és
  a legacy/API-leírás szerinti `-1` reprezentációt fogadja el. Pozitív időablak nem engedélyezett.
- Nincs PIN-, minta- vagy készülékjelszó-fallback a kulcshasználathoz.
- A biometrikus prompt egy `CryptoObject` objektummal magát a GCM-műveletet engedélyezi; a
  program nem egy külön „sikeres volt” logikai jelzőre hagyatkozik.
- A szolgáltatás a UI-fa bejárása előtt ellenőrzi a csomagnevet és a telefonon mért Microsoft
  aláíró SHA-256 tanúsítványt, valamint a pontosan kipróbált Excel-verziót.
- A célmezőnek jelszó `inputType` típusúnak kell lennie, és illeszkednie kell a két sikeres A16
  próbából levezetett, 80–110 node-os/egyetlen text mezős szerkezeti profilhoz. Eltéréskor nincs
  ajánlat.
- Az első Autofill-válaszban nincs jelszó. Csak a felhasználó által kiválasztható, zárolt Dataset
  szerepel; a jelszavas Dataset biometrikus siker után, egyszer használható eredményként készül.
- A kód nem olvassa az Excel mezőértékét, hintjét, leírását, HTML-információját vagy extráit,
  és nem naplóz jelszót, fájlnevet vagy UI-tartalmat.
- A jelszóbeállító és hitelesítő Activity nem exportált, az ablakuk képernyőképtől védett.
- A debug változat szándékosan nem enged valódi jelszót tárolni vagy kitölteni.

## Titokmentes hibakódok

A Keystore-beállítási hibákhoz az alkalmazás stabil, előre meghatározott kódot jelenít meg. A
policy-kód bitmaszkja csak azt jelzi, mely biztonsági invariáns nem igazolható; nem tartalmazza a
provider nyers értékeit vagy üzenetét:

- `0001`: nem igazolt TEE/StrongBox szint;
- `0002`: nincs felhasználói hitelesítési követelmény;
- `0004`: a hitelesítést nem igazoltan biztonságos hardver kényszeríti ki;
- `0008`: nem műveletenkénti hitelesítési időtartam;
- `0010`: nem kizárólag `BIOMETRIC_STRONG`;
- `0020`: nincs biometria-változáskori invalidáció;
- `0040`–`0400`: kulcsméret-, eredet-, cél-, GCM- vagy paddingeltérés.

A kód kizárólag a képernyőn jelenik meg; nincs exception-üzenet, stack trace, log, fájlba írás,
vágólap vagy telemetria.

## A memória valós korlátja

A beviteli karakter- és bájttömböket a kód használat után felülírja. Az Android Autofill API
azonban `CharSequence` értéket kér, ezért sikeres feloldáskor rövid időre elkerülhetetlenül létrejön
egy nem garantáltan törölhető Java-sztring, és a rendszer/Excel is megkapja a jelszót. A jelszó
nem kerül alkalmazásmezőbe, naplóba, vágólapra, beállításfájlba vagy Intent-extra saját adatként.

## Érvénytelenedés és adatvesztés

Új ujjlenyomat felvétele, a biometria vagy a biztonságos képernyőzár módosítása
érvénytelenítheti a Keystore-kulcsot. Ilyenkor a titkosított rekord szándékosan nem állítható
helyre: a valódi XLSX-jelszót újra meg kell adni.

Az alkalmazás eltávolítása, adatainak törlése, telefoncsere vagy gyári visszaállítás ugyanezt
eredményezi. A vault nem a jelszó biztonsági másolata; a valódi jelszót külön, biztonságos helyen
is meg kell őrizni.

## Kiadási kulcs

A végleges APK külön, helyben létrehozott PKCS#12 kiadási kulccsal készül. A nyilvános tanúsítvány
SHA-256 lenyomata a projektben rögzített; más kulccsal a release script leáll. A kulcs és a hozzá
tartozó jelszó elvesztése esetén ugyanazzal az alkalmazásazonosítóval nem készíthető telepíthető
frissítés. A `.p12` fájlról és a jelszóról külön, offline biztonsági másolat szükséges. A jelszó nem
kerül a projektbe vagy a Gradle parancssorába; a kiadási script interaktívan kéri be, és csak a
build gyermekfolyamatának környezetében tartja. Release közben a konfigurációs/build cache tiltott,
a kulcs ACL-je csak az aktuális felhasználó, SYSTEM és a helyi rendszergazdák számára engedélyezett.

## Szándékosan nem alkalmazott megoldások

- nincs Accessibility-alapú UI-automatizálás;
- nincs saját billentyűzet;
- nincs vágólapra másolás;
- nincs jelszó nélküli ideiglenes XLSX és visszatitkosítás;
- nincs felhőszinkron vagy telemetria.

Ezek elkerülését a sikeres natív Excel–Autofill kompatibilitási próba tette lehetővé.

## Maradék célmező-kockázat

Az Android Autofill nem ad dokumentált, Excel-specifikus azonosítót az „XLSX megnyitási jelszó”
jelentéshez. A csomag-, aláírás-, pontos Excel-verzió-, jelszó-inputType- és szerkezeti profil
együttesen erősen szűkít, de elméletileg más, azonos profilú Excel-jelszóablak is megfelelhet.
A jelszavas Dataset soha nem töltődik ki automatikusan: külön rá kell koppintani és biometrikusan
engedélyezni. Ezért a **CSAK XLSX-megnyitás** ajánlatot kizárólag a titkosított fájl megnyitási
ablakában szabad kiválasztani.
