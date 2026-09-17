// ============================================================
// SI TRADUTOR LIVE
// Backend Node.js
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

const BACKEND_VERSION =
  "12.0-Gemini-Live-Audio-Robusto";

const GEMINI_API_KEY =
  process.env.GEMINI_API_KEY || "";

const GEMINI_MODEL =
  process.env.GEMINI_MODEL ||
  "gemini-3.5-live-translate-preview";

const GEMINI_TOKEN_URL =
  "https://generativelanguage.googleapis.com/v1beta/auth_tokens";

const GEMINI_WS_BASE =
  "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained";

// ============================================================
// DIRETÓRIOS
// ============================================================

const UPLOAD_DIR =
  path.join(__dirname, "uploads");

const OUTPUT_DIR =
  path.join(__dirname, "outputs");

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

app.use(
  express.json({
    limit: "50mb"
  })
);

app.use(
  express.urlencoded({
    extended: true,
    limit: "50mb"
  })
);

// ============================================================
// UPLOAD
// ============================================================

const upload =
  multer({
    dest: UPLOAD_DIR,
    limits: {
      fileSize:
        2 * 1024 * 1024 * 1024
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

  "pt-BR":
    "Português do Brasil",

  "en-US":
    "Inglês",

  "es-ES":
    "Espanhol",

  "fr-FR":
    "Francês",

  "de-DE":
    "Alemão",

  "it-IT":
    "Italiano",

  "ja-JP":
    "Japonês",

  "ko-KR":
    "Coreano",

  "zh-CN":
    "Chinês",

  "ru-RU":
    "Russo",

  "ar-SA":
    "Árabe",

  "hi-IN":
    "Hindi",

  "tr-TR":
    "Turco",

  "nl-NL":
    "Holandês",

  "pl-PL":
    "Polonês",

  "uk-UA":
    "Ucraniano",

  "th-TH":
    "Tailandês",

  "id-ID":
    "Indonésio",

  "vi-VN":
    "Vietnamita"
};

// ============================================================
// SESSÕES
// ============================================================

const sessions =
  new Map();

// ============================================================
// BUFFER DE ÁUDIO DO GEMINI
// ============================================================
// O Gemini pode devolver a fala em vários pedaços pequenos.
// Acumulamos os pedaços e liberamos um bloco maior para o
// Android, reduzindo o efeito "palavra -> pausa -> palavra".
// ============================================================

const GEMINI_AUDIO_FLUSH_MS = 350;
const GEMINI_AUDIO_MAX_BUFFER_BYTES = 24000 * 2 * 2;

function criarBufferAudioSession(session) {
  if (!session.pendingOutputBuffers) session.pendingOutputBuffers = [];
  if (typeof session.pendingOutputBytes !== "number") session.pendingOutputBytes = 0;
  if (typeof session.outputFlushTimer === "undefined") session.outputFlushTimer = null;
  if (typeof session.outputTurns !== "number") session.outputTurns = 0;
}

function adicionarAudioGeminiAoBuffer(session, audioBuffer) {
  if (!session || !audioBuffer || audioBuffer.length === 0) return;
  criarBufferAudioSession(session);

  session.pendingOutputBuffers.push(audioBuffer);
  session.pendingOutputBytes += audioBuffer.length;

  console.log(
    `[GEMINI BUFFER] recebido=${audioBuffer.length} bytes ` +
    `acumulado=${session.pendingOutputBytes} bytes ` +
    `partes=${session.pendingOutputBuffers.length}`
  );

  if (session.pendingOutputBytes >= GEMINI_AUDIO_MAX_BUFFER_BYTES) {
    flushAudioGemini(session, "max_buffer");
    return;
  }

  if (session.outputFlushTimer) clearTimeout(session.outputFlushTimer);

  session.outputFlushTimer = setTimeout(() => {
    session.outputFlushTimer = null;
    if (session.pendingOutputBytes > 0) {
      flushAudioGemini(session, "silence_timeout");
    }
  }, GEMINI_AUDIO_FLUSH_MS);
}

function flushAudioGemini(session, reason) {
  if (
    !session ||
    !session.pendingOutputBuffers ||
    session.pendingOutputBuffers.length === 0
  ) return;

  if (session.outputFlushTimer) {
    clearTimeout(session.outputFlushTimer);
    session.outputFlushTimer = null;
  }

  const buffers = session.pendingOutputBuffers;
  const totalBytes = session.pendingOutputBytes;

  session.pendingOutputBuffers = [];
  session.pendingOutputBytes = 0;

  if (totalBytes <= 0) return;

  const combined = Buffer.concat(buffers, totalBytes);
  const usableLength = combined.length - (combined.length % 2);
  const finalAudio =
    usableLength === combined.length
      ? combined
      : combined.subarray(0, usableLength);

  if (finalAudio.length === 0) return;

  session.nextOutputSeq++;
  session.outputChunks.push({
    seq: session.nextOutputSeq,
    audio: finalAudio.toString("base64")
  });
  session.outputBytes += finalAudio.length;
  session.outputTurns++;

  console.log(
    `[GEMINI BUFFER] BLOCO LIBERADO ` +
    `reason=${reason} bytes=${finalAudio.length} ` +
    `partes=${buffers.length} seq=${session.nextOutputSeq}`
  );

  if (session.outputChunks.length > 500) {
    session.outputChunks = session.outputChunks.slice(-500);
  }
}

// ============================================================
// NORMALIZAR IDIOMA
// ============================================================

function normalizeLanguage(language) {

  if (!language) {
    return "pt-BR";
  }

  const value =
    String(language).trim();

  if (
    LANGUAGE_NAMES[value]
  ) {
    return value;
  }

  const lower =
    value.toLowerCase();

  const exact =
    Object.keys(
      LANGUAGE_NAMES
    ).find(
      (key) =>
        key.toLowerCase() ===
        lower
    );

  if (exact) {
    return exact;
  }

  const shortLanguages = {

    pt: "pt-BR",
    en: "en-US",
    es: "es-ES",
    fr: "fr-FR",
    de: "de-DE",
    it: "it-IT",
    ja: "ja-JP",
    ko: "ko-KR",
    zh: "zh-CN",
    ru: "ru-RU",
    ar: "ar-SA",
    hi: "hi-IN",
    tr: "tr-TR",
    nl: "nl-NL",
    pl: "pl-PL",
    uk: "uk-UA",
    th: "th-TH",
    id: "id-ID",
    vi: "vi-VN"
  };

  if (
    shortLanguages[lower]
  ) {
    return shortLanguages[lower];
  }

  return value;
}

// ============================================================
// HEALTH
// ============================================================

app.get(
  "/api/health",
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
        GEMINI_MODEL,

      authentication:
        "API key + ephemeral token"
    });
  }
);

