const express = require("express");
const cors = require("cors");
const http = require("http");
const WebSocket = require("ws");
const crypto = require("crypto");

const app = express();
const server = http.createServer(app);

app.use(cors());
app.use(express.json({ limit: "25mb" }));

const PORT = process.env.PORT || 10000;

const DEEPL_API_KEY = process.env.DEEPL_API_KEY || "";

const DEEPL_API_URL =
  DEEPL_API_KEY.includes(":fx")
    ? "https://api-free.deepl.com/v2/translate"
    : "https://api.deepl.com/v2/translate";

const sessions = new Map();

const SUPPORTED_LANGUAGES = {
  "pt-BR": "PT-BR",
  "en-US": "EN-US",
  "es-ES": "ES",
  "fr-FR": "FR",
  "de-DE": "DE",
  "it-IT": "IT",
  "ja-JP": "JA",
  "ko-KR": "KO",
  "zh-CN": "ZH",
  "ru-RU": "RU",
  "ar-SA": "AR",
  "hi-IN": "HI",
  "tr-TR": "TR",
  "nl-NL": "NL",
  "pl-PL": "PL",
  "uk-UA": "UK",
  "th-TH": "TH",
  "id-ID": "ID",
  "vi-VN": "VI"
};

function gerarId() {
  return crypto.randomUUID();
}

function normalizarIdioma(lang) {
  if (!lang) return "PT-BR";

  if (SUPPORTED_LANGUAGES[lang]) {
    return SUPPORTED_LANGUAGES[lang];
  }

  const encontrado = Object.keys(SUPPORTED_LANGUAGES).find(
    key => key.toLowerCase() === String(lang).toLowerCase()
  );

  if (encontrado) {
    return SUPPORTED_LANGUAGES[encontrado];
  }

  const base = String(lang).split("-")[0].toLowerCase();

  const mapa = {
    pt: "PT-BR",
    en: "EN-US",
    es: "ES",
    fr: "FR",
    de: "DE",
    it: "IT",
    ja: "JA",
    ko: "KO",
    zh: "ZH",
    ru: "RU",
    ar: "AR",
    hi: "HI",
    tr: "TR",
    nl: "NL",
    pl: "PL",
    uk: "UK",
    th: "TH",
    id: "ID",
    vi: "VI"
  };

  return mapa[base] || "PT-BR";
}

async function traduzirDeepL(texto, targetLang) {
  if (!DEEPL_API_KEY) {
    throw new Error("DEEPL_API_KEY não configurada no Render.");
  }

  if (!texto || !String(texto).trim()) {
    return "";
  }

  const resposta = await fetch(DEEPL_API_URL, {
    method: "POST",
    headers: {
      "Authorization": `DeepL-Auth-Key ${DEEPL_API_KEY}`,
      "Content-Type": "application/json"
    },
    body: JSON.stringify({
      text: [String(texto)],
      target_lang: targetLang
    })
  });

  const dados = await resposta.json();

  if (!resposta.ok) {
    throw new Error(
      dados?.message ||
      `DeepL respondeu HTTP ${resposta.status}`
    );
  }

  return dados?.translations?.[0]?.text || "";
}

function criarSessao(clientId, targetLang) {
  const jobId = gerarId();

  const sessao = {
    jobId,
    clientId: clientId || "android",
    targetLang: normalizarIdioma(targetLang),

    status: "created",
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),

    chunksRecebidos: 0,
    bytesRecebidos: 0,

    sourceText: "",
    translatedText: "",

    clients: new Set()
  };

  sessions.set(jobId, sessao);

  return sessao;
}

function atualizarSessao(sessao, dados) {
  Object.assign(sessao, dados);
  sessao.updatedAt = new Date().toISOString();
}

function enviarParaClientes(sessao, mensagem) {
  const texto =
    typeof mensagem === "string"
      ? mensagem
      : JSON.stringify(mensagem);

  for (const cliente of sessao.clients) {
    try {
      if (cliente.readyState === WebSocket.OPEN) {
        cliente.send(texto);
      }
    } catch (erro) {
      console.error("Erro enviando WebSocket:", erro.message);
    }
  }
}

