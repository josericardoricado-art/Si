const express = require("express");
const cors = require("cors");
const multer = require("multer");
const path = require("path");
const fs = require("fs");

const app = express();

const PORT =
  process.env.PORT || 10000;


/* =====================================
   CONFIGURAÇÕES
===================================== */

const ADMIN_PASSWORD =
  process.env.ADMIN_PASSWORD ||
  "troque-esta-senha";


/* =====================================
   ELEVENLABS
===================================== */

const ELEVENLABS_API_KEY =
  process.env.ELEVENLABS_API_KEY || "";

const ELEVENLABS_AGENT_ID =
  process.env.ELEVENLABS_AGENT_ID ||
  "agent_1601m1q929bhf2zvts65479fyzdw";

const ELEVENLABS_VOICE_ID =
  process.env.ELEVENLABS_VOICE_ID ||
  "cjVigY5qzO86Huf0OWal";


/* =====================================
   CORS
===================================== */

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
      "X-Admin-Password",
      "X-Client-Id"
    ]
  })
);


/* =====================================
   BODY
===================================== */

app.use(
  express.json({
    limit: "20mb"
  })
);

app.use(
  express.urlencoded({
    extended: true,
    limit: "20mb"
  })
);


/* =====================================
   UPLOAD
===================================== */

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


/* =====================================
   STORAGE
===================================== */

const storage =
  multer.diskStorage({

    destination:
      function(
        req,
        file,
        cb
      ) {

        cb(
          null,
          uploadFolder
        );
      },

    filename:
      function(
        req,
        file,
        cb
      ) {

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
            .substring(2, 8) +
          extension;

        cb(
          null,
          name
        );
      }

  });


/* =====================================
   MULTER
===================================== */

const upload =
  multer({

    storage: storage,

    limits: {
      fileSize:
        500 * 1024 * 1024
    },

    fileFilter:
      function(
        req,
        file,
        cb
      ) {

        if (
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


/* =====================================
   BANCO SIMPLES DE CHAT
===================================== */

const messages = [];


/* =====================================
   SESSÕES DE ÁUDIO
===================================== */

const audioSessions =
  new Map();


/*
 * Cada sessão guarda:
 *
 * jobId
 * clientId
 * targetLang
 * startedAt
 * chunks
 * bytes
 */

const MAX_AUDIO_SESSIONS = 100;


/* =====================================
   GERAR ID
===================================== */

function generateId() {

  return (
    Date.now() +
    "-" +
    Math.random()
      .toString(36)
      .substring(2, 10)
  );
}


/* =====================================
   HEALTH
===================================== */

app.get(
  "/api/health",
  function(req, res) {

    res.json({

      ok: true,

      service:
        "LinguaLive",

      message:
        "Servidor online",

      audioCapture:
        true,

      elevenlabs:
        Boolean(
          ELEVENLABS_API_KEY
        ),

      time:
        new Date().toISOString()
    });

  }
);


/* =====================================
   CONFIGURAÇÃO DO ÁUDIO
===================================== */

app.get(
  "/api/audio/config",
  function(req, res) {

    res.json({

      ok: true,

      sampleRate:
        48000,

      channels:
        1,

      encoding:
        "PCM_16BIT",

      format:
        "audio/pcm",

      architecture:
        "Android AudioPlaybackCapture -> Render -> ElevenLabs",

      message:
        "Configuração de captura disponível."
    });

  }
);


/* =====================================
   INICIAR SESSÃO DE ÁUDIO
===================================== */

app.post(
  "/api/audio/start",
  function(req, res) {

    try {

      const clientId =
        String(
          req.body.clientId ||
          ""
        ).trim();

      const targetLang =
        String(
          req.body.targetLang ||
          "pt"
        ).trim();


      if (!clientId) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "clientId é obrigatório."
          });
      }


      const allowedLanguages = [
        "pt",
        "en",
        "es",
        "fr",
        "de",
        "it",
        "ja",
        "ko",
        "zh",
        "ru",
        "ar",
        "hi",
        "tr",
        "nl",
        "pl",
        "uk",
        "th",
        "id",
        "vi"
      ];


      if (
        !allowedLanguages.includes(
          targetLang
        )
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "Idioma de destino não suportado."
          });
      }


      if (
        audioSessions.size >=
        MAX_AUDIO_SESSIONS
      ) {

        return res
          .status(503)
          .json({

            ok: false,

            error:
              "Limite de sessões de áudio atingido."
          });
      }


      const jobId =
        generateId();


      audioSessions.set(
        jobId,
        {

          jobId:
            jobId,

          clientId:
            clientId,

          targetLang:
            targetLang,

          startedAt:
            new Date().toISOString(),

          chunks:
            0,

          bytes:
            0,

          lastAudioAt:
            null
        }
      );


      console.log(
        "================================"
      );

      console.log(
        "NOVA SESSÃO DE ÁUDIO"
      );

      console.log(
        "JOB:",
        jobId
      );

      console.log(
        "CLIENTE:",
        clientId
      );

      console.log(
        "IDIOMA:",
        targetLang
      );

      console.log(
        "================================"
      );


      res.json({

        ok: true,

        jobId:
          jobId,

        targetLang:
          targetLang,

        status:
          "capturando_audio",

        sampleRate:
          48000,

        channels:
          1,

        encoding:
          "PCM_16BIT",

        message:
          "Sessão de áudio iniciada."
      });


    } catch (error) {

      console.error(
        "Erro em /api/audio/start:",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao iniciar sessão de áudio."
        });
    }

  }
);


