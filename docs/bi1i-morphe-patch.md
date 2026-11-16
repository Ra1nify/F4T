# bi1i 自毁机制与 Morphe 补丁

面向 `tv.danmaku.bili` + bi1i（`libbiliroamingx.so`）的逆向记录与补丁实现说明。

- 目标宿主：Bilibili 9.18.0，arm64-v8a（APK 内 94 个原生库，单一 ABI）
- 守卫库：`lib/arm64-v8a/libbiliroamingx.so`
  - 大小 `195944` 字节
  - `build-id:5a180747c19c4849433f51799b3997f2c3c30444`
  - 原文件 sha256 `811855f6b60b8ec014017f919672108f47fae73c02d56469e3343c207acf8aed`
- 工具：dexsql（dex）、capstone（arm64 反汇编）、Morphe patcher 2.4.0-dev.4

---

## 1. 现象

原版（bi1i 签名）安装正常。**用任何其它证书重签后，进程在启动后 15–20 秒静默退出**，
系统记录为 `EXIT_SELF status=0`（不是 `APP CRASH`，没有 Java 异常栈）。

这决定了排查方向：干净自杀 ⇒ 有人主动调 `exit(0)`，而不是崩在异常里。

---

## 2. 判决在原生层，执行在别处

`libbiliroamingx.so` 的导入表里**没有** `_exit` / `exit` / `exit_group` / `kill`；
唯一的 `syscall` 调用点（`0x244e4`）传的是 `w0 = 0xb2`（178 = `gettid`），只是个带缓存的 gettid 辅助函数。
`abort` 虽然存在，但那是 SIGABRT，会表现成 `APP CRASH(EXCEPTION)`，和观测到的 `EXIT_SELF status=0` 不符。

结论：**这个库只做判决，不执行**。它把状态码返回给 Java 侧，由 Java 侧完成 `exit(0)`。

---

## 3. 自毁判定的完整形状

### 3.1 武装（每次检测命中都会推一次）

```
0x16610  sub  w9, w8, #1        ; w8 = 命中计数
0x16614  mov  w10, #5
0x16618  cmp  w8, #7
0x1661c  mov  w8, #0x12c        ; 上限 300
0x16620  lsl  w9, w10, w9       ; 5 << (count-1)
0x16624  csel w8, w9, w8, lo
0x16628  add  x8, x8, x1        ; += 当前 tick
0x1662c  adrp x9, #0x32000
0x16630  add  x9, x9, #0xb64
0x16634  stur x8, [x9, #4]      ; deadline = tick + delay   (镜像偏移 0x32b68)
0x16638  stlrb wzr, [x9]        ; 清标志字节
```

同一段代码附近（`0x165ac`–`0x165dc`）还会把「检测到的东西」写进 4 个槽
（`0x377a0` / `0x377a8` / `0x377b0` / `0x377b8`）。
**武装与检测槽同址**，说明倒计时是被「检测器记了一笔」触发的，与具体检测内容无关。

### 3.2 两处判定（全库只有这两条）

```
; 检查 A
0xe780  adrp x8, #0x32000
0xe784  add  x8, x8, #0xb68
0xe788  ldar x8, [x8]           ; deadline
0xe78c  ldur x9, [x29, #-0x60]  ; now
0xe790  cmp  x8, x9
0xe794  mov  w8, #0x2f8e
0xe798  movk w8, #0x3348, lsl #16   ; 过期时的错误码 0x33482f8e
0xe79c  csel w8, w20, w8, gt        ; <-- 唯一的条件步骤
0xe7a0  b    #0xeb88

; 检查 B
0xeb2c  adrp x8, #0x32000
0xeb30  ldr  x8, [x8, #0xb68]
0xeb34  ldur x9, [x29, #-0x60]
0xeb38  cmp  x8, x9
0xeb3c  mov  w8, #0x7274
0xeb40  movk w8, #0xe155, lsl #16   ; 过期时的错误码 0xe1557274
0xeb44  csel w8, w8, w26, gt        ; <-- 唯一的条件步骤
0xeb48  b    #0xeb88
```

两条都汇到同一状态出口 `0xeb88`。

### 3.3 静态补丁

把两条 `csel` 换成无条件 `mov`，"过期"分支就永远不可达：

| 站点 | 文件偏移 | 原指令 | 改后 | 字节 |
|---|---|---|---|---|
| A | `0xe79c` | `csel w8, w20, w8, gt` (`88c2881a`) | `mov w8, w20` | `e803142a` |
| B | `0xeb44` | `csel w8, w8, w26, gt` (`08c19a1a`) | `mov w8, w26` | `e8031a2a` |

