# Forever Companion, built in

The Forever Companion shows WoW's bags, character, quests, crafting, map and chat on the
Thor's other screen, and has 17 mini games and its own settings under **☰ More**. It comes from [thor-companion](https://github.com/weltenaichaos/thor-companion)
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
- The addon (0.33.0 here) splits what it sends over four small colour squares in the corners
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
- The chat (`Chat.lua`, `TH1`): in a crowd there are more lines than the chat corner carries.
  Whispers, your group's and guild's lines and your own go first, twice each; then the newest
  say, yell, channel and system lines, once each, and those not sent within 20 s are left out.
  A chat message takes at most two parts of the corner (one long line alone up to four), and
  the channel list only goes along when it changed and every 30 s (the app keeps the last one).
  Lines are cut only between letters (a cut ä made the app drop the whole message).

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
  and Make again repeats the last one. An enchantment goes on the list with the item picked
  on its card (+1, +5 or + all); Make next then enchants that item once per tap, as the game's
  Enchant button does for gear, until the count is done or the materials run out. Item details now
  compare something you can wear with what you wear in that slot (`ItemCompare.kt`).
  `/thor prof` says what the addon found.

- **☰ More** (only here) at the end of the status line opens Games, Settings and Exit.
  Exit asks first and closes only the companion (`CompanionBridge`), the game keeps running.
- **Games** (only here): 17 small games in `thor.companion.games`. The rules of each game are
  plain Kotlin without Android (`GemBoard`, `Klondike`, `MineField`, `Tiles2048`, `Sudoku`,
  ...) with unit tests in `app/src/test/java/thor/companion/games`; each has a view
  (`GemView`, `SolitaireView`, ...). The action games share `ActionView`. `Arcade.kt` holds the
  Games page and saves each game's state and best score in the preferences file
  `forever_games`. The games never touch WoW and use only their own drawn pictures.
- **Settings** (only here): the switches under Map (Turn with me, quest areas), Chat (speech
  language), Craft (replace an enchantment without asking), Games (reset best scores), and
  Help (versions, Save log).
- The **Type in the game** box (`typeInGame` in `CompanionActivity.kt`) has three modes:
  Email (then it moves on to the password, never logs in early), Password (logs in, then the
  box closes) and Name (no Enter). Each letter goes in on its own key, with Shift for capitals;
  the password is never shown or saved. `KeyboardField.kt` makes ⌫ and Enter work in the
  companion's text boxes.

To take a newer companion over, copy the files in the first table again and keep the
replacements above.

## Logs

- A long press on the companion's status line (or **Save log** in Settings, Help) saves Thor Forever's recent log
  (`Download/ForeverCompanion-log.txt`) and the game's picture as the app sees it
  (`Download/ForeverCompanion-screen.png`, copied from the game's view, no root needed).
