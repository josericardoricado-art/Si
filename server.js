// server.js
// Backend do Si - Tradutor Universal
// Suporta:
// 1. Upload de vídeos MP4 para dublagem
// 2. Consulta de progresso
// 3. Arquivos de saída
// 4. Recepção de áudio em blocos para modo ao vivo
// 5. CORS para GitHub Pages
// 6. Execução do pipeline Python

const express = require("express");
const cors = require("cors");
const multer = require("multer");
const { v4: uuidv4 } = require("uuid");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");

const app = express();

const PORT = process.env.PORT || 10000;

// ======================================================
// CONFIGURAÇÃO
// ======================================================

const UPLOAD_DIR = path.join(__dirname, "uploads");
const OUTPUT_DIR = path.join(__dirname, "outputs");
const LIVE_DIR = path.join(__dirname, "live_audio");

fs.mkdirSync(UPLOAD_DIR, { recursive: true });
fs.mkdirSync(OUTPUT_DIR, { recursive: true });
fs.mkdirSync(LIVE_DIR, { recursive: true });

// ======================================================
// CORS
// ======================================================

app.use(
  cors({
    origin: "*",
    methods: [
      "GET",
      "POST",
      "OPTIONS"
    ],
    allowedHeaders: [
      "Content-Type",
      "Authorization"
    ]
  })
);

// ======================================================
// JSON
// ======================================================

app.use(
  express.json({
    limit: "20mb"
  })
);

// ======================================================
// ARQUIVOS DE SAÍDA
// ======================================================

app.use(
  "/outputs",
  express.static(OUTPUT_DIR)
);

app.use(
  "/live_audio",
  express.static(LIVE_DIR)
);

// ======================================================
// MULTER - UPLOAD DE VÍDEO
// ======================================================

const videoStorage =
  multer.diskStorage({

    destination: function (
      req,
      file,
      cb
    ) {
      cb(
        null,
        UPLOAD_DIR
      );
    },

    filename: function (
      req,
      file,
      cb
    ) {

      const id =
        uuidv4();

      req.jobId =
        id;

      const ext =
        path.extname(
          file.originalname
        ) || ".mp4";

      cb(
        null,
        `${id}${ext}`
      );
    }

  });


const uploadVideo =
  multer({

    storage:
      videoStorage,

    limits: {

      fileSize:
        2 *
        1024 *
        1024 *
        1024

    }

  });


// ======================================================
// MULTER - ÁUDIO AO VIVO
// ======================================================

const liveStorage =
  multer.diskStorage({

    destination: function (
      req,
      file,
      cb
    ) {

      cb(
        null,
        LIVE_DIR
      );

    },

    filename: function (
      req,
      file,
      cb
    ) {

      const id =
        uuidv4();

      const ext =
        path.extname(
          file.originalname
        ) || ".webm";

      cb(
        null,
        `${id}${ext}`
      );

    }

  });


const uploadLive =
  multer({

    storage:
      liveStorage,

    limits: {

      fileSize:
        25 *
        1024 *
        1024

    }

  });


// ======================================================
// JOBS DE VÍDEO
// ======================================================

const jobs =
  new Map();

const queue =
  [];

let processing =
  false;


// ======================================================
// SESSÕES AO VIVO
// ======================================================

const liveSessions =
  new Map();


// ======================================================
// IDIOMAS PERMITIDOS
// ======================================================

const allowedLanguages = [
  "pt",
  "en",
  "es"
];


// ======================================================
// FILA
// ======================================================

function enqueue(
  jobId
) {

  queue.push(
    jobId
  );

  processQueue();

}


// ======================================================
// PROCESSAR FILA
// ======================================================