app.get("/", (req, res) => {
  res.json({
    ok: true,
    service: "SI Tradutor Live",
    version: "13.0-DeepL",
    message: "Backend do SI Tradutor Live funcionando",
    provider: "DeepL",
    endpoints: {
      health: "/api/health",
      deeplTest: "/api/deepl/test",
      audioStart: "POST /api/audio/start",
      audioChunk: "POST /api/audio/chunk",
      audioOutput: "GET /api/audio/output/:jobId",
      audioStatus: "GET /api/audio/status/:jobId",
      diagnostic: "GET /api/audio/diagnostic/:jobId",
      audioStop: "POST /api/audio/stop",
      sessions: "GET /api/audio/sessions",
      websocket: "wss://si-u2ul.onrender.com/ws"
    }
  });
});

app.get("/api/health", (req, res) => {
  res.json({
    ok: true,
    service: "SI Tradutor Live",
    version: "13.0-DeepL",
    provider: "DeepL",
    deeplConfigured: Boolean(DEEPL_API_KEY),
    sessions: sessions.size,
    timestamp: new Date().toISOString()
  });
});
app.get("/api/deepl/test", async (req, res) => {
  try {
    if (!DEEPL_API_KEY) {
      return res.status(500).json({
        ok: false,
        error: "DEEPL_API_KEY não configurada no Render."
      });
    }

    const texto =
      req.query.text ||
      "Olá, este é um teste do SI Tradutor Live.";

    const target =
      normalizarIdioma(req.query.target || "en-US");

    const translation = await traduzirDeepL(
      texto,
      target
    );

    res.json({
      ok: true,
      provider: "DeepL",
      status: 200,
      source: texto,
      target,
      translation
    });

  } catch (erro) {
    console.error("Erro DeepL:", erro);

    res.status(500).json({
      ok: false,
      provider: "DeepL",
      error: erro.message
    });
  }
});


app.post("/api/audio/start", (req, res) => {
  try {
    const {
      clientId,
      targetLang
    } = req.body || {};

    const sessao = criarSessao(
      clientId,
      targetLang
    );

    atualizarSessao(sessao, {
      status: "waiting_audio"
    });

    res.json({
      ok: true,
      jobId: sessao.jobId,
      targetLang: sessao.targetLang,
      status: sessao.status,
      provider: "DeepL"
    });

  } catch (erro) {
    console.error("Erro /api/audio/start:", erro);

    res.status(500).json({
      ok: false,
      error: erro.message
    });
  }
});


app.post("/api/audio/chunk", async (req, res) => {
  try {
    const {
      jobId,
      audio,
      mimeType,
      sampleRate
    } = req.body || {};

    if (!jobId) {
      return res.status(400).json({
        ok: false,
        error: "jobId não informado."
      });
    }

    const sessao = sessions.get(jobId);

    if (!sessao) {
      return res.status(404).json({
        ok: false,
        error: "Sessão não encontrada.",
        jobId
      });
    }

    if (!audio) {
      return res.status(400).json({
        ok: false,
        error: "Áudio não informado."
      });
    }

    let buffer;

    try {
      buffer = Buffer.from(audio, "base64");
    } catch (erro) {
      return res.status(400).json({
        ok: false,
        error: "Áudio base64 inválido."
      });
    }

    sessao.chunksRecebidos += 1;
    sessao.bytesRecebidos += buffer.length;

    atualizarSessao(sessao, {
      status: "receiving_audio",
      lastMimeType: mimeType || "audio/pcm",
      lastSampleRate: sampleRate || 16000
    });

    /*
     * IMPORTANTE:
     *
     * O áudio recebido pelo Android é PCM.
     * A API /v2/translate da DeepL traduz TEXTO,
     * não PCM diretamente.
     *
     * Por isso este endpoint apenas recebe e registra
     * os blocos de áudio neste momento.
     *
     * A etapa de transcrição precisa transformar:
     *
     * PCM -> texto
     *
     * antes de enviar o texto para o DeepL.
     */

    enviarParaClientes(sessao, {
      type: "audio_received",
      jobId: sessao.jobId,
      chunks: sessao.chunksRecebidos,
      bytes: sessao.bytesRecebidos
    });

    res.json({
      ok: true,
      jobId,
      received: true,
      chunks: sessao.chunksRecebidos,
      bytes: sessao.bytesRecebidos
    });

  } catch (erro) {
    console.error("Erro /api/audio/chunk:", erro);

    res.status(500).json({
      ok: false,
      error: erro.message
    });
  }
});


