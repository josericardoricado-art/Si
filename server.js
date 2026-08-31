// server.js
// Si - Tradutor Universal
// Backend para vídeo + tradução ao vivo

const express = require("express");
const cors = require("cors");
const multer = require("multer");
const { v4: uuidv4 } = require("uuid");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");

const app = express();

const PORT = process.env.PORT || 10000;

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
    methods: ["GET", "POST", "OPTIONS"],
    allowedHeaders: ["Content-Type", "Authorization"]
  })
);

app.use(
  express.json({
    limit: "20mb"
  })
);


// ======================================================
// ARQUIVOS ESTÁTICOS
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
// UPLOAD DE VÍDEO
// ======================================================

const videoStorage = multer.diskStorage({

  destination: (req, file, cb) => {
    cb(null, UPLOAD_DIR);
  },

  filename: (req, file, cb) => {

    const id = uuidv4();

    req.jobId = id;

    const ext =
      path.extname(file.originalname) || ".mp4";

    cb(
      null,
      `${id}${ext}`
    );
  }

});

const uploadVideo = multer({

  storage: videoStorage,

  limits: {
    fileSize:
      2 * 1024 * 1024 * 1024
  }

});


// ======================================================
// UPLOAD DE ÁUDIO AO VIVO
// ======================================================

const liveStorage = multer.diskStorage({

  destination: (req, file, cb) => {
    cb(null, LIVE_DIR);
  },

  filename: (req, file, cb) => {

    const id = uuidv4();

    const ext =
      path.extname(file.originalname) || ".webm";

    cb(
      null,
      `${id}${ext}`
    );
  }

});

const uploadLive = multer({

  storage: liveStorage,

  limits: {
    fileSize:
      25 * 1024 * 1024
  }

});


// ======================================================
// JOBS
// ======================================================

const jobs = new Map();

const queue = [];

let processing = false;


// ======================================================
// SESSÕES AO VIVO
// ======================================================

const liveSessions = new Map();


// ======================================================
// IDIOMAS
// ======================================================

const allowedLanguages = [
  "pt",
  "en",
  "es"
];


// ======================================================
// FUNÇÃO PYTHON
// ======================================================

function getPythonCommand() {

  if (
    process.platform === "win32"
  ) {
    return "python";
  }

  return "python3";
}


// ======================================================
// FILA DE VÍDEOS
// ======================================================

function enqueue(jobId) {

  queue.push(jobId);

  processQueue();
}


// ======================================================
// PROCESSAMENTO DE VÍDEO
// ======================================================

