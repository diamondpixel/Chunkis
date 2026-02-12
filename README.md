<img src="core/src/main/resources/assets/logo.png" width="150" alt="Chunkis Logo" style="border-radius: 50%;">

# Chunkis

**A storage format replacement for Minecraft that persists only chunk deltas, not entire chunks.**

For modpack authors, server operators, and performance-conscious developers who understand that disk I/O optimization ≠ rendering optimization.

---

## 📋 Quick Summary

| Aspect | Details |
| :--- | :--- |
| **What it does** | Replaces Anvil (`.mca`) chunk storage with sparse delta encoding (CIS format) |
| **What it doesn't do** | Improve FPS, change gameplay, add content, or optimize rendering |
| **File size reduction** | 90-95% typical for modpacks with player construction |
| **CPU cost** | Negligible; slightly higher during save/load (delta reconstruction) |
| **Compatibility** | Fabric only; incompatible with region-file-reading mods |
| **World reversibility** | **Not reversible** — backup before installing |
| **Maturity** | Beta; tested on single-player and multiplayer servers |

---

## 🎯 Is This For You?

**YES if:**
- You run a modpack server where world backups are a bottleneck
- You need to distribute large world files over the network
- You understand chunk I/O and storage formats
- Your modpack doesn't use mods that read `.mca` files directly
- You can commit to testing on a backup world first

