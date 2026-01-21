import zlib
import struct
import sys
import json
import os
import io
import gzip
import tkinter as tk
from tkinter import filedialog, ttk, messagebox

class BitReader:
    def __init__(self, data):
        self.data = data
        self.byte_index = 0
        self.bit_index = 0

    def read(self, bits):
        if bits == 0: return 0
        result = 0
        while bits > 0:
            if self.byte_index >= len(self.data): return result << bits
            b = self.data[self.byte_index] & 0xFF
            remaining = 8 - self.bit_index
            take = min(remaining, bits)
            chunk = (b >> (remaining - take)) & ((1 << take) - 1)
            result = (result << take) | chunk
            self.bit_index += take
            bits -= take
            if self.bit_index == 8:
                self.byte_index += 1
                self.bit_index = 0
        return result

    def read_bool(self): return self.read(1) == 1
    def read_zigzag(self, bits):
        n = self.read(bits)
        return (n >> 1) ^ -(n & 1)

def get_facing_str(facing_id):
    mapping = {0: "down", 1: "up", 2: "north", 3: "south", 4: "west", 5: "east"}
    return mapping.get(facing_id, None)

class CISDecoder:
    def __init__(self, stream, mapping):
        self.f = stream
        self.mapping = mapping # Map ID -> Name

    def decode_chunk(self):
        magic_bytes = self.f.read(4)
        if not magic_bytes: return None
        magic = struct.unpack(">I", magic_bytes)[0]
        if magic != 0x43495334: return None
        
        version = struct.unpack(">I", self.f.read(4))[0]
        
        # Global Palette (Mapping IDs + Facing Data)
        palette_size = struct.unpack(">I", self.f.read(4))[0]
        global_palette = []
        for _ in range(palette_size):
            mapping_id = struct.unpack(">H", self.f.read(2))[0]
            facing_data = ord(self.f.read(1))
            
            base_name = self.mapping.get(mapping_id, f"unknown_{mapping_id}")
            facing_str = get_facing_str(facing_data)
            full_name = base_name + (f"[facing={facing_str}]" if facing_str else "")
            global_palette.append(full_name)

        # Instructions
        inst_len = struct.unpack(">I", self.f.read(4))[0]
        inst_data = self.f.read(inst_len)
        
        # Block Entities
        be_count_data = self.f.read(4)
        be_count = struct.unpack(">I", be_count_data)[0] if be_count_data else 0
        
        # Decode Instructions
        reader = BitReader(inst_data)
        section_count = reader.read(16)
        
        all_blocks = []
        for _ in range(section_count):
            sy = reader.read_zigzag(16)
            for my in range(4):
                for mz in range(4):
                    for mx in range(4):
                        res = self.decode_micro_cube(reader, global_palette, mx, my, mz, sy)
                        all_blocks.extend(res)
                        
        return {
            "version": version,
            "palette": global_palette,
            "blocks": all_blocks,
            "be_count": be_count
        }

    def decode_micro_cube(self, reader, global_palette, mx, my, mz, sy):
        palette_size = reader.read(8)
        if palette_size == 0: return []
        
        local_to_global = [reader.read(16) for _ in range(palette_size)]
        block_count = reader.read(7)
        bits_per_id = (palette_size - 1).bit_length() if palette_size > 1 else 0

        last_ly, last_lxz, last_lid = 0, 0, 0
        blocks = []
        for _ in range(block_count):
            dy = reader.read_zigzag(6) if reader.read_bool() else 0
            lxz = reader.read(8) if reader.read_bool() else last_lxz
            
            lid = last_lid
            if palette_size > 1:
                if reader.read_bool():
                    lid = reader.read(bits_per_id)
            
            ly = last_ly + dy
            bx, bz = lxz & 15, lxz >> 4
            
            gid = local_to_global[lid]
            blocks.append({
                "x": bx, "y": (sy << 4) + ly, "z": bz,
                "state": global_palette[gid] if gid < len(global_palette) else "INVALID"
            })
            
            last_ly, last_lxz, last_lid = ly, lxz, lid
        return blocks

