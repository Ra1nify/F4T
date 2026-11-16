# VirtualTablet Pro 解锁：逆向记录与补丁实现

目标宿主：`com.sunnysidesoft.VirtualTablet.lite` 4.5.0（versionCode 50，minSdk 24，targetSdk 36）
补丁版本：`4.5.0`（跟随宿主版本）
工具：dexsql（dex）、Morphe patcher 2.4.0-dev.4

---

## 1. 结论先行

这是一个 **Flutter + RevenueCat** 应用：购买判断在 Dart AOT 快照里，**静态改不到**；
但 RevenueCat 的 Kotlin 层是**未混淆**的，而且"客户端认为你有什么权益"这件事有一个
**唯一收口**——把 JSON 解析成 `EntitlementInfo` 的那一步。补丁就打在收口处：

> 把 `buildEntitlementInfos` 的三个入参替换成常量 JSON，并强制 `verification = VERIFIED`。

于是 RevenueCat 自己构造出"永久有效的 pro 权益"，`hybridcommon` 把它映射成 `Map` 交给 Dart，
Dart 侧看到的就是正常订阅——**不需要 root、不需要 Xposed、不需要改 Dart**。

第二个杠杆（兜底）：`EntitlementInfo.isActive()` 恒 `true`，覆盖绕过 JSON 解析的读取路径。

---

## 2. 应用结构（决定了能改什么）

| 事实 | 影响 |
|---|---|
| **split 安装**：`base.apk` + `split_config.arm64_v8a` + 4 个语言/密度 split | 桌面 CLI 需要一个输入包；Morphe Android 版能处理分包 |
| Dart 逻辑在 `lib/arm64-v8a/libapp.so`（9.5 MB AOT 快照） | 业务判断**静态不可达**，只能改它的输入 |
| `libflutter.so`、`assets/flutter_assets/` | 确认是 Flutter；Dart `print` 在 release 不输出 |
| RevenueCat 类完全未混淆（`com.revenuecat.purchases.*`） | 可直接按类名/结构定位，无需锚点字符串 |
| 同时带 `in_app_purchase` 与 `billingclient` | 重签后真实购买/恢复会失效，但不影响绕过 |
| base.apk 三个 dex **全部 STORED（未压缩）** | 改动 dex 不会牵扯压缩方式 |

**没有任何 dex 方法引用精确字符串 `"pro"`** —— 权益 ID 的判断在 Dart 侧，这是"必须注入数据而不是翻转判断"的直接证据。

---

## 3. 为什么不能直接照搬运行期 hook

LuckyFuse 的做法是 hook `EntitlementInfos(Map, VerificationResult)` 构造器，
用反射构造一条 16 参数的 `EntitlementInfo` 塞进 map。

静态做不到等价操作：**那个构造器的寄存器数量装不下 16 个参数的 `invoke-direct/range`**，
而 Morphe 的 `MutableMethod.implementation` 是只读属性，无法扩大寄存器文件。

可行的落点只有两个：
1. 找寄存器足够多的方法（`buildEntitlementInfo` 有 22 个寄存器），但它只在"权益已存在"时被调用；
2. **上游注入数据**，让 RevenueCat 自己去构造对象 ← 本补丁的选择。

---

## 4. 注入点

```
服务器/缓存 JSON
   └─ EntitlementInfoFactoriesKt.buildEntitlementInfos(entitlements, subscriptions,
                                                       nonSubscriptionsLatestPurchases,
                                                       requestDate, verificationResult)
        └─ 遍历 entitlements.keys()
             ├─ productIdentifier = entitlements[id].optString("product_identifier")
             ├─ subscriptions.has(productIdentifier) ?     → buildEntitlementInfo(...)
             └─ nonSubscriptionsLatestPurchases.has(pid) ? → buildEntitlementInfo(...)
        └─ new EntitlementInfos(map, verificationResult)
```

`.registers 12`，参数在 `v7..v11`（依次为 entitlements / subscriptions /
nonSubscriptionsLatestPurchases / requestDate / verificationResult）。

补丁在方法开头插入：

```smali
const-string v0, "{\"pro\":{\"product_identifier\":\"vt_pro_static\",
                \"purchase_date\":\"2024-01-01T00:00:00Z\",\"ownership_type\":\"PURCHASED\"}}"
new-instance v1, Lorg/json/JSONObject;
invoke-direct {v1, v0}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
move-object v7, v1                       # entitlements

const-string v0, "{\"vt_pro_static\":{\"store\":\"app_store\",\"period_type\":\"normal\",
                \"original_purchase_date\":\"2024-01-01T00:00:00Z\",
                \"ownership_type\":\"PURCHASED\",\"is_sandbox\":false}}"
new-instance v1, Lorg/json/JSONObject;
invoke-direct {v1, v0}, Lorg/json/JSONObject;-><init>(Ljava/lang/String;)V
move-object v8, v1                       # subscriptions

sget-object v11, Lcom/revenuecat/purchases/VerificationResult;->VERIFIED:...
```

寄存器号不是硬编码：静态方法的前 `n` 个参数位于最后 `n` 个寄存器，所以基准是
`registerCount - 5`，再按顺序 `+1`、`+4`。换版本时寄存器数量变化也不会错位。

