# ReelsBlocker -- kontext pro Claude Code

## Struktura repa

Repo se na GitHubu jmenuje `scrollblocker`, ale samotný Android projekt
žije v podsložce **`ReelsBlocker/`** -- working directory pro build je
`ReelsBlocker/app/build.gradle`, `ReelsBlocker/README.md` atd. CI běží
z `ReelsBlocker/.github/workflows/android.yml` a je zrcadlený i jako
`.github/workflows/android.yml` v kořeni repa.

(Dřív tu byla i zastaralá duplicitní složka `ScrollBlocker/` z jednoho
starého commitu -- byla needitovaná od "Nova verze" a nikde
nepoužívaná, takže byla smazaná.)

Pokud dole v textu není řečeno jinak, veškeré cesty k souborům
(`app/build.gradle`, `README.md`, `ReelsAccessibilityService.kt`,
`AppLog.kt`, `accessibility_service_config.xml`) jsou relativní vůči
`ReelsBlocker/`.

## O co jde

Android accessibility-service appka, která:
1. Detekuje, když je otevřený celoobrazovkový Instagram Reels přehrávač,
   nechá projít 1 reel a při dalším swipe uživatele přesměruje zpět na
   Home tab (feed) místo prostého "back".
2. Vizuálně zakrývá ikonku Reels ve spodní navigační liště Instagramu
   barevně sladěným overlayem (barva se vzorkuje ze skutečného
   screenshotu obrazovky).
3. Má vlastní UI se záložkami Overview (statistiky + graf posledních 7
   dní), Setup (návod + zástupci na systémová nastavení) a Debug
   (log ukládaný lokálně v appce, žádný adb potřeba).
4. Dole má "hub" appek (Instagram / TikTok / Snapchat) s možností
   přeuspořádat pořadí (podržet ikonku → objeví se šipky → klepnutím
   zvolit cílovou pozici). Zatím je plně funkční jen Instagram --
   ostatní dvě appky jen ukládají přepínač Run/Stop pro budoucí
   detekci, appka na to sama upozorňuje.

## Vlastník projektu

Autor (Lukáš) **nemá přístup k počítači ani Android Studiu** -- veškerý
build běží přes GitHub Actions (`.github/workflows/build.yml`), který na
push automaticky sestaví `app-debug.apk` a nahraje ho jako artifact.
Neočekávej, že bude spouštět příkazy lokálně -- pokud něco potřebuješ
ověřit, spusť to sám (buildni, sleduj Actions run) a výsledek shrň.

## Jak appku testuje

Instaluje apk přímo na svůj Xiaomi Redmi Note 14 Pro+ (HyperOS/MIUI).
Debuguje především přes **vestavěný log v appce** (záložka Debug --
soubor `AppLog.kt`), který mi/tobě vkládá jako text. Nemá snadný přístup
k `adb logcat` (řešili jsme to přes Termux + wireless debugging, je to
otravné) -- pokud něco potřebuješ zjistit, napiš si o export z Debug
tabu, ne o logcat výstup.

## Nutné konvence -- drž se jich

1. **Vždy zvyšuj verzi** v `app/build.gradle` (`versionCode` +1,
   `versionName` navazující, např. 1.5 -> 1.6) při jakékoli změně kódu.
   Verze se zobrazuje v pravém horním rohu appky -- je to jediný způsob,
   jak pozná, že mu běží aktuální build.
2. **Vždy zapisuj změny do `README.md`** jako novou sekci na začátek
   seznamu verzí (nejnovější nahoře), ve stylu už existujících sekcí
   (`## vX.Y -- stručný popis`, pak odrážky s tím, co a proč se změnilo
   -- piš to tak, aby tomu rozuměl i netechnický čtenář). Tohle je jeho
   jediný trvalý záznam historie projektu -- neškrtej starší sekce.
3. **Piš stručně a věcně, česky**, pokud komunikuje česky (odpovídej mu
   v jazyce, ve kterém ti napsal).
4. **Neměň zavedené věci bez důvodu**, hlavně:
   - `gradle.properties` (`android.useAndroidX=true`,
     `android.enableJetifier=true`) -- musí zůstat.
   - Ikonka appky zůstává `android:icon="@android:drawable/sym_def_app_icon"`
     v manifestu, žádné `@mipmap/ic_launcher` ani `roundIcon` (projekt
     nemá vygenerované mipmap zdroje).
   - `accessibility_service_config.xml` musí mít
     `android:canTakeScreenshot="true"` (bez toho tiše selhává
     vzorkování barvy overlaye -- byl to skutečný bug, který nás dlouho
     trápil).
5. **Nikdy needituj věci naslepo bez logu, když jde o detekci UI
   Instagramu.** Resource ID a bounds v `ReelsAccessibilityService.kt`
   jsou odhady z chování popsaného v logu, ne z oficiální dokumentace
   Instagramu (žádná neexistuje). Pokud něco nefunguje, popros o čerstvý
   log z Debug tabu, než měníš detekční logiku znovu.
6. Po každé změně **připomeň mu přesný postup**: `git add . && git
   commit -m "..." && git push`, počkat na GitHub Actions, stáhnout
   `.apk` z Artifacts, nainstalovat. Needěl si zbytečné závěry o tom, že
   umí věci, které nejsou pravda (např. že appka sama nainstaluje
   aktualizaci na telefon -- neumí).

## Otevřené resty / věci, co ještě nejsou hotové

- TikTok a Snapchat v hubu nemají žádnou detekční logiku -- jen UI a
  uložený přepínač.
- Reorder hubu je řešený jako "podrž -> klepni kam přesunout", ne jako
  fyzické tažení prstem -- pokud by chtěl opravdový drag gesture, bude
  to potřeba doladit živě s jeho zařízením.
- Overlay přes ikonku Reels je heuristika nad cizí (Instagram) UI --
  může se kdykoli rozbít po update Instagramu. Postup na dohledání
  nových resource ID je popsaný v `README.md` v sekci "Keeping
  detection working".
