const express = require("express");
const cors = require("cors");
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const WebSocket = require("ws");

const app = express();

const PORT = process.env.PORT || 10000;

const BACKEND_URL =
  process.env.BACKEND_URL ||
  "https://si-u2ul.onrender.com";

const GEMINI_API_KEY =
  process.env.GEMINI_API_KEY || "";

const GEMINI_MODEL =
  process.env.GEMINI_MODEL ||
  "gemini-3.5-live-translate-preview";

const ADMIN_PASSWORD =
  process.env.ADMIN_PASSWORD ||
  "troque-esta-senha";


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
  vi: "vi"
};


/* =========================================================
   CORS
========================================================= */

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
      "Authorization",
      "X-Client-Id"
    ]
  })
);


/* =========================================================
   JSON
========================================================= */

app.use(
  express.json({
    limit: "20mb"
  })
);

app.use(
  express.urlencoded({
    extended: true
  })
);


/* =========================================================
   UPLOADS
========================================================= */

const uploadFolder =
  path.join(
    __dirname,
    "uploads"
  );

if (
  !fs.existsSync(uploadFolder)
) {
  fs.mkdirSync(
    uploadFolder,
    {
      recursive: true
    }
  );
}


const storage =
  multer.diskStorage({

    destination:
      function(req, file, cb) {

        cb(
          null,
          uploadFolder
        );
      },

    filename:
      function(req, file, cb) {

        const extension =
          path.extname(
            file.originalname
          );

        const name =
          "video-" +
          Date.now() +
          "-" +
          Math.random()
            .toString(36)
            .substring(2, 9) +
          extension;

        cb(
          null,
          name
        );
      }

  });


const upload =
  multer({

    storage: storage,

    limits: {
      fileSize:
        500 * 1024 * 1024
    },

    fileFilter:
      function(req, file, cb) {

        if (
          file.mimetype &&
          file.mimetype.startsWith(
            "video/"
          )
        ) {

          cb(
            null,
            true
          );

        } else {

          cb(
            new Error(
              "Envie somente arquivos de vídeo."
            )
          );
        }
      }

  });


/* =========================================================
   SESSÕES DE ÁUDIO
========================================================= */

const audioSessions =
  new Map();


/* =========================================================
   FUNÇÕES AUXILIARES
========================================================= */

function generateId() {

  return (
    Date.now() +
    "-" +
    Math.random()
      .toString(36)
      .substring(2, 12)
  );
}


function targetLanguage(code) {

  return (
    TARGET_LANGUAGES[code] ||
    code ||
    "pt-BR"
  );
}


/* =========================================================
   RESAMPLE PCM16
   24 kHz -> 16 kHz
========================================================= */

function resamplePcm16(
  inputBuffer,
  inputRate,
  outputRate
) {

  if (
    !inputBuffer ||
    inputBuffer.length < 2
  ) {
    return Buffer.alloc(0);
  }

  if (
    inputRate === outputRate
  ) {
    return Buffer.from(inputBuffer);
  }

  const inputSamples =
    Math.floor(
      inputBuffer.length / 2
    );

  const ratio =
    inputRate / outputRate;

  const outputSamples =
    Math.floor(
      inputSamples / ratio
    );

  const outputBuffer =
    Buffer.alloc(
      outputSamples * 2
    );

  for (
    let i = 0;
    i < outputSamples;
    i++
  ) {

    const sourcePosition =
      i * ratio;

    const index =
      Math.floor(
        sourcePosition
      );

    const nextIndex =
      Math.min(
        index + 1,
        inputSamples - 1
      );

    const fraction =
      sourcePosition - index;

    const sample1 =
      inputBuffer.readInt16LE(
        index * 2
      );

    const sample2 =
      inputBuffer.readInt16LE(
        nextIndex * 2
      );

    const sample =
      Math.round(
        sample1 +
        (
          sample2 - sample1
        ) *
        fraction
      );

    outputBuffer.writeInt16LE(
      Math.max(
        -32768,
        Math.min(
          32767,
          sample
        )
      ),
      i * 2
    );
  }

  return outputBuffer;
}


