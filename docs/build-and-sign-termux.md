# 在 Termux 里签名（以及可选的本地构建）

本仓库的 CI 不依赖任何 secrets：它只编译，并上传两个产物。签名在本地做。

| 产物 | 说明 |
| --- | --- |
| `fcmself-<版本>-debug-signed.apk` | debug 签名，**可直接安装**，用于日常测试 |
| `fcmself-<版本>-unsigned.apk` | release 未签名，用下面的命令签完再装 |

## 一次性准备

```bash
pkg update
pkg install -y openjdk-21 apksigner termux-tools
```

`apksigner` 是 Termux 打包的 Android build-tools `apksigner.jar`（依赖 `openjdk-21`，会一起装上）；
`keytool` 由 `openjdk-21` 提供。

## 签名

仓库里带了脚本，一条命令搞定（首次运行会自动生成密钥库并提示设密码）：

```bash
git clone https://github.com/Sumicya/fcmself.git
cd fcmself
./scripts/sign-apk.sh ~/下载/fcmself-<版本>-unsigned.apk
```

等价的手动命令：

```bash
# 1) 生成密钥库（只做一次；fcmself.jks 丢了就再也无法覆盖安装旧版本，务必备份）
keytool -genkeypair -v -keystore ~/fcmself.jks -alias fcmself \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=fcmself, OU=dev, O=sumicya, C=SG"

# 2) 签名
apksigner sign --ks ~/fcmself.jks --ks-key-alias fcmself \
  --out ~/fcmself-signed.apk ~/fcmself-unsigned.apk

# 3) 校验
apksigner verify --print-certs -v ~/fcmself-signed.apk
```

安装：`termux-open ~/fcmself-signed.apk`（调起系统安装器），已 root 也可以 `su -c pm install -r ~/fcmself-signed.apk`。

关于 zipalign：Termux 没有 `zipalign` 包，但 AGP 在打包阶段已经做过对齐，`apksigner` 签名会保持对齐，
所以不需要单独跑。（如果以后想自己核对，需要装 Android SDK build-tools，见下。）

> 提示：CI 里那次 `Sign APK` 失败（build-tools 37.0.0 的 apksigner，exit code 2）大概率是因为仓库没有配置
> `SIGNINGKEYBASE64` 等 secrets，导致 keystore 是空的——不是 apksigner 版本问题。
> 本地签名用的是同一个 apksigner（Termux 包也是 37.0.0），能正常签。

## 可选：完全在 Termux 里构建

能跑，但重（Android SDK + Gradle 发行版约 2–4 GB，手机上编译很慢）。只在不想依赖 CI 时才需要。

```bash
pkg install -y openjdk-21 git unzip

# Android SDK 命令行工具（下面这个文件名/版本号请以 developer.android.com 上的最新链接为准，
# 我这边访问不到 dl.google.com，没法核实当前版本号）
mkdir -p ~/android-sdk/cmdline-tools && cd ~/android-sdk/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip
mv cmdline-tools latest

export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
yes | sdkmanager --licenses
sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"

# 构建（首次会下载 Gradle 8.13，约 200 MB）
cd ~
git clone https://github.com/Sumicya/fcmself.git
cd fcmself
./gradlew assembleRelease

./scripts/sign-apk.sh app/build/outputs/apk/release/app-release-unsigned.apk
```

`ANDROID_HOME` / `PATH` 那两行建议写进 `~/.bashrc`，否则新开 shell 会找不到 SDK。
