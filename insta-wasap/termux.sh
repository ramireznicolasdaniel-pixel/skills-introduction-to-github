#!/data/data/com.termux/files/usr/bin/bash
# Instalador de InstaWasap para Android (Termux).
# Uso:  curl -fsSL https://raw.githubusercontent.com/ramireznicolasdaniel-pixel/skills-introduction-to-github/ccr-45ecf061-x7g29h/insta-wasap/termux.sh | bash
set -e
REPO="https://github.com/ramireznicolasdaniel-pixel/skills-introduction-to-github"
RAMA="ccr-45ecf061-x7g29h"
DIR="$HOME/instawasap"

echo "==> Instalando paquetes (puede tardar unos minutos la primera vez)..."
pkg update -y
pkg install -y python ffmpeg git

echo "==> Descargando la app..."
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" pull --ff-only
else
  git clone --depth 1 -b "$RAMA" "$REPO" "$DIR"
fi

echo "==> Instalando dependencias de Python..."
pip install --upgrade flask requests yt-dlp

# Comando corto para abrirla siempre: instawasap
cat > "$PREFIX/bin/instawasap" <<EOS
#!/data/data/com.termux/files/usr/bin/bash
cd "$DIR/insta-wasap"
git -C "$DIR" pull --ff-only -q 2>/dev/null || true
pip install -q --upgrade yt-dlp 2>/dev/null || true
echo "InstaWasap abierto en http://localhost:5000  (Ctrl+C para cerrar)"
(sleep 2; termux-open-url http://localhost:5000) &
exec python app.py
EOS
chmod +x "$PREFIX/bin/instawasap"

echo ""
echo "✅ Listo. La próxima vez solo escribí:  instawasap"
echo ""
exec instawasap