// ============================================================
// TESTE GEMINI
// ============================================================

app.get(
  "/api/gemini/test",
  async (req, res) => {

    try {

      if (!GEMINI_API_KEY) {

        return res.status(500).json({

          ok: false,

          error:
            "GEMINI_API_KEY não configurada no Render"
        });
      }

      console.log(
        "[GEMINI TEST] Testando API Gemini..."
      );

      const response =
        await fetch(
          "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent",
          {
            method: "POST",

            headers: {

              "Content-Type":
                "application/json",

              "x-goog-api-key":
                GEMINI_API_KEY
            },

            body:
              JSON.stringify({

                contents: [

                  {

                    parts: [

                      {

                        text:
                          "Responda somente OK."

                      }

                    ]

                  }

                ]

              })
          }
        );

      const text =
        await response.text();

      let data;

      try {

        data =
          JSON.parse(text);

      } catch {

        data = {
          raw: text
        };
      }

      return res
        .status(response.status)
        .json({

          ok:
            response.ok,

          status:
            response.status,

          gemini:
            data
        });

    } catch (error) {

      console.error(
        "[GEMINI TEST ERROR]",
        error
      );

      return res.status(500).json({

        ok: false,

        error:
          error.message
      });
    }
  }
);

// ============================================================
// TOKEN EFÊMERO
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
    normalizeLanguage(
      targetLanguage
    );

  const now =
    Date.now();

  const expireTime =
    new Date(
      now +
      30 * 60 * 1000
    ).toISOString();

  const newSessionExpireTime =
    new Date(
      now +
      60 * 1000
    ).toISOString();

  const body = {

    uses: 1,

    expireTime,

    newSessionExpireTime,

    bidiGenerateContentSetup: {

      model:
        `models/${GEMINI_MODEL}`,

      generationConfig: {

        responseModalities: [
          "AUDIO"
        ],

        translationConfig: {

          targetLanguageCode:
            target,

          echoTargetLanguage:
            false
        }
      },

      inputAudioTranscription: {},

      outputAudioTranscription: {}
    }
  };

  const response =
    await fetch(
      GEMINI_TOKEN_URL,
      {

        method: "POST",

        headers: {

          "Content-Type":
            "application/json",

          "x-goog-api-key":
            GEMINI_API_KEY
        },

        body:
          JSON.stringify(body)
      }
    );

  const text =
    await response.text();

  let data;

  try {

    data =
      JSON.parse(text);

  } catch {

    data = {
      raw: text
    };
  }

  if (!response.ok) {

    throw new Error(
      `Falha criando token Gemini (${response.status}): ${
        data?.error?.message ||
        text
      }`
    );
  }

  if (!data.name) {

    throw new Error(
      "Gemini não retornou o token efêmero"
    );
  }

  return data.name;
}

