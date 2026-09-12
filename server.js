const express = require("express");
const cors = require("cors");
const multer = require("multer");
const fs = require("fs");
const path = require("path");
const WebSocket = require("ws");

const app = express();

app.use(cors());
app.use(express.json({ limit: "15mb" }));
app.use(express.urlencoded({ extended: true }));

// ============================================================
// CONFIGURAÇÃO
// ============================================================

const PORT = process.env.PORT || 10000;

const GEMINI_API_KEY =
  process.env.GEMINI_API_KEY || "";

const GEMINI_MODEL =
  process.env.GEMINI_MODEL ||
  "gemini-3.5-live-translate-preview";

const GEMINI_WS_BASE =
  "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

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
// IDIOMAS
// ============================================================

const LANGUAGE_MAP = {
  "pt": "pt-BR",
  "pt-BR": "pt-BR",

  "en": "en-US",
  "en-US": "en-US",

  "es": "es-ES",
  "es-ES": "es-ES",

  "fr": "fr-FR",
  "fr-FR": "fr-FR",

  "de": "de-DE",
  "de-DE": "de-DE",

  "it": "it-IT",
  "it-IT": "it-IT",

  "ja": "ja-JP",
  "ja-JP": "ja-JP",

  "ko": "ko-KR",
  "ko-KR": "ko-KR",

  "zh": "zh-CN",
  "zh-CN": "zh-CN",

  "ru": "ru-RU",
  "ru-RU": "ru-RU",

  "ar": "ar-SA",
  "ar-SA": "ar-SA",

  "hi": "hi-IN",
  "hi-IN": "hi-IN",

  "tr": "tr-TR",
  "tr-TR": "tr-TR",

  "nl": "nl-NL",
  "nl-NL": "nl-NL",

  "pl": "pl-PL",
  "pl-PL": "pl-PL",

  "uk": "uk-UA",
  "uk-UA": "uk-UA",

  "th": "th-TH",
  "th-TH": "th-TH",

  "id": "id-ID",
  "id-ID": "id-ID",

  "vi": "vi-VN",
  "vi-VN": "vi-VN"
};

// ============================================================
// SESSÕES
// ============================================================

const audioSessions = new Map();

// ============================================================
// GERAR ID
// ============================================================

function createJobId() {
  return (
    Date.now().toString() +
    "-" +
    Math.random()
      .toString(36)
      .substring(2, 10)
  );
}

// ============================================================
// IDIOMA
// ============================================================

function normalizeLanguage(language) {

  if (!language) {
    return "pt-BR";
  }

  return (
    LANGUAGE_MAP[language] ||
    language
  );
}

// ============================================================
// ESCAPAR JSON
// ============================================================

function safeJson(value) {

  if (value === undefined) {
    return null;
  }

  try {
    return JSON.parse(
      JSON.stringify(value)
    );
  } catch {
    return String(value);
  }
}

// ============================================================
// CRIAR SESSÃO
// ============================================================

function createAudioSession({
  jobId,
  clientId,
  targetLanguage
}) {

  const session = {

    jobId,

    clientId:
      clientId || "unknown",

    targetLanguage:
      normalizeLanguage(targetLanguage),

    status: "connecting",

    createdAt:
      new Date().toISOString(),

    updatedAt:
      new Date().toISOString(),

    chunks: 0,

    bytesReceived: 0,

    outputQueue: [],

    outputBytes: 0,

    lastAudioAt: null,

    lastTranscript: "",

    lastAgentResponse: "",

    geminiConnected: false,

    geminiReady: false,

    geminiError: null,

    geminiSetupReceived: false,

    geminiMessages: 0,

    ws: null,

    inputQueue: [],

    reconnectTimer: null,

    stopped: false
  };

  audioSessions.set(
    jobId,
    session
  );

  return session;
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

      gemini:
        !!GEMINI_API_KEY,

      geminiModel:
        GEMINI_MODEL,

      sessions:
        audioSessions.size,

      time:
        new Date().toISOString()
    });
  }
);

// ============================================================
// START AUDIO
// ============================================================

