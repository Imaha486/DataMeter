"""Minimal zipalign: rewrite zip so STORED entries' data start at 4-byte boundaries."""
import sys, zipfile

src, dst = sys.argv[1], sys.argv[2]
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, "w") as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        ni = zipfile.ZipInfo(info.filename, date_time=(2008, 1, 1, 0, 0, 0))
        ni.compress_type = info.compress_type
        ni.external_attr = info.external_attr
        if info.compress_type == zipfile.ZIP_STORED:
            # local header = 30 + len(name) + len(extra)
            off = zout.fp.tell()
            hdr = 30 + len(ni.filename.encode())
            pad = (4 - (off + hdr) % 4) % 4
            ni.extra = b"\x00" * pad
        zout.writestr(ni, data)
print("aligned ->", dst)
