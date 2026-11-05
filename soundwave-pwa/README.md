# SoundWave PWA - Música & Torrents

**SoundWave** é um Progressive Web App (PWA) e aplicativo Android moderno de reprodução, busca e download de músicas em fontes abertas e torrents.

## 🌟 Funcionalidades Principais

1. **Interface Moderna (Dark Mode Estilo Spotify / Apple Music):**
   - Design fluido construído com CSS Grid e Flexbox, paleta escura com acentos em ciano e azul elétrico (`#00F2FE` -> `#4FACFE`).
   - Navegação por abas: *Buscar na Web & Torrents* e *Minha Biblioteca*.
   - Player flutuante inferior persistente e tela cheia expansível com animação de onda sonora via HTML5 Canvas.

2. **Mecanismo de Busca Real (Internet & Torrents):**
   - Integração com a **Jamendo API (Creative Commons)** com streaming em alta qualidade (MP3 320kbps).
   - Integração com a **Archive.org Audio Search** com áudios em domínio público e gravações ao vivo.
   - Geração de links **Magnet** com múltiplos rastreadores (trackers BitTorrent abertos).
   - Metadados completos: Nome da música, artista, formato (MP3, FLAC, WAV), tamanho estimado do arquivo e contagem de Seeders (fontes).

3. **Reprodutor de Áudio Integrado Completo:**
   - Play/Pause, avançar/retroceder faixas, seekbar interativo na timeline (arrastável).
   - Tempo decorrido e duração total em tempo real.
   - Controle de volume, botão mudo, modo aleatório (shuffle) e repetição contínua.
   - Seletor de velocidade de reprodução (1.0x, 1.25x, 1.5x, 2.0x).
   - Suporte à **MediaSession API** para exibição e controle na tela de bloqueio e central de notificações do Android.

4. **Biblioteca Local & Importação:**
   - Armazenamento persistente no navegador via `LocalStorage`.
   - Botão para importação direta de arquivos MP3, FLAC, WAV e M4A da memória interna do celular Android (`<input type="file" accept="audio/*">`).

5. **PWA & Modo Offline:**
   - `manifest.json` configurado para instalação nativa (`standalone`).
   - `sw.js` (Service Worker) com cache dos recursos do app para inicialização instantânea offline.

## 🚀 Como Executar Standalone

Você pode rodar esta pasta localmente em qualquer servidor estático ou hospedar na web:

```bash
# Com Python 3
python3 -m http.server 8080

# Ou com Node.js npx
npx serve .
```

Abra `http://localhost:8080` no seu navegador ou smartphone.

## 📱 Compilação para APK via PWA Builder

1. Hospede os arquivos desta pasta em um domínio HTTPS (por exemplo, no Vercel, Netlify ou GitHub Pages).
2. Acesse [PWABuilder.com](https://www.pwabuilder.com/).
3. Insira a URL do seu site e clique em **Start**.
4. Clique em **Package for Android** para gerar o pacote APK / AAB pronto para distribuição ou instalação direta.