app.post(
  "/api/audio/start",
  async (req, res) => {

    try {

      const {
        clientId,
        targetLang,
        targetLanguage
      } = req.body || {};

      if (!GEMINI_API_KEY) {

        return res.status(500).json({

          ok: false,

          error:
            "GEMINI_API_KEY não configurada no Render"
        });
      }

      const selectedLanguage =
        normalizeLanguage(
          targetLang ||
          targetLanguage ||
          "pt-BR"
        );

      const jobId =
        createJobId();

      const session =
        createAudioSession({
          jobId,
          clientId,
          targetLanguage:
            selectedLanguage
        });

      console.log("");
      console.log(
        "=========================================="
      );
      console.log(
        "NOVA SESSÃO DE ÁUDIO"
      );
      console.log(
        "jobId:",
        jobId
      );
      console.log(
        "clientId:",
        clientId
      );
      console.log(
        "targetLanguage:",
        selectedLanguage
      );
      console.log(
        "=========================================="
      );

      /*
       * Conectar ao Gemini.
       *
       * Não esperamos a conexão terminar para
       * responder ao Android.
       */
      connectGemini(session);

      return res.json({

        ok: true,

        jobId,

        targetLang:
          selectedLanguage,

        targetLanguage:
          selectedLanguage,

        status:
          session.status
      });

    } catch (error) {

      console.error(
        "Erro /api/audio/start:",
        error
      );

      return res.status(500).json({

        ok: false,

        error:
          error.message ||
          "Erro ao iniciar sessão"
      });
    }
  }
);

// ============================================================
// CONECTAR GEMINI LIVE
// ============================================================

