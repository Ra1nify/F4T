# Morphe 补丁写作规范（可复用）

面向"给某个已打补丁/魔改的 Android 应用再做一个 Morphe 补丁"这类任务。
本文只写**与具体宿主无关**的链路经验；某个宿主的逆向结论放在该宿主自己的文档里
（例：bi1i 见 [`bi1i-morphe-patch.md`](bi1i-morphe-patch.md)）。

适配版本：`app.morphe.patches` Gradle 插件 2.1.0 + `morphe-patcher` 2.4.0-dev.4
（对应 Morphe Desktop 2.2.0-dev.14 / Morphe Manager 2.6.0-dev.2 内嵌的 patcher）。

---

## 1. 前置条件

| 需要 | 说明 |
|---|---|
| JDK 21 | 这版 AGP / Kotlin 要求 17+；实测 21 可用 |
| classic PAT（`read:packages`） | 插件与 `morphe-patcher` **只发在 GitHub Packages**，匿名读不到，Maven Central 也没有。GCM 存的 `gho_` OAuth token 会 401 |
| 网络 | 首次构建要下 Gradle 发行包（wrapper） |
| Android SDK | **只有引入 `extensions` 模块时才需要**，见 §2 |

凭据写在 `~/.gradle/gradle.properties`：

```properties
gpr.user=<用户名>
gpr.key=<PAT>
```

> 注意：这个文件是明文的。用短期 token，用完吊销。

---

## 2. 最小仓库骨架

### 2.1 保留

```
build.gradle.kts                 # 可以只有一行注释
settings.gradle.kts              # pluginManagement + id("app.morphe.patches") version "2.1.0"
gradle.properties                # version = <补丁版本号>  ← 决定 .mpp 文件名与 MANIFEST Version
gradle/libs.versions.toml        # morphe-patcher 版本（插件从这里读）
gradle/wrapper/**, gradlew*      # wrapper
patches/build.gradle.kts         # group + about 段
patches/src/main/kotlin/<包名>/**
LICENSE / NOTICE / .gitignore
```

`patches/build.gradle.kts` 最小形态：

```kotlin
group = "app.example.patches"

patches {
    about {
        name = "…"
        description = "…"
        source = "na"; author = "…"; contact = "na"; website = "na"; license = "GPLv3"
    }
}
```

### 2.2 从官方模板删掉（除非确实需要）

| 删掉 | 理由 |
|---|---|
| `extensions/**` | 只有在需要往宿主注入**运行期 Java 逻辑**时才要。它会让构建依赖 Android SDK/AGP；删掉后 `./gradlew buildAndroid` 只需要 JDK，`.mpp` 也从 ~700 KB 降到 ~20 KB |
| `.github/**`、`.releaserc`、`package.json`、`package-lock.json`、`CHANGELOG.md` | semantic-release 发版链。私人仓库手动分发 `.mpp` 时纯噪音，且 `release.yml` 还要 `MORPHE_SIGNING_KEY` 才能跑 |
| `patches-bundle.json`、`patches-list.json` | 发版时由 `release.yml` 生成，手动编辑会破坏第三方工具 |
| `patches/.../util/PatchListGenerator.kt` + `gson` 依赖 + `generatePatchesList` 任务 | 同上，只为生成 patch list |

### 2.3 `version` 与产物命名

`gradle.properties` 的 `version` 直接决定：

- `.mpp` 文件名：`patches-<version>.mpp`
- MANIFEST `Version`

---

## 3. 选哪种补丁

| 要改的东西 | 用 | 能拿到什么上下文 |
|---|---|---|
| APK 里的**文件内容**：`.so`、assets、任意 zip 条目 | `rawResourcePatch` | `ResourcePatchContext`：`get(path): File`、`listApkEntries(prefix)` |
| 资源 XML / 解码后的资源 | `resourcePatch` | `ResourcePatchContext` + `document(...)` |
| **字节码**：改方法体、返回值 | `bytecodePatch` | `BytecodePatchContext`：`classDefBy{}`、`mutableClassDefBy{}`、`classDefForEach{}`、`navigate()` |
| 需要往宿主注入**复杂 Java 逻辑** | 上面任一 + `extendWith("extensions/x.mpe")` | 宿主里多出你编译好的类 |

> **`patch()` 目前不能替代上面这些**：2.4.0-dev.4 的 `patch { execute { } }` 是
> `Function0`（**没有接收者**），既用不了 bytecode 也用不了 resource API —— 实测编译报
> `unresolved reference`。官方 deprecation 提示说"用 patch() 可以同时用两者"，但该版本尚未提供
> 统一上下文。**继续用 `bytecodePatch` / `rawResourcePatch`，忽略 deprecation 警告**，
> 等 patcher 真正提供统一上下文再合并。

### 3.1 直接改字节（native 等）

