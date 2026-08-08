# Biztonsági modell – kompatibilitási próba

## Hatókör

Ez a verzió egyetlen kérdésre válaszol: az Excel Android-alkalmazás jelszavas XLSX-megnyitási
mezője kitesz-e használható, fókuszált `AutofillId`-t. Nem kezel titkot és nem valósít meg
biometrikus feloldást.

## Védelmi intézkedések

- A manifestben nincs `<uses-permission>`: nincs internet-, hálózat-, tárhely-, biometrikus,
  vágólap- vagy Accessibility-engedély.
- Az Autofill szolgáltatást a rendszer `BIND_AUTOFILL_SERVICE` signature-szintű engedélye védi.
- A szolgáltatás az Activity csomagnevét még a UI-fa bejárása előtt ellenőrzi, és kizárólag a
  `com.microsoft.office.excel` csomagot fogadja el.
- Más alkalmazás `AssistStructure` fáját a szolgáltatás nem járja be és nem naplózza.
- Az Excel-struktúrából csak számlálók készülnek. A kód nem hívja a `ViewNode.getText()`,
  `getAutofillValue()`, `getHint()`, `getContentDescription()`, `getExtras()` vagy `getHtmlInfo()`
  metódust, és objektumok `toString()` eredményét sem naplózza.
- A próbadataset csak kézi, kétperces élesítés után, egyszer adható vissza, kizárólag a fókuszált
  text típusú Autofill-mezőhöz.
- A tesztérték fix és nyilvános; soha nem lehet felhasználói adat.
- Nincs `SaveInfo`; egy esetleges mentési callback tartalmát a kód nem olvassa.
- A diagnosztika az alkalmazás privát, mentésből kizárt `noBackupFilesDir` területén marad.
- `allowBackup=false`, teljes backup-kizárás és `usesCleartextTraffic=false` van beállítva.

## Ismert korlátok

- Androidon egyszerre egy elsődleges Autofill szolgáltató aktív. A próba idejére a felhasználó
  másik szolgáltatója (például Samsung Pass) leváltható.
- A `PROBE_RESPONSE_SENT` csak azt bizonyítja, hogy a framework megkapta a datasetet. A tényleges
  megjelenést és beírást a felhasználónak kell vizuálisan ellenőriznie.
- A próba csak a hivatalos Excel-csomagnevet ellenőrzi. Valódi titok kezelése előtt a telefonon
  telepített Excel Microsoft-aláíró tanúsítványának SHA-256 lenyomatát is pinelni kell.
- A debug build fejlesztői aláírású. A jelszót kezelő kiadásnak külön kiadási kulcs kell.

## Következő, titkot kezelő fázis minimuma

- Nem exportálható AES-256-GCM kulcs az Android Keystore-ban.
- Kulcshasználatonként `BIOMETRIC_STRONG` + `BiometricPrompt.CryptoObject` hitelesítés.
- A jelszó csak Keystore-kulccsal titkosított blobként, `noBackupFilesDir` alatt tárolható.
- Excel csomagnév- és aláíráspinelés a titok átadása előtt.
- Nincs vágólap, Accessibility, log, crash analytics vagy internetengedély.
- Ujjlenyomat-változás és kulcsérvénytelenedés esetén a valódi jelszó ismételt bekérése.

## Sérülékenység jelentése

Mivel ez helyi, nem publikált projekt, észlelt hibánál ne használjon éles XLSX-jelszót. Állítsa
le a próbát, törölje az app adatait, és javításig ne folytassa a titkot kezelő fázist.
