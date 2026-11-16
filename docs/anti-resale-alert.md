# Anti-resale notice

Patches: `Alert`（Bilibili / VirtualTablet 各一个同名补丁）
Implementation: `extensions/`（Morphe extension）+ `shared/AntiResaleInjector.kt`

## 行为

```
启动
├─ Toast「Morphed by Ra1nify」          ← 每次启动，与下面完全独立
└─ prefs「no_alert_version」== 当前 bundle 版本？
     ├─ 是 → 不再弹
     └─ 否 → 全屏不可绕过弹窗
           ├─ 图：if u bought it, / you are an idiot / 三个笑脸（Canvas 自绘 + 摆动动画）
           ├─ 输入框：占位「Enter the repo address to continue」
           ├─ 复选框「Don't show again」
           └─「Confirm」常亮按钮
                 ├─ 文本不匹配 /(?i)ra1nify\/f4t/ → Toast「ん？」
                 ├─ 匹配 + 勾选 → 写入 no_alert_version = 当前版本 → 关闭
                 └─ 匹配 + 未勾选 → 不写 → 下次启动继续弹
```

绕过处理：Back、点击弹窗外部、未命中输入的 Confirm 全部回 `ん？`（800ms 节流），弹窗不关闭。

`no_alert_version` 存宿主自己的 SharedPreferences（`ra1nify_f4t_alert`），**按 bundle 版本记录**：
每次发新 bundle 版本都会重新弹；同一版本重装不弹；清应用数据重置。

## 为什么这样做

- **Morphe 源是公开可读的**：`.mpp` 可反编译、成品 APK 可 diff，所以这不是"防护"，而是
  **强制买家做一次信息检索**：不知道官方仓库地址的人（包括被倒卖的买家）会被卡在启动页，
  搜索后自然会找到官方发布页；reseller 必须自己把答案写进商品说明，摩擦被转移给他们。
- **Toast 是常驻水印**：每个 patched 构建都带 `Morphed by Ra1nify`，买家可以据此辨别来源。
- **不可绕过是设计意图**：UI 层（Back / 外部点击 / Confirm 校验）全部拦截；系统层（强制停止、
  卸载、清数据）无法拦截，这在"只作用于 app 内"的前提下是预期行为。

## 注入点

`AntiResaleInjector` 不硬编码类名，按结构定位：

1. 收集所有父类链能到达 `android.app.Application` 的类；
2. 取继承树的**叶子**；
3. 用宿主包名匹配叶子（包名可能是 `com.sunnysidesoft.VirtualTablet.lite`，而 Application 在父包
   `com.sunnysidesoft.VirtualTablet`，所以两边互为前缀都算匹配）；
4. 从叶子沿父类链找到**真正声明 `onCreate()V`** 的类，在其开头注入
   `invoke-static {p0}, Lapp/ra1nify/f4t/extension/AntiResaleAlert;->onAppCreate(Landroid/app/Application;)V`。

当前结果：

| 宿主 | Application（manifest） | onCreate 声明处 |
|---|---|---|
| Bilibili 9.18.0 | `tv.danmaku.bili.HiltApplication` | `Ltv/danmaku/bili/A1;` |
| VirtualTablet 4.6.1 | `com.sunnysidesoft.VirtualTablet.VtOriginalApplication` | 同类 |

## Fail-closed

以下任一情况直接让补丁失败并给出可诊断信息，不会写入猜测的注入：

- 找不到任何 `Application` 子类；
- 宿主包名匹配到多个叶子，或没有任何叶子能匹配且总叶子数 ≠ 1；
- 父类链上没有 `onCreate()V`。

运行时同样 fail-closed：extension 里所有入口都包在 `try/catch` 里，弹窗构建/显示失败时**静默跳过**，
绝不因为提示功能破坏宿主启动。

## 弹窗实现要点（extension 限制）

