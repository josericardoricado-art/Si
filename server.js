const express = require("express");
const cors = require("cors");
const multer = require("multer");
const fs = require("fs");
const path = require("path");
const { v4: uuidv4 } = require("uuid");
const WebSocket = require("ws");

const app = express();

const PORT = process.env.PORT || 10000;

const ELEVENLABS_API_KEY = process.env.ELEVENLABS_API_KEY || "";
const ELEVENLABS_AGENT_ID =
  process.env.ELEVENLABS_AGENT_ID ||
  "agent_1601m1q929bhf2zvts65479fyzdw";

const ELEVENLABS_VOICE_ID =
  process.env.ELEVENLABS_VOICE_ID ||
  "cjVigY5qzO86Huf0OWal";

const BACKEND_URL =
  process.env.BACKEND_URL ||
  "https://si-u2ul.onrender.com";

const UPLOAD_DIR = path.join(__dirname, "uploads");

if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}

app.use(
  cors({
    origin: "*",
    methods: ["GET", "POST", "OPTIONS"],
    allowedHeaders: ["Content-Type", "Authorization"],
  })
);

app.use(express.json({ limit: "20mb" }));
app.use(express.urlencoded({ extended: true }));

app.use("/uploads", express.static(UPLOAD_DIR));

/* =========================================================
   SESSÕES DE ÁUDIO
========================================================= */

const audioSessions = new Map();

/* =========================================================
   FUNÇÕES AUXILIARES
========================================================= */

function now() {
  return Date.now();
}

function parseSampleRate(format) {
  if (!format) return null;

  const match = String(format).match(/(\d{4,6})/);

  if (!match) return null;

  return Number(match[1]);
}

/*
 * Converte PCM16 mono de uma frequência para outra.
 * Exemplo:
 * 48000 -> 16000
 * 44100 -> 16000
 * 24000 -> 16000
 */
function resamplePcm16(buffer, inputRate, outputRate) {
  if (!buffer || buffer.length === 0) {
    return Buffer.alloc(0);
  }

  if (!inputRate || !outputRate || inputRate === outputRate) {
    return buffer;
  }

  const sampleCount = Math.floor(buffer.length / 2);

  if (sampleCount <= 0) {
    return Buffer.alloc(0);
  }

  const ratio = inputRate / outputRate;

  const outputCount = Math.max(
    1,
    Math.floor(sampleCount / ratio)
  );

  const output = Buffer.alloc(outputCount * 2);

  for (let i = 0; i < outputCount; i++) {
    const sourceIndex = Math.min(
      sampleCount - 1,
      Math.floor(i * ratio)
    );

    const sample = buffer.readInt16LE(sourceIndex * 2);

    output.writeInt16LE(sample, i * 2);
  }

  return output;
}

function convertOutputTo16k(buffer, inputRate) {
  return resamplePcm16(
    buffer,
    inputRate,
    16000
  );
}

/* =========================================================
   HEALTH
========================================================= */

app.get("/api/health", (req, res) => {
  res.json({
    ok: true,
    service: "LinguaLive",
    message: "Servidor online",
    audioCapture: true,
    elevenlabs: Boolean(ELEVENLABS_API_KEY),
    elevenlabsWebSocket: true,
    agentId: ELEVENLABS_AGENT_ID,
    time: new Date().toISOString(),
  });
});

/* =========================================================
   CONFIGURAÇÃO
========================================================= */

app.get("/api/audio/config", (req, res) => {
  res.json({
    ok: true,
    backendUrl: BACKEND_URL,
    audioCapture: true,
    elevenlabs: Boolean(ELEVENLABS_API_KEY),
    agentId: ELEVENLABS_AGENT_ID,
    sampleRate: 16000,
    channels: 1,
    format: "pcm_s16le",
  });
});

/* =========================================================
   ASSINAR URL ELEVENLABS
========================================================= */

