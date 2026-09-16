// ============================================================
// SI TRADUTOR LIVE - BACKEND
// Gemini Live API + Token Efêmero
// ============================================================

const express = require("express");
const cors = require("cors");
const multer = require("multer");
const crypto = require("crypto");
const WebSocket = require("ws");
const fs = require("fs");
const path = require("path");

// ============================================================
// CONFIGURAÇÃO
// ============================================================

const app = express();

const PORT = process.env.PORT || 10000;

const BACKEND_VERSION = "9.0-Gemini-Ephemeral-Fixed";

const GEMINI_API_KEY =
  process.env.GEMINI_API_KEY || "";

const GEMINI_MODEL =
  process.env.GEMINI_MODEL ||
  "gemini-3.5-live-translate-preview";

const GEMINI_TOKEN_URL =
  "https://generativelanguage.googleapis.com/v1beta/auth_tokens";

const GEMINI_WS_BASE =
  "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained";

const UPLOAD_DIR = path.join(__dirname, "uploads");

const OUTPUT_DIR = path.join(__dirname, "outputs");

// ============================================================
// DIRETÓRIOS
// ============================================================

if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, {
    recursive: true
  });
}

if (!fs.existsSync(OUTPUT_DIR)) {
  fs.mkdirSync(OUTPUT_DIR, {
    recursive: true
  });
}

// ============================================================
// MIDDLEWARE
// ============================================================

app.use(
  cors({
    origin: "*",
    methods: [
      "GET",
      "POST",
      "PUT",
      "DELETE",
      "OPTIONS"
    ],
    allowedHeaders: [
      "Content-Type",
      "Authorization"
    ]
  })
);

app.use(express.json({
  limit: "50mb"
}));

app.use(express.urlencoded({
  extended: true,
  limit: "50mb"
}));

// ============================================================
// MULTER
// ============================================================

const upload = multer({
  dest: UPLOAD_DIR,
  limits: {
    fileSize: 2 * 1024 * 1024 * 1024
  }
});

// ============================================================
// ID
// ============================================================

function createId() {
  return crypto.randomUUID();
}

// ============================================================
// IDIOMAS
// ============================================================

const LANGUAGE_NAMES = {
  "pt-BR": "Português do Brasil",
  "en-US": "Inglês",
  "es-ES": "Espanhol",
  "fr-FR": "Francês",
  "de-DE": "Alemão",
  "it-IT": "Italiano",
  "ja-JP": "Japonês",
  "ko-KR": "Coreano",
  "zh-CN": "Chinês",
  "ru-RU": "Russo",
  "ar-SA": "Árabe",
  "hi-IN": "Hindi",
  "tr-TR": "Turco",
  "nl-NL": "Holandês",
  "pl-PL": "Polonês",
  "uk-UA": "Ucraniano",
  "th-TH": "Tailandês",
  "id-ID": "Indonésio",
  "vi-VN": "Vietnamita"
};

// ============================================================
// SESSÕES
// ============================================================

const sessions = new Map();

// ============================================================
// NORMALIZAR IDIOMA
// ============================================================

function normalizeLanguage(language) {
  if (!language) {
    return "pt-BR";
  }

  const value = String(language).trim();

  if (LANGUAGE_NAMES[value]) {
    return value;
  }

  const lower = value.toLowerCase();

  const found = Object.keys(LANGUAGE_NAMES)
    .find((key) =>
      key.toLowerCase() === lower
    );

  if (found) {
    return found;
  }

  if (lower === "pt") return "pt-BR";
  if (lower === "en") return "en-US";
  if (lower === "es") return "es-ES";
  if (lower === "fr") return "fr-FR";
  if (lower === "de") return "de-DE";
  if (lower === "it") return "it-IT";
  if (lower === "ja") return "ja-JP";
  if (lower === "ko") return "ko-KR";
  if (lower === "zh") return "zh-CN";
  if (lower === "ru") return "ru-RU";
  if (lower === "ar") return "ar-SA";
  if (lower === "hi") return "hi-IN";
  if (lower === "tr") return "tr-TR";
  if (lower === "nl") return "nl-NL";
  if (lower === "pl") return "pl-PL";
  if (lower === "uk") return "uk-UA";
  if (lower === "th") return "th-TH";
  if (lower === "id") return "id-ID";
  if (lower === "vi") return "vi-VN";

  return value;
}

