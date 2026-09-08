const express = require("express");
const cors = require("cors");
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const { WebSocket } = require("ws");

const app = express();

const PORT = process.env.PORT || 10000;

const BACKEND_URL = "https://si-u2ul.onrender.com";

const ELEVENLABS_API_KEY =
  process.env.ELEVENLABS_API_KEY || "";

const ELEVENLABS_AGENT_ID =
  process.env.ELEVENLABS_AGENT_ID ||
  "agent_1601m1q929bhf2zvts65479fyzdw";

const ELEVENLABS_VOICE_ID =
  process.env.ELEVENLABS_VOICE_ID ||
  "cjVigY5qzO86Huf0OWal";

const ADMIN_PASSWORD =
  process.env.ADMIN_PASSWORD ||
  "troque-esta-senha";


/* ============================================================
   APP
============================================================ */

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
      "X-Client-Id",
      "X-Job-Id"
    ]
  })
);

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


/* ============================================================
   PASTAS
============================================================ */

const uploadFolder =
  path.join(__dirname, "uploads");

if (!fs.existsSync(uploadFolder)) {
  fs.mkdirSync(
    uploadFolder,
    {
      recursive: true
    }
  );
}


/* ============================================================
   UPLOAD
============================================================ */

const storage =
  multer.diskStorage({

    destination: function(
      req,
      file,
      cb
    ) {

      cb(
        null,
        uploadFolder
      );
    },

    filename: function(
      req,
      file,
      cb
    ) {

      const extension =
        path.extname(
          file.originalname
        ) || ".mp4";

      const name =
        "video-" +
        Date.now() +
        "-" +
        Math.random()
          .toString(36)
          .substring(2, 10) +
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
      function(
        req,
        file,
        cb
      ) {

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


/* ============================================================
   UTILITÁRIOS
============================================================ */

function generateId() {

  return (
    Date.now() +
    "-" +
    crypto
      .randomBytes(8)
      .toString("hex")
  );
}


function sleep(ms) {

  return new Promise(
    resolve =>
      setTimeout(
        resolve,
        ms
      )
  );
}


/* ============================================================
   IDIOMAS
============================================================ */

const ALLOWED_LANGUAGES = [
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


function validLanguage(
  language
) {

  return ALLOWED_LANGUAGES.includes(
    language
  );
}


/* ============================================================
   SESSÕES DE ÁUDIO
============================================================ */

const audioSessions =
  new Map();


/*
  Estrutura:

  {
    jobId,
    clientId,
    targetLang,
    createdAt,

    status,

    chunks,
    bytesReceived,

    elevenSocket,

    outputQueue,

    outputBytes,

    elevenConnected,

    lastError
  }
*/


/* ============================================================
   CONVERTER PCM 48KHZ -> PCM 16KHZ
============================================================ */

/*
  O Android atual captura 48 kHz.

  A ElevenLabs Agent trabalha com PCM 16 kHz.

  Aqui fazemos uma redução simples de 48 kHz
  para 16 kHz.

  Cada 3 amostras de entrada gera 1 amostra
  de saída.
*/

function resample48kTo16k(
  buffer
) {

  if (
    !Buffer.isBuffer(buffer)
  ) {

    return Buffer.alloc(0);
  }

  const bytesPerSample = 2;

  const inputSamples =
    Math.floor(
      buffer.length /
      bytesPerSample
    );

  if (
    inputSamples < 3
  ) {

    return Buffer.alloc(0);
  }

  const outputSamples =
    Math.floor(
      inputSamples / 3
    );

  const output =
    Buffer.alloc(
      outputSamples *
      bytesPerSample
    );

  for (
    let i = 0;
    i < outputSamples;
    i++
  ) {

    const inputIndex =
      i * 3 * 2;

    const sample =
      buffer.readInt16LE(
        inputIndex
      );

    output.writeInt16LE(
      sample,
      i * 2
    );
  }

  return output;
}


/* ============================================================
   OBTER SIGNED URL DA ELEVENLABS
============================================================ */

async function getElevenLabsSignedUrl() {

  if (
    !ELEVENLABS_API_KEY
  ) {

    throw new Error(
      "ELEVENLABS_API_KEY não configurada no Render."
    );
  }

  const url =
    "https://api.elevenlabs.io/v1/convai/conversation/get-signed-url" +
    "?agent_id=" +
    encodeURIComponent(
      ELEVENLABS_AGENT_ID
    );

  const response =
    await fetch(
      url,
      {
        method: "GET",

        headers: {
          "xi-api-key":
            ELEVENLABS_API_KEY
        }
      }
    );

  const text =
    await response.text();

  if (
    !response.ok
  ) {

    throw new Error(
      "ElevenLabs signed URL HTTP " +
      response.status +
      ": " +
      text
    );
  }

  let data;

  try {

    data =
      JSON.parse(
        text
      );

  } catch (error) {

    throw new Error(
      "Resposta inválida da ElevenLabs."
    );
  }

  if (
    !data.signed_url
  ) {

    throw new Error(
      "ElevenLabs não retornou signed_url."
    );
  }

  return data.signed_url;
}


/* ============================================================
   INICIAR ELEVENLABS
============================================================ */

async function connectElevenLabs(
  session
) {

  if (
    !ELEVENLABS_API_KEY
  ) {

    throw new Error(
      "ELEVENLABS_API_KEY não configurada."
    );
  }

  const signedUrl =
    await getElevenLabsSignedUrl();

  console.log(
    "[ELEVENLABS] Abrindo WebSocket para:",
    session.jobId
  );

  const socket =
    new WebSocket(
      signedUrl
    );

  session.elevenSocket =
    socket;

  return new Promise(
    (
      resolve,
      reject
    ) => {

      let settled =
        false;

      const timeout =
        setTimeout(
          () => {

            if (!settled) {

              settled = true;

              try {
                socket.close();
              } catch (_) {}

              reject(
                new Error(
                  "Timeout conectando na ElevenLabs."
                )
              );
            }

          },
          20000
        );


      socket.on(
        "open",
        () => {

          console.log(
            "[ELEVENLABS] WebSocket conectado:",
            session.jobId
          );

          session.elevenConnected =
            true;

          /*
            Configuração inicial.

            O prompt instrui o Agent a funcionar
            como tradutor em tempo real.
          */

          const target =
            session.targetLang;

          const initiation = {

            type:
              "conversation_initiation_client_data",

            conversation_config_override: {

              agent: {

                prompt: {

                  prompt:
                    "Você é um tradutor de áudio em tempo real. " +
                    "Receba fala em qualquer idioma que conseguir reconhecer " +
                    "e traduza para o idioma de destino: " +
                    target +
                    ". " +
                    "Não converse com o usuário. " +
                    "Não explique o que está fazendo. " +
                    "Apenas produza a tradução falada, de forma natural, " +
                    "curta e rápida. " +
                    "Preserve nomes próprios e números quando possível."
                }
              },

              tts: {

                voice_id:
                  ELEVENLABS_VOICE_ID
              }
            },

            dynamic_variables: {

              target_language:
                target
            }
          };


          try {

            socket.send(
              JSON.stringify(
                initiation
              )
            );

          } catch (error) {

            console.error(
              "[ELEVENLABS] Erro ao enviar configuração:",
              error
            );
          }


          if (!settled) {

            settled = true;

            clearTimeout(
              timeout
            );

            resolve();
          }
        }
      );


      socket.on(
        "message",
        (data) => {

          try {

            const message =
              JSON.parse(
                data.toString()
              );


            /*
              ÁUDIO DEVOLVIDO PELA ELEVENLABS
            */

            if (
              message.type ===
                "audio" &&
              message.audio_event &&
              message.audio_event.audio_base_64
            ) {

              const audioBase64 =
                message
                  .audio_event
                  .audio_base_64;

              session.outputQueue.push(
                audioBase64
              );

              session.outputBytes +=
                Buffer.from(
                  audioBase64,
                  "base64"
                ).length;

              return;
            }


            /*
              TRANSCRIÇÃO RECEBIDA
            */

            if (
              message.type ===
                "user_transcript"
            ) {

              const transcript =
                message
                  .user_transcription_event
                  ?.user_transcript;

              if (transcript) {

                console.log(
                  "[ELEVENLABS] Transcrição:",
                  transcript
                );
              }

              return;
            }


            /*
              RESPOSTA DO AGENTE
            */

            if (
              message.type ===
                "agent_response"
            ) {

              const response =
                message
                  .agent_response_event
                  ?.agent_response;

              if (response) {

                console.log(
                  "[ELEVENLABS] Resposta:",
                  response
                );
              }

              return;
            }


            /*
              ERRO
            */

            if (
              message.type ===
                "error"
            ) {

              console.error(
                "[ELEVENLABS] Erro:",
                message
              );

              session.lastError =
                JSON.stringify(
                  message
                );

              return;
            }


            /*
              PING

              A API pode enviar ping.
            */

            if (
              message.type ===
                "ping"
            ) {

              const eventId =
                message
                  .ping_event
                  ?.event_id;

              try {

                socket.send(
                  JSON.stringify({
                    type:
                      "pong",
                    event_id:
                      eventId
                  })
                );

              } catch (_) {}
            }

          } catch (error) {

            console.error(
              "[ELEVENLABS] Mensagem inválida:",
              error.message
            );
          }
        }
      );


      socket.on(
        "error",
        (error) => {

          console.error(
            "[ELEVENLABS] WebSocket error:",
            error.message
          );

          session.lastError =
            error.message;

          if (!settled) {

            settled = true;

            clearTimeout(
              timeout
            );

            reject(
              error
            );
          }
        }
      );


      socket.on(
        "close",
        (
          code,
          reason
        ) => {

          console.log(
            "[ELEVENLABS] WebSocket fechado:",
            session.jobId,
            code,
            reason
              ? reason.toString()
              : ""
          );

          session.elevenConnected =
            false;
        }
      );
    }
  );
}


/* ============================================================
   ENVIAR ÁUDIO PARA ELEVENLABS
============================================================ */

function sendAudioToElevenLabs(
  session,
  pcmBuffer
) {

  if (
    !session ||
    !session.elevenSocket
  ) {

    return false;
  }

  const socket =
    session.elevenSocket;

  if (
    socket.readyState !==
    WebSocket.OPEN
  ) {

    return false;
  }

  const pcm16 =
    resample48kTo16k(
      pcmBuffer
    );

  if (
    pcm16.length === 0
  ) {

    return false;
  }

  const audioBase64 =
    pcm16.toString(
      "base64"
    );

  const message = {

    user_audio_chunk:
      audioBase64
  };

  try {

    socket.send(
      JSON.stringify(
        message
      )
    );

    return true;

  } catch (error) {

    session.lastError =
      error.message;

    return false;
  }
}


/* ============================================================
   HEALTH
============================================================ */

app.get(
  "/api/health",
  function(
    req,
    res
  ) {

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

      elevenlabsWebSocket:
        true,

      time:
        new Date()
          .toISOString()
    });
  }
);


/* ============================================================
   CONFIGURAÇÃO DO ÁUDIO
============================================================ */

app.get(
  "/api/audio/config",
  function(
    req,
    res
  ) {

    res.json({

      ok: true,

      input: {

        sampleRate:
          48000,

        channels:
          1,

        encoding:
          "pcm_s16le"
      },

      elevenlabs: {

        sampleRate:
          16000,

        channels:
          1,

        encoding:
          "pcm_s16le"
      },

      architecture:
        "Android AudioPlaybackCapture -> Render -> ElevenLabs -> Android"
    });
  }
);


/* ============================================================
   INICIAR SESSÃO DE ÁUDIO
============================================================ */

app.post(
  "/api/audio/start",
  async function(
    req,
    res
  ) {

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


      if (!clientId) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "clientId é obrigatório."
          });
      }


      if (
        !validLanguage(
          targetLang
        )
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "Idioma de destino inválido."
          });
      }


      const jobId =
        generateId();


      const session = {

        jobId,

        clientId,

        targetLang,

        createdAt:
          Date.now(),

        status:
          "connecting",

        chunks:
          0,

        bytesReceived:
          0,

        outputQueue:
          [],

        outputBytes:
          0,

        elevenSocket:
          null,

        elevenConnected:
          false,

        lastError:
          null
      };


      audioSessions.set(
        jobId,
        session
      );


      console.log(
        "================================"
      );

      console.log(
        "[AUDIO] Nova sessão:",
        jobId
      );

      console.log(
        "[AUDIO] Cliente:",
        clientId
      );

      console.log(
        "[AUDIO] Idioma:",
        targetLang
      );

      console.log(
        "================================"
      );


      try {

        await connectElevenLabs(
          session
        );

        session.status =
          "active";

      } catch (error) {

        console.error(
          "[AUDIO] Falha ElevenLabs:",
          error.message
        );

        session.status =
          "error";

        session.lastError =
          error.message;
      }


      res.json({

        ok: true,

        jobId,

        targetLang,

        status:
          session.status,

        elevenlabs:
          session.elevenConnected,

        message:
          session.elevenConnected
            ? "Sessão de tradução iniciada."
            : "Sessão criada, mas a conexão com a ElevenLabs falhou."
      });


    } catch (error) {

      console.error(
        "Erro /api/audio/start:",
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


/* ============================================================
   RECEBER CHUNK DE ÁUDIO
============================================================ */

app.post(
  "/api/audio/chunk",
  function(
    req,
    res
  ) {

    try {

      const jobId =
        String(
          req.headers["x-job-id"] ||
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
              "Sessão não encontrada."
          });
      }


      const base64 =
        String(
          req.body.audioBase64 ||
          req.body.audio ||
          ""
        );


      if (!base64) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "audioBase64 é obrigatório."
          });
      }


      const audioBuffer =
        Buffer.from(
          base64,
          "base64"
        );


      if (
        audioBuffer.length === 0
      ) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "Áudio vazio."
          });
      }


      if (
        audioBuffer.length >
        1024 * 1024
      ) {

        return res
          .status(413)
          .json({

            ok: false,

            error:
              "Chunk de áudio muito grande."
          });
      }


      session.chunks += 1;

      session.bytesReceived +=
        audioBuffer.length;


      const sent =
        sendAudioToElevenLabs(
          session,
          audioBuffer
        );


      res.json({

        ok: true,

        jobId,

        received:
          audioBuffer.length,

        chunks:
          session.chunks,

        bytesReceived:
          session.bytesReceived,

        elevenlabs:
          session.elevenConnected,

        sentToElevenLabs:
          sent
      });


    } catch (error) {

      console.error(
        "Erro /api/audio/chunk:",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao processar áudio."
        });
    }
  }
);