async function getElevenLabsSignedUrl() {
  if (!ELEVENLABS_API_KEY) {
    throw new Error(
      "ELEVENLABS_API_KEY não configurada no Render."
    );
  }

  const url =
    "https://api.elevenlabs.io/v1/convai/conversation/get-signed-url" +
    "?agent_id=" +
    encodeURIComponent(ELEVENLABS_AGENT_ID);

  const response = await fetch(url, {
    method: "GET",
    headers: {
      "xi-api-key": ELEVENLABS_API_KEY,
    },
  });

  const text = await response.text();

  if (!response.ok) {
    throw new Error(
      `ElevenLabs signed URL ${response.status}: ${text}`
    );
  }

  let data;

  try {
    data = JSON.parse(text);
  } catch {
    throw new Error(
      "ElevenLabs retornou resposta inválida ao gerar signed URL."
    );
  }

  if (!data.signed_url) {
    throw new Error(
      "ElevenLabs não retornou signed_url."
    );
  }

  return data.signed_url;
}

/* =========================================================
   CONECTAR AO ELEVENLABS
========================================================= */

function connectElevenLabs(session) {
  return new Promise(async (resolve, reject) => {
    let signedUrl;

    try {
      signedUrl = await getElevenLabsSignedUrl();
    } catch (error) {
      session.error = error.message;
      session.status = "error";

      reject(error);
      return;
    }

    const ws = new WebSocket(signedUrl);

    session.ws = ws;
    session.elevenlabs = false;

    let opened = false;
    let settled = false;

    const connectionTimeout = setTimeout(() => {
      if (!opened) {
        try {
          ws.close();
        } catch {}

        const error =
          "Timeout conectando ao WebSocket do ElevenLabs.";

        session.error = error;
        session.status = "error";

        if (!settled) {
          settled = true;
          reject(new Error(error));
        }
      }
    }, 15000);

    ws.on("open", () => {
      opened = true;

      clearTimeout(connectionTimeout);

      console.log(
        `[ELEVENLABS] WebSocket conectado: ${session.jobId}`
      );

      /*
       * IMPORTANTE:
       *
       * NÃO estamos enviando prompt.
       *
       * O erro anterior era:
       *
       * Override for field 'prompt' is not allowed by config.
       *
       * Portanto, o Agent usa a configuração que está
       * salva diretamente no ElevenLabs.
       */

      const initiationMessage = {
        type: "conversation_initiation_client_data",
      };

      /*
       * Não fazemos override de prompt.
       *
       * Também não fazemos override de voz neste momento.
       * Assim reduzimos ao mínimo a possibilidade de
       * outro erro de segurança do Agent.
       */

      ws.send(
        JSON.stringify(initiationMessage)
      );

      if (!settled) {
        settled = true;

        session.elevenlabs = true;
        session.status = "active";

        resolve();
      }
    });

    ws.on("message", (data) => {
      try {
        const message = JSON.parse(
          data.toString()
        );

        /*
         * METADADOS DA CONVERSA
         */

        if (
          message.type ===
          "conversation_initiation_metadata"
        ) {
          const metadata =
            message.conversation_initiation_metadata_event ||
            {};

          session.conversationId =
            metadata.conversation_id || null;

          session.inputSampleRate =
            parseSampleRate(
              metadata.user_input_audio_format
            );

          session.outputSampleRate =
            parseSampleRate(
              metadata.agent_output_audio_format
            );

          console.log(
            `[ELEVENLABS] metadata ${session.jobId}`,
            {
              conversationId:
                session.conversationId,
              input:
                metadata.user_input_audio_format,
              output:
                metadata.agent_output_audio_format,
            }
          );

          return;
        }

        /*
         * ÁUDIO GERADO PELO AGENT
         */

        if (
          message.type === "audio" &&
          message.audio_event &&
          message.audio_event.audio_base_64
        ) {
          let audioBuffer =
            Buffer.from(
              message.audio_event.audio_base_64,
              "base64"
            );

          const outputRate =
            session.outputSampleRate || 16000;

          if (outputRate !== 16000) {
            audioBuffer =
              convertOutputTo16k(
                audioBuffer,
                outputRate
              );
          }

          if (audioBuffer.length > 0) {
            session.outputQueue.push(
              audioBuffer.toString("base64")
            );

            session.outputBytes +=
              audioBuffer.length;
          }

          return;
        }

        /*
         * TRANSCRIÇÃO RECEBIDA
         */

        if (
          message.type === "user_transcript"
        ) {
          const transcript =
            message.user_transcription_event
              ?.user_transcript ||
            "";

          if (transcript) {
            session.lastTranscript =
              transcript;

            console.log(
              `[ELEVENLABS] transcript ${session.jobId}: ${transcript}`
            );
          }

          return;
        }

        /*
         * RESPOSTA DO AGENT
         */

        if (
          message.type === "agent_response"
        ) {
          const response =
            message.agent_response_event
              ?.agent_response ||
            "";

          if (response) {
            session.lastAgentResponse =
              response;

            console.log(
              `[ELEVENLABS] agent response ${session.jobId}: ${response}`
            );
          }

          return;
        }

        /*
         * PING
         */

        if (
          message.type === "ping"
        ) {
          try {
            ws.send(
              JSON.stringify({
                type: "pong",
                event_id:
                  message.ping_event
                    ?.event_id,
              })
            );
          } catch {}

          return;
        }

        /*
         * ERRO DO ELEVENLABS
         */

        if (
          message.type === "error" ||
          message.type === "client_error" ||
          message.is_error === true
        ) {
          const errorText =
            message.message ||
            message.error ||
            message.reason ||
            message.type ||
            "Erro desconhecido do ElevenLabs.";

          session.error =
            String(errorText);

          console.error(
            `[ELEVENLABS ERROR] ${session.jobId}:`,
            message
          );

          return;
        }
      } catch (error) {
        console.error(
          "[ELEVENLABS MESSAGE ERROR]",
          error.message
        );
      }
    });

    ws.on("error", (error) => {
      console.error(
        `[ELEVENLABS WS ERROR] ${session.jobId}:`,
        error.message
      );

      session.error =
        "WebSocket ElevenLabs: " +
        error.message;

      session.elevenlabs = false;

      if (!opened && !settled) {
        settled = true;
        clearTimeout(connectionTimeout);
        reject(error);
      }
    });

    ws.on("close", (code, reasonBuffer) => {
      const reason =
        reasonBuffer
          ? reasonBuffer.toString()
          : "";

      console.log(
        `[ELEVENLABS] WebSocket fechado ${session.jobId} code=${code} reason=${reason}`
      );

      session.elevenlabs = false;

      if (code !== 1000) {
        session.error =
          `WebSocket ElevenLabs fechado. code=${code} reason=${reason}`;
      }

      if (
        session.status !== "stopped" &&
        session.status !== "error"
      ) {
        session.status =
          "disconnected";
      }
    });
  });
}

