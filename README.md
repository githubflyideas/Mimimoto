# mimimoto

父母的声音，在他们不在场的时候，继续给孩子讲故事。

---

## 这是什么

家长发一条 10 秒语音，孩子听到 15 分钟父母声音讲的故事。

```
爸爸："今天讲三只小猪吧，小猪就叫小豆子"
  ↓ 10 秒，通勤路上随手一条
系统：这条语音同时是 ① 指令 ② 当日新鲜声纹样本
  ↓
孩子：20:30，15 分钟爸爸声音的、主角叫小豆子的故事
```

那条语音既是指令也是样本，所以家长不需要"完成录音任务"——采样是使用的副产品。
同类产品的首要死因是冷启动，这个循环是为了绕开它。

## 当前状态

**立项阶段的骨架。** 核心算法完整且有测试覆盖，周边是可工作的最小实现。

真正该做的下一步不是继续写代码，而是**手工版**：给 5 个家庭手动生成音频、发过去、观察两周。
成本接近零，但能证伪最大的风险——孩子买不买账。`qc` 就是为这一步准备的。

| 模块 | 状态 |
|---|---|
| `core/audioqc` | ✅ 完整，15 项测试。纯 Kotlin，服务端与 App 共用同一份 |
| `server/schedule` | ✅ 完整，20 项测试。6 时区整年 DST 遍历 |
| `server/pipeline` | ✅ 完整，12 项测试 |
| `server/tts` | ✅ 接口 + CosyVoice 2 客户端 + mock |
| `core/json` | ✅ 内置最小实现，6 项测试 |
| `server/httpapi` | ⚠️ 仅覆盖已存在的流程，8 项集成测试（真实 socket） |
| `server/store` | ⚠️ 内存实现。存储形态是未决问题，见 DECISIONS |
| `web/` | ✅ Safari/Chrome 可装的 Web App。录音→门禁→判决，离线可用 |
| `android/` | ⚠️ 录音+门禁跑通，界面是最小实现。`PcmRecorder` 已对桩编译验证，其余靠 CI |
| `worker/` | ⚠️ 骨架，未在 GPU 上验证过 |
| iOS | ❌ 未开始，见 docs/RELEASING.md |

Kotlin / JDK 17+。服务端**运行时零依赖**——`java.time`、`java.net.http`、
`com.sun.net.httpserver` 全在 JDK 里，WAV 解码和 JSON 都是仓库内的实现，
不是要跟版本的依赖。安卓端只加 Compose。

`core` 不碰任何 JVM 桌面 API，所以**服务端和手机跑的是同一份门禁代码**。
这不是洁癖：家长被 App 判「重录」、同一条录音却被服务端接受，就是我们自相矛盾被用户抓到。
一份实现让这件事不可能发生，而不只是不太可能。

## 两个核心模块

### audioqc — 音频质量门禁

克隆质量由参考音频质量主导，而烂参考**不会大声失败**——它产出一个"几乎像"父母的声音。
孩子对语调的敏感度远高于成人，会比任何成年人更早察觉不对。所以：样本要么过这道门，要么永不进入合成。

不信任容器声明的采样率。安卓 HAL 层把 16kHz 上采样成 48kHz 是常态，唯一的识别方法是看频谱在哪里停止：

| 信号 | 实测 cutoff | 判定 |
|---|---|---|
| 真 48kHz 麦克风 | 19969 Hz | PASS |
| 16k 上采样伪装 48k | 8016 Hz | WARN |
| 通话 / VoIP 链路 | 3492 Hz | **FAIL** |

这三个数字被钉进了断言（`cutoff discriminates the three classes at the measured values`），
防止后续改动悄悄把三类糊到一起。另测 SNR、削波（只算连续段，孤立峰值不算）、有效语音时长、DC 偏置。

```console
$ ./gradlew :server:qc -Pargs="--profile enrolment dad.wav"
dad.wav                                  FAIL
  48000 Hz / 16-bit / 1 ch, 30.0s (23.3s speech, 78%)
  SNR 65.2 dB   cutoff 3492 Hz   clipping 0.00%
  ✗ band_limited: audio is band-limited; it has been through a call or
    voice-chat pipeline and is not usable as a reference
```

### schedule — 时区调度与降级阶梯

送达锚定**孩子本地时区**，DST 策略显式定义：

- 本地时刻不存在（春季跳变）→ 时钟跃过该时刻的瞬间
- 本地时刻出现两次（秋季回拨）→ 较早的一次

`ZoneRules.getValidOffsets()` 返回 0/1/2 个 offset，正好就是这三种情况，所以策略是一个
`when(size)` 而不是需要推导的东西。把 `WallKind` 和时刻一起记下来，是为了让 DST 那两天
能在测试里断言，而不是从工单里发现。

