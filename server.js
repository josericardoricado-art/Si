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

            session.nextOutputSeq++;

            session.outputChunks.push({

              seq:
                session.nextOutputSeq,

              audio:
                audioBase64

            });

            session.outputBytes +=
              audioBuffer.length;

            console.log(
              `[GEMINI] Áudio recebido: ${audioBuffer.length} bytes | seq=${session.nextOutputSeq}`
            );

            // ------------------------------------------------
            // MANTER NO MÁXIMO 500 CHUNKS
            // ------------------------------------------------

            if (
              session.outputChunks.length >
              500
            ) {

              session.outputChunks =
                session.outputChunks.slice(
                  -500
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

      // ------------------------------------------------------
      // DIAGNÓSTICO
      // ------------------------------------------------------

      console.log(
        "[AUDIO CHUNK] jobId:",
        jobId
          ? "OK"
          : "AUSENTE"
      );

      console.log(
        "[AUDIO CHUNK] audio:",
        audio
          ? `OK (${String(audio).length} caracteres)`
          : "AUSENTE"
      );

      // ------------------------------------------------------
      // VALIDAR JOB ID
      // ------------------------------------------------------

      if (!jobId) {

        console.error(
          "[AUDIO CHUNK] ERRO: jobId não enviado"
        );

        return res.status(400).json({

          ok: false,

          error:
            "jobId obrigatório",

          receivedFields:
            Object.keys(
              req.body || {}
            )
        });
      }

      // ------------------------------------------------------
      // VALIDAR ÁUDIO
      // ------------------------------------------------------

      if (!audio) {

        console.error(
          "[AUDIO CHUNK] ERRO: áudio não enviado"
        );

        return res.status(400).json({

          ok: false,

          error:
            "audio obrigatório",

          receivedFields:
            Object.keys(
              req.body || {}
            )
        });
      }

      // ------------------------------------------------------
      // PROCURAR SESSÃO
      // ------------------------------------------------------

      const session =
        sessions.get(
          jobId
        );

      if (!session) {

        console.error(
          `[AUDIO CHUNK] Sessão não encontrada: ${jobId}`
        );

        return res.status(404).json({

          ok: false,

          error:
            "Sessão não encontrada",

          jobId
        });
      }

      // ------------------------------------------------------
      // SESSÃO PARADA
      // ------------------------------------------------------

      if (
        session.stopped
      ) {

        console.log(
          `[AUDIO CHUNK] Sessão parada: ${jobId}`
        );

        return res.json({

          ok: false,

          stopped:
            true,

          jobId
        });
      }

      // ------------------------------------------------------
      // LIMPAR POSSÍVEL PREFIXO DATA
      // ------------------------------------------------------

      let cleanAudio =
        String(audio);

      if (
        cleanAudio.includes(",")
      ) {

        const commaIndex =
          cleanAudio.indexOf(",");

        const possiblePrefix =
          cleanAudio.substring(
            0,
            commaIndex
          );

        if (
          possiblePrefix.includes(
            "base64"
          )
        ) {

          cleanAudio =
            cleanAudio.substring(
              commaIndex + 1
            );
        }
      }

      // ------------------------------------------------------
      // DECODIFICAR BASE64
      // ------------------------------------------------------

      let audioBuffer;

      try {

        audioBuffer =
          Buffer.from(
            cleanAudio,
            "base64"
          );

      } catch (decodeError) {

        console.error(
          "[AUDIO CHUNK] Erro Base64:",
          decodeError.message
        );

        return res.status(400).json({

          ok: false,

          error:
            "Áudio Base64 inválido",

          details:
            decodeError.message
        });
      }

      // ------------------------------------------------------
      // VALIDAR ÁUDIO
      // ------------------------------------------------------

      if (
        !audioBuffer ||
        audioBuffer.length === 0
      ) {

        console.error(
          "[AUDIO CHUNK] Áudio vazio após Base64"
        );

        return res.status(400).json({

          ok: false,

          error:
            "Áudio vazio após decodificação Base64"
        });
      }

      // ------------------------------------------------------
      // REGISTRAR RECEBIMENTO
      // ------------------------------------------------------

      session.chunksReceived++;

      session.bytesReceived +=
        audioBuffer.length;

      session.lastActivity =
        Date.now();

      console.log(
        `[AUDIO CHUNK] ${jobId} | ${audioBuffer.length} bytes | chunk=${session.chunksReceived}`
      );

      // ------------------------------------------------------
      // GEMINI PRONTO
      // ------------------------------------------------------

      if (
        session.geminiReady &&
        session.ws &&
        session.ws.readyState ===
        WebSocket.OPEN
      ) {

        const enviado =
          sendAudioToGemini(
            session,
            cleanAudio
          );

        if (enviado) {

          console.log(
            `[AUDIO → GEMINI] Enviado | chunk=${session.chunksReceived} | total=${session.chunksSentToGemini}`
          );

        } else {

          console.error(
            "[AUDIO → GEMINI] Falha no envio"
          );

          if (
            session.pendingInputChunks.length <
            50
          ) {

            session.pendingInputChunks.push(
              cleanAudio
            );
          }
        }

      }

      // ------------------------------------------------------
      // GEMINI AINDA NÃO PRONTO
      // ------------------------------------------------------

      else {

        if (
          session.pendingInputChunks.length <
          50
        ) {

          session.pendingInputChunks.push(
            cleanAudio
          );

          console.log(
            `[AUDIO] Aguardando Gemini | pendentes=${session.pendingInputChunks.length}`
          );

        } else {

          console.warn(
            "[AUDIO] Fila pendente cheia; descartando chunk"
          );
        }
      }

      // ------------------------------------------------------
      // RESPOSTA
      // ------------------------------------------------------

      return res.json({

        ok: true,

        jobId,

        received:
          audioBuffer.length,

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

        error:
          error.message
      });
    }
  }
);

