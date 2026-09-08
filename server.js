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
   UPLOADS
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

    storage,

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
   UTILIDADES
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
   SESSÕES
============================================================ */

const audioSessions =
  new Map();


/*
  Cada sessão:

  {
    jobId,
    clientId,
    targetLang,
    createdAt,

    status,

    chunks,
    bytesReceived,

    outputQueue,
    outputBytes,

    elevenSocket,
    elevenConnected,

    inputSampleRate,
    outputSampleRate,

    conversationId,

    lastError,

    sendChain
  }
*/


/* ============================================================
   RESAMPLE PCM 16 BITS MONO
============================================================ */

/*
  Conversão simples de PCM16 mono.

  O Android envia 48 kHz.

  A ElevenLabs normalmente espera 16 kHz.
*/

function resamplePcm16(
  buffer,
  inputRate,
  outputRate
) {

  if (
    !Buffer.isBuffer(buffer)
  ) {

    return Buffer.alloc(0);
  }


  if (
    inputRate === outputRate
  ) {

    return buffer;
  }


  if (
    inputRate <= 0 ||
    outputRate <= 0
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
    inputSamples < 2
  ) {

    return Buffer.alloc(0);
  }


  const outputSamples =
    Math.floor(
      inputSamples *
      outputRate /
      inputRate
    );


  if (
    outputSamples <= 0
  ) {

    return Buffer.alloc(0);
  }


  const output =
    Buffer.alloc(
      outputSamples *
      bytesPerSample
    );


  const ratio =
    inputRate /
    outputRate;


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
      sourcePosition -
      index;


    const sample1 =
      buffer.readInt16LE(
        index * 2
      );

    const sample2 =
      buffer.readInt16LE(
        nextIndex * 2
      );


    const sample =
      Math.round(
        sample1 +
        (
          sample2 -
          sample1
        ) *
        fraction
      );


    output.writeInt16LE(
      sample,
      i * 2
    );
  }


  return output;
}


/* ============================================================
   CONVERTER SAÍDA DA ELEVENLABS PARA 16 KHZ
============================================================ */

function convertOutputTo16k(
  buffer,
  sampleRate
) {

  if (
    !Buffer.isBuffer(buffer)
  ) {

    return Buffer.alloc(0);
  }


  if (
    !sampleRate ||
    sampleRate === 16000
  ) {

    return buffer;
  }


  return resamplePcm16(
    buffer,
    sampleRate,
    16000
  );
}


/* ============================================================
   SIGNED URL ELEVENLABS
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

  } catch (_) {

    throw new Error(
      "Resposta inválida ao obter signed URL da ElevenLabs."
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
   CONECTAR ELEVENLABS
============================================================ */

