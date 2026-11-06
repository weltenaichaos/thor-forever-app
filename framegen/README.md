# Frame generation layer

The start screen's **Frame gen** button turns on a Vulkan layer that draws extra pictures
between WoW's real ones (2x: 30 real frames show as 60). It sits between DXVK and the Turnip
driver and only sees finished pictures on their way to the screen. It never reads or changes
the game's memory, code or files.

## Where the code comes from

| Part | Source |
|---|---|
| Base | [GameNative/lsfg-vk-android](https://github.com/GameNative/lsfg-vk-android) at `abc77c8` (MIT), the Android build of [lsfg-vk](https://github.com/PancakeTAS/lsfg-vk) |
| `patches/0001-d2r-fixes-layers-1-13.patch` | Our own fixes from the Diablo II: Resurrected work on the AYN Thor ([FEX-D2-reforged](https://github.com/weltenaichaos/FEX-D2-reforged), `tools/lsfg/d2r.patch` at `25248d1`): crash and queue lock fixes, a pacer that doesn't lock at 15 FPS, presents from the layer's own thread, generation started at the game's blit, no generated frame across a freeze, and the UI split (off unless the graphics layer hands over a frame without the UI) |
| `patches/00xx-*.patch` | WoW fixes, added here |
| `thirdparty/dxbc` | DXVK's DXBC to SPIR-V translator (zlib) at the commit the base pins. Its GitHub home (PancakeTAS/dxbc) is gone, so we keep our own copy |

CI (`.github/workflows/build.yml`, job `framegen`) clones the base, applies `patches/*.patch` in
order, builds `liblsfg-vk.so` with the NDK and puts it into the APK. The app writes the layer's
manifest and config at launch (`app/.../core/FrameGen.java`).

## Lossless Scaling

The frame generation shaders belong to Lossless Scaling and are read from `Lossless.dll` at
run time. They are never in this repo or the APK. The player picks their own `Lossless.dll`
(Steam, Lossless Scaling) once on the start screen; the app keeps a private copy on the device.

## Settings

The config is written at every launch to `Winlator/logs/framegen/conf.toml`, with one entry per
WoW `.exe` name, so Battle.net never gets frame generation. The layer writes `stats.txt` next to
it. The D2R switches (`LSFG_D2R_*`, see the patch) work as environment variables.