/* =========================================================
   INICIAR ÁUDIO
========================================================= */

app.post("/api/audio/start", async (req, res) => {
  try {
    const clientId =
      req.body?.clientId ||
      uuidv4();

    const targetLang =
      req.body?.targetLang ||
      "pt";

    const jobId =
      `${Date.now()}-${uuidv4().replace(/-/g, "").slice(0, 16)}`;

    const session = {
      jobId,
      clientId,
      targetLang,

      status: "connecting",

      createdAt: now(),

      chunks: 0,
      bytesReceived: 0,

      outputQueue: [],
      outputBytes: 0,

      elevenlabs: false,

      ws: null,

      sendChain: Promise.resolve(),

      inputSampleRate: 16000,
      outputSampleRate: 16000,

      conversationId: null,

      lastTranscript: "",
      lastAgentResponse: "",

      error: null,
    };

    audioSessions.set(
      jobId,
      session
    );

    console.log(
      `[AUDIO START] ${jobId} client=${clientId} lang=${targetLang}`
    );

    /*
     * Conecta ao ElevenLabs antes de aceitar
     * os chunks do Android.
     */

    try {
      await connectElevenLabs(session);
    } catch (error) {
      session.status = "error";
      session.error =
        error.message;

      return res.status(500).json({
        ok: false,
        jobId,
        error: error.message,
      });
    }

    res.json({
      ok: true,
      jobId,
      clientId,
      targetLang,
      status: session.status,
      elevenlabs: session.elevenlabs,
    });
  } catch (error) {
    console.error(
      "[AUDIO START ERROR]",
      error
    );

    res.status(500).json({
      ok: false,
      error: error.message,
    });
  }
});