async function connectElevenLabs(
  session
) {

  const signedUrl =
    await getElevenLabsSignedUrl();


  console.log(
    "[ELEVENLABS] Abrindo WebSocket:",
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

      let opened =
        false;

      let settled =
        false;


      const timeout =
        setTimeout(
          () => {

            if (
              !opened &&
              !settled
            ) {

              settled =
                true;

              session.lastError =
                "Timeout conectando na ElevenLabs.";

              try {

                socket.close();

              } catch (_) {
              }

              reject(
                new Error(
                  session.lastError
                )
              );
            }

          },
          20000
        );


      /* --------------------------------------------------------
         OPEN
      -------------------------------------------------------- */

      socket.on(
        "open",
        () => {

          opened =
            true;


          session.elevenConnected =
            true;

          session.status =
            "active";


          console.log(
            "[ELEVENLABS] WebSocket conectado:",
            session.jobId
          );


          /*
            Envia configuração inicial.

            A ElevenLabs documenta esse evento
            como conversation_initiation_client_data.
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
                    "Receba a fala do vídeo e traduza imediatamente " +
                    "para o idioma de destino: " +
                    target +
                    ". " +
                    "Não converse. " +
                    "Não explique. " +
                    "Não responda perguntas. " +
                    "Apenas traduza a fala recebida. " +
                    "A tradução deve ser natural, curta e rápida. " +
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


            console.log(
              "[ELEVENLABS] Configuração inicial enviada:",
              session.jobId
            );


          } catch (error) {

            session.lastError =
              "Erro enviando configuração: " +
              error.message;

            console.error(
              "[ELEVENLABS]",
              session.lastError
            );
          }


          if (
            !settled
          ) {

            settled =
              true;

            clearTimeout(
              timeout
            );

            resolve();
          }
        }
      );


      /* --------------------------------------------------------
         MESSAGE
      -------------------------------------------------------- */

      socket.on(
        "message",
        data => {

          try {

            const message =
              JSON.parse(
                data.toString()
              );


            /* ==================================================
               METADATA DA CONVERSA
            ================================================== */

            if (
              message.type ===
              "conversation_initiation_metadata"
            ) {

              const metadata =
                message
                  .conversation_initiation_metadata_event;


              if (
                metadata
              ) {

                session.conversationId =
                  metadata.conversation_id ||
                  null;


                session.inputSampleRate =
                  parseSampleRate(
                    metadata.user_input_audio_format
                  );


                session.outputSampleRate =
                  parseSampleRate(
                    metadata.agent_output_audio_format
                  );


                console.log(
                  "[ELEVENLABS] Formato de entrada:",
                  metadata.user_input_audio_format
                );


                console.log(
                  "[ELEVENLABS] Formato de saída:",
                  metadata.agent_output_audio_format
                );


                console.log(
                  "[ELEVENLABS] Conversation:",
                  session.conversationId
                );
              }


              return;
            }


            /* ==================================================
               ÁUDIO
            ================================================== */

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


              let audioBuffer;


              try {

                audioBuffer =
                  Buffer.from(
                    audioBase64,
                    "base64"
                  );

              } catch (_) {

                return;
              }


              /*
                O Android espera PCM16 mono 16 kHz.

                Se a ElevenLabs estiver usando outro
                sample rate PCM, convertemos aqui.
              */

              if (
                session.outputSampleRate &&
                session.outputSampleRate !==
                16000
              ) {

                audioBuffer =
                  convertOutputTo16k(
                    audioBuffer,
                    session.outputSampleRate
                  );
              }


              if (
                audioBuffer.length > 0
              ) {

                const finalBase64 =
                  audioBuffer.toString(
                    "base64"
                  );


                session.outputQueue.push(
                  finalBase64
                );


                session.outputBytes +=
                  audioBuffer.length;


                console.log(
                  "[ELEVENLABS] Áudio recebido:",
                  audioBuffer.length,
                  "bytes"
                );
              }


              return;
            }


            /* ==================================================
               TRANSCRIÇÃO
            ================================================== */

            if (
              message.type ===
              "user_transcript"
            ) {

              const transcript =
                message
                  .user_transcription_event
                  ?.user_transcript;


              if (
                transcript
              ) {

                console.log(
                  "[ELEVENLABS] Transcrição:",
                  transcript
                );
              }


              return;
            }


            /* ==================================================
               RESPOSTA DO AGENTE
            ================================================== */

            if (
              message.type ===
              "agent_response"
            ) {

              const response =
                message
                  .agent_response_event
                  ?.agent_response;


              if (
                response
              ) {

                console.log(
                  "[ELEVENLABS] Resposta:",
                  response
                );
              }


              return;
            }


            /* ==================================================
               RESPOSTA COMPLETA
            ================================================== */

            if (
              message.type ===
              "agent_response_correction"
            ) {

              console.log(
                "[ELEVENLABS] Correção de resposta recebida."
              );

              return;
            }


            /* ==================================================
               ERRO DO CLIENTE
            ================================================== */

            if (
              message.type ===
              "client_error"
            ) {

              const errorText =
                JSON.stringify(
                  message
                );


              session.lastError =
                errorText;


              session.status =
                "error";


              console.error(
                "[ELEVENLABS] CLIENT ERROR:",
                errorText
              );


              return;
            }


            /* ==================================================
               ERRO GENÉRICO
            ================================================== */

            if (
              message.type ===
              "error"
            ) {

              const errorText =
                JSON.stringify(
                  message
                );


              session.lastError =
                errorText;


              session.status =
                "error";


              console.error(
                "[ELEVENLABS] ERROR:",
                errorText
              );


              return;
            }


            /* ==================================================
               PING
            ================================================== */

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


              } catch (
                error
              ) {

                session.lastError =
                  error.message;
              }


              return;
            }

          } catch (
            error
          ) {

            console.error(
              "[ELEVENLABS] Erro processando mensagem:",
              error.message
            );
          }
        }
      );


      /* --------------------------------------------------------
         ERROR
      -------------------------------------------------------- */

      socket.on(
        "error",
        error => {

          const errorText =
            error?.message ||
            String(error);


          session.lastError =
            errorText;


          session.elevenConnected =
            false;


          session.status =
            "error";


          console.error(
            "[ELEVENLABS] WebSocket error:",
            errorText
          );


          if (
            !settled
          ) {

            settled =
              true;

            clearTimeout(
              timeout
            );

            reject(
              error
            );
          }
        }
      );


      /* --------------------------------------------------------
         CLOSE
      -------------------------------------------------------- */

      socket.on(
        "close",
        (
          code,
          reason
        ) => {

          const reasonText =
            reason
              ? reason.toString()
              : "";


          session.elevenConnected =
            false;


          console.error(
            "[ELEVENLABS] WebSocket fechado:",
            session.jobId,
            "code:",
            code,
            "reason:",
            reasonText
          );


          if (
            session.status !==
            "stopped"
          ) {

            session.status =
              "disconnected";


            if (
              !session.lastError
            ) {

              session.lastError =
                "WebSocket ElevenLabs fechado. code=" +
                code +
                " reason=" +
                reasonText;
            }
          }
        }
      );
    }
  );
}


