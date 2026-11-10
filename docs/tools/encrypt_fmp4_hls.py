"""
Makes an AES-128 encrypted fMP4 HLS stream (ffmpeg can't encrypt fMP4 itself).

Reads the clear fMP4 stream in <root>/fmp4 (made with ffmpeg, see docs/TESTING.md) and writes
<root>/fmp4-aes: media segments encrypted whole-segment AES-128-CBC with IV = media sequence number
(as RFC 8216 defines when no IV attribute is given), init section left clear. Needs `openssl` on PATH.

Usage: python docs/tools/encrypt_fmp4_hls.py <root>
"""
import os, shutil, subprocess, sys

root = sys.argv[1]
src = os.path.join(root, "fmp4")
dst = os.path.join(root, "fmp4-aes")
shutil.rmtree(dst, ignore_errors=True)
os.makedirs(dst)

key = os.urandom(16)
open(os.path.join(dst, "enc.key"), "wb").write(key)
shutil.copy(os.path.join(src, "init.mp4"), dst)

playlist = open(os.path.join(src, "index.m3u8")).read().splitlines()
seq = int(next((l.split(":")[1] for l in playlist if l.startswith("#EXT-X-MEDIA-SEQUENCE")), "0"))
out = []
for line in playlist:
    if line.startswith("#EXT-X-MAP"):
        out.append(line)
        out.append('#EXT-X-KEY:METHOD=AES-128,URI="enc.key"')  # after MAP: init stays clear
        continue
    if line and not line.startswith("#"):
        iv = format(seq, "032x")
        subprocess.run(
            ["openssl", "enc", "-aes-128-cbc", "-K", key.hex(), "-iv", iv,
             "-in", os.path.join(src, line), "-out", os.path.join(dst, line)],
            check=True,
        )
        seq += 1
    out.append(line)
open(os.path.join(dst, "index.m3u8"), "w").write("\n".join(out) + "\n")
print("\n".join(out[:10]))