/* =========================================================
   RECEBER CHUNK DO ANDROID
========================================================= */

app.post(
  "/api/audio/chunk",
  express.json({
    limit: "5mb",
  }),
  async (req, res) => {
    try {
      const jobId =
        req.body?.jobId;

      const audio =
        req.body?.audio;

      if (!jobId) {
        return res.status(400).json({
          ok: false,
          error: "jobId obrigatório.",
        });
      }

      if (!audio) {
        return res.status(400).json({
          ok: false,
          error: "audio obrigatório.",
        });
      }

      const session =
        audioSessions.get(jobId);

      if (!session) {
        return res.status(404).json({
          ok: false,
          error: "Sessão não encontrada.",
        });
      }

      if (
        !session.ws ||
        session.ws.readyState !== WebSocket.OPEN
      ) {
        return res.status(409).json({
          ok: false,
          error:
            "WebSocket ElevenLabs não está conectado.",
          elevenlabs:
            session.elevenlabs,
          sessionError:
            session.error,
        });
      }

      let audioBuffer;

      try {
        audioBuffer =
          Buffer.from(
            audio,
            "base64"
          );
      } catch {
        return res.status(400).json({
          ok: false,
          error:
            "Áudio Base64 inválido.",
        });
      }

      if (!audioBuffer.length) {
        return res.json({
          ok: true,
          ignored: true,
        });
      }

      session.chunks += 1;

      session.bytesReceived +=
        audioBuffer.length;

      /*
       * O Android atualmente envia 16 kHz.
       *
       * O ElevenLabs também informou anteriormente
       * pcm_16000.
       *
       * Portanto enviamos diretamente.
       */

      const base64Audio =
        audioBuffer.toString(
          "base64"
        );

      /*
       * IMPORTANTE:
       *
       * Mantemos os envios em sequência para evitar
       * que vários requests sejam enviados fora de ordem.
       */

      session.sendChain =
        session.sendChain
          .then(() => {
            return new Promise(
              (resolve, reject) => {
                if (
                  !session.ws ||
                  session.ws.readyState !==
                    WebSocket.OPEN
                ) {
                  reject(
                    new Error(
                      "WebSocket fechado."
                    )
                  );
                  return;
                }

                try {
                  session.ws.send(
                    JSON.stringify({
                      user_audio_chunk:
                        base64Audio,
                    }),
                    (error) => {
                      if (error) {
                        reject(error);
                      } else {
                        resolve();
                      }
                    }
                  );
                } catch (error) {
                  reject(error);
                }
              }
            );
          })
          .catch((error) => {
            session.error =
              "Erro enviando áudio ao ElevenLabs: " +
              error.message;

            console.error(
              `[AUDIO SEND ERROR] ${jobId}:`,
              error.message
            );
          });

      res.json({
        ok: true,
        jobId,
        chunks: session.chunks,
        bytesReceived:
          session.bytesReceived,
        elevenlabs:
          session.elevenlabs,
      });
    } catch (error) {
      console.error(
        "[AUDIO CHUNK ERROR]",
        error
      );

      res.status(500).json({
        ok: false,
        error: error.message,
      });
    }
  }
);

/* =========================================================
   PEGAR ÁUDIO DE SAÍDA
========================================================= */

app.get(
  "/api/audio/output/:jobId",
  (req, res) => {
    try {
      const jobId =
        req.params.jobId;

      const session =
        audioSessions.get(jobId);

      if (!session) {
        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada.",
        });
      }

      const audio =
        session.outputQueue.shift() ||
        null;

      res.json({
        ok: true,
        jobId,

        audio,

        hasAudio:
          Boolean(audio),

        outputQueue:
          session.outputQueue.length,

        outputBytes:
          session.outputBytes,

        elevenlabs:
          session.elevenlabs,

        status:
          session.status,

        error:
          session.error,
      });
    } catch (error) {
      res.status(500).json({
        ok: false,
        error: error.message,
      });
    }
  }
);

