const express = require("express");
const cors = require("cors");
const multer = require("multer");
const WebSocket = require("ws");
const crypto = require("crypto");

const app = express();

app.use(cors());
app.use(express.json({ limit: "20mb" }));

const upload = multer({
  storage: multer.memoryStorage(),
  limits: {
    fileSize: 2 * 1024 * 1024 * 1024
  }
});

const PORT = process.env.PORT || 10000;

const GEMINI_API_KEY = process.env.GEMINI_API_KEY || "";

const GEMINI_MODEL =
  process.env.GEMINI_MODEL ||
  "gemini-3.5-live-translate-preview";

const GEMINI_WS_BASE =
  "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

const sessions = new Map();

const languageNames = {
  "pt-BR": "Português do Brasil",
  "en-US": "English",
  "es-ES": "Español",
  "fr-FR": "Français",
  "de-DE": "Deutsch",
  "it-IT": "Italiano",
  "ja-JP": "日本語",
  "ko-KR": "한국어",
  "zh-CN": "中文",
  "ru-RU": "Русский",
  "ar-SA": "العربية",
  "hi-IN": "हिन्दी",
  "tr-TR": "Türkçe",
  "nl-NL": "Nederlands",
  "pl-PL": "Polski",
  "uk-UA": "Українська",
  "th-TH": "ไทย",
  "id-ID": "Bahasa Indonesia",
  "vi-VN": "Tiếng Việt"
};

/* =========================================================
   UTILIDADES
========================================================= */

function now() {
  return new Date().toISOString();
}

function createJobId() {
  return crypto.randomBytes(8).toString("hex");
}

function safeClose(ws) {
  try {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.close();
    }
  } catch (_) {}
}

function sendJson(ws, data) {
  try {
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify(data));
      return true;
    }
  } catch (error) {
    console.error("Erro enviando WebSocket:", error.message);
  }

  return false;
}

/* =========================================================
   HEALTH
========================================================= */

app.get("/api/health", (req, res) => {
  res.json({
    ok: true,
    service: "SI Tradutor Live",
    version: "5.0-Gemini-Text-Live",
    gemini: !!GEMINI_API_KEY,
    model: GEMINI_MODEL,
    authentication: GEMINI_API_KEY
      ? "API key"
      : "not configured",
    sessions: sessions.size,
    time: now()
  });
});

/* =========================================================
   TESTE GEMINI
========================================================= */

app.get("/api/gemini/test", async (req, res) => {
  if (!GEMINI_API_KEY) {
    return res.status(500).json({
      ok: false,
      error: "GEMINI_API_KEY não configurada no Render"
    });
  }

  try {
    const response = await fetch(
      "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "x-goog-api-key": GEMINI_API_KEY
        },
        body: JSON.stringify({
          contents: [
            {
              parts: [
                {
                  text: "Responda somente: OK"
                }
              ]
            }
          ]
        })
      }
    );

    const text = await response.text();

    let data;

    try {
      data = JSON.parse(text);
    } catch (_) {
      data = {
        raw: text
      };
    }

    if (!response.ok) {
      return res.status(response.status).json({
        ok: false,
        status: response.status,
        geminiError: data
      });
    }

    return res.json({
      ok: true,
      status: response.status,
      gemini: data
    });
  } catch (error) {
    return res.status(500).json({
      ok: false,
      error: error.message
    });
  }
});

/* =========================================================
   INICIAR SESSÃO
========================================================= */

app.post("/api/audio/start", (req, res) => {
  try {
    const clientId =
      req.body?.clientId ||
      crypto.randomUUID();

    const targetLanguage =
      req.body?.targetLang ||
      req.body?.targetLanguage ||
      "pt-BR";

    const jobId = createJobId();

    const session = {
      jobId,
      clientId,

      targetLang: targetLanguage,
      targetLanguage,

      targetLanguageName:
        languageNames[targetLanguage] ||
        targetLanguage,

      status: "starting",

      createdAt: now(),
      lastAudioAt: null,

      chunks: 0,
      bytesReceived: 0,

      /*
       * FILA DE TEXTO TRADUZIDO
       */
      textQueue: [],

      lastTranslatedText: "",
      lastTranscript: "",
      lastAgentResponse: "",

      recording: false,

      gemini: !!GEMINI_API_KEY,
      geminiConnected: false,
      geminiReady: false,
      geminiSetupReceived: false,

      geminiMessages: 0,
      geminiError: null,

      geminiAuthMode: null,

      diagnostic: {
        recording: false,
        captureStarted: false,
        readCount: 0,
        lastRead: 0,
        capturedBytes: 0,
        queueSize: 0,
        textQueueSize: 0,
        error: null,
        updatedAt: now()
      },

      pendingAudio: [],

      geminiWs: null,
      reconnectTimer: null,

      stopped: false,
      connecting: false
    };

    sessions.set(jobId, session);

    connectGemini(session);

    res.json({
      ok: true,
      jobId,
      targetLang: targetLanguage,
      targetLanguage:
        languageNames[targetLanguage] ||
        targetLanguage
    });
  } catch (error) {
    console.error("Erro /api/audio/start:", error);

    res.status(500).json({
      ok: false,
      error: error.message
    });
  }
});

