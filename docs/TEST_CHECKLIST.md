# Excel–Autofill próbaellenőrző lista

## Előkészítés

- [ ] A tesztelendő Excel a Play Áruházból származik.
- [ ] A jelszavas XLSX-ről van biztonsági másolat.
- [ ] A próbaappban a telepített Excel verziója és aláírólenyomata megjelenik.
- [ ] A próbaapp az aktív Autofill szolgáltató.
- [ ] A diagnosztikát töröltem, majd a próbát két percre élesítettem.

## Próba

- [ ] Megnyitottam a jelszavas XLSX-et Excelben.
- [ ] Megérintettem a fájljelszó mezőjét.
- [ ] Feljegyeztem, megjelent-e az „Excel-próba” Autofill-javaslat.
- [ ] Ha megjelent, rákoppintottam.
- [ ] Feljegyeztem, bekerült-e pontosan az `XLSX_AUTOFILL_PROBE_7F3A` szöveg.
- [ ] Nem nyomtam meg az Excel Megnyitás/OK gombját.
- [ ] Visszatértem a próbaappba és frissítettem a jelentést.

## Jelentés értelmezése

| Jelentés / látott viselkedés | Következtetés |
|---|---|
| Nincs `REQUEST_RECEIVED` | Az Excel natív módban nem indított Autofill-kérést; következő lépés a külön compatibility-mode build. |
| `REQUEST_RECEIVED`, majd `NO_ELIGIBLE_FIELD` | A kérés eljutott a szolgáltatáshoz, de nincs fókuszált, text típusú Autofill-mező. |
| `PROBE_RESPONSE_SENT`, de nincs javaslat | A rendszer megkapta a választ, de a One UI/Excel nem jelenítette meg. |
| A javaslat látszik, de nem ír be | A mező felismerhető, de az Excel nem fogadta el a kitöltést. |
| A tesztszöveg beíródik | A natív Autofill útvonal működik; megkezdhető a biometrikus Keystore-változat. |

## Negatív kontroll és lezárás

- [ ] Egy másik alkalmazás mezőjében nem jelent meg a próbajavaslat.
- [ ] A biztonsági ellenőrző script nem talált tiltott API-t vagy engedélyt.
- [ ] A teszt után visszaállítottam a korábbi Autofill szolgáltatómat.
- [ ] A próbaappot letiltottam vagy eltávolítottam, ha nincs rá tovább szükség.