/* =========================================================
   CRIAR SESSÃO
========================================================= */

function createAudioSession(
  jobId,
  clientId,
  targetLang
) {

  const session = {

    jobId,

    clientId,

    targetLang,

    targetLanguage:
      targetLanguage(
        targetLang
      ),

    createdAt:
      new Date().toISOString(),

    status:
      "starting",

    chunks: 0,

    bytesReceived: 0,

    outputQueue: [],

    outputBytes: 0,

    gemini: false,

    geminiConnected: false,

    geminiReady: false,

    geminiSocket: null,

    lastTranscript: "",

    lastAgentResponse: "",

    error: null,

    closed: false

  };


  audioSessions.set(
    jobId,
    session
  );


  return session;
}


/* =========================================================
   CONECTAR AO GEMINI
========================================================= */

function connectGemini(
  session
) {

  if (
    !GEMINI_API_KEY
  ) {

    session.error =
      "GEMINI_API_KEY não configurada.";

    session.status =
      "error";

    console.error(
      "[GEMINI] GEMINI_API_KEY ausente."
    );

    return;
  }


  const url =
    "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" +
    encodeURIComponent(
      GEMINI_API_KEY
    );


  console.log(
    "[GEMINI] Abrindo WebSocket:",
    session.jobId
  );


  let socket;

  try {

    socket =
      new WebSocket(
        url
      );

  } catch (error) {

    session.error =
      error.message ||
      "Erro ao criar WebSocket Gemini.";

    session.status =
      "error";

    console.error(
      "[GEMINI] Erro criando WebSocket:",
      error
    );

    return;
  }


  session.geminiSocket =
    socket;


  socket.on(
    "open",
    function() {

      console.log(
        "[GEMINI] WebSocket conectado:",
        session.jobId
      );

      session.geminiConnected =
        true;

      session.gemini =
        true;

      session.status =
        "connected";


      const setupMessage = {

        setup: {

          model:
            "models/" +
            GEMINI_MODEL,

          generationConfig: {

            responseModalities: [
              "AUDIO"
            ],

            inputAudioTranscription: {},

            outputAudioTranscription: {},

            translationConfig: {

              targetLanguageCode:
                session.targetLanguage,

              echoTargetLanguage:
                false
            }
          }
        }
      };


      try {

        socket.send(
          JSON.stringify(
            setupMessage
          )
        );

        console.log(
          "[GEMINI] Setup enviado:",
          session.jobId,
          session.targetLanguage
        );

      } catch (error) {

        session.error =
          error.message ||
          "Erro enviando setup Gemini.";

        console.error(
          "[GEMINI] Erro setup:",
          error
        );
      }
    }
  );


  socket.on(
    "message",
    function(rawMessage) {

      if (
        session.closed
      ) {
        return;
      }


      let message;

      try {

        message =
          JSON.parse(
            rawMessage.toString()
          );

      } catch (error) {

        console.error(
          "[GEMINI] JSON inválido."
        );

        return;
      }


      /* -----------------------------------------
         SETUP
      ----------------------------------------- */

      if (
        message.setupComplete
      ) {

        session.geminiReady =
          true;

        session.status =
          "ready";

        console.log(
          "[GEMINI] Setup concluído:",
          session.jobId
        );

        return;
      }


      /* -----------------------------------------
         ERRO
      ----------------------------------------- */

      if (
        message.error
      ) {

        const errorText =
          JSON.stringify(
            message.error
          );

        session.error =
          errorText;

        session.status =
          "error";

        console.error(
          "[GEMINI] ERRO:",
          errorText
        );

        return;
      }


      /* -----------------------------------------
         GO AWAY
      ----------------------------------------- */

      if (
        message.goAway
      ) {

        console.log(
          "[GEMINI] GoAway:",
          session.jobId
        );

        return;
      }


      const serverContent =
        message.serverContent;


      if (
        !serverContent
      ) {
        return;
      }


      /* -----------------------------------------
         TRANSCRIÇÃO DA ENTRADA
      ----------------------------------------- */

      if (
        serverContent.inputTranscription
      ) {

        const text =
          serverContent
            .inputTranscription
            .text;

        if (text) {

          session.lastTranscript =
            text;

          console.log(
            "[GEMINI] Entrada:",
            text
          );
        }
      }


      /* -----------------------------------------
         TRANSCRIÇÃO DA SAÍDA
      ----------------------------------------- */

      if (
        serverContent.outputTranscription
      ) {

        const text =
          serverContent
            .outputTranscription
            .text;

        if (text) {

          session.lastAgentResponse =
            text;

          console.log(
            "[GEMINI] Tradução:",
            text
          );
        }
      }


      /* -----------------------------------------
         ÁUDIO
      ----------------------------------------- */

      if (
        Array.isArray(
          serverContent.modelTurn?.parts
        )
      ) {

        for (
          const part of
          serverContent.modelTurn.parts
        ) {

          const inlineData =
            part.inlineData ||
            part.inline_data;


          if (
            !inlineData ||
            !inlineData.data
          ) {
            continue;
          }


          try {

            const audio24k =
              Buffer.from(
                inlineData.data,
                "base64"
              );


            /*
             * Gemini envia:
             *
             * PCM16 mono 24 kHz
             *
             * O Android atual trabalha
             * com 16 kHz.
             */

            const audio16k =
              resamplePcm16(
                audio24k,
                24000,
                16000
              );


            if (
              audio16k.length > 0
            ) {

              session.outputQueue.push(
                audio16k.toString(
                  "base64"
                )
              );

              session.outputBytes +=
                audio16k.length;


              /*
               * Evita acumular memória
               * se o celular ficar sem
               * buscar o áudio.
               */

              if (
                session.outputQueue.length >
                100
              ) {

                session.outputQueue =
                  session.outputQueue.slice(
                    -100
                  );
              }
            }

          } catch (error) {

            console.error(
              "[GEMINI] Erro processando áudio:",
              error
            );
          }
        }
      }


      if (
        serverContent.turnComplete
      ) {

        console.log(
          "[GEMINI] Turn complete:",
          session.jobId
        );
      }
    }
  );


  socket.on(
    "error",
    function(error) {

      session.error =
        error.message ||
        "Erro no WebSocket Gemini.";

      session.status =
        "error";

      session.geminiConnected =
        false;

      console.error(
        "[GEMINI] WebSocket error:",
        error.message
      );
    }
  );


  socket.on(
    "close",
    function(code, reason) {

      session.geminiConnected =
        false;

      session.geminiReady =
        false;

      session.geminiSocket =
        null;


      if (
        !session.closed &&
        session.status !==
        "error"
      ) {

        session.status =
          "disconnected";
      }


      console.log(
        "[GEMINI] WebSocket fechado:",
        session.jobId,
        "code=",
        code,
        "reason=",
        reason
          ? reason.toString()
          : ""
      );
    }
  );
}