/* =========================================================
   CONECTAR GEMINI LIVE
========================================================= */

function connectGemini(session) {
  if (!session) return;

  if (session.stopped) return;

  if (!GEMINI_API_KEY) {
    session.geminiError =
      "GEMINI_API_KEY não configurada";

    return;
  }

  if (session.connecting) {
    return;
  }

  session.connecting = true;

  /*
   * Primeiro tenta API key no header.
   */
  connectGeminiWithMode(
    session,
    "header"
  );
}

function connectGeminiWithMode(
  session,
  mode
) {
  if (!session || session.stopped) {
    return;
  }

  let wsUrl = GEMINI_WS_BASE;

  const options = {
    handshakeTimeout: 15000
  };

  if (mode === "header") {
    options.headers = {
      "x-goog-api-key": GEMINI_API_KEY
    };
  } else {
    wsUrl =
      GEMINI_WS_BASE +
      "?key=" +
      encodeURIComponent(
        GEMINI_API_KEY
      );
  }

  console.log(
    `[GEMINI] Conectando usando autenticação: ${mode}`
  );

  session.geminiAuthMode = mode;

  let ws;

  try {
    ws = new WebSocket(
      wsUrl,
      options
    );
  } catch (error) {
    session.connecting = false;

    session.geminiError =
      `Falha criando WebSocket: ${error.message}`;

    scheduleReconnect(session);

    return;
  }

  session.geminiWs = ws;

  ws.on("open", () => {
    console.log(
      `[GEMINI] WebSocket aberto (${mode})`
    );

    session.geminiConnected = true;
    session.geminiReady = false;
    session.geminiSetupReceived = false;
    session.geminiError = null;
    session.connecting = false;

    const target =
      session.targetLanguage ||
      "pt-BR";

    /*
     * GEMINI LIVE:
     *
     * Entrada:
     * PCM16 mono 16 kHz
     *
     * Saída:
     * TEXTO traduzido
     *
     * O Android fará a voz usando
     * TextToSpeech.
     */
    const setupMessage = {
      setup: {
        model:
          `models/${GEMINI_MODEL}`,

        generationConfig: {
          responseModalities: [
            "TEXT"
          ],

          translationConfig: {
            targetLanguageCode: target,
            echoTargetLanguage: false
          }
        },

        inputAudioTranscription: {},

        outputAudioTranscription: {}
      }
    };

    console.log(
      "[GEMINI] Enviando setup:"
    );

    console.log(
      JSON.stringify(
        setupMessage
      )
    );

    sendJson(
      ws,
      setupMessage
    );

    setTimeout(() => {
      flushAudioQueue(session);
    }, 500);
  });

  ws.on("message", (data) => {
    handleGeminiMessage(
      session,
      data
    );
  });

  ws.on("error", (error) => {
    console.error(
      `[GEMINI] WebSocket error (${mode}):`,
      error.message
    );

    session.geminiError =
      error.message ||
      "Erro no WebSocket Gemini";
  });

  ws.on("close", (
    code,
    reasonBuffer
  ) => {
    const reason =
      reasonBuffer
        ? reasonBuffer.toString()
        : "";

    console.log(
      `[GEMINI] WebSocket fechado. code=${code} reason=${reason}`
    );

    session.geminiConnected = false;
    session.geminiReady = false;
    session.geminiSetupReceived = false;
    session.connecting = false;

    session.geminiError =
      `WebSocket fechado: ${code} ${reason}`;

    /*
     * Se o header falhar antes do setup,
     * tenta ?key=.
     */
    if (
      mode === "header" &&
      !session.geminiSetupReceived &&
      !session.stopped
    ) {
      console.log(
        "[GEMINI] Tentando fallback com ?key="
      );

      setTimeout(() => {
        if (!session.stopped) {
          session.connecting = true;

          connectGeminiWithMode(
            session,
            "query"
          );
        }
      }, 1000);

      return;
    }

    if (!session.stopped) {
      scheduleReconnect(session);
    }
  });
}