function processQueue() {

  if (
    processing
  ) {
    return;
  }

  if (
    queue.length === 0
  ) {
    return;
  }

  processing =
    true;


  const jobId =
    queue.shift();


  const job =
    jobs.get(
      jobId
    );


  if (!job) {

    processing =
      false;

    processQueue();

    return;
  }


  job.status =
    "processing";

  job.stage =
    "Iniciando processamento...";

  job.progress =
    1;


  const outputPath =
    path.join(
      OUTPUT_DIR,
      `${jobId}.mp4`
    );


  job.outputPath =
    outputPath;


  const pipelinePath =
    path.join(
      __dirname,
      "pipeline.py"
    );


  const pythonCommand =
    process.platform === "win32"
      ? "python"
      : "python3";


  console.log(
    "========================================"
  );

  console.log(
    "INICIANDO JOB"
  );

  console.log(
    "Job:",
    jobId
  );

  console.log(
    "Python:",
    pythonCommand
  );

  console.log(
    "Pipeline:",
    pipelinePath
  );

  console.log(
    "Entrada:",
    job.inputPath
  );

  console.log(
    "Saída:",
    outputPath
  );

  console.log(
    "Idioma:",
    job.targetLang
  );

  console.log(
    "========================================"
  );


  const pythonArgs = [

    pipelinePath,

    "--input",
    job.inputPath,

    "--output",
    outputPath,

    "--target-lang",
    job.targetLang

  ];


  const py =
    spawn(
      pythonCommand,
      pythonArgs,
      {

        cwd:
          __dirname,

        env: {

          ...process.env,

          PYTHONUNBUFFERED:
            "1"

        }

      }
    );


  let stderrBuffer =
    "";


  // ====================================================
  // STDOUT DO PYTHON
  // ====================================================

  py.stdout.on(
    "data",
    function (data) {

      const output =
        data.toString();


      console.log(
        "[PYTHON]",
        output.trim()
      );


      const lines =
        output.split(
          /\r?\n/
        );


      for (
        const line
        of lines
      ) {

        if (
          line.startsWith(
            "STAGE:"
          )
        ) {

          job.stage =
            line
              .replace(
                "STAGE:",
                ""
              )
              .trim();

        }


        if (
          line.startsWith(
            "PROGRESS:"
          )
        ) {

          const value =
            Number(
              line
                .replace(
                  "PROGRESS:",
                  ""
                )
                .trim()
            );


          if (
            !Number.isNaN(
              value
            )
          ) {

            job.progress =
              Math.max(
                0,
                Math.min(
                  100,
                  value
                )
              );

          }

        }

      }

    }
  );


  // ====================================================
  // STDERR
  // ====================================================

  py.stderr.on(
    "data",
    function (data) {

      const text =
        data.toString();


      console.error(
        "[PYTHON ERROR]",
        text.trim()
      );


      stderrBuffer +=
        text;


      if (
        stderrBuffer.length >
        10000
      ) {

        stderrBuffer =
          stderrBuffer.slice(
            -10000
          );

      }

    }
  );


  // ====================================================
  // ERRO AO INICIAR PYTHON
  // ====================================================

  py.on(
    "error",
    function (error) {

      console.error(
        "Erro ao iniciar Python:",
        error
      );


      job.status =
        "error";

      job.stage =
        "Erro";

      job.error =
        "Não foi possível iniciar o Python: " +
        error.message;


      processing =
        false;


      processQueue();

    }
  );


  // ====================================================
  // PYTHON TERMINOU
  // ====================================================

  py.on(
    "close",
    function (code) {

      console.log(
        `Job ${jobId} terminou com código ${code}`
      );


      if (
        code === 0 &&
        fs.existsSync(
          outputPath
        )
      ) {

        job.status =
          "done";

        job.progress =
          100;

        job.stage =
          "Concluído";

        job.error =
          null;


        console.log(
          "Vídeo dublado com sucesso."
        );

      } else {

        job.status =
          "error";

        job.stage =
          "Erro";


        job.error =
          stderrBuffer.trim() ||
          `O processamento terminou com código ${code}.`;


        console.error(
          job.error
        );

      }


      processing =
        false;


      processQueue();

    }
  );

}


// ======================================================
// ROTA PRINCIPAL
// ======================================================

app.get(
  "/",
  function (
    req,
    res
  ) {

    res.json({

      ok:
        true,

      service:
        "si-tradutor-backend",

      message:
        "Backend do Si funcionando.",

      version:
        "2.0"

    });

  }
);


// ======================================================
// HEALTH CHECK
// ======================================================

app.get(
  "/api/health",
  function (
    req,
    res
  ) {

    res.json({

      ok:
        true,

      service:
        "si-tradutor-backend",

      status:
        "online",

      time:
        new Date().toISOString()

    });

  }
);


// ======================================================
// ENVIAR VÍDEO MP4
// ======================================================

