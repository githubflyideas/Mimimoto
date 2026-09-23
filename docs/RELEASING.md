# 出包

## APK

推一次代码，CI 自动构建。

```
git push
```

然后在 GitHub 的 **Actions → 最近一次 run → Artifacts → mimimoto-debug-apk** 下载。
用 debug keystore 自动签名，不需要配任何密钥，下载即可安装（手机上要先允许「安装未知来源应用」）。

本地构建（需要 Android SDK）：

```
./gradlew :android:assembleDebug
# android/build/outputs/apk/debug/android-debug.apk
```

没有 Android SDK 的机器上，`:android` 模块会被 `settings.gradle.kts` 自动跳过，
服务端仍然能构建和测试。完全连不上 Maven 的机器用 `scripts/build.sh`。

### 正式版（上架用）

需要你自己的 keystore，**不要提交进仓库**：

```
keytool -genkey -v -keystore mimimoto-release.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias mimimoto
```

把 base64 后的 keystore 和口令放进 GitHub Secrets，再给 `android/build.gradle.kts`
加 `signingConfigs`。这一步刻意没有替你做：签名密钥一旦泄露，任何人都能发布冒充你的更新，
而它现在还不需要存在。

---

## IPA

**这个仓库目前没有 iOS 端。** 说清楚为什么，以及需要什么：

1. **必须在 macOS 上编译。** Xcode 只跑在 macOS 上，没有 Linux 版本。CI 可以用
   GitHub Actions 的 `macos-latest` runner 解决这一条。
2. **设备可安装的 IPA 必须用 Apple 开发者证书签名。** 需要付费的 Apple Developer
   Program 账号（USD 99/年），证书绑定你的身份。模拟器构建不需要签名，但装不到真机上。
3. **需要一个 iOS 客户端。** 安卓端的界面是 Compose，不能直接跑在 iOS 上。

两条路：

- **各写一套原生。** iOS 端用 SwiftUI 重写界面，`audioqc` 用 Swift 再移植一遍。
  代价是同一套门禁算法有三份实现（Kotlin / Swift / 已有的 JS），任何一处阈值改动都要同步三次
  ——而这正是 `core` 模块要避免的事情。
- **Kotlin Multiplatform。** `core` 编译成 iOS framework，界面用 SwiftUI 或
  Compose Multiplatform。门禁只有一份实现。这是架构上更对的选择，代价是 KMP 的构建复杂度。

推荐第二条，但**建议等安卓端跑过真机之后再开**：如果门禁的阈值在真实设备上需要大改，
现在多一个平台就是多一份返工。
