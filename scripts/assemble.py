#!/usr/bin/env python3
"""把 aapt2 产出的 base.apk 与 d8 产出的 classes.dex 组装成未对齐的 APK。

要点：resources.arsc 与 *.dex 必须以 STORED 存放，否则 targetSdk>=30 时
安装会失败（Android 要求 resources.arsc 不压缩且 4 字节对齐，由 zipalign 保证）。
"""
import sys
import zipfile

if len(sys.argv) != 4:
    print("usage: assemble.py <base.apk> <classes.dex> <out.apk>")
    sys.exit(2)

base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]

with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, "w") as zout:
    for info in zin.infolist():
        if info.filename == "classes.dex":
            continue
        name = info.filename
        stored = name == "resources.arsc" or name.endswith(".dex")
        zi = zipfile.ZipInfo(name, date_time=info.date_time)
        zi.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
        zi.external_attr = info.external_attr
        zout.writestr(zi, zin.read(name))

    with open(dex, "rb") as f:
        payload = f.read()
    zi = zipfile.ZipInfo("classes.dex", date_time=(2026, 1, 1, 0, 0, 0))
    zi.compress_type = zipfile.ZIP_STORED
    zout.writestr(zi, payload)

print(f"assembled {out} ({len(payload)} bytes dex)")
