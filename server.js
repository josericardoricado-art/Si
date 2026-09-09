const express = require("express");
const cors = require("cors");
const multer = require("multer");
const fs = require("fs");
const path = require("path");
const { v4: uuidv4 } = require("uuid");
const WebSocket = require("ws");

const app = express();

const PORT = process.env.PORT || 10000;

/* =========================================================
   GEMINI
========================================================= */

const GEMINI_API_KEY =
  process.env.GEMINI_API_KEY || "";

const GEMINI_MODEL =
  "gemini-3.5-live-translate-preview";

/* =========================================================
   ELEVENLABS
   Mantido apenas para compatibilidade.
========================================================= */

const ELEVENLABS_API_KEY =
  process.env.ELEVENLABS_API_KEY || "";

const ELEVENLABS_AGENT_ID =
  process.env.ELEVENLABS_AGENT_ID ||
  "agent_1601m1q929bhf2zvts65479fyzdw";

const ELEVENLABS_VOICE_ID =
  process.env.ELEVENLABS_VOICE_ID ||
  "cjVigY5qzO86Huf0OWal";

/* =========================================================
   BACKEND
========================================================= */

const BACKEND_URL =
  process.env.BACKEND_URL ||
  "https://si-u2ul.onrender.com";

const UPLOAD_DIR =
  path.join(__dirname, "uploads");

if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, {
    recursive: true,
  });
}

/* =========================================================
   EXPRESS
========================================================= */

app.use(
  cors({
    origin: "*",
    methods: ["GET", "POST", "OPTIONS"],
    allowedHeaders: [
      "Content-Type",
      "Authorization",
    ],
  })
);

app.use(
  express.json({
    limit: "20mb",
  })
);

app.use(
  express.urlencoded({
    extended: true,
  })
);

app.use(
  "/uploads",
  express.static(UPLOAD_DIR)
);

/* =========================================================
   SESSÕES DE ÁUDIO
========================================================= */

const audioSessions = new Map();

/* =========================================================
   IDIOMAS
========================================================= */

const TARGET_LANGUAGES = {
  pt: "pt-BR",
  en: "en",
  es: "es",
  fr: "fr",
  de: "de",
  it: "it",
  ja: "ja",
  ko: "ko",
  zh: "zh-Hans",
  ru: "ru",
  ar: "ar",
  hi: "hi",
  tr: "tr",
  nl: "nl",
  pl: "pl",
  uk: "uk",
  th: "th",
  id: "id",
  vi: "vi",
};

/* =========================================================
   FUNÇÕES AUXILIARES
========================================================= */

function now() {
  return Date.now();
}

function parseSampleRate(format) {
  if (!format) {
    return null;
  }

  const match =
    String(format).match(
      /(\d{4,6})/
    );

  if (!match) {
    return null;
  }

  return Number(match[1]);
}

/*
 * Resample simples de PCM16 mono.
 *
 * Exemplo:
 * 24000 -> 16000
 */
function resamplePcm16(
  buffer,
  inputRate,
  outputRate
) {
  if (
    !buffer ||
    buffer.length === 0
  ) {
    return Buffer.alloc(0);
  }

  if (
    !inputRate ||
    !outputRate ||
    inputRate === outputRate
  ) {
    return buffer;
  }

  const sampleCount =
    Math.floor(
      buffer.length / 2
    );

  if (sampleCount <= 0) {
    return Buffer.alloc(0);
  }

  const ratio =
    inputRate / outputRate;

  const outputCount =
    Math.max(
      1,
      Math.floor(
        sampleCount / ratio
      )
    );

  const output =
    Buffer.alloc(
      outputCount * 2
    );

  for (
    let i = 0;
    i < outputCount;
    i++
  ) {
    const sourceIndex =
      Math.min(
        sampleCount - 1,
        Math.floor(
          i * ratio
        )
      );

    const sample =
      buffer.readInt16LE(
        sourceIndex * 2
      );

    output.writeInt16LE(
      sample,
      i * 2
    );
  }

  return output;
}

/* =========================================================
   HEALTH
========================================================= */

app.get(
  "/api/health",
  (req, res) => {
    res.json({
      ok: true,

      service:
        "SI Tradutor Live",

      message:
        "Servidor online",

      audioCapture:
        true,

      gemini:
        Boolean(
          GEMINI_API_KEY
        ),

      geminiModel:
        GEMINI_MODEL,

      elevenlabs:
        Boolean(
          ELEVENLABS_API_KEY
        ),

      time:
        new Date().toISOString(),
    });
  }
);

