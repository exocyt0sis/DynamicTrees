# Dynamic Trees - 1.21.1 Backport (NeoForge)

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-5fbf3f?style=for-the-badge)](https://www.minecraft.net/)
[![Loader](https://img.shields.io/badge/NeoForge-21.1.x-2d2d2d?style=for-the-badge&logo=neovim&logoColor=white)](https://neoforged.net/)
[![Vanilla%20Backport](https://img.shields.io/badge/Vanilla%20Backport-1.1.7.6-recommended-ffb347?style=for-the-badge)](https://www.curseforge.com/minecraft/mc-mods/vanillabackport)
[![Status](https://img.shields.io/badge/Status-Feature%20Complete%20Beta-2ea44f?style=for-the-badge)](#)

Minecraft NeoForge mod providing dynamic trees that progressively grow from seed to maturity, with Pale Garden and creaking-heart compatibility backported for 1.21.1.

![Logo](./header.png)

## What This Backport Adds

- Stable Pale Garden support in the Dynamic Trees flow.
- Creaking heart branch lifecycle support (awake, dormant, uprooted states).
- Resin generation, resinized branch behavior, and resin drop handling.
- Runtime compatibility bridge between Dynamic Trees and Vanilla Backport creaking internals.

## Compatibility

- Minecraft: 1.21.1
- Loader: NeoForge 21.1.x
- Vanilla Backport: **1.1.7.6 is expected for full compatibility** with The Pale Garden unique biome mechanics.

Other Vanilla Backport versions may load and run, but full compatibility with Pale Garden behavior is only validated against 1.1.7.6.

## Technical Summary (26.1.2 -> 1.21.1 Backport)

This backport brings key Pale Garden behavior from the 26.1.2 line to 1.21.1 using a compatibility-first approach:

- Added a dedicated compatibility spawner to synchronize creaking spawn/link/despawn behavior around Dynamic Trees heart branches.
- Implemented a reflection-based bridge to Vanilla Backport creaking and heart block-entity internals, avoiding hard compile-time coupling while preserving runtime behavior.
- Synced Dynamic Trees heart visual states with natural-night logic and protector-link state.
- Added resin generation triggers with cooldown control and candidate filtering so resin only applies to valid nearby pale oak branches.
- Hardened orphan cleanup behavior for linked creakings and proxy heart state handling.
- Updated family/resource loading flow so resin properties are applied consistently during setup and reload.
- Finalized model and texture mappings for pale oak creaking heart and resin branches to prevent out-of-model or floating render artifacts.

## Links

- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/dynamictrees)
- [Modrinth](https://modrinth.com/mod/dynamictrees)
- [Discord](https://discord.gg/A4FCBS3)

## Build From Source

1. Clone the repository.
2. Open a terminal in the repository root.
3. Use Java 21.
4. Run `gradlew build` on Windows, or `./gradlew build` on Linux/macOS.
5. The NeoForge artifact is generated in `neoforge/build/libs/`.

## Release Artifacts

Release builds include:

- NeoForge JAR
- Source archive (.zip)
- Source archive (.tar.gz)
