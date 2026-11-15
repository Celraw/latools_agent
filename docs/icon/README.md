# LA Agent 应用图标

自适应图标（Adaptive Icon），设计语言与 App 内一致：深蓝主色 `#1264D8`→`#082B66`、
霓虹青 `#40C4FF`、高光 `#7DE8FF`，主体是「屏幕框 + AI 星芒」——屏幕代表被操控的手机，
星芒代表设备内的 AI Agent。

## 文件

| 文件 | 说明 |
| --- | --- |
| `generate.py` | 图标生成脚本（唯一几何来源），改动配色/形状后重跑即可 |
| `icon-preview.png` | 遮罩 / 尺寸 / 单色主题图标 / 商店图的预览合集 |
| `preview.html` | 预览合集页面（用浏览器打开，或配合 headless Chrome 重新导出 PNG） |
| `la-agent-icon.svg` | 108×108 完整图层画稿（自适应图标坐标） |
| `la-agent-icon-store.svg` | 中央 72×72 可视区（商店/宣传用） |
| `la-agent-icon-512.png` | 512×512 商店图标（Google Play / 应用市场） |
| `la-agent-icon-monochrome.svg` | Android 13 主题图标单色稿 |

## 已接入 Android 资源

```text
app/src/main/res/drawable/ic_launcher_background.xml    # 背景层：渐变 + 冷光
app/src/main/res/drawable/ic_launcher_foreground.xml    # 前景层：屏幕框 + 星芒
app/src/main/res/drawable/ic_launcher_monochrome.xml    # Android 13 主题图标
app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml      # 自适应图标（API 26+）
app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml      # 追加 monochrome（API 33+）
```

`AndroidManifest.xml` 的 `<application>` 已声明 `android:icon` 与 `android:roundIcon`。
minSdk 为 31，无需再放各密度 PNG。

## 重新生成

```bash
# 修改 generate.py 中的 FRAME / SPARK_* / 渐变颜色后：
python docs/icon/generate.py
# 再把 SVG 渲染成 PNG（本仓库用 Chrome headless，也可用任意 SVG 工具）：
chrome --headless=new --window-size=512,512 \
  --screenshot=docs/icon/la-agent-icon-512.png \
  file:///<repo>/docs/icon/la-agent-icon-store.svg
```

> 注意：自适应图标前景层的安全区是中央 66×66dp 圆，可见区是中央 72×72dp；
> 超出部分会被桌面遮罩裁掉，新增元素请保持在中央 72dp 内。