app.post(
  "/api/dublar",

  uploadVideo.single(
    "video"
  ),

  function (
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

            ok:
              false,

            error:
              "Nenhum vídeo foi enviado."

          });

      }


      const targetLang =
        allowedLanguages.includes(
          req.body.targetLang
        )
          ? req.body.targetLang
          : "pt";


      const jobId =
        req.jobId;


      if (!jobId) {

        return res
          .status(500)
          .json({

            ok:
              false,

            error:
              "Não foi possível criar o ID do processamento."

          });

      }


      jobs.set(
        jobId,
        {

          id:
            jobId,

          status:
            "queued",

          progress:
            0,

          stage:
            "Na fila...",

          inputPath:
            req.file.path,

          outputPath:
            null,

          targetLang:
            targetLang,

          error:
            null,

          createdAt:
            Date.now()

        }
      );


      enqueue(
        jobId
      );


      return res
        .status(202)
        .json({

          ok:
            true,

          jobId:
            jobId,

          status:
            "queued",

          message:
            "Vídeo recebido e colocado na fila."

        });


    } catch (
      error
    ) {

      console.error(
        "Erro no upload:",
        error
      );


      return res
        .status(500)
        .json({

          ok:
            false,

          error:
            "Erro interno ao receber o vídeo."

        });

    }

  }
);


// ======================================================
// CONSULTAR STATUS DO JOB
// ======================================================

app.get(
  "/api/status/:jobId",

  function (
    req,
    res
  ) {

    const job =
      jobs.get(
        req.params.jobId
      );


    if (!job) {

      return res
        .status(404)
        .json({

          ok:
            false,

          error:
            "Job não encontrado."

        });

    }


    return res.json({

      ok:
        true,

      id:
        job.id,

      status:
        job.status,

      progress:
        job.progress,

      stage:
        job.stage,

      error:
        job.error,

      downloadUrl:
        job.status === "done"
          ? `/outputs/${job.id}.mp4`
          : null

    });

  }
);


// ======================================================
// INICIAR SESSÃO AO VIVO
// ======================================================

app.post(
  "/api/live/start",

  function (
    req,
    res
  ) {

    try {

      const sessionId =
        uuidv4();


      const targetLang =
        allowedLanguages.includes(
          req.body.targetLang
        )
          ? req.body.targetLang
          : "pt";


      liveSessions.set(
        sessionId,
        {

          id:
            sessionId,

          targetLang:
            targetLang,

          status:
            "active",

          createdAt:
            Date.now(),

          lastAudio:
            Date.now(),

          chunks:
            0

        }
      );


      console.log(
        "Sessão ao vivo iniciada:",
        sessionId
      );


      res.json({

        ok:
          true,

        sessionId:
          sessionId,

        targetLang:
          targetLang,

        status:
          "active"

      });


    } catch (
      error
    ) {

      console.error(
        "Erro ao iniciar sessão:",
        error
      );


      res
        .status(500)
        .json({

          ok:
            false,

          error:
            "Não foi possível iniciar a sessão ao vivo."

        });

    }

  }
);


// ======================================================
// RECEBER ÁUDIO AO VIVO
// ======================================================

app.post(
  "/api/live/audio",

  uploadLive.single(
    "audio"
  ),

  async function (
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

            ok:
              false,

            error:
              "Nenhum bloco de áudio foi enviado."

          });

      }


      const targetLang =
        allowedLanguages.includes(
          req.body.targetLang
        )
          ? req.body.targetLang
          : "pt";


      /*
       * O navegador pode enviar os blocos
       * sem criar uma sessão previamente.
       *
       * Criamos uma sessão automaticamente.
       */

      let sessionId =
        req.body.sessionId;


      if (
        !sessionId ||
        !liveSessions.has(
          sessionId
        )
      ) {

        sessionId =
          uuidv4();


        liveSessions.set(
          sessionId,
          {

            id:
              sessionId,

            targetLang:
              targetLang,

            status:
              "active",

            createdAt:
              Date.now(),

            lastAudio:
              Date.now(),

            chunks:
              0

          }
        );

      }


      const session =
        liveSessions.get(
          sessionId
        );


      session.lastAudio =
        Date.now();

      session.chunks +=
        1;

      session.targetLang =
        targetLang;


      console.log(
        "Áudio ao vivo recebido:",
        {
          sessionId,
          chunk: session.chunks,
          size: req.file.size,
          targetLang
        }
      );


      /*
       * --------------------------------------------------
       * IMPORTANTE
       * --------------------------------------------------
       *
       * O live_pipeline.py será conectado aqui.
       *
       * Por enquanto o servidor confirma o recebimento
       * do bloco. O próximo arquivo implementará:
       *
       * áudio -> Whisper -> tradução -> TTS.
       *
       * --------------------------------------------------
       */


      return res.json({

        ok:
          true,

        sessionId:
          sessionId,

        received:
          true,

        chunk:
          session.chunks,

        progress:
          50,

        stage:
          "Áudio recebido; aguardando processamento de tradução.",

        translation:
          "",

        audioUrl:
          null

      });


    } catch (
      error
    ) {

      console.error(
        "Erro no áudio ao vivo:",
        error
      );


      return res
        .status(500)
        .json({

          ok:
            false,

          error:
            "Erro ao processar o bloco de áudio."

        });

    }

  }
);


