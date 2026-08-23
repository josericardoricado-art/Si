// server.js
// Backend do site de dublagem automática.
// Agora com login de usuários: cada pessoa só vê e baixa os próprios vídeos.

const express = require("express");
const multer = require("multer");
const cors = require("cors");
const { v4: uuidv4 } = require("uuid");
const path = require("path");
const fs = require("fs");
const bcrypt = require("bcryptjs");
const { spawn } = require("child_process");

const db = require("./db");
const { generateToken, requireAuth } = require("./auth");

const app = express();
const PORT = process.env.PORT || 3000;

const UPLOAD_DIR = path.join(__dirname, "uploads");
const OUTPUT_DIR = path.join(__dirname, "outputs");
[UPLOAD_DIR, OUTPUT_DIR].forEach((d) => fs.mkdirSync(d, { recursive: true }));

app.use(cors());
app.use(express.json());

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

// Serve os vídeos de saída SÓ para quem está autenticado e é dono do job
app.get("/outputs/:filename", requireAuth, (req, res) => {
  const jobId = req.params.filename.replace(".mp4", "");
  const job = db.prepare("SELECT * FROM jobs WHERE id = ?").get(jobId);

  if (!job) return res.status(404).json({ error: "Vídeo não encontrado." });
  if (job.user_id !== req.user.id) {
    return res.status(403).json({ error: "Você não tem permissão para acessar este vídeo." });
  }

  res.sendFile(path.join(OUTPUT_DIR, `${jobId}.mp4`));
});

// --- Fila simples (processa 1 job por vez) -----------------------------------
// Para escalar, troque por BullMQ + Redis, com N workers em paralelo.
const queue = [];
let processing = false;

function updateJob(jobId, fields) {
  const keys = Object.keys(fields);
  const setClause = keys.map((k) => `${k} = ?`).join(", ");
  const values = keys.map((k) => fields[k]);
  db.prepare(`UPDATE jobs SET ${setClause} WHERE id = ?`).run(...values, jobId);
}

function enqueue(jobId) {
  queue.push(jobId);
  processQueue();
}

function processQueue() {
  if (processing || queue.length === 0) return;
  processing = true;

  const jobId = queue.shift();
  const job = db.prepare("SELECT * FROM jobs WHERE id = ?").get(jobId);
  if (!job) {
    processing = false;
    return processQueue();
  }

  updateJob(jobId, { status: "processing", stage: "iniciando" });

  const outputPath = path.join(OUTPUT_DIR, `${jobId}.mp4`);

  const py = spawn("python3", [
    path.join(__dirname, "worker", "pipeline.py"),
    "--input", job.input_path,
    "--output", outputPath,
    "--target-lang", job.target_lang || "pt",
  ]);

  py.stdout.on("data", (data) => {
    data
      .toString()
      .split("\n")
      .filter(Boolean)
      .forEach((line) => {
        if (line.startsWith("STAGE:")) updateJob(jobId, { stage: line.replace("STAGE:", "").trim() });
        if (line.startsWith("PROGRESS:")) updateJob(jobId, { progress: Number(line.replace("PROGRESS:", "").trim()) });
      });
  });

  let stderrBuf = "";
  py.stderr.on("data", (data) => {
    stderrBuf += data.toString();
  });

  py.on("close", (code) => {
    if (code === 0) {
      updateJob(jobId, { status: "done", progress: 100, stage: "concluido", output_path: outputPath });
    } else {
      updateJob(jobId, { status: "error", error: stderrBuf.slice(-2000) });
    }
    processing = false;
    processQueue();
  });
}

// --- Rotas de autenticação -----------------------------------------------------

app.post("/api/register", (req, res) => {
  const { name, email, password } = req.body;

  if (!name || !email || !password) {
    return res.status(400).json({ error: "Preencha nome, email e senha." });
  }
  if (password.length < 6) {
    return res.status(400).json({ error: "A senha precisa ter pelo menos 6 caracteres." });
  }

  const existing = db.prepare("SELECT id FROM users WHERE email = ?").get(email);
  if (existing) {
    return res.status(409).json({ error: "Já existe uma conta com esse email." });
  }

  const id = uuidv4();
  const passwordHash = bcrypt.hashSync(password, 10);

  db.prepare(
    "INSERT INTO users (id, name, email, password_hash, created_at) VALUES (?, ?, ?, ?, ?)"
  ).run(id, name, email, passwordHash, Date.now());

  const user = { id, name, email };
  const token = generateToken(user);

  res.json({ token, user });
});

app.post("/api/login", (req, res) => {
  const { email, password } = req.body;

  if (!email || !password) {
    return res.status(400).json({ error: "Preencha email e senha." });
  }

  const user = db.prepare("SELECT * FROM users WHERE email = ?").get(email);
  if (!user) {
    return res.status(401).json({ error: "Email ou senha incorretos." });
  }

  const passwordMatches = bcrypt.compareSync(password, user.password_hash);
  if (!passwordMatches) {
    return res.status(401).json({ error: "Email ou senha incorretos." });
  }

  const token = generateToken(user);
  res.json({ token, user: { id: user.id, name: user.name, email: user.email } });
});

app.get("/api/me", requireAuth, (req, res) => {
  res.json({ user: req.user });
});

// --- Rotas de dublagem (protegidas por login) ----------------------------------

app.post("/api/dublar", requireAuth, upload.single("video"), (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: "Nenhum arquivo de vídeo enviado." });
  }

  const jobId = req.jobId;
  const targetLang = req.body.targetLang || "pt";

  db.prepare(
    `INSERT INTO jobs (id, user_id, status, progress, stage, input_path, output_path, target_lang, original_filename, error, created_at)
     VALUES (?, ?, 'queued', 0, 'na fila', ?, NULL, ?, ?, NULL, ?)`
  ).run(jobId, req.user.id, req.file.path, targetLang, req.file.originalname, Date.now());

  enqueue(jobId);

  res.json({ jobId, status: "queued" });
});

// Status de um job específico (só o dono pode ver)
app.get("/api/status/:jobId", requireAuth, (req, res) => {
  const job = db.prepare("SELECT * FROM jobs WHERE id = ?").get(req.params.jobId);
  if (!job) return res.status(404).json({ error: "Job não encontrado." });
  if (job.user_id !== req.user.id) {
    return res.status(403).json({ error: "Você não tem permissão para ver este job." });
  }

  res.json({
    id: job.id,
    status: job.status,
    progress: job.progress,
    stage: job.stage,
    error: job.error,
    downloadUrl: job.status === "done" ? `/outputs/${job.id}.mp4` : null,
  });
});

// Lista todos os vídeos (jobs) do usuário logado
app.get("/api/meus-videos", requireAuth, (req, res) => {
  const jobs = db
    .prepare("SELECT * FROM jobs WHERE user_id = ? ORDER BY created_at DESC")
    .all(req.user.id);

  res.json({
    videos: jobs.map((job) => ({
      id: job.id,
      status: job.status,
      progress: job.progress,
      stage: job.stage,
      originalFilename: job.original_filename,
      targetLang: job.target_lang,
      createdAt: job.created_at,
      downloadUrl: job.status === "done" ? `/outputs/${job.id}.mp4` : null,
      error: job.error,
    })),
  });
});

app.get("/api/health", (req, res) => res.json({ ok: true }));

app.listen(PORT, () => {
  console.log(`Backend de dublagem rodando em http://localhost:${PORT}`);
});