/* =========================================================
   RECEBER MENSAGENS GEMINI
========================================================= */

function handleGeminiMessage(
  session,
  data
) {
  let message;

  try {
    message = JSON.parse(
      data.toString()
    );
  } catch (error) {
    console.error(
      "[GEMINI] Mensagem não JSON:",
      error.message
    );

    return;
  }

  session.geminiMessages++;

  /*
   * SETUP COMPLETE
   */

  if (message.setupComplete) {
    console.log(
      "[GEMINI] setupComplete recebido!"
    );

    session.geminiSetupReceived =
      true;

    session.geminiReady =
      true;

    session.geminiConnected =
      true;

    session.geminiError =
      null;

    flushAudioQueue(session);

    return;
  }

  /*
   * ERRO GEMINI
   */

  if (message.error) {
    const errorText =
      JSON.stringify(
        message.error
      );

    console.error(
      "[GEMINI] Erro recebido:",
      errorText
    );

    session.geminiError =
      errorText;

    return;
  }

  const content =
    message.serverContent;

  if (!content) {
    return;
  }

  /*
   * TRANSCRIÇÃO DO ÁUDIO DE ENTRADA
   */

  if (
    content.inputTranscription
  ) {
    const text =
      content.inputTranscription.text ||
      "";

    if (text.trim()) {
      session.lastTranscript =
        text.trim();

      console.log(
        "[GEMINI] Transcrição:",
        text.trim()
      );
    }
  }

  /*
   * TEXTO DA TRADUÇÃO
   *
   * O Gemini pode entregar o texto
   * através de outputTranscription.
   */

  if (
    content.outputTranscription
  ) {
    const text =
      content.outputTranscription.text ||
      "";

    if (text.trim()) {
      addTranslatedText(
        session,
        text.trim()
      );
    }
  }

  /*
   * PARTES DE TEXTO DO MODEL TURN
   *
   * Algumas respostas podem chegar
   * diretamente em part.text.
   */

  if (
    content.modelTurn &&
    Array.isArray(
      content.modelTurn.parts
    )
  ) {
    for (
      const part of
      content.modelTurn.parts
    ) {
      if (
        part &&
        typeof part.text ===
          "string" &&
        part.text.trim()
      ) {
        addTranslatedText(
          session,
          part.text.trim()
        );
      }
    }
  }
}

/* =========================================================
   ADICIONAR TEXTO TRADUZIDO
========================================================= */

function addTranslatedText(
  session,
  text
) {
  if (!session) return;

  if (!text) return;

  /*
   * Evita duplicações imediatas.
   */
  if (
    session.lastTranslatedText ===
    text
  ) {
    return;
  }

  session.lastTranslatedText =
    text;

  session.lastAgentResponse =
    text;

  session.textQueue.push(text);

  /*
   * Limite de segurança.
   */
  if (
    session.textQueue.length >
    100
  ) {
    session.textQueue.shift();
  }

  session.diagnostic.textQueueSize =
    session.textQueue.length;

  session.diagnostic.updatedAt =
    now();

  console.log(
    `[GEMINI] TEXTO TRADUZIDO (${session.targetLanguage}):`,
    text
  );
}

/* =========================================================
   ENVIAR ÁUDIO PENDENTE
========================================================= */

function flushAudioQueue(
  session
) {
  if (!session) return;

  if (
    !session.geminiReady
  ) {
    return;
  }

  if (
    !session.geminiWs
  ) {
    return;
  }

  if (
    session.geminiWs.readyState !==
    WebSocket.OPEN
  ) {
    return;
  }

  if (
    !session.pendingAudio ||
    session.pendingAudio.length === 0
  ) {
    return;
  }

  const pending =
    session.pendingAudio;

  session.pendingAudio = [];

  console.log(
    `[GEMINI] Enviando ${pending.length} áudios pendentes`
  );

  for (
    const base64 of pending
  ) {
    sendAudioToGemini(
      session,
      base64
    );
  }
}