/* =========================================================
   HEALTH
========================================================= */

app.get(
  "/api/health",
  function(req, res) {

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
          process.env.ELEVENLABS_API_KEY
        ),

      time:
        new Date().toISOString()
    });
  }
);


/* =========================================================
   CONFIGURAÇÃO DE ÁUDIO
========================================================= */

app.get(
  "/api/audio/config",
  function(req, res) {

    res.json({

      ok: true,

      provider:
        "gemini",

      model:
        GEMINI_MODEL,

      inputAudio:
        "pcm16-16000-mono",

      outputAudio:
        "pcm16-16000-mono",

      geminiOutput:
        "pcm16-24000-mono",

      languages:
        TARGET_LANGUAGES
    });
  }
);


/* =========================================================
   INICIAR ÁUDIO
========================================================= */

app.post(
  "/api/audio/start",
  function(req, res) {

    try {

      const clientId =
        String(
          req.body.clientId ||
          req.headers["x-client-id"] ||
          ""
        ).trim();


      const targetLang =
        String(
          req.body.targetLang ||
          "pt"
        ).trim();


      if (
        !clientId
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "clientId é obrigatório."
          });
      }


      if (
        !GEMINI_API_KEY
      ) {

        return res
          .status(500)
          .json({

            ok: false,

            error:
              "GEMINI_API_KEY não configurada no Render."
          });
      }


      const jobId =
        generateId();


      /*
       * IMPORTANTE:
       *
       * Criamos a sessão e respondemos
       * imediatamente.
       *
       * O Gemini é conectado depois,
       * em segundo plano.
       *
       * Isso evita o timeout do Android.
       */

      const session =
        createAudioSession(
          jobId,
          clientId,
          targetLang
        );


      res.json({

        ok: true,

        jobId:

          jobId,

        clientId:

          clientId,

        targetLang:

          targetLang,

        targetLanguage:

          session.targetLanguage,

        status:

          "starting",

        message:

          "Sessão criada. Conectando ao Gemini."
      });


      /*
       * Só depois de responder ao Android
       * abrimos o WebSocket.
       */

      setImmediate(
        function() {

          connectGemini(
            session
          );
        }
      );

    } catch (error) {

      console.error(
        "[AUDIO START]",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            error.message ||
            "Erro ao iniciar áudio."
        });
    }
  }
);