// ============================================================
// INICIAR ÁUDIO
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

      const jobId =
        createId();

      const session = {

        jobId,

        clientId,

        targetLanguage,

        createdAt:
          Date.now(),

        lastActivity:
          Date.now(),

        status:
          "starting",

        geminiReady:
          false,

        geminiConnecting:
          false,

        stopped:
          false,

        chunksReceived:
          0,

        bytesReceived:
          0,

        chunksSentToGemini:
          0,

        bytesSentToGemini:
          0,

        outputChunks:
          [],

        nextOutputSeq:
          0,

        outputBytes:
          0,

        inputTranscript:
          "",

        outputTranscript:
          "",

        lastError:
          null,

        reconnectTimer:
          null,

        reconnectAttempts:
          0,

        ws:
          null,

        pendingInputChunks:
          [],

        pendingOutputBuffers:
          [],

        pendingOutputBytes:
          0,

        outputFlushTimer:
          null,

        outputTurns:
          0,

        diagnostics:
          []
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

      connectGemini(
        jobId
      ).catch(
        (error) => {

          console.error(
            `[GEMINI] Falha inicial ${jobId}:`,
            error.message
          );

          const current =
            sessions.get(
              jobId
            );

          if (current) {

            current.lastError =
              error.message;

            current.status =
              "gemini_error";
          }
        }
      );

      return res.json({

        ok: true,

        jobId,

        clientId,

        targetLanguage,

        targetLanguageName:
          LANGUAGE_NAMES[
            targetLanguage
          ] ||
          targetLanguage,

        status:
          "starting"
      });

    } catch (error) {

      console.error(
        "[AUDIO START ERROR]",
        error
      );

      return res.status(500).json({

        ok: false,

        error:
          error.message
      });
    }
  }
);
      if (current) {

        current.lastError =
          error.message;

        current.status =
          "gemini_error";
      }
    }
  );

  return res.json({

    ok: true,

    jobId,

    clientId,

    targetLanguage,

    targetLanguageName:
      LANGUAGE_NAMES[
        targetLanguage
      ] ||
      targetLanguage,

    status:
      "starting"
  });

} catch (error) {

  console.error(
    "[AUDIO START ERROR]",
    error
  );

  return res.status(500).json({

    ok: false,

    error:
      error.message
  });
}
}
);

// ============================================================
// CONECTAR GEMINI
// ============================================================

async function connectGemini(
jobId
) {

const session =
sessions.get(
  jobId
);

if (!session) {
return;
}

if (session.stopped) {
return;
}

if (
session.geminiConnecting
) {
return;
}

session.geminiConnecting =
true;

try {

console.log(
  `[GEMINI] Criando conexão para ${jobId}`
);

const token =
await createGeminiEphemeralToken(
  session.targetLanguage
);

const wsUrl =
`${GEMINI_WS_BASE}?access_token=${encodeURIComponent(token)}`;

const ws =
new WebSocket(
  wsUrl
);

session.ws =
ws;

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

  ws.send(
    JSON.stringify(
      setup
    )
  );
}
);

