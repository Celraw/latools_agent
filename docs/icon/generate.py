#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""LA Agent 应用图标生成器。

设计基于 108×108dp 的自适应图标画布（Android Adaptive Icon）：
  · 背景层：深蓝渐变 + 左上角青色冷光
  · 前景层：霓虹屏幕框 + 白色四角星芒（AI 在设备内）
  · 单色层：Android 13 主题图标用（Frame + Spark，单色轮廓）

输出：
  app/src/main/res/drawable/ic_launcher_background.xml
  app/src/main/res/drawable/ic_launcher_foreground.xml
  app/src/main/res/drawable/ic_launcher_monochrome.xml
  docs/icon/la-agent-icon.svg            （512 商店/预览用整图）
  docs/icon/la-agent-icon-monochrome.svg
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app" / "src" / "main" / "res"
DOCS = ROOT / "docs" / "icon"

# ---------------------------------------------------------------- 设计参数
FRAME = dict(x=27.0, y=27.0, w=54.0, h=54.0, r=14.5)
NEON_W = 3.6          # 霓虹描边宽度
GLOW_W = 4.5          # 外圈冷光描边宽度
SPARK_CX, SPARK_CY = 54.0, 54.0
SPARK_R = 16.0
SPARK_WAIST = 0.26


def rr(x: float, y: float, w: float, h: float, r: float) -> str:
    """圆角矩形 pathData（顺时针）。"""
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    return (
        f"M{f(x + r)},{f(y)} "
        f"H{f(x + w - r)} "
        f"A{f(r)},{f(r)} 0 0 1 {f(x + w)},{f(y + r)} "
        f"V{f(y + h - r)} "
        f"A{f(r)},{f(r)} 0 0 1 {f(x + w - r)},{f(y + h)} "
        f"H{f(x + r)} "
        f"A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y + h - r)} "
        f"V{f(y + r)} "
        f"A{f(r)},{f(r)} 0 0 1 {f(x + r)},{f(y)} Z"
    )


def ring(x: float, y: float, w: float, h: float, r: float, stroke: float) -> str:
    """以 pathData 形式表达描边环（outer 顺时针 + inner 同向，evenOdd 填充）。"""
    o = dict(x=x - stroke / 2, y=y - stroke / 2, w=w + stroke, h=h + stroke, r=r + stroke / 2)
    i = dict(x=x + stroke / 2, y=y + stroke / 2, w=w - stroke, h=h - stroke, r=max(r - stroke / 2, 0.1))
    return rr(**o) + " " + rr(**i)


def spark(cx: float, cy: float, R: float, waist: float) -> str:
    """四角星芒（内凹曲线）。"""
    w = R * waist
    f = lambda v: f"{v:.2f}".rstrip("0").rstrip(".")
    return (
        f"M{f(cx)},{f(cy - R)} "
        f"C{f(cx + w * 0.62)},{f(cy - w)} {f(cx + w)},{f(cy - w * 0.62)} {f(cx + R)},{f(cy)} "
        f"C{f(cx + w)},{f(cy + w * 0.62)} {f(cx + w * 0.62)},{f(cy + w)} {f(cx)},{f(cy + R)} "
        f"C{f(cx - w * 0.62)},{f(cy + w)} {f(cx - w)},{f(cy + w * 0.62)} {f(cx - R)},{f(cy)} "
        f"C{f(cx - w)},{f(cy - w * 0.62)} {f(cx - w * 0.62)},{f(cy - w)} {f(cx)},{f(cy - R)} Z"
    )


FRAME_PATH = rr(**FRAME)
GLOW_RING = ring(**FRAME, stroke=GLOW_W)
NEON_RING = ring(**FRAME, stroke=NEON_W)
SPARK_PATH = spark(SPARK_CX, SPARK_CY, SPARK_R, SPARK_WAIST)


def vector(inner: str, size: int = 108) -> str:
    return f'''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="{size}dp"
    android:height="{size}dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{inner}
</vector>
'''


BACKGROUND = vector(f'''    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="8" android:startY="6"
                android:endX="100" android:endY="104">
                <item android:offset="0" android:color="#FF2E8BFF" />
                <item android:offset="0.55" android:color="#FF1264D8" />
                <item android:offset="1" android:color="#FF082B66" />
            </gradient>
        </aapt:attr>
    </path>
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="16" android:centerY="10"
                android:gradientRadius="76">
                <item android:offset="0" android:color="#807DE8FF" />
                <item android:offset="0.55" android:color="#147DE8FF" />
                <item android:offset="1" android:color="#007DE8FF" />
            </gradient>
        </aapt:attr>
    </path>''')