// ============================================================
// HEALTH
// ============================================================

app.get("/api/health", (req, res) => {
  res.json({
    ok: true,
    service: "SI Tradutor Live",
    version: BACKEND_VERSION,
    message: "Backend do SI Tradutor Live funcionando",
    gemini: Boolean(GEMINI_API_KEY),
    model: GEMINI_MODEL,
    authentication: "API key + ephemeral token"
  });
});

// ============================================================
// TESTE GEMINI NORMAL
// ============================================================

app.get("/api/gemini/test", async (req, res) => {
  try {
    if (!GEMINI_API_KEY) {
      return res.status(500).json({
        ok: false,
        error: "GEMINI_API_KEY não configurada"
      });
    }

    console.log("[GEMINI TEST] Testando API Gemini...");

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
                  text: "Responda somente OK."
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
    } catch {
      data = {
        raw: text
      };
    }

    console.log(
      "[GEMINI TEST]",
      response.status
    );

    return res.status(response.status).json({
      ok: response.ok,
      status: response.status,
      gemini: data
    });

  } catch (error) {
    console.error(
      "[GEMINI TEST ERROR]",
      error
    );

    return res.status(500).json({
      ok: false,
      error: error.message
    });
  }
});

// ============================================================
// CRIAR TOKEN EFÊMERO GEMINI
// ============================================================

