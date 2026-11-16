# Morphe 源发布调研

> 结论基于 2026-11 的 Morphe 上游源码（`morphe-manager` / `morphe-patches-template` / `morphe-patches-gradle-plugin` / `morphe-documentation`）与实机 Manager 2.6.0-dev.2 的 dex 字符串核对。

## 1. 一句话结论

Morphe「源」= **一个固定文件名 `patches-bundle.json` 的 manifest + 它指向的 `.mpp` 发布资产**。
Manager 按 URL 拉 manifest、按 `download_url` 下载 bundle，可选校验 Ed25519 签名。

官方推荐做法：**用 `morphe-patches-template` 结构 + semantic-release CI，把 GitHub 仓库本身当源**。
该仓库必须是**公开**的——Manager 用无鉴权的 `raw.githubusercontent.com` 直接 GET。

## 2. Manager 侧实现（源码依据）

### 2.1 四种源类型（`domain/bundles/PatchBundleSource.kt`）

| 类型 | 说明 | 可否自定义 |
|---|---|---|
| `APIPatchBundle` | 官方源 `api.morphe.software`，预装 id=0 | 否 |
| `JsonPatchBundle` | **任意 JSON endpoint**（`patches-bundle.json`） | ✅ |
| `GitHubPullRequestBundle` | 拉 GitHub PR 的构建 artifact | ✅（需 Manager 内配 GitHub PAT） |
| `LocalPatchBundle` | 本地 `.mpp` 文件导入 | ✅ |

关键常量（`util/Constants.kt` + `RemotePatchBundle.Companion`）：

```kotlin
BRANCH_STABLE   = "main"            // 稳定通道
BRANCH_DEV      = "dev"             // 预发布通道（Manager 里"允许 pre-release"才用）
DEFAULT_BUNDLE_FILE = "patches-bundle.json"
ADD_SOURCE_PATH = "/add-source"
MORPHE_WEBSITE_URL = "https://morphe.software"
```

因此源 URL 的规范形态是：

```
https://raw.githubusercontent.com/<owner>/<repo>/main/patches-bundle.json   # 稳定
https://raw.githubusercontent.com/<owner>/<repo>/dev/patches-bundle.json    # 预发布
```

`JsonPatchBundle` 从这个地址读 manifest；`usePrerelease` 打开时把 URL 里的分支从 `main` 换成 `dev`（也支持 GitLab 与任意自建 host，但**只有 GitHub/GitLab raw 支持分支切换**）。

### 2.2 manifest（`network/dto/ReleaseAsset.kt`）

Manager 只反序列化这些字段，`created_at` 是**无时区的 UTC ISO-8601**：

```json
{
  "download_url": "https://github.com/<owner>/<repo>/releases/download/v1.2.3/patches-1.2.3.mpp",
  "created_at":   "2026-11-16T10:00:00",
  "description":  "…",
  "version":      "1.2.3",
  "public_key":   "<base64 Ed25519 公钥，可选>",
  "page_url":     "https://github.com/<owner>/<repo>/releases/tag/v1.2.3"
}
```

模板生成的 `patches-bundle.json` 里还有一个 `signature_download_url`（占位），Manager 不读它；真正生效的是 `public_key` + `.mpp` 内嵌签名。

### 2.3 更新判定

```kotlin
if (hasInstalled() && info.version == installedVersionSignature && info.createdAtMillis == createdAt) return null
```

即 atomic 比较 `(version, created_at)`。**改了内容必须改 version 或 created_at**，否则 Manager 认为无更新。

### 2.4 签名（`morphe-patches-gradle-plugin/BundleSigning.kt`）

- 算法 **Ed25519**，签名内嵌在 `.mpp` zip 的 EOCD 处。
- 私钥来源：`MORPHE_SIGNING_KEY` 环境变量，或项目根目录 `morphe-signing.key`。
- 公钥文件名固定 `morphe-signing.pub`，其内容写进 manifest 的 `public_key`（`@MorpheApp/changelog` 插件的 `releaseJson.publicKeyPath`）。
- Manager 首次安装后**把 key pin 到该源**（`signing.key`），之后只接受同 key 的 release；换 key 需用户手动确认。
- 签名只防"发布后被替换"，**不防仓库作者本人**（能 push 的人都能发签名 release）。

### 2.5 添加源

- 一键链接：`https://morphe.software/add-source?github=<owner>/<repo>`（可带 `&name=`；GitLab 用 `?gitlab=`）。
- 该链接只在 endpoint 恰好是 `<repo>/<main|dev>/patches-bundle.json`（repo 根、固定文件名）时才生成，子目录或自定义文件名会拒绝共享。
- Manager 添加源对话框也接受**任意 http(s) URL**（`AddSourcePanel` 的 `RemoteUrlCheck`），非 GitHub/GitLab host 可用，但没有 prerelease 切换、没有仓库头像、没有分享链接。