/* =========================================================
   ENVIAR PCM PARA GEMINI
========================================================= */

function sendAudioToGemini(
  session,
  base64
) {
  if (!session) return false;

  if (
    !session.geminiReady
  ) {
    if (
      !session.pendingAudio
    ) {
      session.pendingAudio = [];
    }

    session.pendingAudio.push(
      base64
    );

    if (
      session.pendingAudio.length >
      100
    ) {
      session.pendingAudio.shift();
    }

    return false;
  }

  const ws =
    session.geminiWs;

  if (
    !ws ||
    ws.readyState !==
      WebSocket.OPEN
  ) {
    return false;
  }

  const message = {
    realtimeInput: {
      audio: {
        data: base64,
        mimeType:
          "audio/pcm;rate=16000"
      }
    }
  };

  return sendJson(
    ws,
    message
  );
}

/* =========================================================
   RECEBER CHUNKS DO ANDROID
========================================================= */

app.post(
  "/api/audio/chunk",
  (req, res) => {
    try {
      const jobId =
        req.body?.jobId;

      const audio =
        req.body?.audio;

      if (!jobId) {
        return res.status(400).json({
          ok: false,
          error: "jobId ausente"
        });
      }

      if (!audio) {
        return res.status(400).json({
          ok: false,
          error: "audio ausente"
        });
      }

      const session =
        sessions.get(jobId);

      if (!session) {
        return res.status(404).json({
          ok: false,
          error: "Sessão não encontrada"
        });
      }

      if (session.stopped) {
        return res.status(400).json({
          ok: false,
          error: "Sessão já parada"
        });
      }

      session.status =
        "capturing";

      session.recording =
        true;

      session.chunks++;

      const estimatedBytes =
        Math.floor(
          (audio.length * 3) / 4
        );

      session.bytesReceived +=
        estimatedBytes;

      session.lastAudioAt =
        now();

      session.diagnostic.recording =
        true;

      session.diagnostic.captureStarted =
        true;

      session.diagnostic.readCount =
        session.chunks;

      session.diagnostic.lastRead =
        estimatedBytes;

      session.diagnostic.capturedBytes =
        session.bytesReceived;

      session.diagnostic.queueSize =
        session.textQueue.length;

      session.diagnostic.updatedAt =
        now();

      /*
       * Se Gemini ainda não está pronto,
       * guardar temporariamente.
       */

      if (
        !session.geminiReady
      ) {
        if (
          !session.pendingAudio
        ) {
          session.pendingAudio =
            [];
        }

        session.pendingAudio.push(
          audio
        );

        if (
          session.pendingAudio.length >
          100
        ) {
          session.pendingAudio.shift();
        }
      } else {
        sendAudioToGemini(
          session,
          audio
        );
      }

      res.json({
        ok: true,
        received: true,
        jobId,
        chunks:
          session.chunks,
        geminiConnected:
          session.geminiConnected,
        geminiReady:
          session.geminiReady
      });
    } catch (error) {
      console.error(
        "Erro /api/audio/chunk:",
        error
      );

      res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

/* =========================================================
   SAÍDA DE TEXTO TRADUZIDO
========================================================= */

app.get(
  "/api/audio/text/:jobId",
  (req, res) => {
    const session =
      sessions.get(
        req.params.jobId
      );

    if (!session) {
      return res.status(404).json({
        ok: false,
        error: "Sessão não encontrada"
      });
    }

    if (
      session.textQueue.length ===
      0
    ) {
      return res.json({
        ok: true,
        available: false,
        text: null,
        targetLang:
          session.targetLang,
        targetLanguageName:
          session.targetLanguageName
      });
    }

    const text =
      session.textQueue.shift();

    session.diagnostic.textQueueSize =
      session.textQueue.length;

    session.diagnostic.updatedAt =
      now();

    res.json({
      ok: true,
      available: true,
      text,
      targetLang:
        session.targetLang,
      targetLanguage:
        session.targetLanguage,
      targetLanguageName:
        session.targetLanguageName
    });
  }
);

/* =========================================================
   SAÍDA DE ÁUDIO
   Mantido para compatibilidade.
========================================================= */

app.get(
  "/api/audio/output/:jobId",
  (req, res) => {
    const session =
      sessions.get(
        req.params.jobId
      );

    if (!session) {
      return res.status(404).json({
        ok: false,
        error: "Sessão não encontrada"
      });
    }

    /*
     * Nesta versão o Gemini não está
     * sendo usado como gerador de áudio.
     *
     * O Android deverá usar TTS.
     */
    return res.json({
      ok: true,
      available: false,
      audio: null,
      sampleRate: 24000,
      channels: 1,
      format: "pcm_s16le",
      message:
        "Saída de voz agora será feita pelo Android TextToSpeech"
    });
  }
);

/* =========================================================
   STATUS
========================================================= */

app.get(
  "/api/audio/status/:jobId",
  (req, res) => {
    const session =
      sessions.get(
        req.params.jobId
      );

    if (!session) {
      return res.status(404).json({
        ok: false,
        error: "Sessão não encontrada"
      });
    }

    res.json({
      ok: true,

      jobId:
        session.jobId,

      status:
        session.status,

      targetLang:
        session.targetLang,

      targetLanguage:
        session.targetLanguage,

      targetLanguageName:
        session.targetLanguageName,

      gemini:
        session.gemini,

      geminiConnected:
        session.geminiConnected,

      geminiReady:
        session.geminiReady,

      geminiSetupReceived:
        session.geminiSetupReceived,

      geminiMessages:
        session.geminiMessages,

      geminiAuthMode:
        session.geminiAuthMode,

      geminiError:
        session.geminiError,

      chunks:
        session.chunks,

      bytesReceived:
        session.bytesReceived,

      textQueue:
        session.textQueue.length,

      lastTranslatedText:
        session.lastTranslatedText,

      lastTranscript:
        session.lastTranscript,

      lastAgentResponse:
        session.lastAgentResponse,

      lastAudioAt:
        session.lastAudioAt,

      diagnostic:
        session.diagnostic
    });
  }
);

/* =========================================================
   DIAGNÓSTICO
========================================================= */

app.post(
  "/api/audio/diagnostic",
  (req, res) => {
    try {
      const jobId =
        req.body?.jobId;

      if (!jobId) {
        return res.status(400).json({
          ok: false,
          error: "jobId ausente"
        });
      }

      const session =
        sessions.get(jobId);

      if (!session) {
        return res.status(404).json({
          ok: false,
          error: "Sessão não encontrada"
        });
      }

      if (
        typeof req.body.recording !==
        "undefined"
      ) {
        session.diagnostic.recording =
          !!req.body.recording;
      }

      if (
        typeof req.body.captureStarted !==
        "undefined"
      ) {
        session.diagnostic.captureStarted =
          !!req.body.captureStarted;
      }

      if (
        typeof req.body.readCount !==
        "undefined"
      ) {
        session.diagnostic.readCount =
          Number(
            req.body.readCount
          );
      }

      if (
        typeof req.body.lastRead !==
        "undefined"
      ) {
        session.diagnostic.lastRead =
          Number(
            req.body.lastRead
          );
      }

      if (
        typeof req.body.capturedBytes !==
        "undefined"
      ) {
        session.diagnostic.capturedBytes =
          Number(
            req.body.capturedBytes
          );
      }

      if (
        typeof req.body.error !==
        "undefined"
      ) {
        session.diagnostic.error =
          req.body.error;
      }

      session.diagnostic.textQueueSize =
        session.textQueue.length;

      session.diagnostic.updatedAt =
        now();

      res.json({
        ok: true,
        diagnostic:
          session.diagnostic
      });
    } catch (error) {
      res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

/* =========================================================
   LISTAR SESSÕES
========================================================= */

app.get(
  "/api/audio/sessions",
  (req, res) => {
    const list =
      Array.from(
        sessions.values()
      ).map(
        (session) => ({
          jobId:
            session.jobId,

          clientId:
            session.clientId,

          targetLang:
            session.targetLang,

          targetLanguage:
            session.targetLanguage,

          status:
            session.status,

          gemini:
            session.gemini,

          geminiConnected:
            session.geminiConnected,

          geminiReady:
            session.geminiReady,

          geminiSetupReceived:
            session.geminiSetupReceived,

          geminiMessages:
            session.geminiMessages,

          geminiAuthMode:
            session.geminiAuthMode,

          chunks:
            session.chunks,

          bytesReceived:
            session.bytesReceived,

          textQueue:
            session.textQueue.length,

          lastTranslatedText:
            session.lastTranslatedText,

          lastTranscript:
            session.lastTranscript,

          lastAgentResponse:
            session.lastAgentResponse,

          lastAudioAt:
            session.lastAudioAt,

          diagnostic:
            session.diagnostic,

          geminiError:
            session.geminiError,

          error:
            session.geminiError,

          createdAt:
            session.createdAt
        })
      );

    res.json({
      ok: true,
      count: list.length,
      sessions: list
    });
  }
);

/* =========================================================
   PARAR SESSÃO
========================================================= */

app.post(
  "/api/audio/stop",
  (req, res) => {
    try {
      const jobId =
        req.body?.jobId;

      const session =
        sessions.get(jobId);

      if (!session) {
        return res.status(404).json({
          ok: false,
          error: "Sessão não encontrada"
        });
      }

      session.stopped =
        true;

      session.status =
        "stopped";

      session.recording =
        false;

      session.diagnostic.recording =
        false;

      session.diagnostic.updatedAt =
        now();

      if (
        session.reconnectTimer
      ) {
        clearTimeout(
          session.reconnectTimer
        );

        session.reconnectTimer =
          null;
      }

      safeClose(
        session.geminiWs
      );

      session.geminiWs =
        null;

      session.geminiConnected =
        false;

      session.geminiReady =
        false;

      res.json({
        ok: true,
        jobId,
        status: "stopped"
      });
    } catch (error) {
      res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

/* =========================================================
   RECONEXÃO
========================================================= */

function scheduleReconnect(
  session
) {
  if (!session) return;

  if (session.stopped) {
    return;
  }

  if (session.reconnectTimer) {
    return;
  }

  session.reconnectTimer =
    setTimeout(() => {
      session.reconnectTimer =
        null;

      if (
        !session.stopped
      ) {
        connectGemini(
          session
        );
      }
    }, 3000);
}

/* =========================================================
   UPLOAD
========================================================= */

app.post(
  "/api/upload",
  upload.single("video"),
  (req, res) => {
    try {
      if (!req.file) {
        return res.status(400).json({
          ok: false,
          error: "Vídeo não enviado"
        });
      }

      const targetLang =
        req.body?.targetLang ||
        "pt-BR";

      res.json({
        ok: true,
        message:
          "Upload recebido",

        filename:
          req.file.originalname,

        size:
          req.file.size,

        targetLang
      });
    } catch (error) {
      res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

/* =========================================================
   RAIZ
========================================================= */

app.get("/", (req, res) => {
  res.json({
    ok: true,

    service:
      "SI Tradutor Live",

    version:
      "5.0-Gemini-Text-Live",

    message:
      "Backend do SI Tradutor Live funcionando",

    gemini:
      !!GEMINI_API_KEY,

    model:
      GEMINI_MODEL,

    authentication:
      GEMINI_API_KEY
        ? "API key"
        : "not configured",

    mode:
      "Gemini tradução + Android TextToSpeech"
  });
});

/* =========================================================
   LIMPEZA AUTOMÁTICA
========================================================= */

setInterval(() => {
  const limit =
    Date.now() -
    30 * 60 * 1000;

  for (
    const [jobId, session]
    of sessions
  ) {
    const created =
      new Date(
        session.createdAt
      ).getTime();

    if (
      created < limit &&
      session.stopped
    ) {
      safeClose(
        session.geminiWs
      );

      sessions.delete(
        jobId
      );

      console.log(
        "[CLEANUP] Sessão removida:",
        jobId
      );
    }
  }
}, 5 * 60 * 1000);

/* =========================================================
   ERROS
========================================================= */

process.on(
  "uncaughtException",
  (error) => {
    console.error(
      "[PROCESS] uncaughtException:",
      error
    );
  }
);

process.on(
  "unhandledRejection",
  (error) => {
    console.error(
      "[PROCESS] unhandledRejection:",
      error
    );
  }
);

/* =========================================================
   START
========================================================= */

app.listen(
  PORT,
  "0.0.0.0",
  () => {
    console.log(
      "===================================="
    );

    console.log(
      "SI Tradutor Live iniciado"
    );

    console.log(
      `Porta: ${PORT}`
    );

    console.log(
      `Gemini: ${
        GEMINI_API_KEY
          ? "CONFIGURADO"
          : "NÃO CONFIGURADO"
      }`
    );

    console.log(
      `Modelo: ${GEMINI_MODEL}`
    );

    console.log(
      "Modo: Gemini → texto → Android TTS"
    );

    console.log(
      "===================================="
    );
  }
);