/* =========================================================
   RECEBER CHUNK DE ÁUDIO
========================================================= */

app.post(
  "/api/audio/chunk",
  function(req, res) {

    try {

      const jobId =
        String(
          req.body.jobId ||
          ""
        ).trim();


      const audio =
        String(
          req.body.audio ||
          ""
        ).trim();


      if (
        !jobId
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "jobId é obrigatório."
          });
      }


      if (
        !audio
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "audio é obrigatório."
          });
      }


      const session =
        audioSessions.get(
          jobId
        );


      if (
        !session
      ) {

        return res
          .status(404)
          .json({

            ok: false,

            error:
              "Sessão não encontrada."
          });
      }


      const socket =
        session.geminiSocket;


      if (
        !socket ||
        socket.readyState !==
        WebSocket.OPEN
      ) {

        return res
          .status(202)
          .json({

            ok: true,

            accepted: true,

            gemini:
              false,

            message:
              "Áudio recebido. Gemini ainda conectando."
          });
      }


      /*
       * O Android envia:
       *
       * PCM16 mono 16 kHz
       */

      const message = {

        realtimeInput: {

          audio: {

            data:
              audio,

            mimeType:
              "audio/pcm;rate=16000"
          }
        }
      };


      socket.send(
        JSON.stringify(
          message
        )
      );


      session.chunks +=
        1;


      try {

        session.bytesReceived +=
          Buffer.from(
            audio,
            "base64"
          ).length;

      } catch (_) {
      }


      res.json({

        ok: true,

        accepted: true,

        gemini:
          true,

        geminiReady:
          session.geminiReady,

        chunks:
          session.chunks
      });

    } catch (error) {

      console.error(
        "[AUDIO CHUNK]",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            error.message ||
            "Erro recebendo áudio."
        });
    }
  }
);


/* =========================================================
   SAÍDA DE ÁUDIO
========================================================= */

app.get(
  "/api/audio/output/:jobId",
  function(req, res) {

    const jobId =
      req.params.jobId;


    const session =
      audioSessions.get(
        jobId
      );


    if (
      !session
    ) {

      return res
        .status(404)
        .json({

          ok: false,

          error:
            "Sessão não encontrada."
        });
    }


    const audio =
      session.outputQueue.shift();


    if (
      audio
    ) {

      return res.json({

        ok: true,

        available:
          true,

        audio:
          audio,

        gemini:
          session.gemini,

        geminiReady:
          session.geminiReady,

        chunks:
          session.chunks,

        outputBytes:
          session.outputBytes,

        lastTranscript:
          session.lastTranscript,

        lastAgentResponse:
          session.lastAgentResponse
      });
    }


    res.json({

      ok: true,

      available:
        false,

      audio:
        null,

      gemini:
        session.gemini,

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

      error:
        session.error
    });
  }
);


/* =========================================================
   STATUS
========================================================= */

