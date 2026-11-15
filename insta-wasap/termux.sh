#!/data/data/com.termux/files/usr/bin/bash
# Instalador de InstaWasap para Android (Termux).
# Uso:  curl -fsSL https://raw.githubusercontent.com/ramireznicolasdaniel-pixel/skills-introduction-to-github/ccr-45ecf061-x7g29h/insta-wasap/termux.sh | bash
set -e
REPO="https://github.com/ramireznicolasdaniel-pixel/skills-introduction-to-github"
RAMA="ccr-45ecf061-x7g29h"
DIR="$HOME/instawasap"

echo "==> Instalando paquetes (puede tardar unos minutos la primera vez)..."
pkg update -y
pkg install -y python ffmpeg git curl

echo "==> Descargando la app..."
if [ -d "$DIR/.git" ]; then
  git -C "$DIR" pull --ff-only
else
  git clone --depth 1 -b "$RAMA" "$REPO" "$DIR"
fi

echo "==> Instalando dependencias de Python..."
pip install --upgrade flask requests yt-dlp

# Comando corto para abrirla siempre: instawasap
# Si la app ya está corriendo, solo abre el navegador (con el link, si se pasó uno).
cat > "$PREFIX/bin/instawasap" <<EOS
#!/data/data/com.termux/files/usr/bin/bash
URL_APP="http://localhost:5000/"
if [ -n "\$1" ]; then
  URL_APP="\${URL_APP}?url=\$(python -c 'import sys,urllib.parse;print(urllib.parse.quote(sys.argv[1]))' "\$1")"
fi
if curl -s -o /dev/null http://localhost:5000/; then
  termux-open-url "\$URL_APP"
  exit 0
fi
cd "$DIR/insta-wasap"
git -C "$DIR" pull --ff-only -q 2>/dev/null || true
pip install -q --upgrade yt-dlp 2>/dev/null || true
echo "InstaWasap abierto en http://localhost:5000  (Ctrl+C para cerrar)"
(for i in \$(seq 1 20); do curl -s -o /dev/null http://localhost:5000/ && break; sleep 1; done
 termux-open-url "\$URL_APP") &
exec python app.py
EOS
chmod +x "$PREFIX/bin/instawasap"

# Compartir desde Instagram -> Termux: Termux ejecuta ~/bin/termux-url-opener con el link.
mkdir -p "$HOME/bin"
cat > "$HOME/bin/termux-url-opener" <<'EOS'
#!/data/data/com.termux/files/usr/bin/bash
exec instawasap "$1"
EOS
chmod +x "$HOME/bin/termux-url-opener"

echo ""
echo "✅ Listo. La próxima vez solo escribí:  instawasap"
echo "   O desde Instagram: tocá Compartir en un reel > Termux y se descarga solo."
echo ""
exec instawasap
