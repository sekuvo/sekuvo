#!/bin/sh
# dogrula.sh — aktarım araçlarının bütünlük denetimi (vault_takip SEC-023).
#
# Zincir üç halkalı ve her halka bir öncekini doğrular:
#   1. Dosyalar   -> tools/SHA256SUMS  (yerel, git'te izlenir)
#   2. SHA256SUMS -> GitHub'daki kopya (uzak: yerel değişiklik gizlenemez)
#   3. SHA256SUMS -> Vault'taki çıpa   (telefon: ele geçmiş Mac değiştiremez)
#
# Kullanım:
#   tools/dogrula.sh          # denetle
#   tools/dogrula.sh --yaz    # araçlar değiştiyse referansı yenile
#   tools/dogrula.sh --cipa   # telefondaki kayıtla karşılaştırılacak tek değer
set -eu

cd "$(dirname "$0")/.."
# Kapsam: tools/ altındaki TÜM kaynak dosyalar (py/html/sh/pyi). Elle sayım
# import zincirindeki modülleri (consts, utils, writers, cli, helpers) dışarıda
# bırakıyordu — o modüller vault-clip.py sürecinde parolaya ve düz metne erişir.
DOSYALAR=$(find tools -type f \( -name '*.py' -o -name '*.pyi' -o -name '*.html' -o -name '*.sh' \) \
  ! -path '*/__pycache__/*' | LC_ALL=C sort | tr '\n' ' ')
MASA="$HOME/Desktop/aktar.html"

case "${1:-}" in
  --yaz)
    shasum -a 256 $DOSYALAR > tools/SHA256SUMS
    echo "tools/SHA256SUMS yenilendi. Yeni çıpa (Vault'taki kayda yaz):"
    shasum -a 256 tools/SHA256SUMS | cut -d' ' -f1
    echo "Not: değişikliği commit'lemeyi unutma — uzak halka ona bakıyor."
    exit 0
    ;;
  --cipa)
    shasum -a 256 tools/SHA256SUMS | cut -d' ' -f1
    exit 0
    ;;
esac

hata=0

echo "0) Bytecode önbelleği (planted .pyc atlatması)"
pyc=$(find tools -type d -name __pycache__ 2>/dev/null)
if [ -n "$pyc" ]; then
  echo "   ✗ tools/ altında __pycache__ VAR — .pyc kaynağın önüne geçebilir:"
  echo "$pyc" | sed 's/^/     /'
  echo "     sil: find tools -type d -name __pycache__ -exec rm -rf {} +"
  echo "     ve araçları -B ile çalıştır (vault-clip.py bytecode yazmaz)"
  hata=1
else
  echo "   ✓ bytecode önbelleği yok (yalnız denetlenen kaynak koşar)"
fi

echo "1) Dosyalar → SHA256SUMS"
if shasum -a 256 -c tools/SHA256SUMS >/dev/null 2>&1; then
  echo "   ✓ listedeki dosyaların hepsi referansla aynı (bu betik dâhil)"
else
  echo "   ✗ FARK VAR:"
  shasum -a 256 -c tools/SHA256SUMS 2>&1 | grep -v ': OK$' | sed 's/^/     /'
  hata=1
fi

echo "2) Masaüstü kopyası → depo kopyası"
if [ -L "$MASA" ]; then
  # Bağın SONEKİNE değil gerçek hedefine bakılır: /tmp/x/tools/aktar.html gibi
  # sahte bir hedef de "tools/aktar.html" ile biter. Hedef depodaki dosyanın
  # kendisi olmalı; yine de özeti karşılaştırılır (bağ bozulmuş olabilir).
  hedef=$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$MASA")
  depo=$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' tools/aktar.html)
  if [ "$hedef" = "$depo" ] && [ "$(shasum -a 256 "$MASA" | cut -d' ' -f1)" = "$(shasum -a 256 tools/aktar.html | cut -d' ' -f1)" ]; then
    echo "   ✓ masaüstündeki bir BAĞ ve depodaki dosyayı gösteriyor (kopya yok, sürüklenme imkânsız)"
  else
    echo "   ✗ masaüstündeki bağ depodaki dosyayı göstermiyor: $hedef"; hata=1
  fi
elif [ -f "$MASA" ]; then
  a=$(shasum -a 256 "$MASA" | cut -d' ' -f1)
  b=$(shasum -a 256 tools/aktar.html | cut -d' ' -f1)
  if [ "$a" = "$b" ]; then
    echo "   ✓ masaüstündeki aktar.html depodakiyle aynı"
  else
    echo "   ✗ masaüstü kopyası FARKLI — depodan yeniden kopyala:"
    echo "     cp tools/aktar.html \"$MASA\""
    hata=1
  fi
else
  echo "   – masaüstünde kopya yok (atlandı)"
fi

echo "3) Yerel → GitHub (uzak halka)"
dal=$(git rev-parse --abbrev-ref HEAD)
if git fetch -q origin "$dal" 2>/dev/null; then
  if git diff --quiet "origin/$dal" -- tools/; then
    echo "   ✓ tools/ GitHub'daki hâliyle birebir"
  else
    echo "   ✗ yerel tools/ uzaktan FARKLI:"
    git diff --stat "origin/$dal" -- tools/ | sed 's/^/     /'
    hata=1
  fi
else
  echo "   – uzak sorgulanamadı (ağ yok?) — bu halka KOŞMADI, 'temiz' sayma"
fi

echo "4) Çıpa (telefondaki kayıtla elle karşılaştır)"
echo "   Not: kurcalanmış bir betik kendi hakkında yalan söyleyebilir."
echo "   Şüphede betiğe hiç güvenme, iki komutu elle koş:"
echo "     shasum -a 256 -c tools/SHA256SUMS"
echo "     git fetch -q && git diff --stat origin/$dal -- tools/"
echo "   $(shasum -a 256 tools/SHA256SUMS | cut -d' ' -f1)"

[ "$hata" -eq 0 ] && echo "SONUÇ: temiz" || echo "SONUÇ: FARK VAR — kullanmadan önce incele"
exit "$hata"