## 3. 官方发布流水线（`morphe-patches-template`）

### 3.1 分支模型

```
dev  ← 所有开发提交（feat/fix → 自动 pre-release）
 │
 └── merge（禁止 squash）→ main（stable release）
```

`chore:` 提交不触发发布；`fix:` → patch，`feat:` → minor，breaking → major。

### 3.2 必需文件

| 文件 | 作用 |
|---|---|
| `.releaserc` | semantic-release 配置：分支、changelog、manifest 生成、gradle 版本 bump、release 资产、backmerge |
| `package.json` (+`package-lock.json`) | semantic-release 插件依赖 |
| `.github/workflows/release.yml` | CI：npm install → semantic-release → attestation；无发布时校验编译 |
| `patches-bundle.json` | manifest 占位，发布时由 `@MorpheApp/changelog` 覆盖 |
| `patches-list.json` | 补丁清单（第三方工具/README 用），由 gradle 任务生成 |
| `patches/src/main/kotlin/util/PatchListGenerator.kt` | 反射读 `.mpp` → 写 `patches-list.json` |
| `patches/build.gradle.kts` 追加 `generatePatchesList` + `publish` 任务 | 供 semantic-release 调用 |
| `.github/scripts/generate_patches_readme.py` | 把补丁表写进 README |
| `NOTICE` | GPLv3 §7 附加条款：**名字不得暗示 Morphe 官方** |
| `morphe-signing.pub` | 签名公钥（可选但建议） |
| `gradle.properties` 的 `version` | semantic-release 的唯一版本来源 |
| `.github/assets/add-to-morphe.svg` | README 里的"Add to Morphe"按钮 |

### 3.3 发布时发生什么

1. `commit-analyzer` 决定下一个版本 → tag `v<version>`；
2. `gradle-semantic-release-plugin` 把 `version` 写进 `gradle.properties`，执行 `./gradlew publish`；
3. `publish` → `generatePatchesList` → 构建 `.mpp`（若配置了签名 key 则自动签名）+ 生成 `patches-list.json`；
4. `@MorpheApp/changelog` 生成 `patches-bundle.json`（`download_url` 用模板拼 `releases/download/v<ver>/patches-<ver>.mpp`）；
5. exec 步骤把版本写进 `patches-list.json` 并刷新 README 补丁表；
6. `@semantic-release/git` 提交 `CHANGELOG.md / gradle.properties / patches-bundle.json / patches-list.json / README.md`；
7. `@semantic-release/github` 建 release 并上传 `patches-*.mpp`；
8. `attest-build-provenance` 生成构建来源证明；
9. backmerge `main` → `dev`。

### 3.4 CI 认证

`settings.gradle.kts` 从 `gpr.user`/`gpr.key` 或 `GITHUB_ACTOR`/`GITHUB_TOKEN` 取 GitHub Packages 凭据。
Morphe 官方文档明确：**即使是 public 包也需要认证**。模板 CI 只传 `GITHUB_TOKEN`；若在第三方 owner 的仓库里读 MorpheApp/registry 失败，需要额外注入 `read:packages` PAT（映射成 `ORG_GRADLE_PROJECT_gpr_key` 等）。

## 4. f4t 现状 vs 要求

| 项 | 现状 | 要求 |
|---|---|---|
| 仓库可见性 | **private** | **public**（或公开的发布仓库 / 自建 host） |
| 分支 | 仅 `main` | `main` + `dev` |
| 版本 | 自算 `yy.MM.dd.<major>.<minor>` | `gradle.properties` 的 `version`（semantic-release 管理） |
| CI | 无 | `release.yml` + `.releaserc` + `package.json` |
| manifest | 无 | `patches-bundle.json` |
| 补丁清单 | 无 | `patches-list.json` + `PatchListGenerator.kt` |
| README | 无 add-source 按钮 | 需要 badge + 自动补丁表 |
| 签名 | 无 | `morphe-signing.pub` + `MORPHE_SIGNING_KEY` secret（建议） |
| 插件版本 | `app.morphe.patches` 2.1.0 | 模板用 2.1.1（可升） |
| NOTICE | 已存在 | ✅ |

**版本方案是最大冲突点**：`gradle-semantic-release-plugin` 会覆写 `gradle.properties` 的 `version`，并以此命名 tag 和 `.mpp`。现有的日期版本逻辑必须让位，否则要 fork 整条 release 链（模板明确反对自写发布脚本）。

