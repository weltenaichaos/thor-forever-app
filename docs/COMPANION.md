# Forever Companion, built in

The Forever Companion shows WoW's bags, character, quests, map and chat on the Thor's
other screen. It comes from [thor-companion](https://github.com/weltenaichaos/thor-companion)
and is built into Thor Forever here.

Turn it on with the **Companion** button on the start screen. The app then puts the
companion's addon (ThorCompanion) into the game, and opens the companion on the other
screen when WoW starts. Turned off, the companion stays closed and its addon is removed
(only its own `ThorCompanion` folder).

## Where it comes from

Copied from thor-companion's `app/voice` branch, commit `27aa50f` (0.24.2):

| Here | There |
| --- | --- |
| `companion-decoder/src` | `decoder/src` (unchanged, tests included) |
| `app/src/main/assets/thor/companion/ThorCompanion` | `addon/ThorCompanion` (changes below) |
| `app/src/main/java/thor/companion/CompanionActivity.kt` | `app/.../MainActivity.kt` (only renamed) |
| `MapView.kt`, `NameStore.kt`, `TipStore.kt`, `IconStore.kt`, `Ui.kt` | unchanged |
| `GameFiles.kt` | `app/.../GameFiles.kt` (one change, below) |

These are written for Thor Forever instead:

- `TopScreen.kt` reads the game's picture from the view Thor Forever draws it into
  (Android's PixelCopy), not with a root `screencap`.
- `KeySender.kt` sends a tap's key straight into Thor Forever's X server, which hands it to
  WoW. One tap is one key press, as before; chat sends your text and Enter.
- `KeySender.open` follows the game through its files with `GameFiles`, like the standalone
  app's root helper, but inside the app: the game's files are the app's own. The start screen
  tells it the game folder. `GameFiles.process` also takes that folder, because inside Thor
  Forever the game's command line has its Windows path (`C:\...`), not an Android one.
- `RootShell.kt` runs as the app itself: no root is needed. `Updater.kt` and
  `AddonInstaller.kt` do nothing: the companion updates with the app, and the start screen
  installs the addon (`core/CompanionAddon.java`).
- `CompanionBridge.kt` connects the companion to the game screen and opens it on the other
  screen.
- The addon (0.30.2 here) splits what it sends over four small colour squares in the corners
  of the screen (`Corners.lua`, the default `/thor shape corners`), each only there while it
  sends: your position (`TP2`) top left, the chat, chat box and tap answers bottom left, the
  status bottom right, everything else top right (the data square, 20 x 16 cells). The app
  copies only those four corners (`CornerAreas` in the decoder) about every 0.15 s, keeps each
  corner's message numbers apart, and puts a `TP2` into the last status. Only when no corner
  square was read for 25 s does it look at the whole picture (an older addon, or `/thor shape bar`).
  Each square is only as tall as the part it shows, and the position and status go in short
  forms (`P...`, `S...`, written out again by `Compact.kt`), the bags as `B...`. Every message
  is then packed (`Pack.lua`, `Z...`): numbers and map places as digits of 64, and repeats of
  earlier bytes as a short reference. After a login the small messages (map, character, gear,
  cooldowns, spells) go out together in one (`Y...`), so the start-up card fills sooner. `/thor dense on` (a test) uses eight
  colour levels instead of four, so the squares get a third smaller; a long press on the
  app's status line says how well each corner reads.
- It builds the bags, gear, character, quests and cooldowns again
  only when the game says they changed, or every few seconds (`ns.Remember` in `Strip.lua`);
  it skips its idle wait for urgent changes; and it shows the first part of a message after a
  quiet spell for 0.6 s, since the reading loop here looks every 0.4 s where the bar was.
  `/thor mem` says which parts of the addon make the most memory (per second since the last `/thor mem`).

- A **Craft** tab (only here, not in thor-companion): your professions and their levels
  (`TF1`), each profession's recipes (`TR1`, a page at a time, read while its window is open
  in the game and kept per character in the addon's saved settings), and the bank's count of
  the materials (`TK1`, read while the bank is open). See `Professions.lua` and
  `Professions.kt`. A recipe shows what you can make from your bags (grey when nothing),
  its materials as have / need, and Make one / Make 5 / Make all. Those type
  `/thor make <count|all> <item>` into the game's chat box, one tap one command, and the
  addon starts the game's own crafting in the open profession window. When the addon says
  that window isn't open, the app first presses that profession's open key (ALT-SHIFT-F1 to F5:
  a secure button with the profession's spell, Smelting for Mining) and then the Make keys: the
  profession's number, the recipe's row and the count as ALT-CTRL-NUMPAD digits, ALT-CTRL-F1
  between them and ALT-CTRL-F2, which crafts (nothing typed; an older addon gets `/thor make`).
  A crafting list holds what to make next (Make next, one tap per recipe, as the game wants),
  and Make again repeats the last one. Item details now
  compare something you can wear with what you wear in that slot (`ItemCompare.kt`).
  `/thor prof` says what the addon found.

To take a newer companion over, copy the files in the first table again and keep the
replacements above.

## Not yet

- The companion's log button saves Thor Forever's own log, but no screenshot: taking one
  needs root.