async function createGeminiEphemeralToken(
  targetLanguage
) {
  if (!GEMINI_API_KEY) {
    throw new Error(
      "GEMINI_API_KEY não configurada no Render"
    );
  }

  const target =
    normalizeLanguage(targetLanguage);

  console.log(
    `[GEMINI] Criando token temporário para ${target}`
  );

  const now = Date.now();

  const expireTime =
    new Date(
      now + 30 * 60 * 1000
    ).toISOString();

  const newSessionExpireTime =
    new Date(
      now + 60 * 1000
    ).toISOString();

  // ==========================================================
  // IMPORTANTE:
  //
  // O endpoint REST /auth_tokens recebe diretamente AuthToken.
  //
  // NÃO usar:
  //
  // {
  //   authToken: {
  //      ...
  //   }
  // }
  //
  // E NÃO usar:
  //
  // liveConnectConstraints
  //
  // O campo correto é:
  //
  // bidiGenerateContentSetup
  // ==========================================================

  const body = {
    uses: 1,

    expireTime,

    newSessionExpireTime,

    bidiGenerateContentSetup: {
      model: `models/${GEMINI_MODEL}`,

      generationConfig: {
        responseModalities: [
          "AUDIO"
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
    "[GEMINI] Solicitando token efêmero..."
  );

  const response = await fetch(
    GEMINI_TOKEN_URL,
    {
      method: "POST",

      headers: {
        "Content-Type": "application/json",
        "x-goog-api-key": GEMINI_API_KEY
      },

      body: JSON.stringify(body)
    }
  );

  const text = await response.text();

  let data;

  try {
    data = JSON.parse(text);
  } catch {
    data = {
      raw: text
    };
  }

  if (!response.ok) {
    console.error(
      "[GEMINI TOKEN ERROR]",
      JSON.stringify(data)
    );

    throw new Error(
      `Falha criando token Gemini (${response.status}): ${
        data?.error?.message || text
      }`
    );
  }

  if (!data.name) {
    console.error(
      "[GEMINI TOKEN ERROR] Resposta sem token:",
      JSON.stringify(data)
    );

    throw new Error(
      "Gemini não retornou o token efêmero"
    );
  }

  console.log(
    "[GEMINI] Token temporário criado com sucesso"
  );

  return data.name;
}

// ============================================================
// CRIAR SESSÃO DE ÁUDIO
// ============================================================

app.post(
  "/api/audio/start",
  async (req, res) => {

    try {

      const clientId =
        req.body?.clientId ||
        createId();

      const targetLanguage =
        normalizeLanguage(
          req.body?.targetLang ||
          req.body?.targetLanguage ||
          "pt-BR"
        );

      const jobId = createId();

      const session = {
        jobId,
        clientId,
        targetLanguage,

        createdAt: Date.now(),

        status: "starting",

        geminiReady: false,

        geminiConnecting: false,

        stopped: false,

        chunksReceived: 0,

        bytesReceived: 0,

        outputChunks: [],

        outputBytes: 0,

        inputTranscript: "",

        outputTranscript: "",

        lastError: null,

        lastActivity: Date.now(),

        ws: null,

        reconnectTimer: null,

        reconnectAttempts: 0,

        diagnostics: []
      };

      sessions.set(
        jobId,
        session
      );

      console.log(
        `[AUDIO] Nova sessão: ${jobId}`
      );

      console.log(
        `[AUDIO] Idioma destino: ${targetLanguage}`
      );

      // Conecta Gemini sem bloquear resposta
      connectGemini(jobId)
        .catch((error) => {

          console.error(
            `[GEMINI] Falha inicial ${jobId}:`,
            error.message
          );

          const current =
            sessions.get(jobId);

          if (current) {
            current.lastError =
              error.message;

            current.status =
              "gemini_error";
          }
        });

      return res.json({
        ok: true,
        jobId,
        clientId,
        targetLanguage,
        targetLanguageName:
          LANGUAGE_NAMES[targetLanguage] ||
          targetLanguage,
        status: "starting"
      });

    } catch (error) {

      console.error(
        "[AUDIO START ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

// ============================================================
// CONECTAR GEMINI LIVE
// ============================================================

async function connectGemini(jobId) {

  const session =
    sessions.get(jobId);

  if (!session) {
    throw new Error(
      "Sessão não encontrada"
    );
  }

  if (session.stopped) {
    return;
  }

  if (session.geminiConnecting) {
    return;
  }

  session.geminiConnecting = true;

  try {

    console.log(
      `[GEMINI] Criando conexão para ${jobId}`
    );

    const token =
      await createGeminiEphemeralToken(
        session.targetLanguage
      );

    if (
      !token ||
      typeof token !== "string"
    ) {
      throw new Error(
        "Token Gemini inválido"
      );
    }

    const wsUrl =
      `${GEMINI_WS_BASE}?access_token=${encodeURIComponent(token)}`;

    console.log(
      "[GEMINI] Abrindo WebSocket..."
    );

    const ws =
      new WebSocket(wsUrl);

    session.ws = ws;

    ws.binaryType =
      "arraybuffer";

    ws.on(
      "open",
      () => {

        console.log(
          `[GEMINI] WebSocket aberto - ${jobId}`
        );

        session.status =
          "connected";

        session.geminiConnecting =
          false;

        session.reconnectAttempts =
          0;

        session.lastActivity =
          Date.now();

        // ====================================================
        // SETUP DA SESSÃO
        // ====================================================

        const setup = {
          setup: {
            model:
              `models/${GEMINI_MODEL}`,

            generationConfig: {
              responseModalities: [
                "AUDIO"
              ],

              translationConfig: {
                targetLanguageCode:
                  session.targetLanguage,

                echoTargetLanguage:
                  false
              }
            },

            inputAudioTranscription: {},

            outputAudioTranscription: {}
          }
        };

        console.log(
          "[GEMINI] Enviando setup..."
        );

        ws.send(
          JSON.stringify(setup)
        );
      }
    );

    ws.on(
      "message",
      (raw) => {

        handleGeminiMessage(
          jobId,
          raw
        );
      }
    );

    ws.on(
      "error",
      (error) => {

        console.error(
          `[GEMINI WS ERROR] ${jobId}:`,
          error.message
        );

        session.lastError =
          error.message;
      }
    );

    ws.on(
      "close",
      (code, reason) => {

        const reasonText =
          reason
            ? reason.toString()
            : "";

        console.log(
          `[GEMINI] WebSocket fechado. code=${code} reason=${reasonText}`
        );

        session.ws = null;

        session.geminiReady =
          false;

        session.geminiConnecting =
          false;

        if (!session.stopped) {

          session.status =
            "reconnecting";

          scheduleGeminiReconnect(
            jobId
          );
        }
      }
    );

  } catch (error) {

    session.geminiConnecting =
      false;

    session.geminiReady =
      false;

    session.lastError =
      error.message;

    throw error;
  }
}

// ============================================================
// RECONEXÃO
// ============================================================

function scheduleGeminiReconnect(
  jobId
) {

  const session =
    sessions.get(jobId);

  if (!session) {
    return;
  }

  if (session.stopped) {
    return;
  }

  if (session.reconnectTimer) {
    return;
  }

  session.reconnectAttempts++;

  const delay = 3000;

  console.log(
    `[GEMINI] Nova tentativa em 3 segundos: ${jobId}`
  );

  session.reconnectTimer =
    setTimeout(
      async () => {

        session.reconnectTimer =
          null;

        try {

          await connectGemini(
            jobId
          );

        } catch (error) {

          console.error(
            `[GEMINI] Reconexão falhou: ${error.message}`
          );

          scheduleGeminiReconnect(
            jobId
          );
        }

      },
      delay
    );
}

// ============================================================
// PROCESSAR MENSAGENS GEMINI
// ============================================================

function handleGeminiMessage(
  jobId,
  raw
) {

  const session =
    sessions.get(jobId);

  if (!session) {
    return;
  }

  session.lastActivity =
    Date.now();

  let data;

  try {

    if (Buffer.isBuffer(raw)) {
      raw = raw.toString("utf8");
    }

    data =
      JSON.parse(raw.toString());

  } catch (error) {

    console.error(
      "[GEMINI] Mensagem JSON inválida:",
      error.message
    );

    return;
  }

  // ==========================================================
  // SETUP COMPLETO
  // ==========================================================

  if (data.setupComplete) {

    console.log(
      "[GEMINI] setupComplete recebido!"
    );

    session.geminiReady =
      true;

    session.status =
      "ready";

    return;
  }

  // ==========================================================
  // ERRO
  // ==========================================================

  if (data.error) {

    console.error(
      "[GEMINI SERVER ERROR]",
      JSON.stringify(data.error)
    );

    session.lastError =
      data.error.message ||
      JSON.stringify(data.error);

    session.geminiReady =
      false;

    return;
  }

  // ==========================================================
  // TRANSCRIÇÃO DE ENTRADA
  // ==========================================================

  if (data.serverContent) {

    const content =
      data.serverContent;

    if (
      content.inputTranscription
    ) {

      const text =
        content
          .inputTranscription
          .text || "";

      if (text) {

        session.inputTranscript +=
          text;

        console.log(
          `[GEMINI INPUT] ${text}`
        );
      }
    }

    // ========================================================
    // TRANSCRIÇÃO DE SAÍDA
    // ========================================================

    if (
      content.outputTranscription
    ) {

      const text =
        content
          .outputTranscription
          .text || "";

      if (text) {

        session.outputTranscript +=
          text;

        console.log(
          `[GEMINI OUTPUT] ${text}`
        );
      }
    }

    // ========================================================
    // ÁUDIO
    // ========================================================

    if (
      content.modelTurn &&
      Array.isArray(
        content.modelTurn.parts
      )
    ) {

      for (
        const part
        of content.modelTurn.parts
      ) {

        if (
          part.inlineData &&
          part.inlineData.data
        ) {

          const audioBase64 =
            part.inlineData.data;

          try {

            const audioBuffer =
              Buffer.from(
                audioBase64,
                "base64"
              );

            if (
              audioBuffer.length > 0
            ) {

              session.outputChunks.push(
                audioBase64
              );

              session.outputBytes +=
                audioBuffer.length;

              console.log(
                `[GEMINI] Áudio recebido: ${audioBuffer.length} bytes`
              );

              // Evita memória ilimitada
              if (
                session.outputChunks.length >
                200
              ) {

                session.outputChunks =
                  session.outputChunks.slice(
                    -200
                  );
              }
            }

          } catch (error) {

            console.error(
              "[GEMINI AUDIO ERROR]",
              error.message
            );
          }
        }
      }
    }
  }
}

// ============================================================
// RECEBER CHUNK DE ÁUDIO DO ANDROID
// ============================================================

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
          error: "jobId obrigatório"
        });
      }

      if (!audio) {

        return res.status(400).json({
          ok: false,
          error: "audio obrigatório"
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

        return res.json({
          ok: false,
          stopped: true
        });
      }

      session.chunksReceived++;

      let audioBuffer;

      try {

        audioBuffer =
          Buffer.from(
            audio,
            "base64"
          );

      } catch (error) {

        return res.status(400).json({
          ok: false,
          error: "Áudio Base64 inválido"
        });
      }

      session.bytesReceived +=
        audioBuffer.length;

      session.lastActivity =
        Date.now();

      // ======================================================
      // ENVIAR PARA GEMINI
      // ======================================================

      if (
        session.ws &&
        session.ws.readyState ===
        WebSocket.OPEN &&
        session.geminiReady
      ) {

        const message = {
          realtimeInput: {
            audio: {
              mimeType:
                "audio/pcm;rate=16000",
              data: audio
            }
          }
        };

        try {

          session.ws.send(
            JSON.stringify(message)
          );

        } catch (error) {

          console.error(
            "[GEMINI SEND ERROR]",
            error.message
          );

          session.lastError =
            error.message;
        }

      } else {

        // Gemini ainda não está pronto.
        // O Android pode continuar mandando
        // pequenos chunks até a conexão ficar pronta.

        if (
          session.chunksReceived % 20 === 0
        ) {

          console.log(
            `[AUDIO] Gemini ainda não pronto. chunks=${session.chunksReceived}`
          );
        }
      }

      return res.json({
        ok: true,
        received: audioBuffer.length,
        chunksReceived:
          session.chunksReceived,
        geminiReady:
          session.geminiReady
      });

    } catch (error) {

      console.error(
        "[AUDIO CHUNK ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

// ============================================================
// PEGAR ÁUDIO DE SAÍDA
// ============================================================

app.get(
  "/api/audio/output/:jobId",
  (req, res) => {

    try {

      const jobId =
        req.params.jobId;

      const session =
        sessions.get(jobId);

      if (!session) {

        return res.status(404).json({
          ok: false,
          error: "Sessão não encontrada"
        });
      }

      // Sem áudio
      if (
        session.outputChunks.length === 0
      ) {

        return res.json({
          ok: true,
          available: false,
          audio: null,
          sampleRate: 24000,
          channels: 1,
          format: "pcm_s16le"
        });
      }

      // ======================================================
      // PEGA TODOS OS CHUNKS DISPONÍVEIS
      // ======================================================

      const chunks =
        session.outputChunks.splice(
          0,
          session.outputChunks.length
        );

      const buffers =
        chunks.map(
          (chunk) =>
            Buffer.from(
              chunk,
              "base64"
            )
        );

      const combined =
        Buffer.concat(buffers);

      return res.json({
        ok: true,
        available: true,
        audio:
          combined.toString("base64"),
        bytes:
          combined.length,
        sampleRate: 24000,
        channels: 1,
        format: "pcm_s16le"
      });

    } catch (error) {

      console.error(
        "[AUDIO OUTPUT ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

// ============================================================
// STATUS
// ============================================================

app.get(
  "/api/audio/status/:jobId",
  (req, res) => {

    const jobId =
      req.params.jobId;

    const session =
      sessions.get(jobId);

    if (!session) {

      return res.status(404).json({
        ok: false,
        error: "Sessão não encontrada"
      });
    }

    return res.json({
      ok: true,

      jobId:
        session.jobId,

      clientId:
        session.clientId,

      status:
        session.status,

      targetLanguage:
        session.targetLanguage,

      targetLanguageName:
        LANGUAGE_NAMES[
          session.targetLanguage
        ] ||
        session.targetLanguage,

      geminiReady:
        session.geminiReady,

      geminiConnecting:
        session.geminiConnecting,

      chunksReceived:
        session.chunksReceived,

      bytesReceived:
        session.bytesReceived,

      outputChunks:
        session.outputChunks.length,

      outputBytes:
        session.outputBytes,

      inputTranscript:
        session.inputTranscript,

      outputTranscript:
        session.outputTranscript,

      lastError:
        session.lastError,

      lastActivity:
        session.lastActivity,

      uptime:
        Date.now() -
        session.createdAt
    });
  }
);

// ============================================================
// DIAGNÓSTICO
// ============================================================

app.post(
  "/api/audio/diagnostic",
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

      const diagnostic = {
        timestamp:
          new Date().toISOString(),

        data:
          req.body
      };

      session.diagnostics.push(
        diagnostic
      );

      if (
        session.diagnostics.length >
        100
      ) {

        session.diagnostics =
          session.diagnostics.slice(
            -100
          );
      }

      console.log(
        `[DIAGNOSTIC] ${jobId}`,
        JSON.stringify(req.body)
      );

      return res.json({
        ok: true
      });

    } catch (error) {

      return res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

// ============================================================
// LISTAR SESSÕES
// ============================================================

app.get(
  "/api/audio/sessions",
  (req, res) => {

    const result =
      [];

    for (
      const session
      of sessions.values()
    ) {

      result.push({
        jobId:
          session.jobId,

        clientId:
          session.clientId,

        targetLanguage:
          session.targetLanguage,

        status:
          session.status,

        geminiReady:
          session.geminiReady,

        chunksReceived:
          session.chunksReceived,

        outputChunks:
          session.outputChunks.length,

        createdAt:
          session.createdAt,

        lastActivity:
          session.lastActivity,

        lastError:
          session.lastError
      });
    }

    return res.json({
      ok: true,
      count:
        result.length,
      sessions:
        result
    });
  }
);

// ============================================================
// PARAR SESSÃO
// ============================================================

app.post(
  "/api/audio/stop",
  (req, res) => {

    try {

      const jobId =
        req.body?.jobId;

      if (!jobId) {

        return res.status(400).json({
          ok: false,
          error: "jobId obrigatório"
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

      stopSession(session);

      return res.json({
        ok: true,
        jobId,
        status: "stopped"
      });

    } catch (error) {

      console.error(
        "[AUDIO STOP ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

// ============================================================
// PARAR SESSÃO INTERNAMENTE
// ============================================================

function stopSession(session) {

  session.stopped =
    true;

  session.status =
    "stopped";

  session.geminiReady =
    false;

  if (session.reconnectTimer) {

    clearTimeout(
      session.reconnectTimer
    );

    session.reconnectTimer =
      null;
  }

  if (
    session.ws
  ) {

    try {

      if (
        session.ws.readyState ===
        WebSocket.OPEN ||
        session.ws.readyState ===
        WebSocket.CONNECTING
      ) {

        session.ws.close();

      }

    } catch (error) {

      console.error(
        "[GEMINI CLOSE ERROR]",
        error.message
      );
    }

    session.ws =
      null;
  }

  console.log(
    `[AUDIO] Sessão parada: ${session.jobId}`
  );
}

// ============================================================
// UPLOAD DE VÍDEO
// ============================================================

app.post(
  "/api/upload",
  upload.single("video"),
  async (req, res) => {

    try {

      if (!req.file) {

        return res.status(400).json({
          ok: false,
          error: "Nenhum vídeo enviado"
        });
      }

      const targetLanguage =
        normalizeLanguage(
          req.body?.targetLang ||
          "pt-BR"
        );

      const jobId =
        createId();

      const extension =
        path.extname(
          req.file.originalname ||
          ".mp4"
        );

      const finalPath =
        path.join(
          UPLOAD_DIR,
          `${jobId}${extension}`
        );

      fs.renameSync(
        req.file.path,
        finalPath
      );

      console.log(
        `[UPLOAD] Vídeo recebido: ${jobId}`
      );

      return res.json({
        ok: true,
        jobId,
        targetLanguage,
        file: finalPath
      });

    } catch (error) {

      console.error(
        "[UPLOAD ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error: error.message
      });
    }
  }
);

// ============================================================
// ROTA RAIZ
// ============================================================

app.get(
  "/",
  (req, res) => {

    res.json({
      ok: true,
      service:
        "SI Tradutor Live",

      version:
        BACKEND_VERSION,

      message:
        "Backend do SI Tradutor Live funcionando",

      gemini:
        Boolean(GEMINI_API_KEY),

      model:
        GEMINI_MODEL
    });
  }
);

// ============================================================
// 404
// ============================================================

app.use(
  (req, res) => {

    res.status(404).json({
      ok: false,
      error: "Rota não encontrada",
      path: req.path
    });
  }
);

// ============================================================
// ERRO GLOBAL
// ============================================================

app.use(
  (error, req, res, next) => {

    console.error(
      "[GLOBAL ERROR]",
      error
    );

    if (res.headersSent) {
      return next(error);
    }

    res.status(500).json({
      ok: false,
      error:
        error.message ||
        "Erro interno do servidor"
    });
  }
);

// ============================================================
// LIMPEZA AUTOMÁTICA DAS SESSÕES
// ============================================================

setInterval(
  () => {

    const now =
      Date.now();

    const SESSION_MAX_AGE =
      60 * 60 * 1000;

    for (
      const [jobId, session]
      of sessions.entries()
    ) {

      if (
        now -
        session.lastActivity >
        SESSION_MAX_AGE
      ) {

        console.log(
          `[CLEANUP] Removendo sessão ${jobId}`
        );

        stopSession(
          session
        );

        sessions.delete(
          jobId
        );
      }
    }

  },
  5 * 60 * 1000
);

// ============================================================
// PROCESS ERRORS
// ============================================================

process.on(
  "uncaughtException",
  (error) => {

    console.error(
      "[UNCAUGHT EXCEPTION]",
      error
    );
  }
);

process.on(
  "unhandledRejection",
  (error) => {

    console.error(
      "[UNHANDLED REJECTION]",
      error
    );
  }
);

// ============================================================
// INICIAR SERVIDOR
// ============================================================

app.listen(
  PORT,
  "0.0.0.0",
  () => {

    console.log(
      "================================================="
    );

    console.log(
      "SI TRADUTOR LIVE"
    );

    console.log(
      `Versão: ${BACKEND_VERSION}`
    );

    console.log(
      `Porta: ${PORT}`
    );

    console.log(
      `Gemini API: ${
        GEMINI_API_KEY
          ? "CONFIGURADA"
          : "NÃO CONFIGURADA"
      }`
    );

    console.log(
      `Modelo: ${GEMINI_MODEL}`
    );

    console.log(
      "Token: Gemini Ephemeral"
    );

    console.log(
      "================================================="
    );
  }
);
