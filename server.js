// server.js - Backend da aplicação de dublagem automática
const express = require("express");
const cors = require("cors");
const multer = require("multer");
const { v4: uuidv4 } = require("uuid");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");

const app = express();
const PORT = process.env.PORT || 10000;

// ===============================
// DIRETÓRIOS
// ===============================

const UPLOAD_DIR = path.join(__dirname, "uploads");
const OUTPUT_DIR = path.join(__dirname, "outputs");

fs.mkdirSync(UPLOAD_DIR, { recursive: true });
fs.mkdirSync(OUTPUT_DIR, { recursive: true });

// ===============================
// MIDDLEWARE
// ===============================

app.use(cors({
  origin: "*",
  methods: ["GET", "POST", "OPTIONS"],
  allowedHeaders: ["Content-Type"]
}));

app.use(express.json());

// Arquivos finais
app.use("/outputs", express.static(OUTPUT_DIR));

// ===============================
// UPLOAD DO VÍDEO
// ===============================

const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, UPLOAD_DIR);
  },

  filename: (req, file, cb) => {
    const id = uuidv4();

    req.jobId = id;

    const ext =
      path.extname(file.originalname) || ".mp4";

    cb(null, `${id}${ext}`);
  }
});

const upload = multer({
  storage,

  limits: {
    fileSize: 2 * 1024 * 1024 * 1024
  }
});

// ===============================
// FILA DE PROCESSAMENTO
// ===============================

const jobs = new Map();
const queue = [];

let processing = false;

// ===============================
// ADICIONAR JOB À FILA
// ===============================

function enqueue(jobId) {
  queue.push(jobId);

  processQueue();
}

// ===============================
// PROCESSAR FILA
// ===============================

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

  job.outputPath = outputPath;

  // IMPORTANTE:
  // pipeline.py fica na mesma pasta do server.js

  const pipelinePath =
    path.join(
      __dirname,
      "pipeline.py"
    );

  // Render usa Linux.
  // Windows usa python.

  const pythonCommand =
    process.platform === "win32"
      ? "python"
      : "python3";

  console.log(
    "================================="
  );

  console.log(
    "Iniciando processamento"
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
    "================================="
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

  const py = spawn(
    pythonCommand,
    pythonArgs,
    {
      cwd: __dirname,

      env: {
        ...process.env,

        PYTHONUNBUFFERED:
          "1"
      }
    }
  );

  let stderrBuffer = "";

  // ===============================
  // SAÍDA DO PYTHON
  // ===============================

  py.stdout.on(
    "data",
    (data) => {

      const output =
        data.toString();

      console.log(
        "[PYTHON]",
        output.trim()
      );

      const lines =
        output.split(/\r?\n/);

      lines
        .filter(Boolean)
        .forEach(
          (line) => {

            // Exemplo:
            // STAGE: Transcrevendo

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

            // Exemplo:
            // PROGRESS: 50

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
        );
    }
  );

  // ===============================
  // ERROS DO PYTHON
  // ===============================

  py.stderr.on(
    "data",
    (data) => {

      const text =
        data.toString();

      console.error(
        "[PYTHON ERROR]",
        text.trim()
      );

      stderrBuffer += text;

      // Mantém os últimos 10 KB
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

  // ===============================
  // ERRO AO INICIAR PYTHON
  // ===============================

  py.on(
    "error",
    (error) => {

      console.error(
        "Erro ao iniciar Python:",
        error
      );

      job.status = "error";

      job.stage = "Erro";

      job.error =
        `Não foi possível iniciar o Python: ${error.message}`;

      processing = false;

      processQueue();
    }
  );

  // ===============================
  // PYTHON TERMINOU
  // ===============================

  py.on(
    "close",
    (code) => {

      console.log(
        `Job ${jobId} terminou com código ${code}`
      );

      if (
        code === 0 &&
        fs.existsSync(
          outputPath
        )
      ) {

        job.status = "done";

        job.progress = 100;

        job.stage =
          "Concluído";

        job.error = null;

        console.log(
          "Vídeo dublado com sucesso:"
        );

        console.log(
          outputPath
        );

      } else {

        job.status = "error";

        job.stage = "Erro";

        job.error =
          stderrBuffer.trim() ||
          `O processamento terminou com código ${code}.`;

        console.error(
          "Falha no processamento:"
        );

        console.error(
          job.error
        );
      }

      processing = false;

      processQueue();
    }
  );
}

// ===============================
// ROTA PRINCIPAL
// ===============================

app.get(
  "/",
  (req, res) => {

    res.json({

      ok: true,

      service:
        "dublagem-backend",

      message:
        "Backend funcionando"

    });
  }
);

// ===============================
// HEALTH CHECK
// ===============================

app.get(
  "/api/health",
  (req, res) => {

    res.json({

      ok: true,

      service:
        "dublagem-backend",

      status:
        "online"

    });
  }
);

// ===============================
// ENVIAR VÍDEO PARA DUBLAGEM
// ===============================

app.post(
  "/api/dublar",

  upload.single("video"),

  (req, res) => {

    try {

      if (!req.file) {

        return res
          .status(400)
          .json({

            error:
              "Nenhum vídeo foi enviado."

          });
      }

      // Idiomas aceitos
      const allowedLanguages = [
        "pt",
        "en",
        "es"
      ];

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
        "Erro no upload:",
        error
      );

      return res
        .status(500)
        .json({

          error:
            "Erro interno ao receber o vídeo."

        });
    }
  }
);

// ===============================
// CONSULTAR STATUS
// ===============================

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

          error:
            "Job não encontrado."

        });
    }

    return res.json({

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

// ===============================
// ERROS DO UPLOAD
// ===============================

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

          error:
            "O vídeo é maior que o limite permitido."

        });
    }

    if (error) {

      console.error(
        "Erro:",
        error
      );

      return res
        .status(500)
        .json({

          error:
            error.message ||
            "Erro interno do servidor."

        });
    }

    next();
  }
);

// ===============================
// LIMPEZA DE ARQUIVOS ANTIGOS
// ===============================

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

      // Remove depois de 1 hora

      if (
        now -
          job.createdAt >
        60 * 60 * 1000
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
            "Erro na limpeza:",
            error
          );
        }

        jobs.delete(
          jobId
        );
      }
    }

  },

  10 * 60 * 1000
);

// ===============================
// INICIAR SERVIDOR
// ===============================

app.listen(
  PORT,
  "0.0.0.0",
  () => {

    console.log(
      "================================="
    );

    console.log(
      `Servidor rodando na porta ${PORT}`
    );

    console.log(
      "Backend pronto."
    );

    console.log(
      "================================="
    );

  }
);