function connectGemini(session) {

  if (!session) {
    return;
  }

  if (session.stopped) {
    return;
  }

  if (!GEMINI_API_KEY) {

    session.geminiError =
      "GEMINI_API_KEY ausente";

    session.status =
      "error";

    return;
  }

  /*
   * Evitar duas conexões ao mesmo tempo.
   */
  if (
    session.ws &&
    (
      session.ws.readyState ===
      WebSocket.OPEN ||

      session.ws.readyState ===
      WebSocket.CONNECTING
    )
  ) {

    return;
  }

  const wsUrl =
    GEMINI_WS_BASE +
    "?key=" +
    encodeURIComponent(
      GEMINI_API_KEY
    );

  console.log(
    `[Gemini] Conectando job=${session.jobId}`
  );

  console.log(
    `[Gemini] modelo=${GEMINI_MODEL}`
  );

  console.log(
    `[Gemini] destino=${session.targetLanguage}`
  );

  let ws;

  try {

    ws =
      new WebSocket(
        wsUrl,
        {
          handshakeTimeout: 15000,

          perMessageDeflate: false
        }
      );

  } catch (error) {

    session.geminiError =
      "Erro criando WebSocket: " +
      error.message;

    session.status =
      "error";

    console.error(
      "[Gemini] Erro criando WebSocket:",
      error
    );

    scheduleReconnect(
      session
    );

    return;
  }

  session.ws = ws;

  // ==========================================================
  // OPEN
  // ==========================================================

  ws.on(
    "open",
    () => {

      if (session.stopped) {

        try {
          ws.close();
        } catch {}

        return;
      }

      session.geminiConnected =
        true;

      session.status =
        "connected";

      session.geminiError =
        null;

      session.updatedAt =
        new Date().toISOString();

      console.log(
        `[Gemini] WebSocket ABERTO job=${session.jobId}`
      );

      /*
       * ======================================================
       * CONFIGURAÇÃO GEMINI LIVE TRANSLATE
       * ======================================================
       *
       * O modelo recebe:
       *
       * PCM16
       * mono
       * 16 kHz
       *
       * e devolve:
       *
       * PCM16
       * mono
       * 24 kHz
       *
       * A tradução é configurada pelo
       * translationConfig.
       */
      const setupMessage = {

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

              /*
               * false =
               * não repetir áudio que já está
               * no idioma de destino.
               */
              echoTargetLanguage:
                false
            }
          },

          /*
           * Transcrição do áudio de entrada.
           */
          inputAudioTranscription: {},

          /*
           * Transcrição do áudio traduzido.
           */
          outputAudioTranscription: {}
        }
      };

      console.log(
        "[Gemini] Enviando SETUP:"
      );

      console.log(
        JSON.stringify(
          setupMessage
        )
      );

      try {

        ws.send(
          JSON.stringify(
            setupMessage
          )
        );

      } catch (error) {

        session.geminiError =
          "Erro enviando setup: " +
          error.message;

        console.error(
          "[Gemini] Erro enviando setup:",
          error
        );
      }
    }
  );

  // ==========================================================
  // MESSAGE
  // ==========================================================

  ws.on(
    "message",
    (rawData) => {

      session.geminiMessages++;

      session.updatedAt =
        new Date().toISOString();

      let message;

      try {

        const text =
          Buffer.isBuffer(rawData)
            ? rawData.toString("utf8")
            : String(rawData);

        message =
          JSON.parse(text);

      } catch (error) {

        console.error(
          "[Gemini] Mensagem JSON inválida:",
          error
        );

        return;
      }

      /*
       * ======================================================
       * SETUP COMPLETE
       * ======================================================
       */
      if (
        message.setupComplete
      ) {

        session.geminiSetupReceived =
          true;

        session.geminiReady =
          true;

        session.status =
          "connected";

        session.geminiError =
          null;

        console.log(
          `[Gemini] SETUP COMPLETE job=${session.jobId}`
        );

        /*
         * Enviar os chunks que chegaram
         * enquanto o Gemini conectava.
         */
        flushInputQueue(
          session
        );

        return;
      }

      /*
       * ======================================================
       * ERRO GEMINI
       * ======================================================
       */
      if (
        message.error
      ) {

        const errorObject =
          safeJson(
            message.error
          );

        session.geminiError =
          JSON.stringify(
            errorObject
          );

        session.status =
          "error";

        console.error(
          `[Gemini] ERRO job=${session.jobId}:`,
          JSON.stringify(
            errorObject,
            null,
            2
          )
        );

        return;
      }

      /*
       * ======================================================
       * SERVER CONTENT
       * ======================================================
       */
      const serverContent =
        message.serverContent;

      if (!serverContent) {
        return;
      }

      /*
       * ======================================================
       * TRANSCRIÇÃO DA ENTRADA
       * ======================================================
       */
      if (
        serverContent.inputTranscription
      ) {

        const transcript =
          serverContent
            .inputTranscription
            .text || "";

        if (transcript) {

          session.lastTranscript +=
            transcript;

          /*
           * Não deixar crescer infinitamente.
           */
          if (
            session.lastTranscript.length >
            5000
          ) {

            session.lastTranscript =
              session.lastTranscript.slice(
                -5000
              );
          }

          console.log(
            `[Gemini] INPUT: ${transcript}`
          );
        }
      }

      /*
       * ======================================================
       * TRANSCRIÇÃO DA SAÍDA
       * ======================================================
       */
      if (
        serverContent.outputTranscription
      ) {

        const outputText =
          serverContent
            .outputTranscription
            .text || "";

        if (outputText) {

          session.lastAgentResponse +=
            outputText;

          if (
            session.lastAgentResponse.length >
            5000
          ) {

            session.lastAgentResponse =
              session.lastAgentResponse.slice(
                -5000
              );
          }

          console.log(
            `[Gemini] OUTPUT: ${outputText}`
          );
        }
      }

      /*
       * ======================================================
       * MODELTURN
       * ======================================================
       */
      if (
        serverContent.modelTurn &&
        Array.isArray(
          serverContent.modelTurn.parts
        )
      ) {

        for (
          const part
          of serverContent.modelTurn.parts
        ) {

          /*
           * ==================================================
           * ÁUDIO
           * ==================================================
           */
          if (
            part.inlineData &&
            part.inlineData.data
          ) {

            const audioBase64 =
              part.inlineData.data;

            try {

              const pcm =
                Buffer.from(
                  audioBase64,
                  "base64"
                );

              if (
                pcm.length > 0
              ) {

                session.outputQueue.push(
                  pcm
                );

                session.outputBytes +=
                  pcm.length;

                session.lastAudioAt =
                  new Date().toISOString();

                /*
                 * Limite de segurança.
                 */
                if (
                  session.outputQueue.length >
                  100
                ) {

                  session.outputQueue.shift();
                }

                console.log(
                  `[Gemini] ÁUDIO RECEBIDO ${pcm.length} bytes | fila=${session.outputQueue.length}`
                );
              }

            } catch (error) {

              console.error(
                "[Gemini] Erro decodificando áudio:",
                error
              );
            }
          }
        }
      }

      /*
       * ======================================================
       * TURN COMPLETE
       * ======================================================
       */
      if (
        serverContent.turnComplete
      ) {

        console.log(
          `[Gemini] turnComplete job=${session.jobId}`
        );
      }
    }
  );

  // ==========================================================
  // ERROR
  // ==========================================================

  ws.on(
    "error",
    (error) => {

      session.geminiConnected =
        false;

      session.geminiReady =
        false;

      session.geminiError =
        error.message ||
        "WebSocket error";

      session.status =
        "error";

      session.updatedAt =
        new Date().toISOString();

      console.error(
        `[Gemini] WebSocket ERROR job=${session.jobId}:`,
        error
      );
    }
  );

  // ==========================================================
  // CLOSE
  // ==========================================================

  ws.on(
    "close",
    (code, reasonBuffer) => {

      session.geminiConnected =
        false;

      session.geminiReady =
        false;

      session.ws =
        null;

      const reason =
        reasonBuffer
          ? reasonBuffer.toString()
          : "";

      console.log(
        `[Gemini] WebSocket FECHADO job=${session.jobId} code=${code} reason=${reason}`
      );

      if (
        !session.stopped
      ) {

        session.status =
          "reconnecting";

        session.geminiError =
          `WebSocket fechado: ${code} ${reason}`;

        /*
         * Tentar novamente.
         */
        scheduleReconnect(
          session
        );
      }
    }
  );
}