app.post("/api/audio/translate", async (req, res) => {
  try {
    const {
      jobId,
      text,
      sourceText
    } = req.body || {};

    if (!jobId) {
      return res.status(400).json({
        ok: false,
        error: "jobId não informado."
      });
    }

    const sessao = sessions.get(jobId);

    if (!sessao) {
      return res.status(404).json({
        ok: false,
        error: "Sessão não encontrada."
      });
    }

    const texto =
      text ||
      sourceText ||
      "";

    if (!texto.trim()) {
      return res.status(400).json({
        ok: false,
        error: "Texto não informado."
      });
    }

    atualizarSessao(sessao, {
      status: "translating",
      sourceText: texto
    });

    const traducao = await traduzirDeepL(
      texto,
      sessao.targetLang
    );

    atualizarSessao(sessao, {
      status: "translated",
      translatedText: traducao
    });

    enviarParaClientes(sessao, {
      type: "translation",
      jobId: sessao.jobId,
      sourceText: texto,
      translatedText: traducao,
      targetLang: sessao.targetLang
    });

    res.json({
      ok: true,
      jobId: sessao.jobId,
      sourceText: texto,
      translatedText: traducao,
      targetLang: sessao.targetLang
    });

  } catch (erro) {
    console.error("Erro /api/audio/translate:", erro);

    res.status(500).json({
      ok: false,
      error: erro.message
    });
  }
});


app.get("/api/audio/status/:jobId", (req, res) => {
  const sessao = sessions.get(req.params.jobId);

  if (!sessao) {
    return res.status(404).json({
      ok: false,
      error: "Sessão não encontrada."
    });
  }

  res.json({
    ok: true,
    jobId: sessao.jobId,
    status: sessao.status,
    targetLang: sessao.targetLang,
    chunksRecebidos: sessao.chunksRecebidos,
    bytesRecebidos: sessao.bytesRecebidos,
    sourceText: sessao.sourceText,
    translatedText: sessao.translatedText,
    createdAt: sessao.createdAt,
    updatedAt: sessao.updatedAt
  });
});


app.get("/api/audio/output/:jobId", (req, res) => {
  const sessao = sessions.get(req.params.jobId);

  if (!sessao) {
    return res.status(404).json({
      ok: false,
      error: "Sessão não encontrada."
    });
  }

  res.json({
    ok: true,
    jobId: sessao.jobId,
    status: sessao.status,
    sourceText: sessao.sourceText,
    translatedText: sessao.translatedText,
    audioUrl: null,
    message:
      "O áudio traduzido ainda depende da etapa de transcrição e síntese de voz."
  });
});


app.get("/api/audio/diagnostic/:jobId", (req, res) => {
  const sessao = sessions.get(req.params.jobId);

  if (!sessao) {
    return res.status(404).json({
      ok: false,
      error: "Sessão não encontrada."
    });
  }

  res.json({
    ok: true,
    provider: "DeepL",
    jobId: sessao.jobId,
    status: sessao.status,
    targetLang: sessao.targetLang,
    chunksRecebidos: sessao.chunksRecebidos,
    bytesRecebidos: sessao.bytesRecebidos,
    sourceText: sessao.sourceText,
    translatedText: sessao.translatedText,
    deeplConfigured: Boolean(DEEPL_API_KEY),
    createdAt: sessao.createdAt,
    updatedAt: sessao.updatedAt
  });
});
app.post("/api/audio/stop", (req, res) => {
  try {
    const {
      jobId
    } = req.body || {};

    if (!jobId) {
      return res.status(400).json({
        ok: false,
        error: "jobId não informado."
      });
    }

    const sessao = sessions.get(jobId);

    if (!sessao) {
      return res.status(404).json({
        ok: false,
        error: "Sessão não encontrada."
      });
    }

    atualizarSessao(sessao, {
      status: "stopped"
    });

    enviarParaClientes(sessao, {
      type: "session_stopped",
      jobId: sessao.jobId
    });

    res.json({
      ok: true,
      jobId: sessao.jobId,
      status: "stopped"
    });

  } catch (erro) {
    console.error("Erro /api/audio/stop:", erro);

    res.status(500).json({
      ok: false,
      error: erro.message
    });
  }
});