ws.on(
"message",
(data) => {

  try {

    let raw;

    if (
      typeof data ===
      "string"
    ) {

      raw =
        data;

    } else if (
      Buffer.isBuffer(
        data
      )
    ) {

      raw =
        data.toString(
          "utf8"
        );

    } else if (
      data instanceof
      ArrayBuffer
    ) {

      raw =
        Buffer
          .from(data)
          .toString(
            "utf8"
          );

    } else if (
      ArrayBuffer.isView(
        data
      )
    ) {

      raw =
        Buffer
          .from(
            data.buffer,
            data.byteOffset,
            data.byteLength
          )
          .toString(
            "utf8"
          );

    } else {

      raw =
        String(data);
    }

    handleGeminiMessage(
      jobId,
      raw
    );

  } catch (error) {

    console.error(
      `[GEMINI MESSAGE ERROR] ${jobId}:`,
      error.message
    );
  }
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

  session.ws =
    null;

  session.geminiReady =
    false;

  session.geminiConnecting =
    false;

  if (
    !session.stopped
  ) {

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
sessions.get(
  jobId
);

if (!session) {
return;
}

if (session.stopped) {
return;
}

if (
session.reconnectTimer
) {
return;
}

session.reconnectAttempts++;

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
  3000
);
}

// ============================================================
// PROCESSAR GEMINI
// ============================================================

function handleGeminiMessage(
jobId,
raw
) {

const session =
sessions.get(
  jobId
);

if (!session) {
return;
}

session.lastActivity =
Date.now();

let data;

try {

data =
  JSON.parse(
    raw.toString()
  );

} catch (error) {

console.error(
  "[GEMINI] JSON inválido:",
  error.message
);

return;
}

// ----------------------------------------------------------
// SETUP
// ----------------------------------------------------------

if (
data.setupComplete
) {

console.log(
  `[GEMINI] setupComplete recebido - ${jobId}`
);

session.geminiReady =
true;

session.status =
"ready";

session.lastError =
null;

// --------------------------------------------------------
// ENVIAR ÁUDIO PENDENTE
// --------------------------------------------------------

if (
session.pendingInputChunks.length >
0
) {

console.log(
  `[GEMINI] Enviando ${session.pendingInputChunks.length} chunks pendentes`
);

const pending =
  session.pendingInputChunks.splice(
    0,
    session.pendingInputChunks.length
  );

for (
  const audio
  of pending
) {

  sendAudioToGemini(
    session,
    audio
  );
}
}

return;
}

// ----------------------------------------------------------
// ERRO
// ----------------------------------------------------------

if (
data.error
) {

console.error(
"[GEMINI SERVER ERROR]",
JSON.stringify(
  data.error
)
);

session.lastError =
data.error.message ||
JSON.stringify(
  data.error
);

session.geminiReady =
false;

session.status =
"gemini_error";

return;
}

// ----------------------------------------------------------
// SERVER CONTENT
// ----------------------------------------------------------

if (
!data.serverContent
) {
return;
}

const content =
data.serverContent;

// ----------------------------------------------------------
// INPUT TRANSCRIPT
// ----------------------------------------------------------

if (
content.inputTranscription
) {

const text =
content
  .inputTranscription
  .text ||
"";

if (text) {

session.inputTranscript +=
  text;

console.log(
  `[GEMINI INPUT] ${text}`
);
}
}

// ----------------------------------------------------------
// OUTPUT TRANSCRIPT
// ----------------------------------------------------------

if (
content.outputTranscription
) {

const text =
content
  .outputTranscription
  .text ||
"";

if (text) {

session.outputTranscript +=
  text;

console.log(
  `[GEMINI OUTPUT] ${text}`
);
}
}

// ----------------------------------------------------------
// ÁUDIO GERADO
// ----------------------------------------------------------

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
audioBuffer.length >
0
) {

  adicionarAudioGeminiAoBuffer(
    session,
    audioBuffer
  );
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

// ----------------------------------------------------------
// FINAL DA FALA
// ----------------------------------------------------------

if (
content.turnComplete === true
) {

console.log(
`[GEMINI] turnComplete - liberando buffer de áudio - ${jobId}`
);

flushAudioGemini(
  session,
  "turn_complete"
);
}
}

// ============================================================
// ENVIAR ÁUDIO AO GEMINI
// ============================================================

function sendAudioToGemini(
session,
audio
) {

if (
!session.ws ||
session.ws.readyState !==
WebSocket.OPEN
) {

console.warn(
  `[GEMINI] WebSocket não está aberto - ${session.jobId}`
);

return false;
}

const message = {

realtimeInput: {

  audio: {

    mimeType:
      "audio/pcm;rate=16000",

    data:
      audio
  }
}
};

try {

session.ws.send(
JSON.stringify(
  message
)
);

session.chunksSentToGemini++;

session.bytesSentToGemini +=
Buffer.from(
  audio,
  "base64"
).length;

session.lastActivity =
Date.now();

console.log(
`[GEMINI] Áudio enviado: ${Buffer.from(audio, "base64").length} bytes`
);

return true;

} catch (error) {

session.lastError =
error.message;

console.error(
"[GEMINI SEND ERROR]",
error.message
);

return false;
}
}

// ============================================================
// RECEBER ÁUDIO DO ANDROID
// VERSÃO ROBUSTA
// ============================================================

app.post(
"/api/audio/chunk",
(req, res) => {

try {

console.log(
  "[AUDIO CHUNK] Requisição recebida"
);

console.log(
  "[AUDIO CHUNK] Content-Type:",
  req.headers["content-type"]
);

// ------------------------------------------------------
// ACEITAR DIFERENTES NOMES DE JOB ID
// ------------------------------------------------------

const jobId =
req.body?.jobId ||
req.body?.jobID ||
req.body?.id ||
"";

// ------------------------------------------------------
// ACEITAR DIFERENTES NOMES PARA O ÁUDIO
// ------------------------------------------------------

const audio =
req.body?.audio ||
req.body?.audioBase64 ||
req.body?.data ||
"";

if (!jobId) {

return res.status(400).json({

  ok: false,

  error:
    "jobId não informado"
});
}

if (!audio) {

return res.status(400).json({

  ok: false,

  error:
    "Áudio não informado"
});
}

const session =
sessions.get(
jobId
);

if (!session) {

return res.status(404).json({

  ok: false,

  error:
    "Sessão não encontrada",

  jobId
});
}

if (
session.stopped
) {

return res.status(410).json({

  ok: false,

  error:
    "Sessão já foi encerrada",

  jobId
});
}

session.chunksReceived++;

session.bytesReceived +=
Buffer.from(
audio,
"base64"
).length;

session.lastActivity =
Date.now();

// ------------------------------------------------------
// SE GEMINI ESTÁ PRONTO, ENVIA IMEDIATAMENTE
// ------------------------------------------------------

if (
session.geminiReady &&
session.ws &&
session.ws.readyState ===
WebSocket.OPEN
) {

const sent =
sendAudioToGemini(
  session,
  audio
);

if (!sent) {

session.pendingInputChunks.push(
  audio
);

}

} else {

// ----------------------------------------------------
// GEMINI AINDA NÃO ESTÁ PRONTO
// GUARDAR TEMPORARIAMENTE
// ----------------------------------------------------

session.pendingInputChunks.push(
audio
);

if (
session.pendingInputChunks.length >
200
) {

session.pendingInputChunks =
session.pendingInputChunks.slice(
  -200
);
}
}

return res.json({

ok: true,

jobId,

received: true,

chunksReceived:
session.chunksReceived,

bytesReceived:
session.bytesReceived,

geminiReady:
session.geminiReady,

chunksSentToGemini:
session.chunksSentToGemini
});

} catch (error) {

console.error(
"[AUDIO CHUNK ERROR]",
error
);

return res.status(500).json({

ok: false,

error:
error.message
});
}
}
);
// ============================================================
// ÁUDIO DE SAÍDA - CURSOR CONTÍNUO
// ============================================================

app.get("/api/audio/output/:jobId", (req, res) => {

  try {

    const jobId = req.params.jobId;

    const session = sessions.get(jobId);

    if (!session) {
      return res.status(404).json({
        ok: false,
        error: "Sessão não encontrada",
        jobId
      });
    }

    let after = Number(req.query.after || 0);

    if (!Number.isFinite(after) || after < 0) {
      after = 0;
    }

    let limit = Number(req.query.limit || 20);

    if (!Number.isFinite(limit) || limit <= 0) {
      limit = 20;
    }

    limit = Math.min(Math.floor(limit), 50);

    const chunks = session.outputChunks
      .filter(chunk => Number(chunk.seq) > after)
      .slice(0, limit);

    const nextSeq = chunks.length > 0
      ? Number(chunks[chunks.length - 1].seq)
      : after;

    let bytes = 0;

    for (const chunk of chunks) {
      try {
        bytes += Buffer.from(
          chunk.audio,
          "base64"
        ).length;
      } catch (_) {}
    }

    if (chunks.length > 0) {

      console.log(
        `[OUTPUT] ${jobId} | ` +
        `after=${after} | ` +
        `chunks=${chunks.length} | ` +
        `bytes=${bytes} | ` +
        `nextSeq=${nextSeq}`
      );

    }

    return res.json({

      ok: true,

      jobId,

      after,

      nextSeq,

      chunks,

      count: chunks.length,

      bytes,

      available:
        session.outputChunks.length,

      nextOutputSeq:
        session.nextOutputSeq

    });

  } catch (error) {

    console.error(
      "[OUTPUT ERROR]",
      error
    );

    return res.status(500).json({

      ok: false,

      error: error.message

    });

  }

});


// ============================================================
// STATUS DA SESSÃO
// ============================================================

app.get("/api/audio/status/:jobId", (req, res) => {

  try {

    const jobId = req.params.jobId;

    const session = sessions.get(jobId);

    if (!session) {

      return res.status(404).json({

        ok: false,

        error: "Sessão não encontrada",

        jobId

      });

    }

    return res.json({

      ok: true,

      jobId,

      clientId:
        session.clientId,

      targetLanguage:
        session.targetLanguage,

      status:
        session.status,

      geminiReady:
        session.geminiReady,

      geminiConnecting:
        session.geminiConnecting,

      stopped:
        session.stopped,

      chunksReceived:
        session.chunksReceived,

      bytesReceived:
        session.bytesReceived,

      chunksSentToGemini:
        session.chunksSentToGemini,

      bytesSentToGemini:
        session.bytesSentToGemini,

      pendingInputChunks:
        session.pendingInputChunks.length,

      outputChunks:
        session.outputChunks.length,

      nextOutputSeq:
        session.nextOutputSeq,

      outputBytes:
        session.outputBytes,

      outputTurns:
        session.outputTurns || 0,

      inputTranscript:
        session.inputTranscript || "",

      outputTranscript:
        session.outputTranscript || "",

      lastError:
        session.lastError || null,

      createdAt:
        session.createdAt,

      lastActivity:
        session.lastActivity

    });

  } catch (error) {

    console.error(
      "[STATUS ERROR]",
      error
    );

    return res.status(500).json({

      ok: false,

      error: error.message

    });

  }

});


// ============================================================
// DIAGNÓSTICO DA SESSÃO
// ============================================================

app.get("/api/audio/diagnostic/:jobId", (req, res) => {

  try {

    const jobId = req.params.jobId;

    const session = sessions.get(jobId);

    if (!session) {

      return res.status(404).json({

        ok: false,

        error: "Sessão não encontrada",

        jobId

      });

    }

    return res.json({

      ok: true,

      jobId,

      status:
        session.status,

      stopped:
        session.stopped,

      geminiReady:
        session.geminiReady,

      geminiConnecting:
        session.geminiConnecting,

      chunksReceived:
        session.chunksReceived,

      bytesReceived:
        session.bytesReceived,

      chunksSentToGemini:
        session.chunksSentToGemini,

      bytesSentToGemini:
        session.bytesSentToGemini,

      pendingInputChunks:
        session.pendingInputChunks.length,

      outputChunks:
        session.outputChunks.length,

      nextOutputSeq:
        session.nextOutputSeq,

      outputBytes:
        session.outputBytes,

      outputTurns:
        session.outputTurns || 0,

      inputTranscript:
        session.inputTranscript || "",

      outputTranscript:
        session.outputTranscript || "",

      lastError:
        session.lastError || null,

      diagnostics:
        session.diagnostics || []

    });

  } catch (error) {

    console.error(
      "[DIAGNOSTIC ERROR]",
      error
    );

    return res.status(500).json({

      ok: false,

      error: error.message

    });

  }

});
// ============================================================
// LISTAR SESSÕES ATIVAS
// ============================================================

app.get("/api/audio/sessions", (req, res) => {

  try {

    const lista = [];

    for (const [jobId, session] of sessions.entries()) {

      lista.push({

        jobId,

        clientId:
          session.clientId,

        targetLanguage:
          session.targetLanguage,

        status:
          session.status,

        stopped:
          session.stopped,

        geminiReady:
          session.geminiReady,

        geminiConnecting:
          session.geminiConnecting,

        chunksReceived:
          session.chunksReceived,

        bytesReceived:
          session.bytesReceived,

        chunksSentToGemini:
          session.chunksSentToGemini,

        bytesSentToGemini:
          session.bytesSentToGemini,

        pendingInputChunks:
          session.pendingInputChunks.length,

        outputChunks:
          session.outputChunks.length,

        nextOutputSeq:
          session.nextOutputSeq,

        outputBytes:
          session.outputBytes,

        outputTurns:
          session.outputTurns || 0,

        createdAt:
          session.createdAt,

        lastActivity:
          session.lastActivity,

        lastError:
          session.lastError || null

      });

    }

    return res.json({

      ok: true,

      count:
        lista.length,

      sessions:
        lista

    });

  } catch (error) {

    console.error(
      "[SESSIONS ERROR]",
      error
    );

    return res.status(500).json({

      ok: false,

      error:
        error.message

    });

  }

});


// ============================================================
// PARAR SESSÃO
// ============================================================

app.post("/api/audio/stop", (req, res) => {

  try {

    const jobId =
      req.body?.jobId ||
      req.body?.jobID ||
      req.body?.id ||
      "";

    if (!jobId) {

      return res.status(400).json({

        ok: false,

        error:
          "jobId obrigatório"

      });

    }

    const session =
      sessions.get(jobId);

    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Sessão não encontrada",

        jobId

      });

    }

    console.log(
      `[SESSION STOP] ${jobId}`
    );

    session.stopped =
      true;

    session.status =
      "stopping";

    session.lastActivity =
      Date.now();


    // --------------------------------------------------------
    // FINALIZAR BUFFER DE ÁUDIO PENDENTE
    // --------------------------------------------------------

    try {

      flushAudioGemini(
        session,
        "session_stop"
      );

    } catch (flushError) {

      console.error(
        "[SESSION STOP] Erro ao finalizar buffer:",
        flushError.message
      );

    }


    // --------------------------------------------------------
    // CANCELAR TIMER
    // --------------------------------------------------------

    if (
      session.outputFlushTimer
    ) {

      clearTimeout(
        session.outputFlushTimer
      );

      session.outputFlushTimer =
        null;

    }


    // --------------------------------------------------------
    // LIMPAR BUFFER PENDENTE
    // --------------------------------------------------------

    session.pendingOutputBuffers =
      [];

    session.pendingOutputBytes =
      0;


    // --------------------------------------------------------
    // FECHAR WEBSOCKET DO GEMINI
    // --------------------------------------------------------

    try {

      if (
        session.ws &&
        (
          session.ws.readyState ===
            WebSocket.OPEN ||
          session.ws.readyState ===
            WebSocket.CONNECTING
        )
      ) {

        session.ws.close(
          1000,
          "Sessão encerrada"
        );

      }

    } catch (wsError) {

      console.error(
        "[SESSION STOP] Erro fechando Gemini:",
        wsError.message
      );

    }


    session.ws =
      null;

    session.geminiReady =
      false;

    session.geminiConnecting =
      false;

    session.status =
      "stopped";

    session.lastActivity =
      Date.now();


    return res.json({

      ok: true,

      jobId,

      status:
        session.status

    });

  } catch (error) {

    console.error(
      "[STOP ERROR]",
      error
    );

    return res.status(500).json({

      ok: false,

      error:
        error.message

    });

  }

});
// ============================================================
// TESTE DE ÁUDIO DO GEMINI
// ============================================================

