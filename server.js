// server.js
// SI - Tradutor Universal
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
    allowedHeaders: ["Content-Type", "Accept"],
    credentials: false
  })
);

app.options("*", cors());


// ======================================================
// JSON
// ======================================================

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
// UPLOAD ÁUDIO LIVE
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
// SESSÕES LIVE
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
// PYTHON
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
// FILA
// ======================================================

function enqueue(jobId) {

  queue.push(jobId);

  processQueue();

}


// ======================================================
// PROCESSAMENTO
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
  job.stage = "Iniciando processamento...";
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
    "Entrada:",
    job.inputPath
  );

  console.log(
    "Saída:",
    outputPath
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


  const py =
    spawn(
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


  // ==================================================
  // PYTHON STDOUT
  // ==================================================

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
            line.startsWith("STAGE:")
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
            line.startsWith("PROGRESS:")
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


  // ==================================================
  // PYTHON STDERR
  // ==================================================

  py.stderr.on(
    "data",
    (data) => {

      const text =
        data.toString();

      console.error(
        "[PIPELINE ERROR]",
        text.trim()
      );

      stderrBuffer +=
        text;

      if (
        stderrBuffer.length >
        15000
      ) {

        stderrBuffer =
          stderrBuffer.slice(
            -15000
          );

      }

    }
  );


  // ==================================================
  // ERRO AO INICIAR PYTHON
  // ==================================================

  py.on(
    "error",
    (error) => {

      console.error(
        "========================================"
      );

      console.error(
        "ERRO INICIANDO PYTHON"
      );

      console.error(
        error
      );

      console.error(
        "========================================"
      );


      job.status =
        "error";

      job.stage =
        "Erro iniciando Python";

      job.error =
        error.message;

      processing =
        false;

      processQueue();

    }
  );


  // ==================================================
  // PYTHON TERMINOU
  // ==================================================

  py.on(
    "close",
    (code) => {

      console.log(
        "========================================"
      );

      console.log(
        "PYTHON FINALIZADO"
      );

      console.log(
        "Código:",
        code
      );

      console.log(
        "Output existe:",
        fs.existsSync(outputPath)
      );

      console.log(
        "Output:",
        outputPath
      );

      console.log(
        "========================================"
      );


      // ----------------------------------------------
      // Código 0 + arquivo existente
      // ----------------------------------------------

      if (
        code === 0 &&
        fs.existsSync(outputPath)
      ) {

        const fileSize =
          fs.statSync(
            outputPath
          ).size;


        console.log(
          "VÍDEO FINAL CRIADO"
        );

        console.log(
          "Tamanho:",
          fileSize,
          "bytes"
        );


        if (
          fileSize <= 0
        ) {

          job.status =
            "error";

          job.stage =
            "Erro";

          job.error =
            "O pipeline terminou com código 0, mas o vídeo final está vazio.";

        } else {

          job.status =
            "done";

          job.progress =
            100;

          job.stage =
            "Concluído";

          job.error =
            null;

        }

      }

      // ----------------------------------------------
      // Código 0 MAS arquivo não existe
      // ----------------------------------------------

      else if (
        code === 0 &&
        !fs.existsSync(outputPath)
      ) {

        console.error(
          "PYTHON TERMINOU COM CÓDIGO 0, MAS NÃO CRIOU O VÍDEO."
        );


        job.status =
          "error";

        job.stage =
          "Vídeo não encontrado";

        job.error =
          "O pipeline terminou com código 0, mas não criou o arquivo final em: " +
          outputPath;

      }

      // ----------------------------------------------
      // Python terminou com erro
      // ----------------------------------------------

      else {

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

      ok:
        true,

      service:
        "si-tradutor-backend",

      message:
        "Backend do SI funcionando",

      version:
        "3.1"

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

      ok:
        true,

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

            ok:
              false,

            error:
              "Nenhum vídeo foi enviado."

          });

      }


      console.log(
        "========================================"
      );

      console.log(
        "UPLOAD RECEBIDO"
      );

      console.log(
        "Nome:",
        req.file.originalname
      );

      console.log(
        "Arquivo:",
        req.file.path
      );

      console.log(
        "Tamanho:",
        req.file.size,
        "bytes"
      );

      console.log(
        "Tipo:",
        req.file.mimetype
      );

      console.log(
        "========================================"
      );


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


    } catch (error) {

      console.error(
        "ERRO /api/dublar:",
        error
      );


      return res
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
// LIVE START
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
// LIVE AUDIO
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
        "Arquivo:",
        req.file.path
      );

      console.log(
        "========================================"
      );


      const outputName =
        `${uuidv4()}.wav`;


      const outputPath =
        path.join(
          LIVE_DIR,
          outputName
        );


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


      py.on(
        "error",
        (error) => {

          console.error(
            "Erro no live_pipeline:",
            error
          );

        }
      );


      py.on(
        "close",
        (code) => {

          console.log(
            "live_pipeline terminou:",
            code
          );


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


          if (
            code !== 0
          ) {

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


      return res
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