/* =========================================================
   STATUS
========================================================= */

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
          "Sessão não encontrada.",
      });
    }

    res.json({
      ok: true,

      jobId:
        session.jobId,

      clientId:
        session.clientId,

      targetLang:
        session.targetLang,

      status:
        session.status,

      chunks:
        session.chunks,

      bytesReceived:
        session.bytesReceived,

      outputQueue:
        session.outputQueue.length,

      outputBytes:
        session.outputBytes,

      elevenlabs:
        session.elevenlabs,

      inputSampleRate:
        session.inputSampleRate,

      outputSampleRate:
        session.outputSampleRate,

      conversationId:
        session.conversationId,

      lastTranscript:
        session.lastTranscript,

      lastAgentResponse:
        session.lastAgentResponse,

      error:
        session.error,

      createdAt:
        session.createdAt,
    });
  }
);

/* =========================================================
   LISTAR SESSÕES
========================================================= */

app.get(
  "/api/audio/sessions",
  (req, res) => {
    const sessions =
      Array.from(
        audioSessions.values()
      ).map((session) => ({
        jobId:
          session.jobId,

        clientId:
          session.clientId,

        targetLang:
          session.targetLang,

        status:
          session.status,

        chunks:
          session.chunks,

        bytesReceived:
          session.bytesReceived,

        outputQueue:
          session.outputQueue.length,

        outputBytes:
          session.outputBytes,

        elevenlabs:
          session.elevenlabs,

        inputSampleRate:
          session.inputSampleRate,

        outputSampleRate:
          session.outputSampleRate,

        conversationId:
          session.conversationId,

        lastTranscript:
          session.lastTranscript,

        lastAgentResponse:
          session.lastAgentResponse,

        error:
          session.error,

        createdAt:
          session.createdAt,
      }));

    res.json({
      ok: true,
      count:
        sessions.length,
      sessions,
    });
  }
);

/* =========================================================
   PARAR ÁUDIO
========================================================= */

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
            "jobId obrigatório.",
        });
      }

      const session =
        audioSessions.get(jobId);

      if (!session) {
        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada.",
        });
      }

      session.status =
        "stopped";

      session.elevenlabs =
        false;

      if (session.ws) {
        try {
          session.ws.close(
            1000,
            "Sessão encerrada pelo usuário"
          );
        } catch {}
      }

      res.json({
        ok: true,
        jobId,
        status: "stopped",
      });

      /*
       * Remove depois de alguns segundos para
       * permitir diagnóstico.
       */

      setTimeout(() => {
        audioSessions.delete(
          jobId
        );
      }, 30000);
    } catch (error) {
      res.status(500).json({
        ok: false,
        error: error.message,
      });
    }
  }
);

/* =========================================================
   UPLOAD DE VÍDEO
========================================================= */

const storage =
  multer.diskStorage({
    destination: (
      req,
      file,
      cb
    ) => {
      cb(
        null,
        UPLOAD_DIR
      );
    },

    filename: (
      req,
      file,
      cb
    ) => {
      const extension =
        path.extname(
          file.originalname
        ) || ".mp4";

      cb(
        null,
        `${Date.now()}-${uuidv4()}${extension}`
      );
    },
  });

const upload =
  multer({
    storage,

    limits: {
      fileSize:
        500 * 1024 * 1024,
    },
  });

/* =========================================================
   TEST UPLOAD
========================================================= */

app.post(
  "/api/test-upload",
  upload.single("video"),
  (req, res) => {
    try {
      if (!req.file) {
        return res.status(400).json({
          ok: false,
          error:
            "Nenhum vídeo enviado.",
        });
      }

      const videoUrl =
        `${BACKEND_URL}/uploads/${encodeURIComponent(
          req.file.filename
        )}`;

      res.json({
        ok: true,

        message:
          "Vídeo recebido com sucesso.",

        filename:
          req.file.filename,

        size:
          req.file.size,

        url:
          videoUrl,
      });
    } catch (error) {
      console.error(
        "[UPLOAD ERROR]",
        error
      );

      res.status(500).json({
        ok: false,
        error:
          error.message,
      });
    }
  }
);

/* =========================================================
   DUB URL
========================================================= */