app.get("/api/audio/test/:jobId", (req, res) => {

  try {

    const jobId =
      req.params.jobId;

    const session =
      sessions.get(jobId);

    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Sessão não encontrada",

        jobId

      });

    }

    return res.json({

      ok: true,

      jobId,

      status:
        session.status,

      geminiReady:
        session.geminiReady,

      geminiConnecting:
        session.geminiConnecting,

      chunksReceived:
        session.chunksReceived,

      bytesReceived:
        session.bytesReceived,

      chunksSentToGemini:
        session.chunksSentToGemini,

      bytesSentToGemini:
        session.bytesSentToGemini,

      outputChunks:
        session.outputChunks.length,

      outputBytes:
        session.outputBytes,

      outputTurns:
        session.outputTurns || 0,

      inputTranscript:
        session.inputTranscript || "",

      outputTranscript:
        session.outputTranscript || "",

      lastError:
        session.lastError || null

    });

  } catch (error) {

    console.error(
      "[AUDIO TEST ERROR]",
      error
    );

    return res.status(500).json({

      ok: false,

      error:
        error.message

    });

  }

});


// ============================================================
// ROTA RAIZ
// ============================================================

app.get("/", (req, res) => {

  return res.json({

    ok: true,

    service:
      "SI Tradutor Live",

    message:
      "Backend do SI Tradutor Live funcionando",

    version:
      "10.0-Gemini-Live-WebSocket-Fixed",

    gemini:
      !!GEMINI_API_KEY,

    model:
      GEMINI_MODEL,

    authentication:
      "API key + ephemeral token"

  });

});