/* ============================================================
   SAMPLE RATE
============================================================ */

function parseSampleRate(
  format
) {

  if (
    !format
  ) {

    return 16000;
  }


  const text =
    String(
      format
    ).toLowerCase();


  if (
    text.includes(
      "16000"
    )
  ) {

    return 16000;
  }


  if (
    text.includes(
      "22050"
    )
  ) {

    return 22050;
  }


  if (
    text.includes(
      "24000"
    )
  ) {

    return 24000;
  }


  if (
    text.includes(
      "44100"
    )
  ) {

    return 44100;
  }


  if (
    text.includes(
      "48000"
    )
  ) {

    return 48000;
  }


  return 16000;
}


/* ============================================================
   ENVIAR ÁUDIO PARA ELEVENLABS
============================================================ */

/*
  Mantemos uma fila sequencial.

  Isso evita que vários chunks enviados
  simultaneamente troquem de ordem.
*/

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


  /*
    Android envia 48 kHz.

    ElevenLabs normalmente trabalha
    com PCM16 16 kHz.
  */

  const inputRate =
    48000;


  const targetRate =
    session.inputSampleRate ||
    16000;


  const pcm16 =
    resamplePcm16(
      pcmBuffer,
      inputRate,
      targetRate
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

    /*
      Fila sequencial de envio.
    */

    session.sendChain =
      session.sendChain
        .then(
          () =>
            new Promise(
              resolve => {

                try {

                  if (
                    socket.readyState ===
                    WebSocket.OPEN
                  ) {

                    socket.send(
                      JSON.stringify(
                        message
                      )
                    );
                  }

                } catch (
                  error
                ) {

                  session.lastError =
                    error.message;
                }


                resolve();
              }
            )
        )
        .catch(
          error => {

            session.lastError =
              error.message;
          }
        );


    return true;

  } catch (
    error
  ) {

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

      agentId:
        ELEVENLABS_AGENT_ID,

      time:
        new Date()
          .toISOString()
    });
  }
);


/* ============================================================
   CONFIG ÁUDIO
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

      outputAndroid: {

        sampleRate:
          16000,

        channels:
          1,

        encoding:
          "pcm_s16le"
      },

      elevenlabsAgent:
        ELEVENLABS_AGENT_ID,

      architecture:
        "Android AudioPlaybackCapture -> Render -> ElevenLabs -> Render -> Android"
    });
  }
);


/* ============================================================
   INICIAR SESSÃO
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

        inputSampleRate:
          16000,

        outputSampleRate:
          16000,

        conversationId:
          null,

        lastError:
          null,

        sendChain:
          Promise.resolve()
      };


      audioSessions.set(
        jobId,
        session
      );


      console.log(
        "========================================"
      );

      console.log(
        "[AUDIO] NOVA SESSÃO"
      );

      console.log(
        "[AUDIO] Job:",
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
        "========================================"
      );


      try {

        await connectElevenLabs(
          session
        );


        /*
          Importante:

          O WebSocket abriu.
          A sessão agora está ativa.

          Se depois fechar, o status será
          atualizado para disconnected/error.
        */

        session.status =
          "active";


      } catch (
        error
      ) {

        console.error(
          "[AUDIO] Falha ElevenLabs:",
          error.message
        );


        session.status =
          "error";


        session.elevenConnected =
          false;


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

        error:
          session.lastError,

        message:
          session.elevenConnected
            ? "Sessão ElevenLabs conectada."
            : "Sessão criada, mas ElevenLabs não está conectada."
      });


    } catch (
      error
    ) {

      console.error(
        "Erro /api/audio/start:",
        error
      );


      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao iniciar sessão."
        });
    }
  }
);