app.post(
  "/api/dub-url",
  async (req, res) => {
    try {
      const url =
        req.body?.url;

      const targetLang =
        req.body?.targetLang ||
        "pt";

      if (!url) {
        return res.status(400).json({
          ok: false,
          error:
            "URL obrigatória.",
        });
      }

      /*
       * Endpoint mantido para compatibilidade
       * com o frontend.
       */

      res.json({
        ok: true,

        message:
          "Solicitação recebida.",

        url,

        targetLang,

        status:
          "pending",
      });
    } catch (error) {
      res.status(500).json({
        ok: false,
        error:
          error.message,
      });
    }
  }
);

/* =========================================================
   CHAT
========================================================= */

const chatMessages =
  new Map();

app.post(
  "/api/chat/send",
  (req, res) => {
    try {
      const clientId =
        req.body?.clientId;

      const message =
        req.body?.message;

      if (!clientId) {
        return res.status(400).json({
          ok: false,
          error:
            "clientId obrigatório.",
        });
      }

      if (!message) {
        return res.status(400).json({
          ok: false,
          error:
            "message obrigatório.",
        });
      }

      if (
        !chatMessages.has(clientId)
      ) {
        chatMessages.set(
          clientId,
          []
        );
      }

      chatMessages
        .get(clientId)
        .push({
          id: uuidv4(),
          clientId,
          message,
          createdAt:
            new Date().toISOString(),
        });

      res.json({
        ok: true,
      });
    } catch (error) {
      res.status(500).json({
        ok: false,
        error:
          error.message,
      });
    }
  }
);

app.get(
  "/api/chat/messages/:clientId",
  (req, res) => {
    const messages =
      chatMessages.get(
        req.params.clientId
      ) || [];

    res.json({
      ok: true,
      messages,
    });
  }
);

/* =========================================================
   ADMIN MESSAGES
========================================================= */

app.get(
  "/api/admin/messages",
  (req, res) => {
    const allMessages = [];

    for (
      const [
        clientId,
        messages,
      ] of chatMessages.entries()
    ) {
      for (
        const message of messages
      ) {
        allMessages.push({
          ...message,
          clientId,
        });
      }
    }

    res.json({
      ok: true,
      count:
        allMessages.length,
      messages:
        allMessages,
    });
  }
);

/* =========================================================
   LIMPEZA AUTOMÁTICA
========================================================= */

setInterval(() => {
  const expiration =
    30 * 60 * 1000;

  const cutoff =
    now() - expiration;

  for (
    const [
      jobId,
      session,
    ] of audioSessions.entries()
  ) {
    if (
      session.createdAt <
      cutoff
    ) {
      try {
        if (session.ws) {
          session.ws.close(
            1000,
            "Sessão expirada"
          );
        }
      } catch {}

      audioSessions.delete(
        jobId
      );

      console.log(
        `[CLEANUP] Sessão removida: ${jobId}`
      );
    }
  }
}, 5 * 60 * 1000);

/* =========================================================
   404
========================================================= */

app.use(
  (req, res) => {
    res.status(404).json({
      ok: false,
      error:
        "Endpoint not found",
      path:
        req.originalUrl,
    });
  }
);

/* =========================================================
   ERROS
========================================================= */

app.use(
  (
    error,
    req,
    res,
    next
  ) => {
    console.error(
      "[SERVER ERROR]",
      error
    );

    res.status(
      error.status || 500
    ).json({
      ok: false,
      error:
        error.message ||
        "Erro interno do servidor.",
    });
  }
);

/* =========================================================
   INICIAR SERVIDOR
========================================================= */

app.listen(
  PORT,
  "0.0.0.0",
  () => {
    console.log(
      "========================================"
    );

    console.log(
      "SI Tradutor Live"
    );

    console.log(
      `Servidor rodando na porta ${PORT}`
    );

    console.log(
      `Backend: ${BACKEND_URL}`
    );

    console.log(
      `ElevenLabs Agent: ${ELEVENLABS_AGENT_ID}`
    );

    console.log(
      `ElevenLabs Voice: ${ELEVENLABS_VOICE_ID}`
    );

    console.log(
      `ElevenLabs API Key: ${
        ELEVENLABS_API_KEY
          ? "CONFIGURADA"
          : "NÃO CONFIGURADA"
      }`
    );

    console.log(
      "========================================"
    );
  }
);