// ============================================================
// TRATAMENTO DE ERROS
// ============================================================

app.use((err, req, res, next) => {

  console.error(
    "[EXPRESS ERROR]",
    err
  );

  if (
    res.headersSent
  ) {

    return next(err);

  }

  return res.status(500).json({

    ok: false,

    error:
      err.message ||
      "Erro interno do servidor"

  });

});


// ============================================================
// LIMPEZA AUTOMÁTICA DAS SESSÕES
// ============================================================

setInterval(() => {

  try {

    const agora =
      Date.now();

    const TEMPO_MAXIMO =
      30 * 60 * 1000;

    for (
      const [
        jobId,
        session
      ]
      of sessions.entries()
    ) {

      const ultimaAtividade =
        session.lastActivity ||
        session.createdAt ||
        agora;

      if (
        agora -
        ultimaAtividade >
        TEMPO_MAXIMO
      ) {

        console.log(
          `[CLEANUP] Removendo sessão antiga: ${jobId}`
        );

        try {

          if (
            session.outputFlushTimer
          ) {

            clearTimeout(
              session.outputFlushTimer
            );

            session.outputFlushTimer =
              null;

          }

          if (
            session.ws &&
            session.ws.readyState ===
              WebSocket.OPEN
          ) {

            session.ws.close(
              1000,
              "Sessão expirada"
            );

          }

        } catch (_) {}

        sessions.delete(
          jobId
        );

      }

    }

  } catch (error) {

    console.error(
      "[CLEANUP ERROR]",
      error
    );

  }

}, 5 * 60 * 1000);


// ============================================================
// INICIAR SERVIDOR
// ============================================================

const PORT =
  Number(
    process.env.PORT ||
    10000
  );

app.listen(
  PORT,
  "0.0.0.0",
  () => {

    console.log(
      "============================================================"
    );

    console.log(
      `SI Tradutor Live iniciado na porta ${PORT}`
    );

    console.log(
      `Gemini configurado: ${!!GEMINI_API_KEY}`
    );

    console.log(
      `Modelo Gemini: ${GEMINI_MODEL}`
    );

    console.log(
      "Servidor pronto."
    );

    console.log(
      "============================================================"
    );

  }
);