## 5. 三条可行路线

### 路线 A：官方模板流（推荐）

把 f4t 改成模板结构并**公开**。

- 优点：一键 add-source 链接、自动版本/CHANGELOG/README、签名 + attestation、Manager 原生支持 pre-release 通道。
- 代价：仓库公开；版本改用语义化；需要 `dev` 分支 + semantic commit 纪律。
- 公开前的检查项：`local.properties` 已被忽略 / 历史提交无凭据 / docs 里的本机路径可脱敏 / 无 `_analysis` 二进制。

### 路线 B：私有源码 + 公开发布仓库（保持源码不公开）

- 源码仓（私有）继续开发；新建一个**公开**仓库只放 `patches-bundle.json` + release 资产，add-source 链接指向它。
- 优点：源码不公开；仍能享受 add-source 链接与签名。
- 代价：两处维护；发布流程需要自己拼 CI（或复用模板的 release.yml 只做 manifest+资产）。

### 路线 C：自建 JSON 源（完全不公开任何仓库）

- 把 `patches-bundle.json` + `.mpp` 放到任意公网地址（Cloudflare R2 / 对象存储 / VPS / GitHub Pages）。
- Manager 「添加源」里手填 JSON URL。
- 优点：仓库可完全私有；无仓库结构约束。
- 代价：无 add-source 链接、无 prerelease 通道、无自动更新链，manifest 需自己维护（version/created_at/download_url），且签名仍要自己做。

### 路线 D（不发布）：本地 `.mpp` 导入

Manager 支持本地 `.mpp` 导入（`MppImportDialog`），Morphe Desktop 直接 `--patches`。零基建，适合自用，但没有更新提醒。

## 6. 若走路线 A：落地清单

1. 仓库设为 public（Settings → General → Danger Zone）。
2. 从模板或手工补齐：`.releaserc`、`package.json`、`package-lock.json`、`.github/workflows/release.yml`、`.github/scripts/generate_patches_readme.py`、`.github/assets/add-to-morphe.svg`、`patches-bundle.json`、`patches-list.json`、`CHANGELOG.md`、`patches/src/main/kotlin/util/PatchListGenerator.kt`。
3. `patches/build.gradle.kts`：删掉自定义日期版本逻辑，追加 `generatePatchesList` 与 `publish` 任务；`settings.gradle.kts` 升到 `2.1.1`；`gradle.properties` 写 `version = 1.0.0`。
4. 建 `dev` 分支并设为默认开发分支。
5. Settings → Actions → General → Workflow permissions：允许 Actions 创建/批准 PR（backmerge 需要）。
6. 可选签名：`./gradlew generateSigningKey` → 提交 `morphe-signing.pub`、把 `morphe-signing.key` 内容存 `MORPHE_SIGNING_KEY` secret、备份后删除本地 key。
7. README 顶部放：
   ```html
   <a href="https://morphe.software/add-source?github=Ra1nify/f4t">
     <img src=".github/assets/add-to-morphe.svg" alt="Add to Morphe" height="48">
   </a>
   ```
8. 首次 push `dev`（`feat:` 提交）→ 产出 pre-release `v1.0.0-dev.1` 类版本；merge 到 `main` → stable。
9. 验证：
   - `https://raw.githubusercontent.com/Ra1nify/f4t/main/patches-bundle.json` 返回合法 JSON；
   - `download_url` 可直接下载 `.mpp`；
   - Manager 添加源 → 识别 4 个补丁 / 2 个包名 → 能下载并显示签名指纹。

## 7. 已知坑

- **`chore:` 提交不发布会让你以为 CI 坏了**：确认提交类型。
- **私有仓库不会被 Manager 读取**：`raw.githubusercontent.com` 请求无鉴权，直接 404。
- **GitHub Packages 认证**：CI 若报 `401/403`，加 `read:packages` PAT 并以 `ORG_GRADLE_PROJECT_gpr_key` 形式注入。
- **`patches-bundle.json` 的 `created_at` 必须是无时区 UTC**：写成 `...Z` 会解析失败。
- **换签名 key** 会让所有已装用户拒绝下一次更新，需提前说明。
- **命名**：`NOTICE` 7c 禁止暗示 Morphe 官方出品；仓库名/补丁名用 "Ra1nify" 即可，但不要叫 "Morphe Patches"。
- **不要手改生成物**：`patches-bundle.json` / `patches-list.json` / `CHANGELOG.md` 全部由 CI 生成。
- **`local.properties` 的 `morphe.*` 路径**只用于本地一键任务，发布 CI 不依赖它；确认它仍是 gitignored。