// ============================================================
// RECONEXÃO
// ============================================================

function scheduleReconnect(
  session
) {

  if (!session) {
    return;
  }

  if (session.stopped) {
    return;
  }

  if (session.reconnectTimer) {
    return;
  }

  console.log(
    `[Gemini] Reconexão programada job=${session.jobId}`
  );

  session.reconnectTimer =
    setTimeout(
      () => {

        session.reconnectTimer =
          null;

        if (
          !session.stopped
        ) {

          connectGemini(
            session
          );
        }

      },
      2000
    );
}

// ============================================================
// ENVIAR FILA PENDENTE
// ============================================================

function flushInputQueue(
  session
) {

  if (!session) {
    return;
  }

  if (
    !session.ws ||
    session.ws.readyState !==
    WebSocket.OPEN
  ) {
    return;
  }

  if (
    !session.geminiReady
  ) {
    return;
  }

  while (
    session.inputQueue.length > 0
  ) {

    const base64 =
      session.inputQueue.shift();

    sendAudioToGemini(
      session,
      base64
    );
  }
}

// ============================================================
// ENVIAR ÁUDIO PARA GEMINI
// ============================================================

function sendAudioToGemini(
  session,
  base64
) {

  if (!session) {
    return false;
  }

  const ws =
    session.ws;

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

        data:
          base64,

        mimeType:
          "audio/pcm;rate=16000"
      }
    }
  };

  try {

    ws.send(
      JSON.stringify(
        message
      )
    );

    return true;

  } catch (error) {

    session.geminiError =
      "Erro enviando áudio para Gemini: " +
      error.message;

    console.error(
      "[Gemini] Erro enviando áudio:",
      error
    );

    return false;
  }
}

// ============================================================
// RECEBER CHUNK DO ANDROID
// ============================================================

app.post(
  "/api/audio/chunk",
  (req, res) => {

    try {

      const {
        jobId,
        audio
      } = req.body || {};

      if (!jobId) {

        return res.status(400).json({

          ok: false,

          error:
            "jobId obrigatório"
        });
      }

      if (!audio) {

        return res.status(400).json({

          ok: false,

          error:
            "audio obrigatório"
        });
      }

      const session =
        audioSessions.get(
          jobId
        );

      if (!session) {

        return res.status(404).json({

          ok: false,

          error:
            "Sessão não encontrada"
        });
      }

      if (session.stopped) {

        return res.status(410).json({

          ok: false,

          error:
            "Sessão encerrada"
        });
      }

      session.chunks++;

      /*
       * Calcular bytes recebidos.
       */
      try {

        const byteLength =
          Buffer.byteLength(
            audio,
            "base64"
          );

        session.bytesReceived +=
          byteLength;

      } catch {
        /*
         * Não interromper a sessão
         * por diagnóstico de tamanho.
         */
      }

      session.lastAudioAt =
        new Date().toISOString();

      session.updatedAt =
        new Date().toISOString();

      /*
       * ======================================================
       * GEMINI PRONTO
       * ======================================================
       */
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

          /*
           * Guardar se não conseguiu enviar.
           */
          queueInputAudio(
            session,
            audio
          );
        }

      } else {

        /*
         * Gemini ainda conectando.
         */
        queueInputAudio(
          session,
          audio
        );
      }

      return res.json({

        ok: true,

        jobId,

        chunks:
          session.chunks,

        bytesReceived:
          session.bytesReceived,

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

      return res.status(500).json({

        ok: false,

        error:
          error.message
      });
    }
  }
);