降级阶梯保证**孩子的 20:30 永不空白**，且家长忘了录音时，惩罚不落在孩子身上：

| 当天状态 | 孩子听到 | Tier |
|---|---|---|
| 有当日语音 | 开场白 + 当日点的故事 | `fresh` |
| 无当日语音，有库存 | 库存开场白 + 故事 | `inventory` |
| 只有声纹 | 无开场白，预设故事 | `voice_only` |
| 合成失败 | 重播已缓存 | `cached` |

`voice_only` 及以下**不提示"爸爸没来"**。激励靠开场白的稀缺性，不靠断供。

`Plan.startGenerationAt` 是 GPU 队列的全部调度策略：按它排序即可。
12 语言 × 全球时区意味着睡前高峰在 UTC 上滚动一整圈，按 deadline 排序会自动填谷。

## 手机上跑（最快的一条）

`web/` 是一个可以「添加到主屏幕」的 Web App，iPhone 和安卓都能用，不需要 Mac、
不需要开发者账号、不需要装任何东西。推一次代码，GitHub Pages 自动部署
（仓库里开一次：Settings → Pages → Source → GitHub Actions）。

用的是同一份 `audioqc.js`，跟 Kotlin 服务端逐项对过数（19969 / 8016 / 3492 Hz）。

**它能做的**：按住录音 → 拿到未压缩 PCM（AudioWorklet，不是 `MediaRecorder`，理由见
D-014）→ 当场出判决和频谱图 → 逐条下载 WAV 存档。离线可用，不联网。

**它做不到的**：iOS 不让网页选「未处理」采集源，系统降噪绕不掉。界面上那行
「浏览器实际给的」就是告诉你这次到底拿到了什么——原生 App 能绕，网页不能。

## 拿到 APK

推一次代码，CI 自动出包，在 **Actions → Artifacts** 下载安装。用 debug keystore
自动签名，不需要配任何密钥。细节和正式版签名见 `docs/RELEASING.md`。

IPA 需要 macOS + Xcode + 你自己的 Apple 开发者证书，而且还需要一个 iOS 客户端——
同样见 `docs/RELEASING.md`，里面写了两条路和推荐哪条。

## 构建

```console
$ ./gradlew :server:suite                                  # 测试套件
$ ./gradlew :server:qc -Pargs="--json recordings/*.wav"    # 质量门禁
$ ./gradlew :server:run                                    # 服务
$ ./gradlew :android:assembleDebug                         # APK（需 Android SDK）
```

没有 Android SDK 的机器上 `:android` 会被自动跳过，服务端照常构建。

构建主机连不上 Maven 时，用 kotlinc 直接编译（项目零依赖、测试用仓库内 runner，所以这条路是通的）：

```console
$ KOTLINC=/path/to/kotlinc/bin/kotlinc ./scripts/build.sh
```

TTS worker 需要 GPU 和 CosyVoice 2，装法见 `worker/requirements.txt` 顶部。

## 红线

这几条写进了架构而不是隐私政策，拆掉它们需要先读 `docs/DECISIONS.md` 里的理由：

- **绝不生成父母没说过的话**（D-002）。`StorySource` 是只含已批准值的 enum，非法来源**构造不出来**；
  唯一的字符串入口 `StorySource.fromWire` 直接拒绝而不是给默认值。合成链路上**没有**重复检查——
  死代码正是红线悄悄失效的方式：后人读到不可达分支，认为冗余，连真正的守卫一起删掉。
- **永不采集儿童语音**（D-003）。孩子端不申请麦克风权限。`Child` 里没有任何字段能存音频。
  这同时是绕开 COPPA/GDPR-K 和留在 App Store Kids Category 之外的凭据。
- **声纹注册需活体挑战**（D-005）。API 里**没有**"上传音频来克隆"的路径——那是诈骗工具的形状。
- **对端音频永不落盘**（D-004）。德国刑法 §201 把未经同意录制他人言语定为刑事犯罪。

## 未决

- **声纹存哪**。服务端集中存储是 GPU 调度的前提，但等于持有一个泄露后无法重置的高价值资产。
  方向是每家独立密钥 + 原始录音短期销毁 + 长期只留 embedding。**在验证孩子买不买账之前属于过早优化。**
- **首发打哪个市场**。引擎（CosyVoice 2）支持中日英韩粤，泰越葡西暂时做不了——
  移工输出国那条线要等换引擎或解决许可，见 D-015。
- 已故父母声音的伦理立场。产品一定会被这样使用，主动做还是明确禁止，出事之前定。

详见 `docs/DECISIONS.md`。