/* ============================================================
   PEGAR ÁUDIO TRADUZIDO
============================================================ */

app.get(
  "/api/audio/output/:jobId",
  function(
    req,
    res
  ) {

    try {

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


      /*
        Entrega os áudios acumulados
        para o Android.

        O Android pode chamar esta rota
        repetidamente.
      */

      const output =
        session.outputQueue.splice(
          0,
          session.outputQueue.length
        );


      res.json({

        ok: true,

        jobId,

        audio: output,

        chunks:
          output.length,

        elevenlabs:
          session.elevenConnected,

        status:
          session.status,

        error:
          session.lastError
      });


    } catch (error) {

      console.error(
        "Erro /api/audio/output:",
        error
      );

      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao obter áudio traduzido."
        });
    }
  }
);


/* ============================================================
   STATUS DA SESSÃO
============================================================ */

app.get(
  "/api/audio/status/:jobId",
  function(
    req,
    res
  ) {

    const session =
      audioSessions.get(
        req.params.jobId
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

      status:
        session.status,

      chunks:
        session.chunks,

      bytesReceived:
        session.bytesReceived,

      outputQueue:
        session.outputQueue.length,

      elevenlabs:
        session.elevenConnected,

      error:
        session.lastError
    });
  }
);


/* ============================================================
   PARAR SESSÃO
============================================================ */