这是 LuckyFuse 运行期做法的静态等价物——它在运行时把 `deadline` 写成 `Long.MAX_VALUE`，
我们在文件里让判定不再可能成立。区别是不需要 root、不需要 Xposed，且**只改 `.so`，dex 一个字节不动**。

补丁后该库 sha256 = `2cfc2428af553bd026d03dadf0185cc9c3cd3c2f3ca045a88a532787ffe60208`。

---

## 4. 等级/会员闸门（第二件事）

宿主在网络/账号层保留两个 `public static final` 无参 boolean 闸门，强制为 `true` 即解除等级/会员限制。

定位方式与 LuckyFuse 的解析器一致，用**锚点字符串**：

- 同时引用 `"accountMigrate"` 与 `"bili.passport.auth"` 的类，全宿主唯一 ⇒ `Lkofua/zIp;`
  （`Lkofua/nif;` 只用后者，所以复合锚点是必要的）

该类的方法形状与解析器的结构验证一致：

| 方法 | 形状 | 角色 |
|---|---|---|
| `EUQ()Z` | static 无参 boolean | 闸门 flag |
| `HNf()Z` | static 无参 boolean | 闸门 flag（内部调 `uNA()`） |
| `Ffpb()J` | static 无参 long | 登录 mid 探针 |
| `uNA()Lkofua/OgY;` | static 无参、非 JDK 类型 | 账号探针 |

补丁把它们重写成 `const/4 v0, 0x1 / return v0`（`opcode_hex = 12 0f`），**只动 static 无参 boolean**，
两个探针方法保持原样。

> 与 LuckyFuse 的差异：运行期 hook 会先探测登录态，只对已登录账号放行；本补丁无条件返回 `true`。
> 若将来需要严格对等，可用 extension DEX 加账号探针。

---

## 5. 签名身份不是功能门控（因此不做签名 bypass）

重签后 `cwqb()` 算出的证书 MD5 与宿主内常量 `d6cd98a96eee91706e8689ddffb9bf68` 不一致，
但它的消费者只有 4 个：

```
Lkofua/IKdb;->run()V                 无直接调用者
Lkofua/NJib;->muz(Z)Z                仅 AboutFragment（关于页）+ 两个内部方法
Lkofua/YXB;->ldqb(Object)Object      同时调用 guard 的 native 入口 Lkofua/rr;
Lkofua/mbv;->uNA()V                  无直接调用者
```

**没有任何功能代码用它门控**。所以：重签不会关闭 bi1i 功能，只可能让关于页显示"非官方构建"。
既然无收益，就不为它引入 dex 改动。

`Lkofua/rr;` 是守卫的 JNI 面（`a(I)String` / `b(String)String` / `c()V` / `d()V`），
直接调用者恰好 4 个，其中 `Lkofua/YXB;->ldqb` 与 `Lkofua/YCtb;->ztsb` 都在这条链上 ——
**签名身份 → guard native 入口** 这条通路是成立的。

---

## 6. Gate D：一个早期的假警报

早期用手工 repack 改过 dex，产物**立即**崩在
`NoClassDefFoundError: Failed resolution of: LG0/h;`（`DefaultClassLoaderService.<init>`），
一度被当成"宿主有 dex 完整性校验"。

用 Morphe 正规重建（新增一个 dex 承载被改的类 + 从原 dex 中 strip 掉该类）后，**同样改 dex 完全无痛**。
所以那是手工工具的锅：zip 重排 / dex 重写不完整，不是宿主的清单校验。

⇒ 结论：dex 侧改动没有"物理上限"。任何需要 smali 的补丁（anti-detect、toast、签名常量）都可做，
只是目前按收益判断先不做。

---

## 7. 补丁实现要点

文件布局：

```
patches/src/main/kotlin/app/ra1nify/f4t/
├── shared/Compatibilities.kt           # Compatibility（只声明包名，两个宿主共用）
└── bilibili/
    ├── GuardSelfDestructPatch.kt       # rawResourcePatch：改 .so
    └── GateBypassPatch.kt              # bytecodePatch：改闸门 flag
```

- **不写死文件偏移**：`.so` 侧用 28/24 字节上下文指纹定位，命中数必须恰好 1，否则 `PatchException`
- **不写死类名**：dex 侧用锚点字符串结构定位，要求唯一
- **fail closed**：命中 0 个或多个都不打补丁；错误信息带上实际的 size / sha256 / 各锚点命中情况，方便换版本时重新推导
- **只声明包名**（`tv.danmaku.bili`），不声明 `targets`、`signatures`
  - ⇒ 任意版本可用；且不是 universal patch，因此可以 `default = true`（Manager 里自动勾选）
  - ⇒ 目标是要重签的 APK，所以绝不能写死 `signatures`