/* =========================================================
   CONFIGURAÇÃO DE ÁUDIO
========================================================= */

app.get(
  "/api/audio/config",
  (req, res) => {
    res.json({
      ok: true,

      backendUrl:
        BACKEND_URL,

      audioCapture:
        true,

      gemini:
        Boolean(
          GEMINI_API_KEY
        ),

      model:
        GEMINI_MODEL,

      sampleRate:
        16000,

      outputSampleRate:
        16000,

      channels:
        1,

      format:
        "pcm_s16le",

      languages:
        TARGET_LANGUAGES,
    });
  }
);

/* =========================================================
   CONECTAR GEMINI LIVE TRANSLATION
========================================================= */

function connectGemini(session) {
  return new Promise(
    (resolve, reject) => {
      if (!GEMINI_API_KEY) {
        const error =
          "GEMINI_API_KEY não configurada no Render.";

        session.error =
          error;

        session.status =
          "error";

        reject(
          new Error(error)
        );

        return;
      }

      const targetLanguage =
        TARGET_LANGUAGES[
          session.targetLang
        ] ||
        session.targetLang ||
        "pt-BR";

      const wsUrl =
        "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1beta." +
        "GenerativeService.BidiGenerateContent" +
        "?key=" +
        encodeURIComponent(
          GEMINI_API_KEY
        );

      console.log(
        `[GEMINI] Conectando ${session.jobId}`
      );

      console.log(
        `[GEMINI] Modelo: ${GEMINI_MODEL}`
      );

      console.log(
        `[GEMINI] Idioma destino: ${targetLanguage}`
      );

      const ws =
        new WebSocket(
          wsUrl
        );

      session.ws =
        ws;

      session.gemini =
        false;

      let opened =
        false;

      let settled =
        false;

      const timeout =
        setTimeout(
          () => {
            if (!opened) {
              try {
                ws.close();
              } catch {}

              const error =
                "Timeout conectando ao Gemini Live.";

              session.error =
                error;

              session.status =
                "error";

              if (!settled) {
                settled =
                  true;

                reject(
                  new Error(
                    error
                  )
                );
              }
            }
          },
          20000
        );

      /* =====================================================
         OPEN
      ===================================================== */

      ws.on(
        "open",
        () => {
          opened =
            true;

          clearTimeout(
            timeout
          );

          console.log(
            `[GEMINI] WebSocket conectado: ${session.jobId}`
          );

          /*
           * Configuração oficial do Live Translation.
           *
           * O modelo é especializado em tradução
           * voz-para-voz.
           */

          const setupMessage = {
            setup: {
              model:
                `models/${GEMINI_MODEL}`,

              generationConfig: {
                responseModalities: [
                  "AUDIO",
                ],

                inputAudioTranscription:
                  {},

                outputAudioTranscription:
                  {},

                translationConfig: {
                  targetLanguageCode:
                    targetLanguage,

                  /*
                   * false =
                   * se o áudio já estiver no idioma
                   * escolhido, não repete o áudio.
                   */
                  echoTargetLanguage:
                    false,
                },
              },
            },
          };

          try {
            ws.send(
              JSON.stringify(
                setupMessage
              )
            );

            console.log(
              `[GEMINI] Setup enviado: ${session.jobId}`
            );
          } catch (error) {
            session.error =
              "Erro enviando setup Gemini: " +
              error.message;

            session.status =
              "error";

            if (!settled) {
              settled =
                true;

              reject(error);
            }
          }
        }
      );

      /* =====================================================
         MESSAGE
      ===================================================== */

      ws.on(
        "message",
        (data) => {
          try {
            const message =
              JSON.parse(
                data.toString()
              );

            /* ===============================================
               SETUP COMPLETO
            =============================================== */

            if (
              message.setupComplete
            ) {
              session.gemini =
                true;

              session.status =
                "active";

              console.log(
                `[GEMINI] Setup completo: ${session.jobId}`
              );

              if (!settled) {
                settled =
                  true;

                resolve();
              }

              return;
            }

            /* ===============================================
               ERRO
            =============================================== */

            if (
              message.error
            ) {
              const errorText =
                message.error.message ||
                message.error.status ||
                JSON.stringify(
                  message.error
                );

              session.error =
                `Gemini: ${errorText}`;

              console.error(
                `[GEMINI ERROR] ${session.jobId}:`,
                message.error
              );

              if (
                !opened &&
                !settled
              ) {
                settled =
                  true;

                reject(
                  new Error(
                    errorText
                  )
                );
              }

              return;
            }

            /* ===============================================
               SERVER CONTENT
            =============================================== */

            const content =
              message.serverContent;

            if (!content) {
              return;
            }

            /* ===============================================
               TRANSCRIÇÃO DO ÁUDIO ORIGINAL
            =============================================== */

            if (
              content.inputTranscription
            ) {
              const text =
                content
                  .inputTranscription
                  .text ||
                "";

              if (text) {
                session.lastTranscript =
                  text;

                console.log(
                  `[GEMINI INPUT] ${session.jobId}: ${text}`
                );
              }
            }

            /* ===============================================
               TRANSCRIÇÃO DA TRADUÇÃO
            =============================================== */

            if (
              content.outputTranscription
            ) {
              const text =
                content
                  .outputTranscription
                  .text ||
                "";

              if (text) {
                session.lastAgentResponse =
                  text;

                console.log(
                  `[GEMINI OUTPUT] ${session.jobId}: ${text}`
                );
              }
            }

            /* ===============================================
               ÁUDIO TRADUZIDO
            =============================================== */

            if (
              content.modelTurn &&
              Array.isArray(
                content.modelTurn.parts
              )
            ) {
              for (
                const part of
                  content
                    .modelTurn
                    .parts
              ) {
                if (
                  !part.inlineData ||
                  !part.inlineData.data
                ) {
                  continue;
                }

                let audioBuffer;

                try {
                  audioBuffer =
                    Buffer.from(
                      part
                        .inlineData
                        .data,
                      "base64"
                    );
                } catch (
                  error
                ) {
                  console.error(
                    "[GEMINI AUDIO DECODE]",
                    error.message
                  );

                  continue;
                }

                if (
                  !audioBuffer.length
                ) {
                  continue;
                }

                /*
                 * Gemini Live entrega áudio
                 * PCM16 mono 24 kHz.
                 *
                 * Android está esperando
                 * PCM16 mono 16 kHz.
                 */

                audioBuffer =
                  resamplePcm16(
                    audioBuffer,
                    24000,
                    16000
                  );

                if (
                  !audioBuffer.length
                ) {
                  continue;
                }

                /*
                 * Limite de segurança da fila.
                 *
                 * Evita memória crescer
                 * indefinidamente se o Android
                 * estiver temporariamente lento.
                 */

                if (
                  session
                    .outputQueue
                    .length >= 100
                ) {
                  session
                    .outputQueue
                    .shift();
                }

                session
                  .outputQueue
                  .push(
                    audioBuffer.toString(
                      "base64"
                    )
                  );

                session.outputBytes +=
                  audioBuffer.length;
              }
            }

            /* ===============================================
               GO AWAY
            =============================================== */

            if (
              content.goAway
            ) {
              console.log(
                `[GEMINI] GoAway recebido: ${session.jobId}`,
                content.goAway
              );
            }

            /* ===============================================
               TURN COMPLETE
            =============================================== */

            if (
              content.turnComplete
            ) {
              session.lastTurnAt =
                now();
            }
          } catch (
            error
          ) {
            console.error(
              `[GEMINI MESSAGE ERROR] ${session.jobId}:`,
              error.message
            );

            session.error =
              "Erro processando resposta Gemini: " +
              error.message;
          }
        }
      );

      /* =====================================================
         ERROR
      ===================================================== */

      ws.on(
        "error",
        (error) => {
          console.error(
            `[GEMINI WS ERROR] ${session.jobId}:`,
            error.message
          );

          session.gemini =
            false;

          session.error =
            "WebSocket Gemini: " +
            error.message;

          if (
            !opened &&
            !settled
          ) {
            settled =
              true;

            clearTimeout(
              timeout
            );

            reject(error);
          }
        }
      );

      /* =====================================================
         CLOSE
      ===================================================== */

      ws.on(
        "close",
        (
          code,
          reasonBuffer
        ) => {
          const reason =
            reasonBuffer
              ? reasonBuffer.toString()
              : "";

          console.log(
            `[GEMINI] WebSocket fechado ${session.jobId} code=${code} reason=${reason}`
          );

          session.gemini =
            false;

          if (
            code !== 1000 &&
            session.status !==
              "stopped"
          ) {
            session.error =
              `WebSocket Gemini fechado. code=${code} reason=${reason}`;

            session.status =
              "disconnected";
          }
        }
      );
    }
  );
}

