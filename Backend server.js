// server.js
// Backend do site de dublagem automatica.
// Recebe um upload de video, coloca na fila, dispara o pipeline em Python
// (transcricao -> traducao -> geracao de voz -> remontagem com ffmpeg)
// e deixa o cliente consultar o status / baixar o resultado.

const express = require("express");
const multer = require("multer");
const cors = require("cors");
const { v4: uuidv4 } = require("uuid");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");

const app = express();
const PORT = process.env.PORT || 3000;

const UPLOAD_DIR = path.join(__dirname, "uploads");
const OUTPUT_DIR = path.join(__dirname, "outputs");
[UPLOAD_DIR, OUTPUT_DIR].forEach((d) => fs.mkdirSync(d, { recursive: true }));

app.use(cors());
app.use(express.json());
app.use("/outputs", express.static(OUTPUT_DIR));

// --- Armazenamento de upload -------------------------------------------------
const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOAD_DIR),
  filename: (req, file, cb) => {
    const id = uuidv4();
    req.jobId = id;
    cb(null, `${id}${path.extname(file.originalname)}`);
  },
});
const upload = multer({
  storage,
  limits: { fileSize: 2 * 1024 * 1024 * 1024 }, // 2GB, ajuste conforme seu servidor
});

// --- "Banco de dados" de jobs em memoria -------------------------------------
// Para producao, troque isso por Redis/Postgres. Aqui e so pra deixar o
// projeto rodando sem depender de infra extra.
const jobs = new Map();
// job = { id, status, progress, stage, inputPath, outputPath, targetLang, error }

// --- Fila simples (processa 1 job por vez) -----------------------------------
// Para escalar, troque por BullMQ + Redis, com N workers em paralelo.
const queue = [];
let processing = false;

function enqueue(jobId) {
  queue.push(jobId);
  processQueue();
}

function processQueue() {
  if (processing || queue.length === 0) return;
  processing = true;

  const jobId = queue.shift();
  const job = jobs.get(jobId);
  if (!job) {
    processing = false;
    return processQueue();
  }

  job.status = "processing";
  job.stage = "iniciando";

  const outputPath = path.join(OUTPUT_DIR, `${jobId}.mp4`);
  job.outputPath = outputPath;

  // Chama o worker Python, que faz: transcricao -> traducao -> tts -> merge.
  // Ele imprime linhas tipo "STAGE:transcrevendo" e "PROGRESS:40" em stdout
  // pra gente atualizar o status em tempo real.
  const py = spawn("python3", [
    path.join(__dirname, "worker", "pipeline.py"),
    "--input", job.inputPath,
    "--output", outputPath,
    "--target-lang", job.targetLang || "pt",
  ]);

  py.stdout.on("data", (data) => {
    data
      .toString()
      .split("\n")
      .filter(Boolean)
      .forEach((line) => {
        if (line.startsWith("STAGE:")) job.stage = line.replace("STAGE:", "").trim();
        if (line.startsWith("PROGRESS:")) job.progress = Number(line.replace("PROGRESS:", "").trim());
      });
  });

  let stderrBuf = "";
  py.stderr.on("data", (data) => {
    stderrBuf += data.toString();
  });

  py.on("close", (code) => {
    if (code === 0) {
      job.status = "done";
      job.progress = 100;
      job.stage = "concluido";
    } else {
      job.status = "error";
      job.error = stderrBuf.slice(-2000); // ultimos 2000 chars do erro
    }
    processing = false;
    processQueue();
  });
}

// --- Rotas --------------------------------------------------------------------

// Envia um video e um idioma de destino -> devolve um jobId pra acompanhar
app.post("/api/dublar", upload.single("video"), (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: "Nenhum arquivo de video enviado." });
  }

  const jobId = req.jobId;
  const targetLang = req.body.targetLang || "pt";

  jobs.set(jobId, {
    id: jobId,
    status: "queued",
    progress: 0,
    stage: "na fila",
    inputPath: req.file.path,
    outputPath: null,
    targetLang,
    error: null,
    createdAt: Date.now(),
  });

  enqueue(jobId);

  res.json({ jobId, status: "queued" });
});

// Consulta o status de um job
app.get("/api/status/:jobId", (req, res) => {
  const job = jobs.get(req.params.jobId);
  if (!job) return res.status(404).json({ error: "Job nao encontrado." });

  res.json({
    id: job.id,
    status: job.status,
    progress: job.progress,
    stage: job.stage,
    error: job.error,
    downloadUrl: job.status === "done" ? `/outputs/${job.id}.mp4` : null,
  });
});

app.get("/api/health", (req, res) => res.json({ ok: true }));

app.listen(PORT, () => {
  console.log(`Backend de dublagem rodando em http://localhost:${PORT}`);
});
