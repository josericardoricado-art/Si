// ============================================================
// SI TRADUTOR LIVE
// Backend Node.js + Gemini Live Translation
// ============================================================

const express = require("express");
const cors = require("cors");
const multer = require("multer");
const fs = require("fs");
const path = require("path");
const WebSocket = require("ws");

const app = express();

const PORT = process.env.PORT || 10000;

const GEMINI_API_KEY = process.env.GEMINI_API_KEY || "";

const GEMINI_MODEL =
  process.env.GEMINI_MODEL ||
  "gemini-3.5-live-translate-preview";

const GEMINI_WS_URL =
  "wss://generativelanguage.googleapis.com/ws/" +
  "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";


// ============================================================
// MIDDLEWARE
// ============================================================

app.use(cors());

app.use(express.json({
  limit: "20mb"
}));

app.use(express.urlencoded({
  extended: true,
  limit: "20mb"
}));


// ============================================================
// DIRETÓRIOS
// ============================================================

const uploadsDir = path.join(__dirname, "uploads");
const outputsDir = path.join(__dirname, "outputs");

if (!fs.existsSync(uploadsDir)) {
  fs.mkdirSync(uploadsDir, { recursive: true });
}

if (!fs.existsSync(outputsDir)) {
  fs.mkdirSync(outputsDir, { recursive: true });
}


// ============================================================
// UPLOAD
// ============================================================

const upload = multer({
  dest: uploadsDir,
  limits: {
    fileSize: 2 * 1024 * 1024 * 1024
  }
});


// ============================================================
// IDIOMAS
// ============================================================

