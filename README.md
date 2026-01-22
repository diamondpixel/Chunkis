<img src="assets/logo.png" width="150" alt="Chunkis Logo" style="border-radius: 50%;">

# Chunkis

**Chunkis** is a high-performance, sparse chunk storage mod for Minecraft. It replaces or augments the traditional Anvil (`.mca`) format with a custom binary format called **CIS (Chunk Information Storage)**, designed for extreme space efficiency and rapid delta-updates.

## 🚀 The Philosophy: Sparse & Lean
Traditional Minecraft storage saves every single block in a chunk, including thousands of "Air" blocks. **Chunkis** takes the opposite approach: **If it hasn't changed, don't save it.** 

By only persisting "Deltas" (changes relative to the world generator), Chunkis files are often **90-95% smaller** than vanilla files while maintaining bit-perfect accuracy for player-placed blocks, inventories, and states.

> [!IMPORTANT]
> **Beta Stage**: Currently only supports Single Player / Client-side worlds. Dedicated Server support is planned. Always backup your world!

---

## ✨ Key Features

- **CIS Binary Format**: A custom bit-packed protocol that uses ZigZag delta encoding and variable-width bitstreams.
- **Context-Aware Packing**: Automatically compresses block states (Facing, Open/Closed, Powered, etc.) into single bytes.
- **Robust Block Entity Persistence**: Captures and restores full NBT data for Chests, Furnaces, and custom containers independently of block state changes.
- **Deadlock-Free I/O**: Multi-threaded storage engine with granular locking to ensure the game never stutters during auto-saves.
- **Zero-Waste Storage**: Automatic sparse-to-dense conversion. Small changes stay as lean instructions; massive changes migrate to optimized arrays.

---

## 🏗️ How it Works

### 1. Interception (Mixins)
Chunkis hooks into the Minecraft saving pipeline (`ThreadedAnvilChunkStorage`) and chunk construction (`WorldChunk`). 
- When a block is placed, a `ChunkDelta` records the change.
- When a chunk saves, Chunkis serializes this Delta to a `.cis` file.
- When a chunk loads, Chunkis reconstructs the player's changes on top of the base world.

### 2. The CIS4 Data Format
A `.cis` file is structured into four main layers:

| Layer | Description |
| :--- | :--- |
| **Header** | Magic `CIS` signature and versioning. |
| **Global Palette** | A list of unique `BlockStates` used in the chunk, including their packed metadata (facing, properties). |
| **Instruction Stream** | A bit-packed sequence of instructions. It uses "jumps" and "deltas" to define where blocks are placed without storing every coordinate. |
| **Block Entities** | Accurate NBT snapshots of all container blocks and tile entities. |

### 3. State Packing Logic
To save bits, Chunkis "packs" Minecraft properties into a single byte:
- **Bits 0-2**: Directional Facing (North, South, etc.)
- **Bit 3**: Double-block half (Upper/Lower) or Bed part (Head/Foot).
- **Bit 4**: Open/Closed state or Door Hinge side.
- **Bit 5**: Powered/Occupied status.
- **Bits 6-7**: Chest Type (Single, Left, Right).

---

## 📁 Data Layout Overview
```text
Root/
└── chunkis/
    ├── global_ids.json    (Mapping registry for portability)
    └── regions/
        ├── r.0.0.cis      (Sparse region containing 32x32 chunks)
        ├── r.0.1.cis
        └── ...
```

---

## 🛠️ Development & Building
Chunkis is built using the **Fabric** modding toolchain.

1. Clone the repository.
2. Run `./gradlew build`.
3. The mod jar will be in `build/libs/`.