---

## 8. 验证方法

### 8.1 离线（不需要设备）

```python
# .so 两处是否改到位
so = zip["lib/arm64-v8a/libbiliroamingx.so"]
so[0xe79c:0xe7a0] == bytes.fromhex("e803142a")
so[0xeb44:0xeb48] == bytes.fromhex("e8031a2a")
```

```bash
# dex 数量应从 37 变 38（新增 classes38.dex）
# 用 dexsql 确认闸门方法体变成 const/4 + return
dexsql query --input classes38.dex \
  --sql "SELECT name, opcode_count, opcode_hex FROM methods
         WHERE class_descriptor='Lkofua/zIp;' AND name IN ('EUQ','HNf')"
# 期望 opcode_hex = "12 0f"
```

### 8.2 设备

装完启动，等 25 秒以上（远超 15–20 秒的自杀窗口），确认：

```bash
adb shell pidof tv.danmaku.bili        # 仍有 pid
adb shell dumpsys activity exit-info tv.danmaku.bili | grep EXIT_SELF   # 无新记录
```

⚠️ 若同时启用 LuckyFuse（其 bi1i scope 也会中和同一份 guard），测试结论会被污染。
验证本补丁时必须关掉模块对该包的作用域。

---

## 9. 换宿主版本时的工作流

1. 用 `_analysis/bili-bi1i/find_guard_state_refs.py` 重新枚举对 `0x32b60/0x32b68/0x377a0…` 的引用，
   找到新的两处 `csel` 与新的上下文指纹（脚本需 `capstone`，注意 `md.skipdata = True`，否则首个非法字节就停）
2. 更新 `GuardSelfDestructPatch.kt` 里的 `SITE_A/SITE_B` 指纹
3. dex 侧跑一次 `list-patches` + 补丁，若闸门类锚点漂移，异常信息会列出 `both/only-accountMigrate/only-passport`
   三组候选，据此调整锚点
4. 重新构建 → 离线验证 → 设备验证

---

## 10. 工具链踩坑（Morphe）

| 坑 | 表现 | 应对 |
|---|---|---|
| GitHub Packages 需要 classic PAT | GCM 存的 `gho_` token 拉包 **401** | 建 `read:packages` 的 classic PAT，写进 `~/.gradle/gradle.properties` 的 `gpr.user`/`gpr.key` |
| 插件从项目的 version catalog 取 patcher 版本 | 改 `libs.versions.toml` 的 `morphe-patcher` 才生效；版本会写进 `.mpp` 的 `Patcher-Version` | 与 desktop 内嵌版本对齐（desktop 2.2.0-dev.14 ⇒ patcher 2.4.0-dev.4） |
| universal patch 必须 `default=false` | loader 直接拒载：`Universal patches must be declared with default false` | 声明 `compatibleWith`，或显式 opt-in |
| `getAllClassesWithString` / `classDefByStrings` 对 Kotlin 不可见 | `Unresolved reference`（javap 里却是 public，实际是 `@JvmSynthetic`） | 用 `mutableClassDefBy { predicate }` / `classDefForEach { }` |
| `MutableMethod.implementation` 只读 | `'val' cannot be reassigned` | 强转 `MutableMethodImplementation` 后 `removeInstruction` + `addInstructions` |
| `patch()` 没有上下文接收者 | 里面既不能用 bytecode 也不能用 resource API（2.4.0-dev.4 实测） | 仍用 `bytecodePatch` / `rawResourcePatch`（deprecation 警告可忽略），等 patcher 提供统一上下文再合并 |
| `local.properties` 带 BOM | 属性名变成 `\ufeffsdk.dir`，SDK 定位失败且报错不指向 BOM | 用无 BOM UTF-8 写。本项目没有 `extensions` 模块，正常情况下**不需要**这个文件 |
| CLI 参数名 | `-p` 是 `--with-packages`，**不是** `--patches` | 用 `--patches=<mpp>` |

---

## 11. 已知限制

- **只覆盖 arm64-v8a**。9.18.0 的 APK 只有这一个 ABI；若遇到含多 ABI 的构建，其它 ABI 的守卫不会被改（但指纹不匹配会 fail closed，不会打错）
- **闸门无条件 `true`**：不判断登录态，未登录会话也按已授权处理
- **只签 V2**：`minSdkVersion 24` 下 V2 足够；V3 只多提供密钥轮换，对补丁产物无收益。若将来宿主 targetSdk/校验要求 V3，再补
- **未做**（按收益判断）：anti-detect（`y30.a$a#a/b/c`）、toast 抑制、签名常量替换