/* =========================================================
   INICIAR ÁUDIO
========================================================= */

app.post(
  "/api/audio/start",
  async (req, res) => {
    try {
      const clientId =
        req.body?.clientId ||
        uuidv4();

      const targetLang =
        req.body?.targetLang ||
        "pt";

      const jobId =
        `${Date.now()}-${uuidv4()
          .replace(/-/g, "")
          .slice(0, 16)}`;

      const session = {
        jobId,

        clientId,

        targetLang,

        status:
          "connecting",

        createdAt:
          now(),

        chunks:
          0,

        bytesReceived:
          0,

        outputQueue:
          [],

        outputBytes:
          0,

        gemini:
          false,

        /*
         * Mantido para compatibilidade
         * com versões antigas do Android.
         */
        elevenlabs:
          false,

        ws:
          null,

        sendChain:
          Promise.resolve(),

        inputSampleRate:
          16000,

        outputSampleRate:
          16000,

        conversationId:
          null,

        lastTranscript:
          "",

        lastAgentResponse:
          "",

        lastTurnAt:
          null,

        error:
          null,
      };

      audioSessions.set(
        jobId,
        session
      );

      console.log(
        `[AUDIO START] ${jobId} client=${clientId} lang=${targetLang}`
      );

      if (
        !TARGET_LANGUAGES[
          targetLang
        ]
      ) {
        console.log(
          `[GEMINI] Código de idioma não mapeado: ${targetLang}`
        );
      }

      try {
        await connectGemini(
          session
        );
      } catch (
        error
      ) {
        session.status =
          "error";

        session.error =
          error.message;

        return res.status(
          500
        ).json({
          ok: false,
          jobId,
          error:
            error.message,
        });
      }

      res.json({
        ok: true,

        jobId,

        clientId,

        targetLang,

        status:
          session.status,

        gemini:
          session.gemini,
      });
    } catch (
      error
    ) {
      console.error(
        "[AUDIO START ERROR]",
        error
      );

      res.status(
        500
      ).json({
        ok: false,
        error:
          error.message,
      });
    }
  }
);

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
        return res.status(
          400
        ).json({
          ok: false,
          error:
            "jobId obrigatório.",
        });
      }

      if (!audio) {
        return res.status(
          400
        ).json({
          ok: false,
          error:
            "audio obrigatório.",
        });
      }

      const session =
        audioSessions.get(
          jobId
        );

      if (!session) {
        return res.status(
          404
        ).json({
          ok: false,
          error:
            "Sessão não encontrada.",
        });
      }

      if (
        !session.ws ||
        session.ws.readyState !==
          WebSocket.OPEN
      ) {
        return res.status(
          409
        ).json({
          ok: false,

          error:
            "WebSocket Gemini não está conectado.",

          gemini:
            session.gemini,

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
        return res.status(
          400
        ).json({
          ok: false,
          error:
            "Áudio Base64 inválido.",
        });
      }

      if (
        !audioBuffer.length
      ) {
        return res.json({
          ok: true,
          ignored: true,
        });
      }

      session.chunks +=
        1;

      session.bytesReceived +=
        audioBuffer.length;

      /*
       * O Android envia PCM16 16 kHz.
       *
       * O Gemini Live Translation aceita
       * exatamente esse formato.
       */

      const base64Audio =
        audioBuffer.toString(
          "base64"
        );

      /*
       * Envio sequencial.
       */

      session.sendChain =
        session.sendChain
          .then(
            () => {
              return new Promise(
                (
                  resolve,
                  reject
                ) => {
                  if (
                    !session.ws ||
                    session.ws.readyState !==
                      WebSocket.OPEN
                  ) {
                    reject(
                      new Error(
                        "WebSocket Gemini fechado."
                      )
                    );

                    return;
                  }

                  try {
                    session.ws.send(
                      JSON.stringify({
                        realtimeInput: {
                          audio: {
                            data:
                              base64Audio,

                            mimeType:
                              "audio/pcm;rate=16000",
                          },
                        },
                      }),
                      (
                        error
                      ) => {
                        if (
                          error
                        ) {
                          reject(
                            error
                          );
                        } else {
                          resolve();
                        }
                      }
                    );
                  } catch (
                    error
                  ) {
                    reject(
                      error
                    );
                  }
                }
              );
            }
          )
          .catch(
            (error) => {
              session.error =
                "Erro enviando áudio ao Gemini: " +
                error.message;

              console.error(
                `[AUDIO SEND ERROR] ${jobId}:`,
                error.message
              );
            }
          );

      res.json({
        ok: true,

        jobId,

        chunks:
          session.chunks,

        bytesReceived:
          session.bytesReceived,

        gemini:
          session.gemini,
      });
    } catch (
      error
    ) {
      console.error(
        "[AUDIO CHUNK ERROR]",
        error
      );

      res.status(
        500
      ).json({
        ok: false,
        error:
          error.message,
      });
    }
  }
);