app.post(
  "/api/audio/stop",
  function(
    req,
    res
  ) {

    try {

      const jobId =
        String(
          req.body.jobId ||
          req.headers["x-job-id"] ||
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

        return res.json({

          ok: true,

          message:
            "Sessão já encerrada."
        });
      }


      session.status =
        "stopped";


      if (
        session.elevenSocket
      ) {

        try {

          session.elevenSocket.close();

        } catch (_) {}
      }


      session.elevenSocket =
        null;

      session.elevenConnected =
        false;


      /*
        Mantemos a sessão por alguns minutos
        para permitir diagnóstico.
      */

      setTimeout(
        () => {

          audioSessions.delete(
            jobId
          );

        },
        5 * 60 * 1000
      );


      res.json({

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

      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao parar sessão."
        });
    }
  }
);


/* ============================================================
   LISTAR SESSÕES
============================================================ */

app.get(
  "/api/audio/sessions",
  function(
    req,
    res
  ) {

    const sessions =
      Array.from(
        audioSessions.values()
      ).map(
        session => ({

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

          elevenlabs:
            session.elevenConnected,

          createdAt:
            session.createdAt
        })
      );


    res.json({

      ok: true,

      count:
        sessions.length,

      sessions
    });
  }
);


/* ============================================================
   UPLOAD DE VÍDEO
============================================================ */