```kotlin
val somePatch = rawResourcePatch(name = "…", description = "…", default = true) {
    compatibleWith(COMPATIBILITY_TARGET)
    execute {
        val file: File = get("lib/arm64-v8a/libfoo.so")
        val bytes = file.readBytes()
        // 定位 + 改字节
        file.writeBytes(bytes)
    }
}
```

`get()` 返回的是工作区里的可变文件，改完由 patcher 重新打包并签名。

### 3.2 改方法体

```kotlin
val somePatch = bytecodePatch(name = "…", description = "…", default = true) {
    compatibleWith(COMPATIBILITY_TARGET)
    execute {
        val clazz = mutableClassDefBy { it.usesString("ANCHOR") }   // 唯一性由它自己保证
        val flags = clazz.methods.filterIsInstance<MutableMethod>()
            .filter { it.isStaticZeroArgBoolean() }
        if (flags.isEmpty()) throw PatchException("…")               // fail closed
        flags.forEach { it.forceReturnTrue() }
    }
}

private fun MutableMethod.forceReturnTrue() {
    // implementation 是只读属性，必须强转后原地改
    val body = implementation as? MutableMethodImplementation
        ?: throw PatchException("$definingClass->$name has no mutable implementation")
    while (body.instructions.isNotEmpty()) body.removeInstruction(body.instructions.size - 1)
    addInstructions(0, "const/4 v0, 0x1\nreturn v0")
}
```

---

## 4. 定位目标的策略（重要）

宿主每次升级，混淆名和偏移都会变。按可靠性从高到低：

1. **锚点字符串**（最稳）
   找一段"功能上必然存在"的常量，例如配置 key、协议字段、URL、资源名。
   组合两个锚点通常就能唯一定位一个类。
2. **结构谓词**（次稳）
   在候选类上验证形状：static/无参/返回类型/参数个数/字段类型/调用关系。
   用 `mutableClassDefBy { }` 或 `classDefForEach { }` 实现。
3. **字节指纹**（native 专用）
   取目标指令前后 20–30 字节作为指纹。指纹越长越安全；**不要用 4 字节**（会撞）。

**强制要求**

- 命中数必须 **恰好 1**；0 个或多个一律 `PatchException`，绝不"猜一个最近的"
- 错误信息必须带**可诊断事实**，让人能立刻重新推导：
  - native：文件大小、sha256、命中数、期望偏移
  - dex：各锚点的命中情况（both / only-A / only-B 的类列表）
- 不要写死混淆类名/方法名；不要写死文件偏移；不要静默 fallback

---

## 5. 常见坑速查

| 症状 | 原因 | 对策 |
|---|---|---|
| 插件/依赖解析 401 | GitHub Packages 需要 classic PAT `read:packages`；`gho_` OAuth token 不行 | 建 classic PAT，写 `gpr.user`/`gpr.key` |
| 改了 `libs.versions.toml` 的 patcher 版本没生效 | 插件确实会读这个别名（`catalog.findVersion("morphe-patcher")`），但版本若与你手上的 Desktop/Manager 内嵌版本不匹配，加载会失败 | 用 `Patcher-Version`（mpp MANIFEST）对齐对方的 patcher 版本 |
| `Universal patches must be declared with default false` | 没声明 `compatibleWith` ⇒ universal ⇒ 必须 opt-in | 声明 `Compatibility(packageName=…)`（**不写 targets** 即"任意版本"），从而可 `default = true` |
| `Unresolved reference 'getAllClassesWithString'` | 该类方法对 Kotlin 不可见（`@JvmSynthetic`），javap 里却是 public | 改用 `mutableClassDefBy { }` / `classDefForEach { }` |
| `'val' cannot be reassigned`（改方法体时） | `MutableMethod.implementation` 只读 | 强转 `MutableMethodImplementation` 后 `removeInstruction`/`addInstructions` |
| `SDK location not found` | 存在 `extensions` 模块（Android library） | 删 `extensions/`，或补 `local.properties` 的 `sdk.dir` |
| `local.properties` 被忽略 | 文件头有 UTF-8 **BOM**，属性名变成 `\ufeffsdk.dir` | 用无 BOM UTF-8 写 |
| CLI 找不到补丁包 | `-p` 是 `--with-packages`，不是 `--patches` | 用 `--patches=<mpp>` |
| 手工 repack 改 dex 后启动即崩 | 手工 zip/dex 重写不完整（**不是**宿主的完整性校验） | 交给 patcher 正规重建：它会新增一个 dex 承载被改的类，并从原 dex strip 掉该类 |
| `.mpp` 体积极大 | 模板的 `extensions` 模块被打进去了 | 不用 extension 就删掉 |

---

## 6. 验证闭环

**先把离线能验的都验完，再上设备**，这样设备只用来回答"功能对不对"。

### 6.1 bundle 冒烟

```bash
java -jar morphe-desktop-*-all.jar list-patches --patches=patches/build/libs/patches-*.mpp
```