/* =====================================
   RECEBER BLOCO DE ÁUDIO
===================================== */

app.post(
  "/api/audio/chunk",
  function(req, res) {

    try {

      const jobId =
        String(
          req.headers[
            "x-job-id"
          ] ||
          req.body.jobId ||
          ""
        ).trim();


      if (!jobId) {

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


      if (!session) {

        return res
          .status(404)
          .json({

            ok: false,

            error:
              "Sessão de áudio não encontrada."
          });
      }


      /*
       * O Android poderá enviar
       * o PCM diretamente como corpo
       * application/octet-stream.
       */

      let audioBuffer = null;


      if (
        Buffer.isBuffer(
          req.body
        )
      ) {

        audioBuffer =
          req.body;

      } else if (
        req.body &&
        req.body.audioBase64
      ) {

        audioBuffer =
          Buffer.from(
            String(
              req.body.audioBase64
            ),
            "base64"
          );

      } else if (
        req.body &&
        req.body.audio
      ) {

        audioBuffer =
          Buffer.from(
            String(
              req.body.audio
            ),
            "base64"
          );
      }


      if (
        !audioBuffer ||
        audioBuffer.length === 0
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "Nenhum áudio recebido."
          });
      }


      /*
       * Proteção contra blocos gigantes.
       */

      const MAX_CHUNK_SIZE =
        1024 * 1024;


      if (
        audioBuffer.length >
        MAX_CHUNK_SIZE
      ) {

        return res
          .status(413)
          .json({

            ok: false,

            error:
              "Bloco de áudio muito grande."
          });
      }


      session.chunks += 1;

      session.bytes +=
        audioBuffer.length;

      session.lastAudioAt =
        new Date().toISOString();


      /*
       * Neste momento o Render recebeu
       * corretamente o áudio.
       *
       * NÃO salvamos cada bloco no disco.
       *
       * A próxima etapa poderá encaminhar
       * os blocos para o processamento
       * de tradução/dublagem.
       */

      console.log(
        "ÁUDIO RECEBIDO:",
        jobId,
        "chunk:",
        session.chunks,
        "bytes:",
        audioBuffer.length
      );


      res.json({

        ok: true,

        jobId:
          jobId,

        received:
          true,

        chunk:
          session.chunks,

        bytes:
          audioBuffer.length,

        totalBytes:
          session.bytes,

        status:
          "audio_recebido"
      });


    } catch (error) {

      console.error(
        "Erro em /api/audio/chunk:",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao receber bloco de áudio."
        });
    }

  }
);


/* =====================================
   STATUS DA SESSÃO DE ÁUDIO
===================================== */

app.get(
  "/api/audio/status/:jobId",
  function(req, res) {

    const jobId =
      req.params.jobId;


    const session =
      audioSessions.get(
        jobId
      );


    if (!session) {

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

      startedAt:
        session.startedAt,

      chunks:
        session.chunks,

      bytes:
        session.bytes,

      lastAudioAt:
        session.lastAudioAt,

      status:
        "capturando_audio"
    });

  }
);


/* =====================================
   ENCERRAR SESSÃO DE ÁUDIO
===================================== */

app.post(
  "/api/audio/stop",
  function(req, res) {

    try {

      const jobId =
        String(
          req.body.jobId ||
          req.headers[
            "x-job-id"
          ] ||
          ""
        ).trim();


      if (!jobId) {

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


      if (!session) {

        return res
          .status(404)
          .json({

            ok: false,

            error:
              "Sessão não encontrada."
          });
      }


      const result = {

        jobId:
          session.jobId,

        clientId:
          session.clientId,

        targetLang:
          session.targetLang,

        startedAt:
          session.startedAt,

        finishedAt:
          new Date().toISOString(),

        chunks:
          session.chunks,

        bytes:
          session.bytes,

        status:
          "encerrado"
      };


      audioSessions.delete(
        jobId
      );


      console.log(
        "SESSÃO DE ÁUDIO ENCERRADA:",
        jobId
      );


      res.json({

        ok: true,

        session:
          result,

        message:
          "Sessão encerrada."
      });


    } catch (error) {

      console.error(
        "Erro em /api/audio/stop:",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao encerrar sessão."
        });
    }

  }
);