/* ============================================================
   RECEBER CHUNK
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


      if (
        !base64
      ) {

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
        audioBuffer.length ===
        0
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
              "Chunk muito grande."
          });
      }


      session.chunks +=
        1;


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
          sent,

        inputSampleRate:
          session.inputSampleRate,

        outputSampleRate:
          session.outputSampleRate,

        error:
          session.lastError
      });


    } catch (
      error
    ) {

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
   ÁUDIO DE SAÍDA
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


      const output =
        session.outputQueue.splice(
          0,
          session.outputQueue.length
        );


      res.json({

        ok: true,

        jobId,

        audio:
          output,

        chunks:
          output.length,

        elevenlabs:
          session.elevenConnected,

        status:
          session.status,

        inputSampleRate:
          session.inputSampleRate,

        outputSampleRate:
          session.outputSampleRate,

        conversationId:
          session.conversationId,

        outputBytes:
          session.outputBytes,

        error:
          session.lastError
      });


    } catch (
      error
    ) {

      console.error(
        "Erro /api/audio/output:",
        error
      );


      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao obter áudio."
        });
    }
  }
);


/* ============================================================
   STATUS
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
        session.elevenConnected,

      inputSampleRate:
        session.inputSampleRate,

      outputSampleRate:
        session.outputSampleRate,

      conversationId:
        session.conversationId,

      error:
        session.lastError
    });
  }
);


/* ============================================================
   PARAR
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
            "Sessão já encerrada."
        });
      }


      session.status =
        "stopped";


      session.elevenConnected =
        false;


      if (
        session.elevenSocket
      ) {

        try {

          session.elevenSocket.close();

        } catch (_) {
        }
      }


      session.elevenSocket =
        null;


      res.json({

        ok: true,

        jobId,

        status:
          "stopped"
      });


      setTimeout(
        () => {

          audioSessions.delete(
            jobId
          );

        },
        5 * 60 * 1000
      );


    } catch (
      error
    ) {

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

          outputBytes:
            session.outputBytes,

          elevenlabs:
            session.elevenConnected,

          inputSampleRate:
            session.inputSampleRate,

          outputSampleRate:
            session.outputSampleRate,

          conversationId:
            session.conversationId,

          error:
            session.lastError,

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
   UPLOAD
============================================================ */

app.post(
  "/api/test-upload",
  upload.single("video"),
  function(
    req,
    res
  ) {

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


    } catch (
      error
    ) {

      console.error(
        "Erro upload:",
        error
      );


      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao receber vídeo."
        });
    }
  }
);


/* ============================================================
   DUB URL
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


      if (
        !videoUrl
      ) {

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
          "aguardando_audio"
      });


    } catch (
      error
    ) {

      console.error(
        "Erro /api/dub-url:",
        error
      );


      res
        .status(500)
        .json({

          ok: false,

          error:
            "Erro ao processar link."
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


    } catch (
      error
    ) {

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
   UPLOADS STATIC
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


/* ============================================================
   INICIAR SERVIDOR
============================================================ */

app.listen(
  PORT,
  "0.0.0.0",
  function() {

    console.log(
      "========================================"
    );

    console.log(
      "SI TRADUTOR LIVE"
    );

    console.log(
      "BACKEND ONLINE"
    );

    console.log(
      "Porta:",
      PORT
    );

    console.log(
      "ElevenLabs API:",
      ELEVENLABS_API_KEY
        ? "CONFIGURADA"
        : "NÃO CONFIGURADA"
    );

    console.log(
      "Agent ID:",
      ELEVENLABS_AGENT_ID
    );

    console.log(
      "Voice ID:",
      ELEVENLABS_VOICE_ID
    );

    console.log(
      "WebSocket:",
      "ATIVADO"
    );

    console.log(
      "========================================"
    );
  }
);