应看到你的补丁、`Enabled: true`、以及声明的包名。**这里报错比在设备上报错便宜得多**
（例如 universal/default 规则、类加载失败都会在这一步暴露）。

### 6.2 打一次补丁

```bash
java -jar morphe-desktop-*-all.jar patch <input.apk> --patches=<mpp> \
  -e="<补丁名>" -o <out.apk> \
  --keystore=<keystore> --keystore-entry-alias=<alias> \
  --keystore-password=<pw> --keystore-entry-password=<pw> \
  -r <result.json>
```

> PowerShell 里传参用数组形式 `@('-o', $path)`，不要写 `-o=$path`——实测某些情况下变量不会展开。

### 6.3 产物离线校验（按补丁类型）

| 补丁类型 | 检查什么 |
|---|---|
| native / 文件级 | 目标条目**只有预期的那几个字节变了**（与原始 APK 逐字节比对）；条目压缩方式是否与原来一致（`compress_type`）；改了 `.so` 时确认没有连带改动其它条目 |
| bytecode | `.dex` 数量（通常 +1）；被改的类**只在一个 dex 里定义**（避免 DuplicateClass）；用 dexsql 看方法体：`SELECT name, opcode_hex FROM methods WHERE …`，与预期指令序列比对；被改类里的其它方法未被误伤 |
| 通用 | `apksigner verify --print-certs`；`aapt2 dump badging` 看 minSdk/targetSdk 是否仍符合预期 |

### 6.4 设备校验

```bash
adb shell am start -n <pkg>/<主 Activity>
adb shell pidof <pkg>                                   # 超时点后仍在
adb shell dumpsys activity exit-info <pkg> | grep EXIT_SELF
```

**关键陷阱**：如果设备上还装着别的 hook 框架（LSPosed 模块等）且对同一目标生效，
它可能**替你掩盖**了问题，让你误判自己的补丁有效。验证前务必：
关掉该框架对目标包的作用域，或核对框架持久日志（确认补丁生效时间窗内**没有**注入记录）。

---

## 7. 接入一个新应用的 checklist

1. **确认目标形态**：官方包 / 别人已魔改的包？后者才有"守卫/自毁/签名校验"这类问题
2. **静态摸底**，别急着写补丁：
   - 是否有 native 守卫库？导入表里有没有 `_exit`/`exit`/`kill`/`syscall`
   - 自毁/校验是"判决"还是"执行"？判决在 native、执行在 Java 的非常常见
   - 触发条件是签名？检测到 hook？文件完整性？**先定位触发点，再决定补哪里**
   - 签名判定是否真的门控功能（查它的消费者，可能只是"关于页"文案）
3. **建仓**：复制模板 → 按 §2.2 删干净 → 改 `group`/`about`/`rootProject.name`/`version`
4. **写定位逻辑**：锚点字符串 → 结构谓词 → （native）字节指纹；每步都 fail closed
5. **离线验证**：§6.1 → §6.2 → §6.3
6. **设备验证**：§6.4，并排除其它框架污染
7. **文档**：把该宿主专属的逆向结论写成 `docs/<host>-*.md`，通用经验沉淀到本文

---

## 8. 兼容性与分发

- **`Compatibility`**：只写 `packageName` ⇒ 任意版本可用，且可 `default = true`（用户不用手勾）。
  一旦写 `targets`，宿主升级后补丁就会"不匹配"而失效——只在补丁**确实**绑死某版本时写。
- **`signatures`**：除非要严格拒绝改签包，否则**不要写**。目标本身是重签包时写死签名哈希会让
  用户拿着合法文件也被拒。
- **`Patcher-Version`**：写在 `.mpp` MANIFEST 里，用于和 Desktop/Manager 内嵌 patcher 比对。
  用不高于对方版本的 patcher 编译最安全；用了新 API 就必须提高它。
- **签名方案**：`minSdk ≥ 24` 时 **V2 足够**，V1 不需要；V3/V3.1 只增加密钥轮换能力，
  对补丁产物无收益（且 lineage 无法事后补）。
- **Android 端打补丁的证书冲突**：Manager 用它自己的签名器出包，证书和你之前装的版本不同 ⇒
  不能原地覆盖安装，必须先卸载（会丢数据）。要避免，就在 Manager 里把签名器换成同一份 keystore。

---

## 9. 反模式

- 写死混淆类名 / 方法名 / 文件偏移
- 只匹配 4 字节字节串就改指令
- 命中 0 个或多个时"继续执行"或"取第一个"
- 用 `getOrNull()`、空 catch 吞掉定位失败
- 把实验性探针（伪造签名、入口打点）留在发布补丁里
- 手工 repack 改 dex/`.so` 而不走 patcher（会出现莫名 `NoClassDefFoundError`，误判成宿主完整性校验）
- 手工编辑 `patches-list.json` / `patches-bundle.json` / `CHANGELOG.md` 这些生成物
