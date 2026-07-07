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
3. Má vlastní UI: nahoře vlevo šipka otevírá boční vysouvací panel s
   Overview (statistiky + graf posledních 7 dní), Settings (návod +
   zástupci na systémová nastavení), Log (log ukládaný lokálně v appce,
   žádný adb potřeba, jde i stáhnout jako `.txt` přes share sheet) a
   Languages (přepínač jazyka appky, nezávislý na systémovém jazyce
   telefonu -- `AppCompatDelegate.setApplicationLocales`).
4. Dole má "hub" appek (Instagram / TikTok / Snapchat, s jednoduchými
   vektorovými ikonkami) -- klepnutí přepne aktivní appku, podržení
   otevře action sheet s možnostmi Stats / Move / Run-Stop. "Move"
   spustí režim přesunu (zvětší se, ostatní ztlumí, klepni kam
   přesunout). Zatím je plně funkční detekce jen pro Instagram --
   ostatní dvě appky jen ukládají přepínač Run/Stop pro budoucí
   detekci, appka na to sama upozorňuje.
5. Appka je lokalizovaná do ~30 jazyků (viz `res/values-*/strings.xml`).

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
   - Od v1.13 má appka vlastní adaptivní ikonku (`@mipmap/ic_launcher`
     -> `res/mipmap-anydpi-v26/ic_launcher.xml` +
     `res/drawable/ic_launcher_foreground.xml` (fialový obrys mozku
     s gradientem) + `ic_launcher_background.xml`) -- fialový mozek na
     tmavém pozadí, ručně nakreslená vektorová grafika podle referenční
     fotky. Dřív tu byl systémový placeholder
     (`@android:drawable/sym_def_app_icon`), protože projekt neměl
     vygenerované mipmap zdroje -- teď má, takže tenhle bod už neplatí.
     Neměň ikonku zpátky na placeholder bez výslovného požadavku.
   - `accessibility_service_config.xml` musí mít
     `android:canTakeScreenshot="true"` (bez toho tiše selhává
     vzorkování barvy overlaye -- byl to skutečný bug, který nás dlouho
     trápil).
5. **Nikdy needituj věci naslepo bez logu, když jde o detekci UI
   Instagramu.** Resource ID a bounds v `ReelsAccessibilityService.kt`
   jsou odhady z chování popsaného v logu, ne z oficiální dokumentace
   Instagramu (žádná neexistuje). Pokud něco nefunguje, popros o čerstvý
   log z menu -> Log, než měníš detekční logiku znovu. Strukturální věci
   (výkon, hlavní vlákno, hysterze zobrazování, životní cyklus služby)
   naopak řešit jde i bez logu -- to není hádání o Instagramu, to je
   normální inženýrská práce nad naším vlastním kódem.
6. **Nové UI texty vždy přidávej jako string resource** do
   `res/values/strings.xml` (anglicky, je to zdroj pravdy), ne natvrdo
   do kódu/layoutu. Appka je lokalizovaná do ~30 jazyků
   (`res/values-<kód>/strings.xml`) -- když přidáš nový text, ideálně
   dopiš překlad aspoň do několika hlavních jazyků (cs, sk, de, es, fr...).
   Minimálně ale musí být v `values/strings.xml`, ať appka nespadne na
   chybějícím resource. Nepřekládej vlastní jména appek
   (Instagram/TikTok/Snapchat) ani `app_name`.
7. Po každé změně **připomeň mu přesný postup**: `git add . && git
   commit -m "..." && git push`, počkat na GitHub Actions, stáhnout
   `.apk` z Artifacts, nainstalovat. Needěl si zbytečné závěry o tom, že
   umí věci, které nejsou pravda (např. že appka sama nainstaluje
   aktualizaci na telefon -- neumí).

## Design systém -- drž se ho, i v budoucích úpravách

Appka má záměrně tmavý, minimalistický, plochý vzhled. Než přidáš nové
UI, drž se těchto zavedených pravidel:

- **Paleta:** pozadí appky `#121212`, kartičky/panely `#1A1A1A` až
  `#181818`, hranice/oddělovače `#232323`/`#2A2A2A`, sekundární text
  `#808080`-`#909090`, primární text bílá. Akcentová barva je teal
  `#26A69A` (aktivní stavy, Run tlačítko). Neutrální/needuretknuté
  tlačítko `#2E2E2E`. Destruktivní akce (Stop, Clear, badge) `#C62828`.
- **Kartičky:** `@drawable/bg_card` -- `#1A1A1A`, rohy 18dp. Používej to
  jako pozadí pro každou logickou sekci (status, statistiky, setup, log,
  languages), s paddingem ~18dp.
- **Tlačítka:** plochá, bez ALL CAPS textu, zaoblené rohy (~14dp) --
  nastaveno globálně přes `Widget.App.Button` v `styles.xml`
  (`materialButtonStyle`), takže nový `<Button>` v layoutu tohle dědí
  automaticky, není potřeba nic nastavovat ručně.
- **Menu položky** (boční panel, action sheet, seznam jazyků):
  `@drawable/bg_menu_item` (ripple, průhledné pozadí, rohy 14dp) pro
  neaktivní, `@drawable/bg_menu_item_active` (jemný teal nádech) pro
  aktivní/vybrané. Jsou to obyčejné `TextView` s paddingem 14dp, ne
  `Button` -- jednodušší na styl bez Material overheadu.
  Ikonová kolečka nahoře (šipka menu) mají `@drawable/bg_icon_button`.
- **Vysouvací panely** (drawer, hub action sheet): tmavší scrim
  `#99000000` přes celou obrazovku + `translationX`/`translationY`
  animace ~160-220ms, žádné instantní zobrazení/schování bez animace.
- **Overlay/accessibility okna** (čtvereček přes Reels ikonku, "zpět do
  feedu" pilulka): fade animace (alpha), ne tvrdé
  GONE/VISIBLE přepínání -- to dřív způsobovalo viditelné blikání.
- Ikonky appek v hubu jsou jednoduché vektorové drawables
  (`ic_instagram.xml`, `ic_tiktok.xml`, `ic_snapchat.xml`) -- generické
  tvary (fotoaparát/nota/duch), záměrně ne přesné kopie oficiálních log
  kvůli ochranným známkám. Tintují se programově přes `imageTintList`.

## Otevřené resty / věci, co ještě nejsou hotové

- TikTok a Snapchat v hubu nemají žádnou detekční logiku -- jen UI a
  uložený přepínač.
- Reorder hubu je pořád řešený jako "Move v action sheetu -> klepni kam
  přesunout", ne jako fyzické tažení prstem -- pokud by chtěl opravdový
  drag gesture, bude to potřeba doladit živě s jeho zařízením.
- Overlay přes ikonku Reels je heuristika nad cizí (Instagram) UI --
  může se kdykoli rozbít po update Instagramu. Postup na dohledání
  nových resource ID je popsaný v `README.md` v sekci "Keeping
  detection working".
- **Známá mezera:** overlay/"1 reel" logika reaguje na celoobrazovkový
  Reels přehrávač bez ohledu na to, jak se do něj vstoupilo -- ale podle
  zpětné vazby jde do Reels swipnout stranou přímo z feedu/DM a appka to
  v některých případech nezachytí. Potřeba čerstvý log z právě tohohle
  konkrétního scénáře (swipe do Reels ze strany, ne přes tab), než se do
  toho bude sahat -- viz pravidlo 5 výše.
