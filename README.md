# Dynamic Trees - 1.21.1 Backport (NeoForge)

[![Minecraft](https://img.shields.io/badge/Minecraft-1.21.1-3C8527.svg)](https://www.minecraft.net/)
[![NeoForge](https://img.shields.io/badge/NeoForge-21.1.233-43853d.svg)](https://neoforged.net/)
[![MIT license](https://img.shields.io/badge/License-MIT-blue.svg)](https://lbesson.mit-license.org/)
[![Release](https://img.shields.io/badge/status-release_1.8.0-brightgreen.svg)](https://github.com/exocyt0sis/DynamicTrees/releases/tag/1.8.0-BETA02-backport-1.21.1)

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

## Technical Summary (26.1.2 ⇾ 1.21.1 Backport)

This backport brings key Pale Garden behavior from the 26.1.2 line to 1.21.1 using a compatibility-first approach:

- Added a dedicated compatibility spawner to synchronize creaking spawn/link/despawn behavior around Dynamic Trees heart branches.
- Implemented a reflection-based bridge to Vanilla Backport creaking and heart block-entity internals, avoiding hard compile-time coupling while preserving runtime behavior.
- Synced Dynamic Trees heart visual states with natural-night logic and protector-link state.
- Added resin generation triggers with cooldown control and candidate filtering so resin only applies to valid nearby pale oak branches.
- Hardened orphan cleanup behavior for linked creakings and proxy heart state handling.
- Updated family/resource loading flow so resin properties are applied consistently during setup and reload.
- Finalized model and texture mappings for pale oak creaking heart and resin branches to prevent out-of-model or floating render artifacts.

## Build From Source

1. Clone the repository.
2. Open a terminal in the repository root.
3. Use Java 21.
4. Run `gradlew build` on Windows, or `./gradlew build` on Linux/MacOS.
5. The NeoForge artifact is generated in `neoforge/build/libs/`.

## Release Artifacts

Release builds include:

- NeoForge JAR
- Source archive (.zip)
- Source archive (.tar.gz)
