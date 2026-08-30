# Site de dublagem automática de vídeos

Projeto completo, 100% com ferramentas open-source (sem custo de API):
- **Frontend**: HTML/JS simples (upload + acompanhamento de progresso)
- **Backend**: Node.js/Express (fila de processamento + status dos jobs)
- **Pipeline**: Python (Whisper para transcrição, Argos Translate para tradução,
  Coqui TTS para a voz dublada, FFmpeg para remontar o vídeo)

## Requisitos do servidor

- Node.js 18+
- Python 3.10+
- FFmpeg instalado (`apt install ffmpeg` no Ubuntu/Debian)
- **GPU recomendada** (NVIDIA com CUDA). Sem GPU o processamento roda, mas fica
  bem mais lento — um vídeo de poucos minutos pode levar dezenas de minutos
  usando só CPU.
- Espaço em disco suficiente para os modelos (Whisper + TTS somam alguns GB).

## Instalação

### 1. Backend (Node.js)
```bash
cd backend
npm install
```

### 2. Worker (Python)
```bash
cd backend/worker
python3 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### 3. Baixar os idiomas de tradução (uma única vez, precisa de internet)
```bash
python3 install_languages.py
```

### 4. Rodar o backend
```bash
cd backend
npm start
```
O servidor sobe em `http://localhost:3000`.

### 5. Abrir o frontend
Basta abrir o arquivo `frontend/index.html` no navegador (ou servir com
qualquer servidor estático). Ajuste a constante `API_BASE` no HTML se o
backend estiver em outro endereço.

## Como funciona o fluxo

1. Usuário faz upload do vídeo pelo site.
2. Backend salva o arquivo e coloca um job na fila.
3. O worker Python processa o job: extrai áudio → transcreve → traduz →
   gera a nova voz → remonta o vídeo com FFmpeg.
4. O frontend consulta `/api/status/:jobId` a cada poucos segundos e mostra
   o progresso.
5. Quando pronto, o vídeo fica disponível para download em `/outputs/:jobId.mp4`.

## Limitações desta primeira versão (importante)

- **Fila sequencial**: processa um vídeo por vez. Para produção com vários
  usuários simultâneos, troque a fila em memória por **BullMQ + Redis** e
  rode múltiplos workers em paralelo.
- **"Banco de dados" em memória**: os jobs somem se o servidor reiniciar.
  Para produção, use Postgres, SQLite ou Redis para persistir o status.
- **Uma voz só por padrão**: o Coqui TTS (modelo XTTS v2) permite *clonagem
  de voz* a partir de uma amostra de áudio, se quiser manter a voz original
  do vídeo falando no novo idioma — isso é um passo extra a implementar.
- **Sincronização labial**: este pipeline troca o áudio, mas não sincroniza
  os lábios com a nova fala (isso é um problema de pesquisa em aberto,
  ferramentas como Wav2Lip existem mas são mais complexas de integrar).
- **Sem fila de espera visível para o usuário** nem sistema de contas/login.
- Nenhuma etapa aqui baixa vídeo de links externos (YouTube etc.) — o
  usuário precisa fazer upload do próprio arquivo, para evitar violar
  termos de serviço de outras plataformas.

## Próximos passos possíveis

- Trocar a fila em memória por BullMQ + Redis (processamento paralelo)
- Adicionar autenticação de usuários
- Adicionar clonagem de voz (manter a voz original, só traduzida)
- Adicionar legendas automáticas como alternativa mais leve à dublagem
- Deploy: um serviço com GPU (RunPod, Vast.ai, Lambda Labs) ou seu próprio
  servidor com placa NVIDIA