// ======================================================
// PARAR SESSÃO AO VIVO
// ======================================================

app.post(
  "/api/live/stop",

  function (
    req,
    res
  ) {

    const sessionId =
      req.body.sessionId;


    if (
      sessionId &&
      liveSessions.has(
        sessionId
      )
    ) {

      const session =
        liveSessions.get(
          sessionId
        );


      session.status =
        "stopped";


      console.log(
        "Sessão ao vivo encerrada:",
        sessionId
      );

    }


    res.json({

      ok:
        true,

      status:
        "stopped"

    });

  }
);


// ======================================================
// ERROS DO MULTER / UPLOAD
// ======================================================

app.use(
  function (
    error,
    req,
    res,
    next
  ) {

    if (
      error &&
      error.code ===
        "LIMIT_FILE_SIZE"
    ) {

      return res
        .status(413)
        .json({

          ok:
            false,

          error:
            "O arquivo ultrapassa o limite permitido."

        });

    }


    if (
      error
    ) {

      console.error(
        "Erro:",
        error
      );


      return res
        .status(500)
        .json({

          ok:
            false,

          error:
            error.message ||
            "Erro interno do servidor."

        });

    }


    next();

  }
);


// ======================================================
// LIMPEZA DOS JOBS
// ======================================================

setInterval(
  function () {

    const now =
      Date.now();


    for (
      const [
        jobId,
        job
      ]
      of jobs.entries()
    ) {

      /*
       * Remove jobs com mais de 1 hora.
       */

      if (
        now -
        job.createdAt >
        60 *
        60 *
        1000
      ) {

        try {

          if (
            job.inputPath &&
            fs.existsSync(
              job.inputPath
            )
          ) {

            fs.unlinkSync(
              job.inputPath
            );

          }


          if (
            job.outputPath &&
            fs.existsSync(
              job.outputPath
            )
          ) {

            fs.unlinkSync(
              job.outputPath
            );

          }

        } catch (
          error
        ) {

          console.error(
            "Erro removendo job:",
            error
          );

        }


        jobs.delete(
          jobId
        );

      }

    }


    /*
     * Limpa sessões ao vivo
     * abandonadas há mais de 30 minutos.
     */

    for (
      const [
        sessionId,
        session
      ]
      of liveSessions.entries()
    ) {

      if (
        now -
        session.lastAudio >
        30 *
        60 *
        1000
      ) {

        liveSessions.delete(
          sessionId
        );

      }

    }


    /*
     * Remove arquivos de áudio
     * temporários com mais de 30 minutos.
     */

    try {

      const files =
        fs.readdirSync(
          LIVE_DIR
        );


      for (
        const file
        of files
      ) {

        const filePath =
          path.join(
            LIVE_DIR,
            file
          );


        const stat =
          fs.statSync(
            filePath
          );


        if (
          now -
          stat.mtimeMs >
          30 *
          60 *
          1000
        ) {

          fs.unlinkSync(
            filePath
          );

        }

      }

    } catch (
      error
    ) {

      console.error(
        "Erro limpando áudio ao vivo:",
        error
      );

    }

  },

  10 *
  60 *
  1000
);


// ======================================================
// 404
// ======================================================

app.use(
  function (
    req,
    res
  ) {

    res
      .status(404)
      .json({

        ok:
          false,

        error:
          "Endpoint não encontrado.",

        path:
          req.originalUrl

      });

  }
);


// ======================================================
// INICIAR SERVIDOR
// ======================================================

app.listen(
  PORT,
  "0.0.0.0",
  function () {

    console.log(
      "========================================"
    );

    console.log(
      "        SI TRADUTOR UNIVERSAL"
    );

    console.log(
      "========================================"
    );

    console.log(
      `Servidor rodando na porta ${PORT}`
    );

    console.log(
      `Diretório de uploads: ${UPLOAD_DIR}`
    );

    console.log(
      `Diretório de outputs: ${OUTPUT_DIR}`
    );

    console.log(
      `Diretório de áudio ao vivo: ${LIVE_DIR}`
    );

    console.log(
      "Backend pronto."
    );

    console.log(
      "========================================"
    );

  }
);
