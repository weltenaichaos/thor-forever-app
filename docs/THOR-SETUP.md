# Thor Forever: setup on the AYN Thor

What the app sets up by itself, and the few things only you can set on the device.
The values come from the Thor Forever launcher (`thor-forever-reforged`) and from a
WoW shortcut that runs well on the Thor.

## Before you play

These are Android and Thor settings. The app can't change them.

- **Disable child process restrictions.** Developer options, "Disable child process
  restrictions" (Android 14 and newer). Without it, Android can stop Wine's helper
  processes. With esync on, that shows up as crashes at the login screen.
  On Android 12 or 13 the same is done with adb:
  ```
  adb shell device_config set_sync_disabled_for_tests persistent
  adb shell device_config put activity_manager max_phantom_processes 2147483647
  adb shell settings put global settings_enable_monitor_phantom_procs false
  ```
- **Battery: Unrestricted.** Settings, Apps, Thor Forever, Battery.
- **Maximum performance mode with the fan up.** In the standard mode the GPU stays at
  401 MHz instead of up to 680 MHz, and cities drop to about 21 FPS.

## What the first setup does

| Setting | Value | Why |
| --- | --- | --- |
| Wine | Thor Forever Wine 11.0-arm64ec, build 7 | Contains the esync fixes (fd leak that crashed WoW after about 6 minutes, quit crash) |
| DXVK | 2.6.2 Thor (ARM64X) | Smoother than 2.4.1 and 2.5.3; about 5% less CPU per frame |
| Vulkan driver | Turnip 26.2.3 (Thor build) | Stable with esync |
| Esync | on (`WINEESYNC=1`) | Lower CPU overhead |
| Start | one core first, then all cores | Avoids a blank-window crash at start with esync |
| WoW start arguments | `-d3d11 -config Config-Thor-Forever.wtf` | Direct3D 11, and the game settings below |
| Game settings | `WTF/Config-Thor-Forever.wtf` | Portal, language, allocator fix, screen narration off, D3D11 and a low graphics starting point |
| Present mode | mailbox | As in the working shortcut |
| `DXVK_CONFIG` | `dxvk.enableGraphicsPipelineLibrary=false;dxvk.tilerMode=False;d3d11.relaxedBarriers=True` | GPL on crashed after login; tiler off was faster |
| `TU_DEBUG`, `DXVK_HUD` | empty | Turnip's autotuner (`TU_AUTOTUNE_ALGO=profiled`) picks the rendering mode; no overlay |
| `DXVK_STATE_CACHE`, `WRAPPER_NO_PATCH_OPCONSTCOMP` | `0`, `1` | As in the working shortcut |
| FEXCore preset | "Thor Extreme" | The working shortcut's preset |
| Screen narration | off in `Config.wtf` too | Under Wine, WoW's narration crashes the game at the intro |

The game settings file is written only when it is missing. WoW saves your own
graphics options into it, and the app never overwrites it. The graphics lines are the
same as WoW's own Graphics options (the "Low" preset), plus a few view distances. In
the launcher's tests the complete set fixed a picture glitch in the menu.

## More than one game version

Battle.net installs each game version (beta, stress test, release) into its own folder
next to the others, like `_classic_beta_`. When the start screen finds a version
without a shortcut, it adds one (`World of Warcraft Forever Live.desktop` and the like)
with the settings of the current WoW shortcut: the same variables, start arguments, FPS
cap and FEXCore preset, and the game settings file above in that version's `WTF` folder.
A version installed after the current one becomes the one **Play** starts. The **Game**
button switches between them.

The Forever Companion's add-on gets the interface number of the version it goes into,
so a game patch doesn't mark it out of date.

## Not included

These are in the working shortcut, but the app doesn't ship them:

- VKD3D proton-3.0a (WoW runs on Direct3D 11, so VKD3D isn't used for the game).
- FEXCore 2510.1 (the app ships 2601).
- The Rutabaga Turnip driver. It installs through the driver menu's "install from file".

You can download VKD3D from the app's Contents list.

## When WoW crashes

- The app writes its logs to `Winlator/logs`: `app-exits.txt`, `cpu_*.txt`, `x-windows_*.txt`,
  and `wowb-arm64_*.txt` when Settings, Logs, "Enable Wine debug" is on.
- WoW writes its own crash reports to the `Errors` folder next to the game's `.exe` (`WowB-ARM64.exe` in the beta).

Logs and WoW's `WTF` and `Account` folders can contain account and character names.
Don't post them publicly.