app.post(
  "/api/test-upload",
  upload.single("video"),
  function(
    req,
    res
  ) {

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


      res.json({

        ok: true,

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
        "Erro upload:",
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


/* ============================================================
   DUBLAGEM POR LINK
============================================================ */

app.post(
  "/api/dub-url",
  function(
    req,
    res
  ) {

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
              "Link inválido."
          });
      }


      const jobId =
        generateId();


      res.json({

        ok: true,

        jobId,

        message:
          "Link recebido.",

        url:
          videoUrl,

        targetLang,

        status:
          "aguardando_audio",

        architecture:
          "Android Audio Capture -> Render -> ElevenLabs"
      });


    } catch (error) {

      console.error(
        "Erro /api/dub-url:",
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


/* ============================================================
   CHAT
============================================================ */

const messages = [];


app.post(
  "/api/chat/send",
  function(
    req,
    res
  ) {

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

            ok: false,

            error:
              "clientId é obrigatório."
          });
      }


      if (!message) {

        return res
          .status(400)
          .json({

            ok: false,

            error:
              "A mensagem está vazia."
          });
      }


      const item = {

        id:
          generateId(),

        clientId,

        sender:
          "client",

        message,

        date:
          new Date()
            .toISOString()
      };


      messages.push(
        item
      );


      res.json({

        ok: true,

        message:
          item
      });


    } catch (error) {

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
  function(
    req,
    res
  ) {

    const clientId =
      req.params.clientId;


    res.json({

      ok: true,

      messages:
        messages.filter(
          item =>
            item.clientId ===
            clientId
        )
    });
  }
);


/* ============================================================
   ADMIN
============================================================ */

app.get(
  "/api/admin/messages",
  function(
    req,
    res
  ) {

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

      messages
    });
  }
);


/* ============================================================
   ARQUIVOS
============================================================ */

app.use(
  "/uploads",
  express.static(
    uploadFolder
  )
);


/* ============================================================
   404
============================================================ */

app.use(
  function(
    req,
    res
  ) {

    res
      .status(404)
      .json({

        ok: false,

        error:
          "Endpoint não encontrado."
      });
  }
);


/* ============================================================
   ERROS
============================================================ */

app.use(
  function(
    error,
    req,
    res,
    next
  ) {

    console.error(
      "ERRO:",
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


/* ============================================================
   INICIAR
============================================================ */

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
      "ElevenLabs:",
      ELEVENLABS_API_KEY
        ? "CONFIGURADA"
        : "NÃO CONFIGURADA"
    );

    console.log(
      "Agent:",
      ELEVENLABS_AGENT_ID
    );

    console.log(
      "WebSocket:",
      "ATIVADO"
    );

    console.log(
      "======================================"
    );
  }
);