app.get("/api/audio/sessions", (req, res) => {
  const lista = [];

  for (const sessao of sessions.values()) {
    lista.push({
      jobId: sessao.jobId,
      clientId: sessao.clientId,
      targetLang: sessao.targetLang,
      status: sessao.status,
      chunksRecebidos: sessao.chunksRecebidos,
      bytesRecebidos: sessao.bytesRecebidos,
      sourceText: sessao.sourceText,
      translatedText: sessao.translatedText,
      createdAt: sessao.createdAt,
      updatedAt: sessao.updatedAt
    });
  }

  res.json({
    ok: true,
    count: lista.length,
    sessions: lista
  });
});


const wss = new WebSocket.Server({
  server,
  path: "/ws"
});


wss.on("connection", (ws) => {
  console.log("WebSocket Android conectado.");

  ws.send(JSON.stringify({
    type: "connected",
    provider: "DeepL",
    message: "WebSocket do SI conectado."
  }));


  ws.on("message", (mensagem) => {
    try {
      const dados = JSON.parse(mensagem.toString());

      if (dados.type === "subscribe") {
        const sessao = sessions.get(dados.jobId);

        if (!sessao) {
          ws.send(JSON.stringify({
            type: "error",
            error: "Sessão não encontrada.",
            jobId: dados.jobId
          }));

          return;
        }

        sessao.clients.add(ws);

        ws.jobId = dados.jobId;

        ws.send(JSON.stringify({
          type: "subscribed",
          jobId: dados.jobId,
          targetLang: sessao.targetLang,
          status: sessao.status
        }));

        return;
      }


      if (dados.type === "unsubscribe") {
        if (ws.jobId) {
          const sessao = sessions.get(ws.jobId);

          if (sessao) {
            sessao.clients.delete(ws);
          }
        }

        ws.jobId = null;

        ws.send(JSON.stringify({
          type: "unsubscribed"
        }));

        return;
      }

    } catch (erro) {
      console.error(
        "Mensagem WebSocket inválida:",
        erro.message
      );
    }
  });


  ws.on("close", () => {
    console.log("WebSocket Android desconectado.");

    if (ws.jobId) {
      const sessao = sessions.get(ws.jobId);

      if (sessao) {
        sessao.clients.delete(ws);
      }
    }
  });


  ws.on("error", (erro) => {
    console.error(
      "Erro WebSocket:",
      erro.message
    );
  });
});


setInterval(() => {
  const agora = Date.now();

  for (const [jobId, sessao] of sessions.entries()) {
    const atualizado =
      new Date(sessao.updatedAt).getTime();

    const idade =
      agora - atualizado;

    /*
     * Remove sessões com mais de 30 minutos
     * sem atualização.
     */
    if (idade > 30 * 60 * 1000) {
      for (const cliente of sessao.clients) {
        try {
          cliente.close();
        } catch (_) {}
      }

      sessions.delete(jobId);

      console.log(
        "Sessão removida:",
        jobId
      );
    }
  }
}, 60 * 1000);


app.use((req, res) => {
  res.status(404).json({
    ok: false,
    error: "Rota não encontrada.",
    path: req.originalUrl
  });
});


server.listen(PORT, "0.0.0.0", () => {
  console.log(
    `SI Tradutor Live rodando na porta ${PORT}`
  );

  console.log(
    `DeepL configurado: ${Boolean(DEEPL_API_KEY)}`
  );

  console.log(
    `WebSocket: ws://0.0.0.0:${PORT}/ws`
  );
});