// ============================================================
// ÁUDIO DE SAÍDA - CURSOR
// ============================================================

app.get(
  "/api/audio/output/:jobId",
  (req, res) => {

    try {

      const jobId =
        req.params.jobId;

      const session =
        sessions.get(
          jobId
        );

      if (!session) {

        return res.status(404).json({

          ok: false,

          error:
            "Sessão não encontrada"
        });
      }

      const after =
        Number(
          req.query.after || 0
        );

      const limitRaw =
        Number(
          req.query.limit || 20
        );

      const limit =
        Math.max(
          1,
          Math.min(
            50,
            Number.isFinite(
              limitRaw
            )
              ? limitRaw
              : 20
          )
        );

      const firstSeq =
        session.outputChunks.length >
        0
          ? session.outputChunks[0].seq
          : session.nextOutputSeq + 1;

      const lastSeq =
        session.outputChunks.length >
        0
          ? session.outputChunks[
              session.outputChunks.length - 1
            ].seq
          : session.nextOutputSeq;

      const resetRequired =
        session.outputChunks.length >
        0 &&
        after <
        firstSeq - 1;

      const chunks =
        session.outputChunks
          .filter(
            (item) =>
              item.seq >
              after
          )
          .slice(
            0,
            limit
          );

      return res.json({

        ok: true,

        available:
          chunks.length >
          0,

        chunks,

        firstSeq,

        lastSeq,

        nextSeq:
          session.nextOutputSeq + 1,

        resetRequired,

        sampleRate:
          24000,

        channels:
          1,

        format:
          "pcm_s16le"
      });

    } catch (error) {

      console.error(
        "[AUDIO OUTPUT ERROR]",
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
// STATUS
// ============================================================

app.get(
  "/api/audio/status/:jobId",
  (req, res) => {

    const jobId =
      req.params.jobId;

    const session =
      sessions.get(
        jobId
      );

    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Sessão não encontrada"
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
// DIAGNÓSTICO - POST
// ============================================================

app.post(
  "/api/audio/diagnostic",
  (req, res) => {

    try {

      const jobId =
        req.body?.jobId;

      if (!jobId) {

        return res.status(400).json({

          ok: false,

          error:
            "jobId obrigatório"
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
            "Sessão não encontrada"
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
        JSON.stringify(
          req.body
        )
      );

      return res.json({

        ok: true
      });

    } catch (error) {

      return res.status(500).json({

        ok: false,

        error:
          error.message
      });
    }
  }
);

// ============================================================
// DIAGNÓSTICO - GET
// ============================================================

app.get(
  "/api/audio/diagnostic/:jobId",
  (req, res) => {

    const jobId =
      req.params.jobId;

    const session =
      sessions.get(
        jobId
      );

    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Sessão não encontrada"
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

      pendingInputChunks:
        session.pendingInputChunks.length,

      outputChunks:
        session.outputChunks.length,

      nextOutputSeq:
        session.nextOutputSeq,

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

      diagnostics:
        session.diagnostics.slice(
          -20
        )
    });
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
      const [
        jobId,
        session
      ]
      of sessions.entries()
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

        bytesReceived:
          session.bytesReceived,

        chunksSentToGemini:
          session.chunksSentToGemini,

        bytesSentToGemini:
          session.bytesSentToGemini,

        outputChunks:
          session.outputChunks.length,

        nextOutputSeq:
          session.nextOutputSeq,

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
// PARAR ÁUDIO
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

          error:
            "jobId obrigatório"
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
            "Sessão não encontrada"
        });
      }

      stopSession(
        session
      );

      return res.json({

        ok: true,

        jobId,

        status:
          "stopped"
      });

    } catch (error) {

      console.error(
        "[AUDIO STOP ERROR]",
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
// PARAR SESSÃO
// ============================================================

function stopSession(
  session
) {

  session.stopped =
    true;

  session.status =
    "stopped";

  session.geminiReady =
    false;

  session.pendingInputChunks =
    [];

  if (
    session.reconnectTimer
  ) {

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
// UPLOAD
// ============================================================

app.post(
  "/api/upload",
  upload.single("video"),
  async (req, res) => {

    try {

      if (!req.file) {

        return res.status(400).json({

          ok: false,

          error:
            "Nenhum vídeo enviado"
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

      return res.json({

        ok: true,

        jobId,

        targetLanguage,

        file:
          finalPath
      });

    } catch (error) {

      console.error(
        "[UPLOAD ERROR]",
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
// RAIZ
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

      error:
        "Rota não encontrada",

      path:
        req.path
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

    if (
      res.headersSent
    ) {

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
// LIMPEZA
// ============================================================

setInterval(
  () => {

    const now =
      Date.now();

    const SESSION_MAX_AGE =
      60 * 60 * 1000;

    for (
      const [
        jobId,
        session
      ]
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
// ERROS DO PROCESSO
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
// SERVIDOR
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
      "Áudio: CURSOR POR SEQUÊNCIA"
    );

    console.log(
      "Entrada: API ROBUSTA"
    );

    console.log(
      "Diagnóstico: GET + POST"
    );

    console.log(
      "================================================="
    );
  }
);