// ============================================================
// FILA DE ENTRADA
// ============================================================

function queueInputAudio(
  session,
  audio
) {

  /*
   * Guardar no máximo 100 chunks
   * (~10 segundos a 100 ms/chunk).
   */
  const MAX_INPUT_QUEUE =
    100;

  session.inputQueue.push(
    audio
  );

  if (
    session.inputQueue.length >
    MAX_INPUT_QUEUE
  ) {

    session.inputQueue.shift();

    console.warn(
      `[Gemini] Fila de entrada cheia job=${session.jobId}; descartando áudio antigo`
    );
  }
}

// ============================================================
// SAÍDA DE ÁUDIO PARA ANDROID
// ============================================================

app.get(
  "/api/audio/output/:jobId",
  (req, res) => {

    const session =
      audioSessions.get(
        req.params.jobId
      );

    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Sessão não encontrada"
      });
    }

    /*
     * Se houver áudio,
     * retirar o primeiro da fila.
     */
    if (
      session.outputQueue.length > 0
    ) {

      const pcm =
        session.outputQueue.shift();

      return res.json({

        ok: true,

        audio:
          pcm.toString(
            "base64"
          ),

        sampleRate:
          24000,

        channels:
          1,

        format:
          "pcm_s16le",

        remaining:
          session.outputQueue.length
      });
    }

    /*
     * Sem áudio neste momento.
     */
    return res.json({

      ok: true,

      audio: null,

      sampleRate:
        24000,

      channels:
        1,

      format:
        "pcm_s16le",

      remaining:
        0
    });
  }
);

// ============================================================
// STATUS
// ============================================================

app.get(
  "/api/audio/status/:jobId",
  (req, res) => {

    const session =
      audioSessions.get(
        req.params.jobId
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

      status:
        session.status,

      targetLanguage:
        session.targetLanguage,

      chunks:
        session.chunks,

      bytesReceived:
        session.bytesReceived,

      geminiConnected:
        session.geminiConnected,

      geminiReady:
        session.geminiReady,

      geminiSetupReceived:
        session.geminiSetupReceived,

      geminiMessages:
        session.geminiMessages,

      outputQueue:
        session.outputQueue.length,

      outputBytes:
        session.outputBytes,

      lastTranscript:
        session.lastTranscript,

      lastAgentResponse:
        session.lastAgentResponse,

      lastAudioAt:
        session.lastAudioAt,

      geminiError:
        session.geminiError,

      updatedAt:
        session.updatedAt
    });
  }
);

// ============================================================
// DIAGNÓSTICO DO ANDROID
// ============================================================