- Morphe extension 是**纯 DEX**，没有资源：图/动画用 `Canvas` 自绘（`MemeView`），文案写在代码里
  （英文 only，不做 i18n）。
- 全屏 `Dialog` + 居中卡片 + `ScrollView`（`holder.setMinimumHeight(scroll.getHeight())` 保证垂直居中
  且小屏/键盘可滚）。
- 外部点击用自定义 `FrameLayout.dispatchTouchEvent` 判断坐标是否在卡片内。
- `setCancelable(false)` + `setCanceledOnTouchOutside(false)` + Back 拦截。
- 旋转/Activity 重建时弹窗重弹，已输入文本通过静态 `pendingText` 保留。
- 只处理第一个 `onActivityResumed` 且要求 `hasWindowFocus()`，避免与宿主 splash / 首启弹窗叠加；
  焦点未就绪时最多重试 6 次。

## 构建

- `extensions/extension` 是 Android library（`com.android.application`，由 Morphe 插件配置），
  因此构建需要 **Android SDK**（`compileSdk 36`）；在 git-ignored 的 `local.properties` 里写
  `sdk.dir=...`。
- extension 默认会带上完整 Kotlin stdlib（~2 MB）。`settings.gradle.kts` 里通过
  `configure<SettingsExtension> { extensions { proguardFiles("proguard-rules.pro") } }` 开启 R8，
  并用 `extensions/extension/proguard-rules.pro` 保留入口，最终 extension DEX 约 **17 KB**。
- R8 开启后 dex 任务从 `mergeDexRelease` 切到 `minifyReleaseWithR8`；**如果 R8 把入口裁掉，补丁仍能
  编译但运行时会 `NoClassDefFoundError`**，所以改 keep 规则后务必重新 verify（见下）。

## 验证清单（离线）

```bash
./gradlew buildAndroid
# 1. bundle 里带 extension
python - <<'PY'
import zipfile
z = zipfile.ZipFile("patches/build/libs/patches-<version>.mpp")
print("extensions/extension.mpe" in z.namelist(), z.getinfo("extensions/extension.mpe").file_size)
PY

# 2. 打补丁时注入点日志
#    [f4t] anti-resale notice installed into ...->onCreate() (Application: ...)
```

```python
# 3. 成品 APK 里 extension 与文案都在
z = zipfile.ZipFile("out.apk")
dex = [n for n in z.namelist() if n.endswith(".dex")]
assert any(b"Lapp/ra1nify/f4t/extension/AntiResaleAlert;" in z.read(n) for n in dex)
assert any(b"Morphed by Ra1nify" in z.read(n) for n in dex)
```

## 验证清单（真机）

- [ ] 冷启动：Toast 出现，弹窗在 ~1s 后出现，不与宿主 splash 叠加；
- [ ] Back / 点黑色区域 / 输入错误点 Confirm → 都只回 `ん？`，弹窗不关；
- [ ] 输入 `github.com/Ra1nify/F4T`（大小写不限）→ Confirm 可关闭；
- [ ] 勾选「Don't show again」后关闭 → 之后启动不再弹，但 Toast 仍在；
- [ ] 未勾选关闭 → 下次启动继续弹；
- [ ] 装新 bundle 版本 → 即使勾选过也重新弹（版本号变了）；
- [ ] 旋转屏幕 / 切后台再回来 → 弹窗仍在，已输入文本保留；
- [ ] 清应用数据 → 重新弹；
- [ ] VT 分包安装、Bilibili 冷启动均不崩。

## 已知局限

- 答案字符串 `ra1nify/f4t` 在 APK 里可被提取；reseller 只要把答案写进商品说明即可绕过门槛
  ——它的作用是增加摩擦/教育买家，不是防护。
- UI 层不可绕过，但系统层（强停/卸载/清数据）无法拦截。
- 弹窗是"启动即接管"，误伤不熟悉该仓库的用户是设计上接受的成本。