class CISVisualizer:
    def __init__(self, root):
        self.root = root
        self.root.title("Chunkis CIS Visualizer (Mapping-Aware)")
        self.root.geometry("1100x750")
        
        self.mapping_data = {} # Map ID -> Name
        self.chunks = [] 
        self.region_pos = (0, 0)
        
        style = ttk.Style()
        style.theme_use('clam')
        style.configure("Treeview", rowheight=25)
        
        self.setup_ui()

    def setup_ui(self):
        top = tk.Frame(self.root, bg="#2d2d2d", pady=10)
        top.pack(fill=tk.X)
        
        btn_mapping = tk.Button(top, text="📄 Load mapping (global_ids.json)", command=self.load_mapping,
                              bg="#2196F3", fg="white", font=("Arial", 9, "bold"), relief=tk.FLAT)
        btn_mapping.pack(side=tk.LEFT, padx=15)

        btn_open = tk.Button(top, text="📂 Open Region File", command=self.load_file, 
                           bg="#4CAF50", fg="white", font=("Arial", 10, "bold"), relief=tk.FLAT)
        btn_open.pack(side=tk.LEFT, padx=5)
        
        self.lbl_file = tk.Label(top, text="Ready", fg="#cccccc", bg="#2d2d2d", font=("Consolas", 10))
        self.lbl_file.pack(side=tk.LEFT, padx=10)

        paned = tk.PanedWindow(self.root, orient=tk.HORIZONTAL, bg="#1e1e1e", sashwidth=4)
        paned.pack(fill=tk.BOTH, expand=True)

        left = tk.Frame(paned, bg="#1e1e1e")
        tk.Label(left, text="CHUNKS", bg="#1e1e1e", fg="#569cd6", font=("Arial", 9, "bold")).pack(pady=5)
        self.chunk_list = ttk.Treeview(left, columns=("pos", "abs_range", "count"), show="headings")
        self.chunk_list.heading("pos", text="Local")
        self.chunk_list.heading("abs_range", text="World Coords")
        self.chunk_list.heading("count", text="Changes")
        self.chunk_list.column("pos", width=60, anchor="center")
        self.chunk_list.column("abs_range", width=120, anchor="center")
        self.chunk_list.column("count", width=80, anchor="center")
        self.chunk_list.pack(fill=tk.BOTH, expand=True)
        self.chunk_list.bind("<<TreeviewSelect>>", self.on_chunk_select)
        paned.add(left)

        right = tk.Frame(paned, bg="#1e1e1e")
        tk.Label(right, text="INSTRUCTIONS", bg="#1e1e1e", fg="#569cd6", font=("Arial", 9, "bold")).pack(pady=5)
        self.detail_list = ttk.Treeview(right, columns=("idx", "abs_pos", "state"), show="headings")
        self.detail_list.heading("idx", text="#")
        self.detail_list.heading("abs_pos", text="World Position")
        self.detail_list.heading("state", text="Block State (Mapped + Packed Facing)")
        self.detail_list.column("idx", width=40, anchor="center")
        self.detail_list.column("abs_pos", width=180, anchor="center")
        self.detail_list.column("state", width=500)
        
        scroll = ttk.Scrollbar(right, orient=tk.VERTICAL, command=self.detail_list.yview)
        self.detail_list.configure(yscroll=scroll.set)
        
        self.detail_list.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)
        paned.add(right)

    def load_mapping(self):
        path = filedialog.askopenfilename(filetypes=[("JSON mapping", "*.json")])
        if not path: return
        try:
            with open(path, 'r') as f:
                raw = json.load(f)
                # Flip it: Name -> ID  to  ID -> Name
                self.mapping_data = {v: k for k, v in raw.items()}
            messagebox.showinfo("Success", f"Loaded {len(self.mapping_data)} block mappings")
        except Exception as e:
            messagebox.showerror("Error", f"Failed to load mapping: {e}")

    def load_file(self):
        if not self.mapping_data:
            messagebox.showwarning("Warning", "Please load a mapping file (global_ids.json) first!")
            return
            
        path = filedialog.askopenfilename(filetypes=[("CIS Region", "r.*.*.cis"), ("CIS files", "*.cis"), ("All files", "*.*")])
        if not path: return
        
        self.lbl_file.config(text=os.path.abspath(path))
        self.chunks.clear()
        for item in self.chunk_list.get_children(): self.chunk_list.delete(item)
        
        base = os.path.basename(path)
        if base.startswith("r."):
            parts = base.split(".")
            try:
                self.region_pos = (int(parts[1]), int(parts[2]))
            except: self.region_pos = (0, 0)
        else:
            self.region_pos = (0, 0)

        try:
            with open(path, "rb") as f:
                header = f.read(2048)
                if len(header) < 2048:
                    f.seek(0)
                    self.parse_single_chunk(f, "Single")
                else:
                    self.parse_region(f, header)
        except Exception as e:
            messagebox.showerror("Error", f"Failed: {e}")

    def parse_region(self, f, header):
        for i in range(256):
            off, length = struct.unpack(">II", header[i*8:i*8+8])
            if off > 0 and length > 0:
                f.seek(off)
                data = f.read(length)
                try:
                    decompressed = zlib.decompress(data)
                    chunk_data = CISDecoder(io.BytesIO(decompressed), self.mapping_data).decode_chunk()
                    if chunk_data:
                        lx, lz = i % 16, i // 16
                        self.chunks.append(((lx, lz), chunk_data))
                        wx = (self.region_pos[0] * 16 + lx) * 16
                        wz = (self.region_pos[1] * 16 + lz) * 16
                        self.chunk_list.insert("", tk.END, values=(f"{lx}, {lz}", f"[{wx}, {wz}]", len(chunk_data["blocks"])))
                except: pass

    def parse_single_chunk(self, f, label):
        raw = f.read()
        try: raw = zlib.decompress(raw)
        except: pass
        chunk_data = CISDecoder(io.BytesIO(raw), self.mapping_data).decode_chunk()
        if chunk_data:
            self.chunks.append(((0,0), chunk_data))
            self.chunk_list.insert("", tk.END, values=(label, "N/A", len(chunk_data["blocks"])))

    def on_chunk_select(self, event):
        sel = self.chunk_list.selection()
        if not sel: return
        idx = self.chunk_list.index(sel[0])
        local_pos, data = self.chunks[idx]
        for item in self.detail_list.get_children(): self.detail_list.delete(item)
        wchunk_x = (self.region_pos[0] * 16) + local_pos[0]
        wchunk_z = (self.region_pos[1] * 16) + local_pos[1]
        for i, b in enumerate(data["blocks"]):
            abs_x, abs_z = (wchunk_x * 16) + b["x"], (wchunk_z * 16) + b["z"]
            self.detail_list.insert("", tk.END, values=(i+1, f"({abs_x}, {b['y']}, {abs_z})", b["state"]))

if __name__ == "__main__":
    root = tk.Tk()
    root.configure(bg="#1e1e1e")
    app = CISVisualizer(root)
    root.mainloop()