app.get(
  "/api/audio/status/:jobId",
  function(req, res) {

    const session =
      audioSessions.get(
        req.params.jobId
      );


    if (
      !session
    ) {

      return res
        .status(404)
        .json({

          ok: false,

          error:
            "Sessão não encontrada."
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

      error:
        session.error,

      createdAt:
        session.createdAt
    });
  }
);


/* =========================================================
   LISTAR SESSÕES
========================================================= */

app.get(
  "/api/audio/sessions",
  function(req, res) {

    const sessions =
      Array.from(
        audioSessions.values()
      ).map(
        function(session) {

          return {

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

            error:
              session.error,

            createdAt:
              session.createdAt
          };
        }
      );


    res.json({

      ok: true,

      count:
        sessions.length,

      sessions:
        sessions
    });
  }
);


/* =========================================================
   PARAR ÁUDIO
========================================================= */

app.post(
  "/api/audio/stop",
  function(req, res) {

    const jobId =
      String(
        req.body.jobId ||
        ""
      ).trim();


    if (
      !jobId
    ) {

      return res
        .status(400)
        .json({

          ok: false,

          error:
            "jobId é obrigatório."
        });
    }


    const session =
      audioSessions.get(
        jobId
      );


    if (
      !session
    ) {

      return res.json({

        ok: true,

        message:
          "Sessão já não existe."
      });
    }


    session.closed =
      true;

    session.status =
      "stopped";


    if (
      session.geminiSocket
    ) {

      try {

        session.geminiSocket.close();

      } catch (_) {
      }
    }


    session.geminiSocket =
      null;

    session.geminiConnected =
      false;

    session.geminiReady =
      false;


    res.json({

      ok: true,

      jobId:
        jobId,

      message:
        "Monitoramento parado."
    });


    /*
     * Mantém a sessão por alguns minutos
     * para permitir diagnóstico.
     */

    setTimeout(
      function() {

        audioSessions.delete(
          jobId
        );

      },
      5 * 60 * 1000
    );
  }
);


/* =========================================================
   UPLOAD DE VÍDEO
========================================================= */

app.post(
  "/api/test-upload",
  upload.single("video"),
  function(req, res) {

    try {

      if (
        !req.file
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "Nenhum vídeo foi enviado."
          });
      }


      const jobId =
        generateId();


      console.log(
        "[UPLOAD] Vídeo recebido:",
        req.file.filename
      );


      res.json({

        ok: true,

        jobId:
          jobId,

        message:
          "Vídeo recebido com sucesso.",

        filename:
          req.file.filename,

        targetLang:
          req.body.targetLang ||
          "pt"
      });

    } catch (error) {

      console.error(
        "[UPLOAD]",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao receber o vídeo."
        });
    }
  }
);


/* =========================================================
   ARQUIVOS DE UPLOAD
========================================================= */

app.use(
  "/uploads",
  express.static(
    uploadFolder
  )
);


/* =========================================================
   CHAT
========================================================= */

const messages = [];


app.post(
  "/api/chat/send",
  function(req, res) {

    try {

      const clientId =
        String(
          req.body.clientId ||
          ""
        ).trim();


      const message =
        String(
          req.body.message ||
          ""
        ).trim();


      if (
        !clientId
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "clientId é obrigatório."
          });
      }


      if (
        !message
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "A mensagem está vazia."
          });
      }


      if (
        message.length > 2000
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "Mensagem muito grande."
          });
      }


      const newMessage = {

        id:
          generateId(),

        clientId:
          clientId,

        sender:
          "client",

        message:
          message,

        date:
          new Date().toISOString()
      };


      messages.push(
        newMessage
      );


      console.log(
        "[CHAT] Cliente:",
        clientId,
        message
      );


      res.json({

        ok: true,

        message:
          newMessage
      });

    } catch (error) {

      console.error(
        "[CHAT]",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao enviar mensagem."
        });
    }
  }
);


