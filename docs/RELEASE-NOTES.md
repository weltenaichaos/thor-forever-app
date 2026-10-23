# Thor Forever v0.6.0 (alpha, pre-release)

Thor Forever is an Android app that starts World of Warcraft: Forever on the AYN Thor with one tap.
It is a fork of Winlator Bionic (Winlator Ludashi). This is a test release, tested on one AYN Thor.

## New in v0.6.0

Ready for the game's release on November 4:
- Battle.net puts each game version (beta, stress test, release) in its own folder. The start screen now gives each one its own Thor settings. A version installed later becomes the one **Play** starts.
- With two or more versions, a **Game** button on the start screen switches which one **Play** starts.
- Each version logs in to its own servers. Before, the stress test (and the release) went to the beta's login when started with **Play**, and only the beta's account was offered.

The Forever Companion:
- **Enchanting** on the Craft tab. Pick an enchantment, or tap a piece of your gear to see what fits it. One tap on **Enchant** puts it on. **Enchant again** repeats the last one, for skilling. A switch in Settings replaces an old enchantment without asking. Binding an item is always asked.
- Enchantments can go on the crafting list (+1, +5, + all). The list is now a page of its own.
- **☰ More** at the end of the status line holds **Games**, **Settings** and **Exit**. Exit asks first and closes only the companion.
- **Games:** 17 small games for a flight or a wait: Gem Match, Solitaire, Minesweeper, 2048, Sudoku, Nonogram, Mahjong, Block Drop, Pipe Connect, Slide Puzzle, Word Search, Memory Pairs, Peg Bounce, Gryphon Glide, Fishing Bobber, Snake and Brick Breaker. They run only in the companion, never touch the game, and keep where you stopped.
- **Settings** gathers the switches under Map, Chat, Craft and Games, with Help at the end.
- Typing into the game (the login, a new character's name) no longer crashes the game. Each letter goes in on its own key, with Shift for capitals.
- The **Type in the game** box has three modes: **Email** (moves on to the password, never logs in early), **Password** (logs in, then the box closes) and **Name** (no Enter). It now sits above Android's keyboard instead of under it.
- **⌫** and **Enter** work in the companion's text boxes.
- Chat keeps up in busy places. Whispers, group and guild lines and your own come first, in about a second.
- Item icons no longer jump while **Get icons** runs.

Coming from v0.1.1 or later: the start screen's **New version** button (or Settings > **Check for updates**) installs it. Coming from v0.1.0: install this APK over it. Your setup, the game and your add-ons stay. The companion add-on updates itself to 0.31.0 the next time the companion starts.

## New in v0.5.0

The app:
- When a new Thor Forever is out, a small **New version** button shows at the top right of the start screen, and a line in the middle says so. Nothing opens by itself.
- Tap the button to see what's new, then **Update now**. It downloads the update and opens Android's installer: press **Update**, then **Open**. Your setup, the game and your add-ons stay.
- You can go back while it downloads. The button shows how far it is, then **Install**. **Stop download** stops it.

The Forever Companion:
- A new look, like the start screen: dark blue, gold frames and round buttons.
- Names in chat have their class colours, like in the game's chat.
- The gear boxes on the Character tab are all the same height.

Coming from v0.1.1 or later: Settings > **Check for updates** installs it. Coming from v0.1.0: install this APK over it. Your setup, the game and your add-ons stay. The companion add-on updates itself to 0.30.3 the next time the companion starts. From v0.5.0 on, the start screen tells you by itself when the next release is out.

## New in v0.4.0

The Forever Companion:
- **Exit** in the companion closes only the companion. The game keeps running. Before, the whole app closed and the game went back to its login screen.
- The game keeps running on the top screen while something else is on the bottom screen.
- The "Type in the game" box closes when you tap the game, at the character screen too. Before, a controller button could then make Android close the app as "not responding".
- The **Map** tab, made over:
  - The map fills the whole bottom screen at every zoom.
  - Drag to look around, and tap **Back to me** to return. **+** and **−** zoom, and so does a pinch.
  - Quest areas have the real shape the game's own map shows. A quest the game has no shape for gets a circle.
  - Tap a pin or a quest area for a card with how far and which way it is, and **Lead me there**.
  - **Turn with me** turns the map with you, like the minimap (still being tried out).
- When you die and release your spirit, the Map tab opens by itself and leads you to your corpse, also in the zone next door. Once you live again, the companion goes back to the tab you were on.
- **Errands** and the Quests tab show where each finished quest is handed in, also in another zone ("Turn it in at Brill, Tirisfal Glades"). Fewer quests are left under "Turn-in place not known".
- The mouse cursor starts in the middle of the game's screen. In the top-left corner it covered the square with your position, and the map didn't follow you until you swiped the cursor away.

Coming from v0.1.1 or later: Settings > **Check for updates** installs it. Coming from v0.1.0: install this APK over it. Your setup, the game and your add-ons stay. The companion add-on updates itself to 0.30.2 the next time the companion starts.

## New in v0.3.0

The Forever Companion:
- A new **Craft** tab: your professions and their levels, the recipes of each profession window you open, what you can make from your bags, and the materials in your bank.
- **Make one**, **Make 5** and **Make all** start the game's own crafting with a tap: the app opens the profession window and presses the add-on's Make keys, nothing is typed.
- A crafting list: add recipes with +1, +5 or + all, then tap **Start** and **Next** to make them one after another (the game wants a tap for each). It shows how many are made, waits until the game is done, and marks in red what will run out of materials. **Make again** repeats the last thing you made.
- Chat lines go into the game in one go, through the game's own clipboard. Android's clipboard is not used, so there is no pop-up. If a paste doesn't take, the line is typed key by key as before.
- The game's chat box closes right after your line is sent, instead of staying open for a few seconds.
- Item details compare a piece of gear with what you wear in that slot.
- Closing the companion's text box gives the keys back to the game. Before, a controller button could make Android close the app as "not responding".
- The companion does less while it is hidden.

The app:
- WoW's crash reports are copied to `Winlator/logs/wow-errors` on your device, where you can open them. This happens when the game closes and before it starts, never while you play. The game's own files are not changed.

## New in v0.2.0

The game:
- With the Forever Companion on, the game no longer hangs on its first loading screen until you tap the top screen.
- The loading screen stays until the game shows its login screen, instead of a black screen.
- When you quit, the loading screen comes back as soon as the game's picture goes black, instead of a long black screen and the shutdown notice.
- At the login screen, the companion has a **Type in the game** box: type your account name or password on the bottom screen, and it goes into the game key by key. The password is never shown or saved.

The Forever Companion:
- It follows the game through its files as well as its picture, so the data is there sooner.
- The add-on sends less and packs everything small: bags, character, quests and the map show up faster, above all right after login.
- Chat: your own lines now show in the app, and chat lines are typed into the game instead of pasted (no Android clipboard pop-up). Since v0.3.0 they are pasted again, without Android's clipboard.
- Speech to text: other guesses to tap, hold the mic to pick a language, and WoW words it understands better.
- A reading error no longer closes the app or the game.
- Leftovers from the old standalone companion app are removed.

## New in v0.1.1

- Sending a chat message from the Forever Companion no longer freezes the game.
- The app can update itself: **Settings > Check for updates** lists the published releases and installs the one you tap.

## Install

1. Download the `.apk` file below. Do not use **Code > Download ZIP**.
2. Install it. Allow your browser to install apps when Android asks.
3. Open **Thor Forever** and follow the first setup.

You need your own Battle.net account with access to WoW: Forever. No game files are included.
Step by step, and the Android settings to set first: [README](https://github.com/weltenaichaos/thor-forever-app#how-to-install).

## What's in it

- A full-screen start screen with **Play**, **Update with Battle.net** and **Quit**, and the installed game version.
- A first setup that runs by itself: Wine 11 (arm64ec, Thor build 7), DXVK 2.6.2 Thor (ARM64X), Turnip 26.2.3 (Thor build), esync, the "Thor Extreme" FEXCore preset and the game settings file `Config-Thor-Forever.wtf`.
- Install Battle.net from the start screen, log in, install the game, then Play.
- The start screen says when a game update is waiting.
- The start screen says when a new Thor Forever is out, and installs it with a tap.
- FPS cap (Off, 30, 40, 45, 60), timed to the screen refresh.
- Overlay: Off, FPS, or FPS with a graph.
- Add-ons: a store (popular add-ons from GitHub, and WoWInterface add-ons made for the game), install from a `.zip`, and uninstall.
- Forever Companion built in: bags, character, quests, map and chat on the Thor's other screen. Turn it on with the **Companion** button.
- **More** opens the normal Winlator screens.
- The app writes diagnostic logs to `Winlator/logs` (from the test builds), to help find crashes.

## Known issues and not yet

- The Forever Companion is new and still being tested on the device.
- With **Turn with me** on, the names printed in the zone picture turn with the map.
- The app does not set or check the Android settings (child process restrictions, battery Unrestricted, maximum performance mode). Set them yourself, see [docs/THOR-SETUP.md](https://github.com/weltenaichaos/thor-forever-app/blob/v0.4.0/docs/THOR-SETUP.md).
- Thor Forever is its own app (`com.thorforever.app`). Test builds and Winlator stay separate apps, and a setup in them does not carry over.
- FEXCore is version 2601. The tested shortcut used 2510.1, which is not included.
- VKD3D is not used. WoW runs on Direct3D 11 through DXVK.
- The Rutabaga Turnip driver is not included. You can add it with the driver menu's "install from file".
- Installing an add-on never replaces a folder that is already there. To update an add-on, uninstall it first.

## Credits and licence

The source code for this release is this repository at the tag `v0.4.0`.
Licences and notices for the bundled parts are in [LICENSE](https://github.com/weltenaichaos/thor-forever-app/blob/v0.4.0/LICENSE) and the [notices](https://github.com/weltenaichaos/thor-forever-app/tree/v0.4.0/notices) folder.
Credits are in the [README](https://github.com/weltenaichaos/thor-forever-app/blob/v0.4.0/README.md#credits).

Not affiliated with or endorsed by Blizzard Entertainment or AYN. World of Warcraft is a trademark of Blizzard Entertainment, Inc.