/* =====================================
   STATUS DE TODAS AS SESSÕES
===================================== */

app.get(
  "/api/audio/sessions",
  function(req, res) {

    const sessions =
      Array.from(
        audioSessions.values()
      );


    res.json({

      ok: true,

      total:
        sessions.length,

      sessions:
        sessions
    });

  }
);


/* =====================================
   UPLOAD DE VÍDEO
===================================== */

app.post(
  "/api/test-upload",
  upload.single("video"),
  function(req, res) {

    try {

      if (!req.file) {

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
        "Vídeo recebido:",
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

      console.error(error);

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


/* =====================================
   DUBLAGEM POR LINK
===================================== */

app.post(
  "/api/dub-url",
  async function(req, res) {

    try {

      const videoUrl =
        String(
          req.body.url ||
          ""
        ).trim();


      const targetLang =
        String(
          req.body.targetLang ||
          "pt"
        ).trim();


      if (!videoUrl) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "O link do vídeo é obrigatório."
          });
      }


      if (
        !videoUrl.startsWith(
          "http://"
        ) &&
        !videoUrl.startsWith(
          "https://"
        )
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "Link inválido. Use um endereço começando com https://"
          });
      }


      console.log(
        "================================"
      );

      console.log(
        "NOVO LINK PARA DUBLAGEM"
      );

      console.log(
        "URL:",
        videoUrl
      );

      console.log(
        "IDIOMA:",
        targetLang
      );

      console.log(
        "================================"
      );


      const jobId =
        generateId();


      res.json({

        ok: true,

        jobId:
          jobId,

        message:
          "Link recebido com sucesso.",

        url:
          videoUrl,

        targetLang:
          targetLang,

        status:
          "aguardando_audio",

        architecture:
          "Android Audio Capture -> Render -> ElevenLabs"
      });


    } catch (error) {

      console.error(
        "Erro em /api/dub-url:",
        error
      );


      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao processar o link."
        });
    }

  }
);


/* =====================================
   CHAT — CLIENTE ENVIA
===================================== */

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


      if (!clientId) {

        return res
          .status(400)
          .json({

            error:
              "clientId é obrigatório."
          });
      }


      if (!message) {

        return res
          .status(400)
          .json({

            error:
              "A mensagem está vazia."
          });
      }


      if (
        message.length >
        2000
      ) {

        return res
          .status(400)
          .json({

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
        "NOVA MENSAGEM DO CLIENTE:"
      );

      console.log(
        clientId +
        ": " +
        message
      );


      res.json({

        ok: true,

        message:
          newMessage
      });


    } catch (error) {

      console.error(error);

      res
        .status(500)
        .json({

          error:
            "Erro ao enviar mensagem."
        });
    }

  }
);


/* =====================================
   CHAT — CLIENTE RECEBE
===================================== */

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


/* =====================================
   ADMIN — VER MENSAGENS
===================================== */

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


/* =====================================
   ADMIN — RESPONDER
===================================== */

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


    console.log(
      "RESPOSTA ENVIADA PARA:",
      clientId
    );


    res.json({

      ok: true,

      message:
        newMessage
    });

  }
);


/* =====================================
   ADMIN — LISTAR CLIENTES
===================================== */

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


    const clientIds = [
      ...new Set(
        messages.map(
          item =>
            item.clientId
        )
      )
    ];


    const clients =
      clientIds.map(
        function(clientId) {

          const clientMessages =
            messages.filter(
              item =>
                item.clientId ===
                clientId
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


/* =====================================
   ARQUIVOS DE UPLOAD
===================================== */

app.use(
  "/uploads",
  express.static(
    uploadFolder
  )
);


/* =====================================
   ERROS DO MULTER
===================================== */

app.use(
  function(
    error,
    req,
    res,
    next
  ) {

    console.error(
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


    if (error) {

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


/* =====================================
   404
===================================== */

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


/* =====================================
   INICIAR SERVIDOR
===================================== */

app.listen(
  PORT,
  "0.0.0.0",
  function() {

    console.log(
      "================================"
    );

    console.log(
      "LinguaLive Backend ONLINE"
    );

    console.log(
      "Porta:",
      PORT
    );

    console.log(
      "Audio Capture: ATIVO"
    );

    console.log(
      "ElevenLabs Agent:",
      ELEVENLABS_AGENT_ID
    );

    console.log(
      "Voice ID:",
      ELEVENLABS_VOICE_ID
    );

    console.log(
      "================================"
    );

  }
);