app.get(
  "/api/chat/messages/:clientId",
  function(req, res) {

    const clientId =
      req.params.clientId;


    const clientMessages =
      messages.filter(
        function(item) {

          return (
            item.clientId ===
            clientId
          );
        }
      );


    res.json({

      ok: true,

      messages:
        clientMessages
    });
  }
);


/* =========================================================
   ADMIN — MENSAGENS
========================================================= */

app.get(
  "/api/admin/messages",
  function(req, res) {

    const password =
      req.headers[
        "x-admin-password"
      ];


    if (
      password !==
      ADMIN_PASSWORD
    ) {

      return res
        .status(401)
        .json({

          ok: false,

          error:
            "Senha de administrador inválida."
        });
    }


    res.json({

      ok: true,

      messages:
        messages
    });
  }
);


/* =========================================================
   ADMIN — RESPONDER
========================================================= */

app.post(
  "/api/admin/reply",
  function(req, res) {

    const password =
      req.headers[
        "x-admin-password"
      ];


    if (
      password !==
      ADMIN_PASSWORD
    ) {

      return res
        .status(401)
        .json({

          ok: false,

          error:
            "Senha de administrador inválida."
        });
    }


    const clientId =
      String(
        req.body.clientId ||
        ""
      ).trim();


    const message =
      String(
        req.body.message ||
        ""
      ).trim();


    if (
      !clientId ||
      !message
    ) {

      return res
        .status(400)
        .json({

          ok: false,

          error:
            "clientId e message são obrigatórios."
        });
    }


    const newMessage = {

      id:
        generateId(),

      clientId:
        clientId,

      sender:
        "admin",

      message:
        message,

      date:
        new Date().toISOString()
    };


    messages.push(
      newMessage
    );


    res.json({

      ok: true,

      message:
        newMessage
    });
  }
);


/* =========================================================
   ADMIN — CLIENTES
========================================================= */

app.get(
  "/api/admin/clients",
  function(req, res) {

    const password =
      req.headers[
        "x-admin-password"
      ];


    if (
      password !==
      ADMIN_PASSWORD
    ) {

      return res
        .status(401)
        .json({

          ok: false,

          error:
            "Senha inválida."
        });
    }


    const clientIds =
      [
        ...new Set(
          messages.map(
            function(item) {

              return item.clientId;
            }
          )
        )
      ];


    const clients =
      clientIds.map(
        function(clientId) {

          const clientMessages =
            messages.filter(
              function(item) {

                return (
                  item.clientId ===
                  clientId
                );
              }
            );


          return {

            clientId:
              clientId,

            messages:
              clientMessages,

            lastMessage:
              clientMessages[
                clientMessages.length - 1
              ]
          };
        }
      );


    res.json({

      ok: true,

      clients:
        clients
    });
  }
);


/* =========================================================
   ERRO MULTER / SERVIDOR
========================================================= */

app.use(
  function(
    error,
    req,
    res,
    next
  ) {

    console.error(
      "[SERVER ERROR]",
      error
    );


    if (
      error instanceof
      multer.MulterError
    ) {

      return res
        .status(400)
        .json({

          ok: false,

          error:
            "Erro no upload: " +
            error.message
        });
    }


    if (
      error
    ) {

      return res
        .status(400)
        .json({

          ok: false,

          error:
            error.message ||
            "Erro no servidor."
        });
    }


    next();
  }
);


/* =========================================================
   404
========================================================= */

app.use(
  function(req, res) {

    res
      .status(404)
      .json({

        ok: false,

        error:
          "Endpoint não encontrado."
      });
  }
);


/* =========================================================
   INICIAR SERVIDOR
========================================================= */

app.listen(
  PORT,
  "0.0.0.0",
  function() {

    console.log(
      "======================================"
    );

    console.log(
      "SI TRADUTOR LIVE - BACKEND ONLINE"
    );

    console.log(
      "Porta:",
      PORT
    );

    console.log(
      "Gemini:",
      GEMINI_API_KEY
        ? "CONFIGURADO"
        : "NÃO CONFIGURADO"
    );

    console.log(
      "Modelo:",
      GEMINI_MODEL
    );

    console.log(
      "======================================"
    );
  }
);
