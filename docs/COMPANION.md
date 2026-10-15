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
| `app/src/main/assets/thor/companion/ThorCompanion` | `addon/ThorCompanion` (unchanged) |
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

To take a newer companion over, copy the files in the first table again and keep the
replacements above.

## Not yet

- The companion's log button saves Thor Forever's own log, but no screenshot: taking one
  needs root.