**NO if:**
- You expect FPS improvements (this doesn't happen)
- You want a "set and forget" mod
- Your modpack includes mods listed under "Known Incompatibilities"
- You don't have a backup strategy for your world

---

## 🔍 Comparison: Chunkis vs Other Solutions

| Solution | Purpose | Trade-offs | Best For |
| :--- | :--- | :--- | :--- |
| **Chunkis** | Sparse delta storage | Requires compatible modlist; not reversible | Large modpacks; network-distributed servers |
| **Lithium** | General server optimization | Marginal FPS impact | All servers (drop-in compatible) |
| **Starlight** | Lighting rebuild optimization | Niche use case | Servers with heavy chunk generation |
| **Mods that compress regions** (e.g., WorldEdit compression) | Post-hoc region compression | Requires manual export/import | One-time world optimization |
| **Standard Anvil** | Vanilla storage | Larger files; slower backups | Default choice; always works |

**TL;DR:** If Lithium or Starlight don't solve your problem, and your modpack is compatible, Chunkis might be the answer.

---

## 🏗️ How It Works

### The Problem
Vanilla Minecraft saves **entire chunks** to disk—including thousands of unmodified air blocks from world generation. A 1GB world contains mostly vanilla terrain that never changed.

### The Solution
Chunkis saves only **deltas**: the difference between what the world generator created and what players modified. Most blocks never get written to disk.

### Under the Hood

**1. Interception**
- Hooks `ThreadedAnvilChunkStorage` (save) and `WorldChunk` (load) via Fabric mixins
- Records changes in `ChunkDelta` objects as blocks are placed/modified

**2. Serialization (CIS7 Format)**
Each `.cis` file contains:
- **Header**: Magic bytes `CIS`, version V7
- **Palette**: Unique `BlockState` IDs used in this chunk
- **Instruction Stream**: Bit-packed delta commands; uses "jump" instructions to skip unmodified blocks
- **Entity Data**: NBT snapshots of block entities (Chests, Furnaces) and global entities (mobs, items)

**3. State Compression**
Block properties (Facing, Open/Closed, Powered, etc.) are bit-packed into single bytes:
- Bits 0-2: Directional facing
- Bit 3: Double-block half / Bed part
- Bit 4: Open/Closed, hinge direction
- Bit 5: Powered or occupied
- Bits 6-7: Specialized data (chest type, instrument, etc.)

**Result:** Most block states compress from 2+ bytes to 1 byte.

### On-Disk Layout
```
World Root/
└── chunkis/
    ├── global_ids.json        (BlockState ID registry)
    └── regions/
        ├── r.0.0.cis
        ├── r.0.1.cis
        └── ...
```

---

## ⚠️ Known Incompatibilities

**Definite conflicts:**
- **WorldEdit** (region file reading)
- **Terralith, Tectonic, or other gen-modification mods** (if they alter previously-generated chunks post-hoc)
- Any mod that directly reads/writes `.mca` region files

**Probable conflicts:**
- Custom world managers
- Chunk post-processing mods (applied after generation)
- Mods with custom NBT serialization hooks on chunks

**To check compatibility:** Review your modlist for anything that mentions "region files," "chunk serialization," "world editing," or "persistent world state." When in doubt, test on a **backup world first**.

---

## 🚀 Installation & Setup

### Requirements
- Fabric Loader
- Fabric API
- Minecraft 1.21+

### Steps
1. Download `chunkis-<version>.jar` from Releases
2. Place in `mods/` folder
3. **Create a backup of your world**
4. Launch Minecraft
5. On first load, Chunkis will begin converting chunks to CIS format as they save

### Reverting
**Chunkis conversion is one-way.** To return to Anvil format:
- Restore from a backup made before Chunkis installation
- No automatic conversion tool exists

---

## 🎯 Performance Expectations

### What Improves
- World backup time (90-95% smaller files)
- Network transfer time for world downloads
- Disk I/O throughput during auto-saves (fewer bytes written)

### What Doesn't Change
- FPS / frame time
- Chunk loading time (no faster or slower than Anvil)
- Server TPS
- Gameplay

### CPU Cost
- **Save phase:** Negligible (delta encoding is lightweight)
- **Load phase:** Negligible (delta reconstruction is simple addition)
- **Noticeable only on:** Extremely CPU-constrained systems (Raspberry Pi level)

---

## ❓ FAQ

### "Will this improve my FPS?"
No. Chunkis only affects disk I/O and file size. Rendering, ticking, and gameplay are unchanged. If you need FPS improvements, use **Lithium**, **Starlight**, or **Sodium/Iris** instead.

### "Does this work on servers?"
Yes. Chunkis is tested on single-player and multiplayer servers. Delta encoding is per-chunk, so it scales linearly with player count.

### "Can I use this with [mod name]?"
Check the Known Incompatibilities section above. If the mod reads `.mca` files or modifies world generation post-hoc, it's likely incompatible. When in doubt, test on a backup.

### "Why is my world not converting to CIS?"
Chunks only convert when they save. Unvisited chunks remain in Anvil format until players enter and modify them. This is intentional—no unnecessary conversion overhead.

### "How do I convert my existing world back to Anvil?"
Restore from a backup. There is no automatic conversion tool. This is why backups are mandatory before installing.

### "Can I transfer a CIS world to a different server?"
Yes, if both servers have Chunkis installed. The `global_ids.json` file ensures portability across different mod versions. Without Chunkis, the world cannot be loaded.

### "What happens if Chunkis development stops?"
Your world is frozen in CIS format. You must either keep Chunkis installed or revert to a pre-Chunkis backup. This is why testing on a backup first is non-negotiable.

### "Is this compatible with modpack X?"
Most modpacks work fine. The only blockers are mods that manipulate region files directly. Review the Known Incompatibilities section and test on a backup.

### "Will you support [older Minecraft version]?"
Development targets newer versions. Community backports via pull request are welcome and encouraged.

### "I found a bug / incompatibility. Where do I report it?"
See **Reporting Issues** below.

---

## 🐞 Reporting Issues

**Before opening an issue, confirm:**
1. You have tested on a **backup world** (not your main save)
2. You have reviewed the Known Incompatibilities section
3. Your modlist doesn't include region-file-reading mods
4. You have the latest version of Chunkis installed

**When opening an issue, include:**
- Minecraft version, Fabric Loader version, and **full modlist**
- `latest.log` or crash report (if applicable)
- Steps to reproduce
- Whether the issue persists with Lithium, Starlight, or other optimization mods disabled
- Screenshot or video (if visual)

**Issues missing this information may be closed.** This is not meant to be harsh—it just keeps development tractable.

---

## 🛠️ Development & Building

Chunkis uses the Fabric toolchain.

```bash
git clone <repository>
cd chunkis
./gradlew build
# Output: build/libs/chunkis-<version>.jar
```

### Architecture Overview
- `ChunkDelta`: Records block changes relative to world generation
- `CIS7Encoder` / `CIS7Decoder`: Serialization logic
- `ThreadedAnvilChunkStorage` mixins: Hook into save/load pipeline
- `StatePackingContext`: Bit-packing logic for block properties

---

## 🤝 Contributing

Contributions are welcome. Submit:
- Bug fixes
- Performance optimizations
- Backports to older versions
- Documentation improvements
- Additional compatibility testing

**Process:**
1. Fork the repository
2. Create a feature branch (`git checkout -b feature/your-feature`)
3. Implement changes with clear commit messages
4. Open a pull request with description of what and why

**Community backports and fixes are fully welcome and encouraged.**

---
