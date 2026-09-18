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

**立项阶段的骨架。** 核心算法已实现并有测试覆盖；周边是可工作的最小实现。

真正该做的下一步不是继续写代码，而是**手工版**：给 5 个家庭手动生成音频、微信发过去、观察两周。
成本接近零，但能证伪最大的风险——孩子买不买账。`mimimoto-qc` 就是为这一步准备的。

| 模块 | 状态 |
|---|---|
| `internal/audioqc` | ✅ 完整，有测试。纯 Go，零依赖 |
| `internal/schedule` | ✅ 完整，有测试。5 时区整年 DST 遍历 |
| `internal/pipeline` | ✅ 完整，有测试 |
| `internal/tts` | ✅ 接口 + FireRedTTS3 客户端 + mock |
| `internal/store` | ⚠️ 内存实现。存储形态是未决问题，见 DECISIONS |
| `internal/httpapi` | ⚠️ 仅覆盖已存在的流程 |
| `worker/` | ⚠️ 骨架，未在 GPU 上验证过 |
| 客户端 | ❌ 未开始 |

零外部依赖（Go 侧），`go test ./...` 即可全绿。

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

三类分得很开，判别稳健。另测 SNR、削波（只算连续段，孤立峰值不算）、有效语音时长、DC 偏置。

```console
$ mimimoto-qc -profile enrolment dad.wav
dad.wav                                  FAIL
  48000 Hz / 16-bit / 1 ch, 30.0s (23.2s speech, 77%)
  SNR 64.1 dB   cutoff 3492 Hz   clipping 0.00%
  ✗ band_limited: audio is band-limited; it has been through a call or
    voice-chat pipeline and is not usable as a reference
```

### schedule — 时区调度与降级阶梯

送达锚定**孩子本地时区**。DST 策略显式定义而非依赖 `time.Date` 的归一化行为——
Go 在重复时刻的选择明确声明为不保证，而这个产品里"晚一小时"意味着孩子已经睡着了。

- 本地时刻不存在（春季跳变）→ 时钟跃过该时刻的瞬间
- 本地时刻出现两次（秋季回拨）→ 较早的一次

降级阶梯保证**孩子的 20:30 永不空白**，且家长忘了录音时，惩罚不落在孩子身上：

| 当天状态 | 孩子听到 | Tier |
|---|---|---|
| 有当日语音 | 开场白 + 当日点的故事 | `fresh` |
| 无当日语音，有库存 | 库存开场白 + 故事 | `inventory` |
| 只有声纹 | 无开场白，预设故事 | `voice_only` |
| 合成失败 | 重播已缓存 | `cached` |

`voice_only` 及以下**不提示"爸爸没来"**。激励靠开场白的稀缺性，不靠断供。

`Plan.StartGenerationAt` 是 GPU 队列的全部调度策略：按它排序即可。
12 语言 × 全球时区意味着睡前高峰在 UTC 上滚动一整圈，按 deadline 排序会自动填谷。

## 快速开始

```console
$ go test ./...
$ go build ./cmd/...

# 质量门禁 —— 手工版阶段唯一真正要用的东西
$ ./mimimoto-qc -profile daily recordings/*.wav
$ ./mimimoto-qc -json recordings/*.wav | jq -r 'select(.passed|not) | .file'

# 服务
$ ./mimimotod -addr :8080 -blobs ./data/blobs
```

TTS worker 需要 GPU 和 FireRedTTS3，见 `worker/README` 注释。

## 红线

这几条写进了架构而不是隐私政策，拆掉它们需要先读 `docs/DECISIONS.md` 里的理由：

- **绝不生成父母没说过的话**（D-002）。克隆声音只朗读父母写的或选的内容，没有自由对话。
  `pipeline` 拒绝任何 provenance 不合法的文本，且这道检查排在所有其他校验之前。
- **永不采集儿童语音**（D-003）。孩子端不申请麦克风权限。这同时是绕开 COPPA/GDPR-K
  和留在 App Store Kids Category 之外的凭据。
- **声纹注册需活体挑战**（D-005）。本人设备 + 当场朗读随机短句。
  没有"上传音频来克隆"这条路径——那是诈骗工具的形状。
- **对端音频永不落盘**（D-004）。德国刑法 §201 把未经同意录制他人言语定为刑事犯罪。

## 未决

- **声纹存哪**。服务端集中存储是 GPU 调度的前提，但等于持有一个泄露后无法重置的高价值资产。
  方向是每家独立密钥 + 原始录音短期销毁 + 长期只留 embedding。**在验证孩子买不买账之前属于过早优化。**
- **FireRedTTS3 的商用许可**。仓库是 Apache-2.0，model card 写的是仅供学术研究。两者矛盾，
  商用前必须书面解决。
- 首发市场收窄，以及已故父母声音的伦理立场。

详见 `docs/DECISIONS.md`。