FOREGROUND = vector(f'''    <!-- 外圈冷光 -->
    <path
        android:pathData="{GLOW_RING}"
        android:fillColor="#2E40C4FF"
        android:fillType="evenOdd" />
    <!-- 屏幕内暗面 -->
    <path android:pathData="{FRAME_PATH}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="40" android:startY="27"
                android:endX="70" android:endY="81">
                <item android:offset="0" android:color="#AD062350" />
                <item android:offset="1" android:color="#57062350" />
            </gradient>
        </aapt:attr>
    </path>
    <!-- 霓虹屏幕边框 -->
    <path android:pathData="{NEON_RING}" android:fillType="evenOdd">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="27" android:startY="27"
                android:endX="81" android:endY="81">
                <item android:offset="0" android:color="#FFC9F9FF" />
                <item android:offset="0.45" android:color="#FF40C4FF" />
                <item android:offset="1" android:color="#FF2979FF" />
            </gradient>
        </aapt:attr>
    </path>
    <!-- AI 星芒 -->
    <path android:pathData="{SPARK_PATH}">
        <aapt:attr name="android:fillColor">
            <gradient
                android:type="linear"
                android:startX="40" android:startY="38"
                android:endX="70" android:endY="72">
                <item android:offset="0" android:color="#FFFFFFFF" />
                <item android:offset="1" android:color="#FFDCEBFF" />
            </gradient>
        </aapt:attr>
    </path>''')

MONO_RING = ring(**FRAME, stroke=4.5)
MONOCHROME = vector(f'''    <!-- 单色轮廓：屏幕框（加粗，小尺寸更清晰） -->
    <path
        android:pathData="{MONO_RING}"
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd" />
    <!-- AI 星芒 -->
    <path
        android:pathData="{SPARK_PATH}"
        android:fillColor="#FFFFFFFF" />''')

# ---------------------------------------------------------------- SVG 整图
def svg_icon(mono: bool = False, viewbox: str = "0 0 108 108") -> str:
    if mono:
        body = f'''  <rect width="108" height="108" fill="#0E2A5A" />
  <g fill="#ffffff">
    <path d="{MONO_RING}" fill-rule="evenodd" />
    <path d="{SPARK_PATH}" />
  </g>'''
    else:
        body = f'''  <rect width="108" height="108" fill="#0E2A5A" />
  {bg_svg()}'''
    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="{viewbox}">
  <!-- LA Agent app icon · generated by docs/icon/generate.py -->
{body}
</svg>
'''


def bg_svg() -> str:
    return f'''<defs>
    <linearGradient id="bg" x1="8" y1="6" x2="100" y2="104" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#2E8BFF" />
      <stop offset="0.55" stop-color="#1264D8" />
      <stop offset="1" stop-color="#082B66" />
    </linearGradient>
    <radialGradient id="glow" cx="16" cy="10" r="76" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#7DE8FF" stop-opacity="0.5" />
      <stop offset="0.55" stop-color="#7DE8FF" stop-opacity="0.08" />
      <stop offset="1" stop-color="#7DE8FF" stop-opacity="0" />
    </radialGradient>
    <linearGradient id="neon" x1="27" y1="27" x2="81" y2="81" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#C9F9FF" />
      <stop offset="0.45" stop-color="#40C4FF" />
      <stop offset="1" stop-color="#2979FF" />
    </linearGradient>
    <linearGradient id="inner" x1="40" y1="27" x2="70" y2="81" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#062350" stop-opacity="0.68" />
      <stop offset="1" stop-color="#062350" stop-opacity="0.34" />
    </linearGradient>
    <linearGradient id="white" x1="40" y1="38" x2="70" y2="72" gradientUnits="userSpaceOnUse">
      <stop offset="0" stop-color="#FFFFFF" />
      <stop offset="1" stop-color="#DCEBFF" />
    </linearGradient>
  </defs>
  <rect width="108" height="108" fill="url(#bg)" />
  <rect width="108" height="108" fill="url(#glow)" />
  <path d="{GLOW_RING}" fill="#40C4FF" fill-opacity="0.18" fill-rule="evenodd" />
  <path d="{FRAME_PATH}" fill="url(#inner)" />
  <path d="{NEON_RING}" fill="url(#neon)" fill-rule="evenodd" />
  <path d="{SPARK_PATH}" fill="url(#white)" />'''


def main() -> None:
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    DOCS.mkdir(parents=True, exist_ok=True)
    (RES / "drawable" / "ic_launcher_background.xml").write_text(BACKGROUND, encoding="utf-8")
    (RES / "drawable" / "ic_launcher_foreground.xml").write_text(FOREGROUND, encoding="utf-8")
    (RES / "drawable" / "ic_launcher_monochrome.xml").write_text(MONOCHROME, encoding="utf-8")
    (DOCS / "la-agent-icon.svg").write_text(svg_icon(), encoding="utf-8")
    # 商店 / 图标预览：只取自适应图标可见的中央 72×72 区域
    (DOCS / "la-agent-icon-store.svg").write_text(svg_icon(viewbox="18 18 72 72"), encoding="utf-8")
    (DOCS / "la-agent-icon-monochrome.svg").write_text(svg_icon(mono=True), encoding="utf-8")
    print("icon assets written")


if __name__ == "__main__":
    main()