app.post(
  "/api/audio/diagnostic",
  (req, res) => {

    try {

      const {
        jobId,
        recording,
        captureStarted,
        readCount,
        lastRead,
        capturedBytes,
        queueSize,
        error
      } = req.body || {};

      const session =
        audioSessions.get(
          jobId
        );

      if (!session) {

        return res.status(404).json({

          ok: false,

          error:
            "Sessão não encontrada"
        });
      }

      session.androidDiagnostic = {

        recording:
          !!recording,

        captureStarted:
          !!captureStarted,

        readCount:
          Number(readCount || 0),

        lastRead:
          Number(lastRead || 0),

        capturedBytes:
          Number(capturedBytes || 0),

        queueSize:
          Number(queueSize || 0),

        error:
          error || null,

        updatedAt:
          new Date().toISOString()
      };

      session.updatedAt =
        new Date().toISOString();

      return res.json({

        ok: true
      });

    } catch (error) {

      console.error(
        "Erro diagnóstico:",
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
// LISTAR SESSÕES
// ============================================================

app.get(
  "/api/audio/sessions",
  (req, res) => {

    const sessions =
      Array.from(
        audioSessions.values()
      )
      .slice(-20)
      .reverse()
      .map(
        session => ({

          jobId:
            session.jobId,

          clientId:
            session.clientId,

          targetLang:
            session.targetLanguage,

          targetLanguage:
            session.targetLanguage,

          status:
            session.status,

          gemini:
            !!GEMINI_API_KEY,

          geminiConnected:
            session.geminiConnected,

          geminiReady:
            session.geminiReady,

          geminiSetupReceived:
            session.geminiSetupReceived,

          geminiMessages:
            session.geminiMessages,

          chunks:
            session.chunks,

          bytesReceived:
            session.bytesReceived,

          outputQueue:
            session.outputQueue.length,

          outputBytes:
            session.outputBytes,

          lastTranscript:
            session.lastTranscript,

          lastAgentResponse:
            session.lastAgentResponse,

          lastAudioAt:
            session.lastAudioAt,

          diagnostic:
            session.androidDiagnostic ||
            null,

          geminiError:
            session.geminiError,

          error:
            session.geminiError,

          createdAt:
            session.createdAt
        })
      );

    return res.json({

      ok: true,

      count:
        sessions.length,

      sessions
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

      const {
        jobId
      } = req.body || {};

      if (!jobId) {

        return res.status(400).json({

          ok: false,

          error:
            "jobId obrigatório"
        });
      }

      const session =
        audioSessions.get(
          jobId
        );

      if (!session) {

        return res.status(404).json({

          ok: false,

          error:
            "Sessão não encontrada"
        });
      }

      stopAudioSession(
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
        "Erro /api/audio/stop:",
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

function stopAudioSession(
  session
) {

  if (!session) {
    return;
  }

  session.stopped =
    true;

  session.status =
    "stopped";

  session.geminiConnected =
    false;

  session.geminiReady =
    false;

  if (
    session.reconnectTimer
  ) {

    clearTimeout(
      session.reconnectTimer
    );

    session.reconnectTimer =
      null;
  }

  if (session.ws) {

    try {

      session.ws.close(
        1000,
        "Session stopped"
      );

    } catch {}

    session.ws =
      null;
  }

  session.inputQueue =
    [];

  session.updatedAt =
    new Date().toISOString();

  console.log(
    `[Gemini] Sessão parada job=${session.jobId}`
  );
}

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

app.post(
  "/api/upload",
  upload.single("video"),
  (req, res) => {

    try {

      if (!req.file) {

        return res.status(400).json({

          ok: false,

          error:
            "Nenhum vídeo enviado"
        });
      }

      console.log(
        "Vídeo recebido:",
        req.file.filename
      );

      return res.json({

        ok: true,

        message:
          "Vídeo recebido",

        file:
          req.file.filename,

        size:
          req.file.size,

        originalName:
          req.file.originalname
      });

    } catch (error) {

      console.error(
        "Erro upload:",
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
        "4.0-Gemini-Live",

      gemini:
        !!GEMINI_API_KEY,

      model:
        GEMINI_MODEL,

      endpoints: [

        "/api/health",

        "/api/audio/start",

        "/api/audio/chunk",

        "/api/audio/output/:jobId",

        "/api/audio/status/:jobId",

        "/api/audio/diagnostic",

        "/api/audio/sessions",

        "/api/audio/stop"
      ]
    });
  }
);

// ============================================================
// LIMPEZA AUTOMÁTICA
// ============================================================

setInterval(
  () => {

    const now =
      Date.now();

    for (
      const [
        jobId,
        session
      ]
      of audioSessions
    ) {

      const created =
        new Date(
          session.createdAt
        ).getTime();

      /*
       * Sessões com mais de 30 minutos
       * são removidas.
       */
      if (
        now - created >
        30 * 60 * 1000
      ) {

        console.log(
          `[Cleanup] Removendo sessão ${jobId}`
        );

        stopAudioSession(
          session
        );

        audioSessions.delete(
          jobId
        );
      }
    }

  },
  5 * 60 * 1000
);

// ============================================================
// INICIAR SERVIDOR
// ============================================================

app.listen(
  PORT,
  "0.0.0.0",
  () => {

    console.log("");
    console.log(
      "=========================================="
    );

    console.log(
      "SI TRADUTOR LIVE"
    );

    console.log(
      "Backend iniciado"
    );

    console.log(
      `Porta: ${PORT}`
    );

    console.log(
      `Gemini API Key: ${
        GEMINI_API_KEY
          ? "CONFIGURADA"
          : "AUSENTE"
      }`
    );

    console.log(
      `Gemini Model: ${GEMINI_MODEL}`
    );

    console.log(
      "Gemini Live Translate: ATIVO"
    );

    console.log(
      "=========================================="
    );

    console.log("");
  }
);