function processQueue() {

  if (processing) {
    return;
  }

  if (queue.length === 0) {
    return;
  }

  processing = true;

  const jobId = queue.shift();

  const job = jobs.get(jobId);

  if (!job) {

    processing = false;

    processQueue();

    return;
  }

  job.status = "processing";

  job.stage =
    "Iniciando processamento...";

  job.progress = 1;

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
    getPythonCommand();

  console.log(
    "========================================"
  );

  console.log(
    "PROCESSANDO VÍDEO"
  );

  console.log(
    "Job:",
    jobId
  );

  console.log(
    "Pipeline:",
    pipelinePath
  );

  console.log(
    "========================================"
  );

  const args = [

    pipelinePath,

    "--input",
    job.inputPath,

    "--output",
    outputPath,

    "--target-lang",
    job.targetLang

  ];

  const py = spawn(
    pythonCommand,
    args,
    {
      cwd: __dirname,

      env: {
        ...process.env,

        PYTHONUNBUFFERED: "1"
      }
    }
  );

  let stderrBuffer = "";


  py.stdout.on(
    "data",
    (data) => {

      const text =
        data.toString();

      console.log(
        "[PIPELINE]",
        text.trim()
      );

      const lines =
        text.split(/\r?\n/);

      lines.forEach(
        (line) => {

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
              !Number.isNaN(value)
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
      );

    }
  );


  py.stderr.on(
    "data",
    (data) => {

      const text =
        data.toString();

      console.error(
        "[PIPELINE ERROR]",
        text.trim()
      );

      stderrBuffer += text;

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


  py.on(
    "error",
    (error) => {

      console.error(
        "Erro iniciando Python:",
        error
      );

      job.status =
        "error";

      job.stage =
        "Erro";

      job.error =
        error.message;

      processing =
        false;

      processQueue();

    }
  );


  py.on(
    "close",
    (code) => {

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

      } else {

        job.status =
          "error";

        job.stage =
          "Erro";

        job.error =
          stderrBuffer.trim() ||
          `Processamento terminou com código ${code}.`;

      }

      processing =
        false;

      processQueue();

    }
  );

}


// ======================================================
// HOME
// ======================================================

app.get(
  "/",
  (req, res) => {

    res.json({

      ok: true,

      service:
        "si-tradutor-backend",

      message:
        "Backend do Si funcionando",

      version:
        "3.0"

    });

  }
);


// ======================================================
// HEALTH
// ======================================================

app.get(
  "/api/health",
  (req, res) => {

    res.json({

      ok: true,

      status:
        "online",

      service:
        "si-tradutor-backend",

      time:
        new Date().toISOString()

    });

  }
);


// ======================================================
// DUBLAR VÍDEO
// ======================================================

app.post(
  "/api/dublar",

  uploadVideo.single("video"),

  (req, res) => {

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

      const targetLang =
        allowedLanguages.includes(
          req.body.targetLang
        )
          ? req.body.targetLang
          : "pt";

      const jobId =
        req.jobId;

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

      enqueue(jobId);

      res.status(202).json({

        ok:
          true,

        jobId:
          jobId,

        status:
          "queued",

        message:
          "Vídeo recebido."

      });

    } catch (error) {

      console.error(
        error
      );

      res
        .status(500)
        .json({

          ok:
            false,

          error:
            error.message

        });

    }

  }
);


// ======================================================
// STATUS
// ======================================================

app.get(
  "/api/status/:jobId",

  (req, res) => {

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

    res.json({

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
// INICIAR LIVE
// ======================================================

app.post(
  "/api/live/start",

  (req, res) => {

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

        chunks:
          0,

        createdAt:
          Date.now(),

        lastAudio:
          Date.now()

      }
    );

    console.log(
      "LIVE START:",
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

  }
);


// ======================================================
// PROCESSAR ÁUDIO LIVE
// ======================================================

app.post(
  "/api/live/audio",

  uploadLive.single("audio"),

  (req, res) => {

    try {

      if (!req.file) {

        return res
          .status(400)
          .json({

            ok:
              false,

            error:
              "Áudio não enviado."

          });

      }

      let sessionId =
        req.body.sessionId;

      const targetLang =
        allowedLanguages.includes(
          req.body.targetLang
        )
          ? req.body.targetLang
          : "pt";


      // -----------------------------------------------
      // Criar sessão automaticamente
      // -----------------------------------------------

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

            chunks:
              0,

            createdAt:
              Date.now(),

            lastAudio:
              Date.now()

          }
        );

      }


      const session =
        liveSessions.get(
          sessionId
        );

      session.targetLang =
        targetLang;

      session.chunks +=
        1;

      session.lastAudio =
        Date.now();


      console.log(
        "========================================"
      );

      console.log(
        "LIVE AUDIO"
      );

      console.log(
        "Sessão:",
        sessionId
      );

      console.log(
        "Bloco:",
        session.chunks
      );

      console.log(
        "Idioma:",
        targetLang
      );

      console.log(
        "Arquivo:",
        req.file.path
      );

      console.log(
        "========================================"
      );


      // ==================================================
      // ARQUIVO DE SAÍDA
      // ==================================================

      const outputName =
        `${uuidv4()}.wav`;

      const outputPath =
        path.join(
          LIVE_DIR,
          outputName
        );


      // ==================================================
      // LIVE PIPELINE
      // ==================================================

      const livePipeline =
        path.join(
          __dirname,
          "live_pipeline.py"
        );

      const pythonCommand =
        getPythonCommand();


      const args = [

        livePipeline,

        "--input",
        req.file.path,

        "--output",
        outputPath,

        "--target-lang",
        targetLang

      ];


      console.log(
        "Executando live_pipeline.py..."
      );


      const py =
        spawn(
          pythonCommand,
          args,
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


      let stdoutBuffer =
        "";

      let stderrBuffer =
        "";


      // ==================================================
      // STDOUT
      // ==================================================

      py.stdout.on(
        "data",
        (data) => {

          const text =
            data.toString();

          stdoutBuffer +=
            text;

          console.log(
            "[LIVE PYTHON]",
            text.trim()
          );

        }
      );


      // ==================================================
      // STDERR
      // ==================================================

      py.stderr.on(
        "data",
        (data) => {

          const text =
            data.toString();

          stderrBuffer +=
            text;

          console.error(
            "[LIVE PYTHON ERROR]",
            text.trim()
          );

        }
      );


      // ==================================================
      // ERRO AO INICIAR
      // ==================================================

      py.on(
        "error",
        (error) => {

          console.error(
            "Erro no live_pipeline:",
            error
          );

        }
      );


      // ==================================================
      // FINAL DO PYTHON
      // ==================================================

      py.on(
        "close",
        (code) => {

          console.log(
            "live_pipeline terminou:",
            code
          );


          // ----------------------------------------------
          // Apagar áudio original
          // ----------------------------------------------

          try {

            if (
              fs.existsSync(
                req.file.path
              )
            ) {

              fs.unlinkSync(
                req.file.path
              );

            }

          } catch (error) {

            console.error(
              "Erro removendo áudio:",
              error
            );

          }


          // ----------------------------------------------
          // Erro
          // ----------------------------------------------

          if (
            code !== 0
          ) {

            console.error(
              "Erro no pipeline:",
              stderrBuffer
            );

            return res
              .status(500)
              .json({

                ok:
                  false,

                sessionId:
                  sessionId,

                error:
                  stderrBuffer ||
                  "Erro no processamento do áudio."

              });

          }


          // ----------------------------------------------
          // Procurar texto
          // ----------------------------------------------

          let text =
            "";

          let translation =
            "";


          const lines =
            stdoutBuffer
              .split(/\r?\n/);


          lines.forEach(
            (line) => {

              if (
                line.startsWith(
                  "RESULT_TEXT:"
                )
              ) {

                text =
                  line
                    .replace(
                      "RESULT_TEXT:",
                      ""
                    )
                    .trim();

              }


              if (
                line.startsWith(
                  "RESULT_TRANSLATION:"
                )
              ) {

                translation =
                  line
                    .replace(
                      "RESULT_TRANSLATION:",
                      ""
                    )
                    .trim();

              }

            }
          );


          // ----------------------------------------------
          // URL do áudio
          // ----------------------------------------------

          let audioUrl =
            null;


          if (
            fs.existsSync(
              outputPath
            )
          ) {

            audioUrl =
              `/live_audio/${outputName}`;

          }


          // ----------------------------------------------
          // Resposta
          // ----------------------------------------------

          return res.json({

            ok:
              true,

            sessionId:
              sessionId,

            chunk:
              session.chunks,

            text:
              text,

            translation:
              translation,

            audioUrl:
              audioUrl,

            status:
              "processed"

          });

        }
      );


    } catch (error) {

      console.error(
        "Erro LIVE:",
        error
      );

      res
        .status(500)
        .json({

          ok:
            false,

          error:
            error.message

        });

    }

  }
);


// ======================================================
// PARAR LIVE
// ======================================================

app.post(
  "/api/live/stop",

  (req, res) => {

    const sessionId =
      req.body.sessionId;

    if (
      sessionId
    ) {

      liveSessions.delete(
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
// ERROS DE UPLOAD
// ======================================================

app.use(
  (
    error,
    req,
    res,
    next
  ) => {

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
            "Arquivo maior que o limite permitido."

        });

    }

    if (error) {

      console.error(
        error
      );

      return res
        .status(500)
        .json({

          ok:
            false,

          error:
            error.message ||
            "Erro interno."

        });

    }

    next();

  }
);


// ======================================================
// 404
// ======================================================

app.use(
  (req, res) => {

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
// LIMPEZA
// ======================================================

setInterval(
  () => {

    const now =
      Date.now();


    // -----------------------------------------------
    // Jobs antigos
    // -----------------------------------------------

    for (
      const [
        jobId,
        job
      ]
      of jobs.entries()
    ) {

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

        } catch (error) {

          console.error(
            error
          );

        }

        jobs.delete(
          jobId
        );

      }

    }


    // -----------------------------------------------
    // Sessões antigas
    // -----------------------------------------------

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

  },

  10 *
  60 *
  1000
);


// ======================================================
// SERVIDOR
// ======================================================

app.listen(
  PORT,
  "0.0.0.0",
  () => {

    console.log(
      "========================================"
    );

    console.log(
      "       SI - TRADUTOR UNIVERSAL"
    );

    console.log(
      "========================================"
    );

    console.log(
      `Servidor rodando na porta ${PORT}`
    );

    console.log(
      "Modo vídeo: ATIVO"
    );

    console.log(
      "Modo live: ATIVO"
    );

    console.log(
      "========================================"
    );

  }
);