const LANGUAGES = {
  pt: "pt-BR",
  en: "en-US",
  es: "es-ES",
  fr: "fr-FR",
  de: "de-DE",
  it: "it-IT",
  ja: "ja-JP",
  ko: "ko-KR",
  zh: "zh-Hans",
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


// ============================================================
// SESSÕES DE ÁUDIO
// ============================================================

const audioSessions = new Map();


// ============================================================
// CRIAR JOB ID
// ============================================================

function createJobId() {
  return (
    Date.now() +
    "-" +
    Math.random().toString(36).substring(2, 12)
  );
}


// ============================================================
// PEGAR IDIOMA
// ============================================================

function getTargetLanguage(lang) {
  return LANGUAGES[lang] || "pt-BR";
}


// ============================================================
// RESPOSTA DE ERRO
// ============================================================

function sendError(res, status, message) {
  return res.status(status).json({
    ok: false,
    error: message
  });
}


// ============================================================
// HEALTH
// ============================================================

app.get("/", (req, res) => {
  res.json({
    ok: true,
    service: "SI Tradutor Live",
    message: "Backend do SI Tradutor Live funcionando",
    gemini: !!GEMINI_API_KEY,
    geminiModel: GEMINI_MODEL,
    time: new Date().toISOString()
  });
});


app.get("/api/health", (req, res) => {
  res.json({
    ok: true,
    service: "SI Tradutor Live",
    message: "Servidor online",
    audioCapture: true,
    gemini: !!GEMINI_API_KEY,
    geminiModel: GEMINI_MODEL,
    time: new Date().toISOString()
  });
});


// ============================================================
// CRIAR SESSÃO DE ÁUDIO
// ============================================================

app.post("/api/audio/start", (req, res) => {

  try {

    if (!GEMINI_API_KEY) {
      return sendError(
        res,
        500,
        "GEMINI_API_KEY não configurada no Render"
      );
    }

    const clientId =
      req.body?.clientId ||
      "android-client";

    const targetLang =
      req.body?.targetLang ||
      "pt";

    const targetLanguage =
      getTargetLanguage(targetLang);

    const jobId = createJobId();

    const session = {

      jobId,

      clientId,

      targetLang,

      targetLanguage,

      status: "starting",

      gemini: true,

      geminiConnected: false,

      geminiReady: false,

      geminiSocket: null,

      chunks: 0,

      bytesReceived: 0,

      outputQueue: [],

      outputBytes: 0,

      lastTranscript: "",

      lastAgentResponse: "",

      error: null,

      createdAt: new Date().toISOString(),

      lastAudioAt: null,

      diagnostic: {

        recording: false,

        readCount: 0,

        lastRead: 0,

        capturedBytes: 0,

        error: null,

        updatedAt: null

      }

    };

    audioSessions.set(jobId, session);


    console.log(
      `[AUDIO START] job=${jobId} lang=${targetLanguage}`
    );


    res.json({
      ok: true,
      jobId,
      targetLang,
      targetLanguage,
      status: "starting"
    });


    // Conecta ao Gemini depois de responder ao Android.
    setImmediate(() => {

      connectGemini(session)
        .catch((err) => {

          console.error(
            "[GEMINI CONNECT ERROR]",
            err
          );

          session.error = err.message;
          session.status = "error";

        });

    });

  } catch (err) {

    console.error(
      "[AUDIO START ERROR]",
      err
    );

    return sendError(
      res,
      500,
      err.message
    );
  }

});


// ============================================================
// CONECTAR AO GEMINI LIVE
// ============================================================

async function connectGemini(session) {

  return new Promise((resolve, reject) => {

    const url =
      GEMINI_WS_URL +
      "?key=" +
      encodeURIComponent(GEMINI_API_KEY);


    console.log(
      `[GEMINI] Conectando job=${session.jobId}`
    );


    const ws = new WebSocket(url);

    session.geminiSocket = ws;


    const timeout = setTimeout(() => {

      if (!session.geminiReady) {

        console.error(
          `[GEMINI] Timeout de conexão job=${session.jobId}`
        );

        session.error =
          "Timeout ao conectar ao Gemini";

        try {
          ws.close();
        } catch (_) {}

        reject(
          new Error(
            "Timeout ao conectar ao Gemini"
          )
        );

      }

    }, 20000);


    ws.on("open", () => {

      console.log(
        `[GEMINI] WebSocket aberto job=${session.jobId}`
      );


      const setupMessage = {

        setup: {

          model:
            `models/${GEMINI_MODEL}`,

          generationConfig: {

            responseModalities: [
              "AUDIO"
            ],

            inputAudioTranscription: {},

            outputAudioTranscription: {},

            translationConfig: {

              targetLanguageCode:
                session.targetLanguage,

              echoTargetLanguage: false

            }

          }

        }

      };


      try {

        ws.send(
          JSON.stringify(setupMessage)
        );

      } catch (err) {

        clearTimeout(timeout);

        session.error = err.message;

        reject(err);

      }

    });


    ws.on("message", (data) => {

      try {

        const text =
          Buffer.isBuffer(data)
            ? data.toString("utf8")
            : String(data);


        const message =
          JSON.parse(text);


        // ====================================================
        // SETUP COMPLETO
        // ====================================================

        if (message.setupComplete) {

          clearTimeout(timeout);

          session.geminiConnected = true;
          session.geminiReady = true;
          session.status = "running";
          session.error = null;


          console.log(
            `[GEMINI] Pronto job=${session.jobId}`
          );


          resolve();

          return;
        }


        // ====================================================
        // ERRO DO GEMINI
        // ====================================================

        if (message.error) {

          const errorMessage =
            message.error.message ||
            "Erro retornado pelo Gemini";

          console.error(
            `[GEMINI ERROR] ${errorMessage}`
          );

          session.error = errorMessage;
          session.status = "error";

          return;
        }


        // ====================================================
        // CONTEÚDO DO GEMINI
        // ====================================================

        const serverContent =
          message.serverContent;

        if (!serverContent) {
          return;
        }


        // ====================================================
        // TRANSCRIÇÃO DO ÁUDIO DE ENTRADA
        // ====================================================

        if (
          serverContent.inputTranscription &&
          serverContent.inputTranscription.text
        ) {

          session.lastTranscript =
            serverContent.inputTranscription.text;

          console.log(
            `[TRANSCRIPT] ${session.lastTranscript}`
          );

        }


        // ====================================================
        // TRANSCRIÇÃO DA SAÍDA
        // ====================================================

        if (
          serverContent.outputTranscription &&
          serverContent.outputTranscription.text
        ) {

          session.lastAgentResponse =
            serverContent.outputTranscription.text;

          console.log(
            `[OUTPUT] ${session.lastAgentResponse}`
          );

        }


        // ====================================================
        // ÁUDIO GERADO
        // ====================================================

        const modelTurn =
          serverContent.modelTurn;

        if (
          modelTurn &&
          Array.isArray(modelTurn.parts)
        ) {

          for (
            const part of modelTurn.parts
          ) {

            if (
              part.inlineData &&
              part.inlineData.data
            ) {

              const audioBase64 =
                part.inlineData.data;


              const audioBuffer =
                Buffer.from(
                  audioBase64,
                  "base64"
                );


              if (audioBuffer.length > 0) {

                session.outputQueue.push(
                  audioBase64
                );

                session.outputBytes +=
                  audioBuffer.length;


                console.log(
                  `[GEMINI AUDIO] job=${session.jobId} ` +
                  `bytes=${audioBuffer.length} ` +
                  `queue=${session.outputQueue.length}`
                );

              }

            }

          }

        }

      } catch (err) {

        console.error(
          "[GEMINI MESSAGE ERROR]",
          err
        );

        session.error = err.message;

      }

    });


    ws.on("close", (code, reason) => {

      clearTimeout(timeout);


      session.geminiConnected = false;
      session.geminiReady = false;


      const reasonText =
        reason
          ? reason.toString()
          : "";


      console.log(
        `[GEMINI CLOSE] job=${session.jobId} ` +
        `code=${code} reason=${reasonText}`
      );


      if (
        session.status !== "stopped" &&
        session.status !== "error"
      ) {

        session.status = "disconnected";

      }

    });


    ws.on("error", (err) => {

      console.error(
        `[GEMINI SOCKET ERROR] job=${session.jobId}`,
        err.message
      );


      session.error =
        err.message;

      session.geminiConnected =
        false;

      session.geminiReady =
        false;


      if (
        session.status !== "stopped"
      ) {

        session.status = "error";

      }


      clearTimeout(timeout);

      reject(err);

    });

  });

}


// ============================================================
// RECEBER CHUNK DE ÁUDIO DO ANDROID
// ============================================================

app.post(
  "/api/audio/chunk",
  express.json({
    limit: "10mb"
  }),
  (req, res) => {

    try {

      const {
        jobId,
        audio
      } = req.body || {};


      if (!jobId) {

        return sendError(
          res,
          400,
          "jobId não informado"
        );

      }


      const session =
        audioSessions.get(jobId);


      if (!session) {

        return sendError(
          res,
          404,
          "Sessão não encontrada"
        );

      }


      if (!audio) {

        return sendError(
          res,
          400,
          "Áudio não informado"
        );

      }


      const audioBuffer =
        Buffer.from(
          audio,
          "base64"
        );


      if (
        !audioBuffer ||
        audioBuffer.length === 0
      ) {

        return sendError(
          res,
          400,
          "Chunk de áudio vazio"
        );

      }


      session.chunks += 1;

      session.bytesReceived +=
        audioBuffer.length;

      session.lastAudioAt =
        new Date().toISOString();


      // ======================================================
      // ENVIAR PARA GEMINI
      // ======================================================

      if (
        session.geminiSocket &&
        session.geminiReady &&
        session.geminiSocket.readyState ===
          WebSocket.OPEN
      ) {

        const message = {

          realtimeInput: {

            audio: {

              data: audio,

              mimeType:
                "audio/pcm;rate=16000"

            }

          }

        };


        try {

          session.geminiSocket.send(
            JSON.stringify(message)
          );


          if (
            session.chunks === 1 ||
            session.chunks % 20 === 0
          ) {

            console.log(
              `[AUDIO → GEMINI] ` +
              `job=${jobId} ` +
              `chunks=${session.chunks} ` +
              `bytes=${session.bytesReceived}`
            );

          }

        } catch (err) {

          console.error(
            "[SEND GEMINI ERROR]",
            err
          );

          session.error =
            err.message;

        }

      } else {

        console.log(
          `[AUDIO] Gemini ainda não está pronto ` +
          `job=${jobId} ` +
          `ready=${session.geminiReady}`
        );

      }


      return res.json({

        ok: true,

        received: true,

        jobId,

        chunks:
          session.chunks,

        bytesReceived:
          session.bytesReceived,

        geminiReady:
          session.geminiReady

      });

    } catch (err) {

      console.error(
        "[AUDIO CHUNK ERROR]",
        err
      );

      return sendError(
        res,
        500,
        err.message
      );

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
        audioSessions.get(jobId);


      if (!session) {

        return sendError(
          res,
          404,
          "Sessão não encontrada"
        );

      }


      const audio =
        session.outputQueue.shift() ||
        null;


      res.json({

        ok: true,

        jobId,

        audio,

        available:
          !!audio,

        outputQueue:
          session.outputQueue.length,

        outputBytes:
          session.outputBytes,

        lastTranscript:
          session.lastTranscript,

        lastAgentResponse:
          session.lastAgentResponse,

        status:
          session.status,

        error:
          session.error

      });

    } catch (err) {

      console.error(
        "[OUTPUT ERROR]",
        err
      );

      return sendError(
        res,
        500,
        err.message
      );

    }

  }
);


// ============================================================
// STATUS DA SESSÃO
// ============================================================

app.get(
  "/api/audio/status/:jobId",
  (req, res) => {

    const session =
      audioSessions.get(
        req.params.jobId
      );


    if (!session) {

      return sendError(
        res,
        404,
        "Sessão não encontrada"
      );

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

      gemini:
        true,

      geminiConnected:
        session.geminiConnected,

      geminiReady:
        session.geminiReady,

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

      diagnostic:
        session.diagnostic,

      error:
        session.error

    });

  }
);


