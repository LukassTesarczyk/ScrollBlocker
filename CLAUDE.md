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
     `res/drawable-xxxhdpi/ic_launcher_foreground.png` +
     `res/drawable/ic_launcher_background.xml`) -- fialový mozek na tmavém
     pozadí. Od v1.14 je to skutečná fotka `Icon.jpg` (nahraná do rootu
     `main` větve na GitHubu), automaticky zpracovaná (odstranění pozadí,
     vystředění do bezpečné zóny) na PNG s průhledností, ne ruční
     překreslení -- pokud se ikonka bude v budoucnu ještě měnit, zdrojová
     fotka je `Icon.jpg` v rootu repa. Dřív tu byl systémový placeholder
     (`@android:drawable/sym_def_app_icon`), protože projekt neměl
     vygenerované mipmap zdroje -- teď má, takže tenhle bod už neplatí.
     Neměň ikonku zpátky na placeholder bez výslovného požadavku.
     minSdk appky je 26 (stejná verze, co zavedla adaptivní ikonky), takže
     žádné legacy mipmap PNG fallbacky nejsou potřeba.
   - Od v1.15 appka používá pevný, do repa commitnutý debug keystore
     (`ReelsBlocker/keystore/debug.keystore`, referencovaný z
     `app/build.gradle` přes `signingConfigs.debug`) místo defaultního
     keystore, co si Gradle sám generuje. GitHub Actions runner nemá
     trvalé úložiště pro `~/.android/debug.keystore`, takže bez tohohle
     by měl každý build jiný náhodný podpisový klíč a instalace
     aktualizace přes starou verzi by tiše selhávala (Android to
     odmítne bez odinstalace). Je to veřejně známé heslo/alias
     (`android`/`androiddebugkey`), není to citlivý soubor -- commitnutí
     do repa je běžná praxe přesně z tohohle důvodu. Neměň zpátky na
     defaultní auto-generovaný keystore bez výslovného požadavku.
5. **Úpravy tohoto `CLAUDE.md` jsou v pořádku, ale vždy se nejdřív zeptej
   autora a počkej na jeho souhlas**, než v něm cokoli změníš, přidáš
   nebo smažeš.