/* =========================================================
   ÁUDIO DE SAÍDA
========================================================= */

app.get(
  "/api/audio/output/:jobId",
  (req, res) => {
    try {
      const jobId =
        req.params.jobId;

      const session =
        audioSessions.get(
          jobId
        );

      if (!session) {
        return res.status(
          404
        ).json({
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
          session.outputQueue
            .length,

        outputBytes:
          session.outputBytes,

        gemini:
          session.gemini,

        status:
          session.status,

        error:
          session.error,
      });
    } catch (
      error
    ) {
      res.status(
        500
      ).json({
        ok: false,
        error:
          error.message,
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
      return res.status(
        404
      ).json({
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
        session.outputQueue
          .length,

      outputBytes:
        session.outputBytes,

      gemini:
        session.gemini,

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
      ).map(
        (session) => ({
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
            session.outputQueue
              .length,

          outputBytes:
            session.outputBytes,

          gemini:
            session.gemini,

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
        })
      );

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
        return res.status(
          400
        ).json({
          ok: false,
          error:
            "jobId obrigatório.",
        });
      }

      const session =
        audioSessions.get(
          jobId
        );

      if (!session) {
        return res.status(
          404
        ).json({
          ok: false,
          error:
            "Sessão não encontrada.",
        });
      }

      session.status =
        "stopped";

      session.gemini =
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

        status:
          "stopped",
      });

      setTimeout(
        () => {
          audioSessions.delete(
            jobId
          );
        },
        30000
      );
    } catch (
      error
    ) {
      res.status(
        500
      ).json({
        ok: false,
        error:
          error.message,
      });
    }
  }
);

/* =========================================================
   UPLOAD DE VÍDEO
========================================================= */

const storage =
  multer.diskStorage({
    destination:
      (
        req,
        file,
        cb
      ) => {
        cb(
          null,
          UPLOAD_DIR
        );
      },

    filename:
      (
        req,
        file,
        cb
      ) => {
        const extension =
          path.extname(
            file.originalname
          ) ||
          ".mp4";

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
        500 *
        1024 *
        1024,
    },
  });

/* =========================================================
   TEST UPLOAD
========================================================= */

app.post(
  "/api/test-upload",
  upload.single(
    "video"
  ),
  (req, res) => {
    try {
      if (!req.file) {
        return res.status(
          400
        ).json({
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
    } catch (
      error
    ) {
      console.error(
        "[UPLOAD ERROR]",
        error
      );

      res.status(
        500
      ).json({
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
        return res.status(
          400
        ).json({
          ok: false,
          error:
            "URL obrigatória.",
        });
      }

      res.json({
        ok: true,

        message:
          "Solicitação recebida.",

        url,

        targetLang,

        status:
          "pending",
      });
    } catch (
      error
    ) {
      res.status(
        500
      ).json({
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
        return res.status(
          400
        ).json({
          ok: false,
          error:
            "clientId obrigatório.",
        });
      }

      if (!message) {
        return res.status(
          400
        ).json({
          ok: false,
          error:
            "message obrigatório.",
        });
      }

      if (
        !chatMessages.has(
          clientId
        )
      ) {
        chatMessages.set(
          clientId,
          []
        );
      }

      chatMessages
        .get(clientId)
        .push({
          id:
            uuidv4(),

          clientId,

          message,

          createdAt:
            new Date().toISOString(),
        });

      res.json({
        ok: true,
      });
    } catch (
      error
    ) {
      res.status(
        500
      ).json({
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
    const allMessages =
      [];

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

setInterval(
  () => {
    const expiration =
      30 *
      60 *
      1000;

    const cutoff =
      now() -
      expiration;

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
          if (
            session.ws
          ) {
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
  },
  5 * 60 * 1000
);

/* =========================================================
   404
========================================================= */

app.use(
  (req, res) => {
    res.status(
      404
    ).json({
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
      error.status ||
        500
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
      `Gemini: ${
        GEMINI_API_KEY
          ? "CONFIGURADO"
          : "NÃO CONFIGURADO"
      }`
    );

    console.log(
      `Gemini Model: ${GEMINI_MODEL}`
    );

    console.log(
      `ElevenLabs: ${
        ELEVENLABS_API_KEY
          ? "CONFIGURADO"
          : "NÃO CONFIGURADO"
      }`
    );

    console.log(
      "========================================"
    );
  }
);