// ============================================================
// DIAGNÓSTICO DO AUDIORECORD ANDROID
// ============================================================

app.post(
  "/api/audio/diagnostic",
  express.json({
    limit: "100kb"
  }),
  (req, res) => {

    try {

      const {
        jobId,
        recording,
        readCount,
        lastRead,
        capturedBytes,
        error
      } = req.body || {};


      if (!jobId) {

        return sendError(
          res,
          400,
          "jobId não informado"
        );

      }


      const session =
        audioSessions.get(jobId);


      if (!session) {

        return sendError(
          res,
          404,
          "Sessão não encontrada"
        );

      }


      session.diagnostic = {

        recording:
          !!recording,

        readCount:
          Number(readCount || 0),

        lastRead:
          Number(lastRead || 0),

        capturedBytes:
          Number(capturedBytes || 0),

        error:
          error || null,

        updatedAt:
          new Date().toISOString()

      };


      console.log(
        `[ANDROID DIAG] ` +
        `job=${jobId} ` +
        `recording=${session.diagnostic.recording} ` +
        `reads=${session.diagnostic.readCount} ` +
        `lastRead=${session.diagnostic.lastRead} ` +
        `bytes=${session.diagnostic.capturedBytes} ` +
        `error=${session.diagnostic.error || "none"}`
      );


      return res.json({

        ok: true,

        diagnostic:
          session.diagnostic

      });

    } catch (err) {

      console.error(
        "[ANDROID DIAG ERROR]",
        err
      );


      return sendError(
        res,
        500,
        err.message
      );

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
      ).map((s) => ({

        jobId:
          s.jobId,

        clientId:
          s.clientId,

        targetLang:
          s.targetLang,

        targetLanguage:
          s.targetLanguage,

        status:
          s.status,

        gemini:
          !!s.gemini,

        geminiConnected:
          !!s.geminiConnected,

        geminiReady:
          !!s.geminiReady,

        chunks:
          s.chunks || 0,

        bytesReceived:
          s.bytesReceived || 0,

        outputQueue:
          s.outputQueue
            ? s.outputQueue.length
            : 0,

        outputBytes:
          s.outputBytes || 0,

        lastTranscript:
          s.lastTranscript || "",

        lastAgentResponse:
          s.lastAgentResponse || "",

        lastAudioAt:
          s.lastAudioAt || null,

        diagnostic:
          s.diagnostic || null,

        error:
          s.error || null,

        createdAt:
          s.createdAt

      }));


    res.json({

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

      const jobId =
        req.body?.jobId;


      if (!jobId) {

        return sendError(
          res,
          400,
          "jobId não informado"
        );

      }


      const session =
        audioSessions.get(jobId);


      if (!session) {

        return sendError(
          res,
          404,
          "Sessão não encontrada"
        );

      }


      session.status =
        "stopped";


      session.geminiReady =
        false;

      session.geminiConnected =
        false;


      if (
        session.geminiSocket
      ) {

        try {

          session.geminiSocket.close();

        } catch (_) {}

      }


      session.geminiSocket =
        null;


      console.log(
        `[AUDIO STOP] job=${jobId}`
      );


      return res.json({

        ok: true,

        jobId,

        status: "stopped"

      });

    } catch (err) {

      console.error(
        "[AUDIO STOP ERROR]",
        err
      );

      return sendError(
        res,
        500,
        err.message
      );

    }

  }
);


// ============================================================
// UPLOAD DE VÍDEO
// ============================================================

app.post(
  "/api/upload",
  upload.single("video"),
  (req, res) => {

    try {

      if (!req.file) {

        return sendError(
          res,
          400,
          "Nenhum vídeo enviado"
        );

      }


      console.log(
        `[UPLOAD] ${req.file.filename} ` +
        `${req.file.size} bytes`
      );


      return res.json({

        ok: true,

        message:
          "Vídeo recebido",

        filename:
          req.file.filename,

        originalname:
          req.file.originalname,

        size:
          req.file.size

      });

    } catch (err) {

      console.error(
        "[UPLOAD ERROR]",
        err
      );

      return sendError(
        res,
        500,
        err.message
      );

    }

  }
);


// ============================================================
// LIMPEZA AUTOMÁTICA DE SESSÕES ANTIGAS
// ============================================================

setInterval(() => {

  const now =
    Date.now();


  for (
    const [jobId, session]
    of audioSessions.entries()
  ) {

    const created =
      new Date(
        session.createdAt
      ).getTime();


    // Remove sessões com mais de 1 hora.

    if (
      now - created >
      60 * 60 * 1000
    ) {

      try {

        if (
          session.geminiSocket
        ) {

          session.geminiSocket.close();

        }

      } catch (_) {}


      audioSessions.delete(
        jobId
      );


      console.log(
        `[CLEANUP] Sessão removida ${jobId}`
      );

    }

  }

}, 10 * 60 * 1000);


// ============================================================
// INICIAR SERVIDOR
// ============================================================

app.listen(
  PORT,
  "0.0.0.0",
  () => {

    console.log(
      "=================================================="
    );

    console.log(
      "SI TRADUTOR LIVE"
    );

    console.log(
      `Servidor rodando na porta ${PORT}`
    );

    console.log(
      `Gemini: ${GEMINI_API_KEY ? "ATIVADO" : "NÃO CONFIGURADO"}`
    );

    console.log(
      `Modelo: ${GEMINI_MODEL}`
    );

    console.log(
      "Áudio em tempo real: ATIVADO"
    );

    console.log(
      "=================================================="
    );

  }
);
