# Thor Forever v0.1.0 (alpha, pre-release)

Thor Forever is an Android app that starts World of Warcraft: Forever on the AYN Thor with one tap.
It is a fork of Winlator Bionic (Winlator Ludashi). This is the first test release, tested on one AYN Thor.

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
- FPS cap (Off, 30, 40, 45, 60), timed to the screen refresh.
- Overlay: Off, FPS, or FPS with a graph.
- Add-ons: a store (popular add-ons from GitHub, and WoWInterface add-ons made for the game), install from a `.zip`, and uninstall.
- Forever Companion built in: bags, character, quests, map and chat on the Thor's other screen. Turn it on with the **Companion** button.
- **More** opens the normal Winlator screens.
- The app writes diagnostic logs to `Winlator/logs` (from the test builds), to help find crashes.

## Known issues and not yet

- The Forever Companion is new and not yet tested on the device.
- The companion follows the game only by its picture. Following the game through its files is not built in yet.
- The app does not set or check the Android settings (child process restrictions, battery Unrestricted, maximum performance mode). Set them yourself, see [docs/THOR-SETUP.md](https://github.com/weltenaichaos/thor-forever-app/blob/v0.1.0/docs/THOR-SETUP.md).
- Thor Forever is its own app (`com.thorforever.app`). Test builds and Winlator stay separate apps, and a setup in them does not carry over.
- FEXCore is version 2601. The tested shortcut used 2510.1, which is not included.
- VKD3D is not used. WoW runs on Direct3D 11 through DXVK.
- The Rutabaga Turnip driver is not included. You can add it with the driver menu's "install from file".
- Installing an add-on never replaces a folder that is already there. To update an add-on, uninstall it first.
- WoW's crash reports stay in the game's `Errors` folder. They are not copied anywhere else yet.

## Credits and licence

The source code for this release is this repository at the tag `v0.1.0`.
Licences and notices for the bundled parts are in [LICENSE](https://github.com/weltenaichaos/thor-forever-app/blob/v0.1.0/LICENSE) and the [notices](https://github.com/weltenaichaos/thor-forever-app/tree/v0.1.0/notices) folder.
Credits are in the [README](https://github.com/weltenaichaos/thor-forever-app/blob/v0.1.0/README.md#credits).

Not affiliated with or endorsed by Blizzard Entertainment or AYN. World of Warcraft is a trademark of Blizzard Entertainment, Inc.