### 4.1 字段归属（这是最容易搞错的地方）

`buildEntitlementInfo(rawEntitlement, identifier, productData, requestDate, verification)`
从**两个不同的 JSON** 读字段：

| 字段 | 来自 | 读取方式 |
|---|---|---|
| `product_identifier` | rawEntitlement | `getString`（**必须存在**） |
| `expires_date` | rawEntitlement | `optDate`（缺省 = null = 永久） |
| `purchase_date` | rawEntitlement | `getDate`（**必须存在且可解析**） |
| `product_plan_identifier` | rawEntitlement | `optNullableString` |
| `store` | productData | `Store.Companion.fromString`（**小写**服务器形式） |
| `period_type` | productData | `optString` + 小写 switch（normal/trial/intro/prepaid） |
| `original_purchase_date` | productData | `getDate` |
| `unsubscribe_detected_at` / `billing_issues_detected_at` | productData | `optDate` |
| `is_sandbox` | productData | `optBoolean` |
| `ownership_type` | 两者皆可 | `optOwnershipType` |

放错位置的表现：`getString`/`getDate` 抛异常 → 整个 `CustomerInfo` 构建失败 →
Dart 拿不到数据 → UI 显示未解锁，**但不会有任何崩溃或日志**。

---

## 5. 验证方法

### 5.1 离线（不需要设备）

```bash
# 注入是否落进 dex（Morphe 会新增一个 dex 承载被改的类）
dexsql decode --input out.apk --file-id <新dex> --method \
  'Lcom/revenuecat/purchases/common/EntitlementInfoFactoriesKt;->buildEntitlementInfos(...)'
# 期望前 8 条就是我们插入的 const-string / new-instance / move-object

# 类定义必须唯一（否则 DuplicateClass）
# → 只有新 dex 里存在该类的定义
```

### 5.2 设备

诊断补丁 `Diagnostics: log RevenueCat channel calls`（`default=false`）打开后，
logcat tag `VTPatch` 会依次打印：

```
setupPurchases → getCustomerInfo → syncPurchases → getCustomerInfo
entitlement factory entered
inputs replaced
buildEntitlementInfo entered
EntitlementInfos ctor entered     ← 关键：没有这一行说明 builder 抛了异常
getOfferings
```

⚠️ **必须启动两次再判断**：第一次启动常常走不到成功路径，第二次（网络刷新后）才生效。

⚠️ logcat 主缓冲只有 256 KiB，OPPO 等机型十几秒就冲干净。要先挂过滤器再启动：

```bash
adb logcat -c && adb logcat -s VTPatch:D    # 另开一个窗口挂着
adb shell am start -n <pkg>/<MainActivity>
```

---

## 6. 踩坑清单

| 症状 | 原因 | 处理 |
|---|---|---|
| 注入执行了，但 `EntitlementInfos ctor` 从不出现 | `buildEntitlementInfo` 抛异常（字段放错/缺失） | 用诊断补丁逐段确认；对照 §4.1 的字段归属 |
| 改了个字段后"突然好了" | 首次启动 vs 第二次启动的差异被误当成修复 | 每次改完都启动两次再判定 |
| `VerifyError: cannot access instance field val$result from object of type String` | 插入日志时算错 locals，覆盖了 `this` | `locals = registerCount - (参数数 + 是否实例)`，`< 2` 就不许插日志 |
| 补丁阶段成功、运行即崩 | 同上——dex 校验在类首次使用时才发生 | 任何插桩都要先算寄存器，别靠试 |
| `store` 写成不认识的值 | `fromString` 只接受固定小写集合 | 见 §4.1；`play_store`/`app_store` 均合法 |
| Dart 侧没有任何输出 | release 构建的 `print` 不落地 | 只能从 Kotlin 层打点 |

---

## 7. 换版本时的工作流

1. 拉新包，确认 RevenueCat 仍未被混淆：
   `dexsql query --input base.apk --sql "SELECT descriptor FROM classes WHERE descriptor LIKE 'Lcom/revenuecat/purchases/%'"`
2. 确认收口方法仍在且形状不变：静态、5 参、返回 `EntitlementInfos`、
   且引用 `nonSubscriptionsLatestPurchases` 字符串（补丁的锚点）
3. 若字段语义变了，用诊断补丁跑一次看断在哪一步
4. 重新构建 → 离线验证 → 设备验证（启动两次）

---

## 8. 限制

- 只对 **本应用本版本** 的 RevenueCat 层生效；Dart 侧若额外校验（例如校验 `verification` 字符串
  之外的东西）需要另找落点
- `store` 字段目前写的是 `app_store`。功能上无碍，但 Play 应用写 `app_store` 在语义上不准确；
  若要修正为 `play_store`，改完必须重新验证（首启/二启两轮）
- 首次启动不一定立即生效（见 §5.2）
- 重签后 Play 相关的真实购买/恢复能力失效——不影响解锁，但用户如果想真的买/恢复要用原版
- 未声明 `targets`：补丁对任意版本可用，版本漂移时靠 fail-closed 报错而不是静默错改
