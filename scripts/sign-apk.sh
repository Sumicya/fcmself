#!/data/data/com.termux/files/usr/bin/bash
#
# 在 Termux 里给 fcmself 的 release APK 签名。
#
# 用法:
#   ./scripts/sign-apk.sh <未签名.apk> [输出.apk]
#
# 依赖（Termux 包，已核实存在）:
#   apksigner  —— Android build-tools 的 apksigner.jar 包装，依赖 openjdk-21
#   openjdk-21 —— 提供 keytool（首次生成密钥库时用）
#
# 环境变量:
#   FCMSELF_KEYSTORE   密钥库路径，默认 ~/fcmself.jks
#   FCMSELF_KEY_ALIAS  密钥别名，默认 fcmself
#
set -euo pipefail

KS="${FCMSELF_KEYSTORE:-$HOME/fcmself.jks}"
ALIAS="${FCMSELF_KEY_ALIAS:-fcmself}"

IN="${1:-}"
if [ -z "$IN" ]; then
    echo "用法: $0 <未签名.apk> [输出.apk]" >&2
    exit 1
fi
[ -f "$IN" ] || { echo "找不到输入文件: $IN" >&2; exit 1; }

OUT="${2:-}"
if [ -z "$OUT" ]; then
    case "$IN" in
        *-unsigned.apk) OUT="${IN%-unsigned.apk}-signed.apk" ;;
        *.apk)          OUT="${IN%.apk}-signed.apk" ;;
        *)              OUT="$IN-signed.apk" ;;
    esac
fi

for cmd in apksigner keytool; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "缺少 $cmd，先执行: pkg install apksigner openjdk-21" >&2
        exit 1
    }
done

if [ ! -f "$KS" ]; then
    echo "没有找到密钥库 $KS，现在生成一个（会提示输入密码，请记牢并备份该文件）。"
    keytool -genkeypair -v \
        -keystore "$KS" \
        -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=fcmself, OU=dev, O=sumicya, C=SG"
fi

echo "签名中: $IN -> $OUT"
apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" --out "$OUT" "$IN"

echo "校验中:"
apksigner verify --print-certs -v "$OUT"

echo
echo "完成: $OUT"
echo "安装: termux-open \"$OUT\"   （或已 root 时: su -c pm install -r \"$OUT\"）"
