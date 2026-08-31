// server.js
// SI - Tradutor Universal
// Backend para upload, tradução e dublagem de vídeos

const express = require("express");
const cors = require("cors");
const multer = require("multer");
const { v4: uuidv4 } = require("uuid");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");

const app = express();

// ======================================================
// PORTA
// ======================================================

const PORT = process.env.PORT || 10000;

// ======================================================
// DIRETÓRIOS
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
// CONFIGURAÇÃO DO UPLOAD DE VÍDEO
// ======================================================

const videoStorage = multer.diskStorage({

    destination: function (req, file, cb) {
        cb(null, UPLOAD_DIR);
    },

    filename: function (req, file, cb) {

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
        fileSize: 2 * 1024 * 1024 * 1024
    }

});

// ======================================================
// UPLOAD DE ÁUDIO LIVE
// ======================================================

const liveStorage = multer.diskStorage({

    destination: function (req, file, cb) {
        cb(null, LIVE_DIR);
    },

    filename: function (req, file, cb) {

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
        fileSize: 25 * 1024 * 1024
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

    if (process.platform === "win32") {
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
// PROCESSAR FILA
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

    const outputPath = path.join(
        OUTPUT_DIR,
        `${jobId}.mp4`
    );

    job.outputPath = outputPath;

    const pipelinePath = path.join(
        __dirname,
        "pipeline.py"
    );

    console.log("========================================");
    console.log("PROCESSANDO VÍDEO");
    console.log("Job:", jobId);
    console.log("Entrada:", job.inputPath);
    console.log("Saída:", outputPath);
    console.log("Pipeline:", pipelinePath);
    console.log("========================================");

    if (!fs.existsSync(pipelinePath)) {

        job.status = "error";
        job.stage = "Erro";
        job.error = "pipeline.py não foi encontrado.";

        processing = false;

        processQueue();

        return;
    }

    const pythonCommand =
        getPythonCommand();

    const args = [
        pipelinePath,
        "--input",
        job.inputPath,
        "--output",
        outputPath,
        "--target-lang",
        job.targetLang
    ];

    console.log(
        "Executando:",
        pythonCommand,
        args.join(" ")
    );

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
    let stdoutBuffer = "";

    py.stdout.on(
        "data",
        function (data) {

            const text =
                data.toString();

            stdoutBuffer += text;

            console.log(
                "[PIPELINE]",
                text.trim()
            );

            const lines =
                text.split(/\r?\n/);

            lines.forEach(
                function (line) {

                    if (
                        line.startsWith("STAGE:")
                    ) {

                        job.stage =
                            line
                                .replace("STAGE:", "")
                                .trim();
                    }

                    if (
                        line.startsWith("PROGRESS:")
                    ) {

                        const value =
                            Number(
                                line
                                    .replace("PROGRESS:", "")
                                    .trim()
                            );

                        if (!Number.isNaN(value)) {

                            job.progress =
                                Math.max(
                                    0,
                                    Math.min(100, value)
                                );
                        }
                    }
                }
            );
        }
    );

    py.stderr.on(
        "data",
        function (data) {

            const text =
                data.toString();

            stderrBuffer += text;

            if (stderrBuffer.length > 15000) {

                stderrBuffer =
                    stderrBuffer.slice(-15000);
            }

            console.error(
                "[PIPELINE ERROR]",
                text.trim()
            );
        }
    );

    py.on(
        "error",
        function (error) {

            console.error(
                "Erro iniciando Python:",
                error
            );

            job.status = "error";
            job.stage = "Erro";
            job.error = error.message;

            processing = false;

            processQueue();
        }
    );

    py.on(
        "close",
        function (code) {

            console.log(
                "Pipeline terminou com código:",
                code
            );

            // ------------------------------------------
            // Código 0 NÃO significa que o arquivo existe.
            // Verificamos o arquivo obrigatoriamente.
            // ------------------------------------------

            if (
                code === 0 &&
                fs.existsSync(outputPath) &&
                fs.statSync(outputPath).size > 0
            ) {

                job.status = "done";
                job.progress = 100;
                job.stage = "Concluído";
                job.error = null;

                console.log(
                    "VÍDEO FINAL CRIADO:",
                    outputPath
                );

            } else {

                job.status = "error";
                job.stage = "Erro";

                if (code === 0) {

                    job.error =
                        "O pipeline terminou com código 0, mas não criou o arquivo final em: " +
                        outputPath;

                } else {

                    job.error =
                        stderrBuffer.trim() ||
                        `Pipeline terminou com código ${code}.`;
                }

                console.error(
                    "ERRO FINAL:",
                    job.error
                );
            }

            processing = false;

            processQueue();
        }
    );
}

// ======================================================
// HOME
// ======================================================

app.get(
    "/",
    function (req, res) {

        res.json({

            ok: true,

            service:
                "si-tradutor-backend",

            message:
                "Backend do SI funcionando",

            version:
                "4.0"
        });
    }
);

// ======================================================
// HEALTH
// ======================================================

app.get(
    "/api/health",
    function (req, res) {

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
// TESTE DE UPLOAD
// ======================================================

app.post(
    "/api/test-upload",
    uploadVideo.single("video"),

    function (req, res) {

        try {

            if (!req.file) {

                return res
                    .status(400)
                    .json({

                        ok: false,

                        error:
                            "Nenhum arquivo recebido."
                    });
            }

            console.log("========================================");
            console.log("TESTE DE UPLOAD");
            console.log("Nome:", req.file.originalname);
            console.log("Tamanho:", req.file.size);
            console.log("Tipo:", req.file.mimetype);
            console.log("Arquivo:", req.file.path);
            console.log("========================================");

            return res.json({

                ok: true,

                message:
                    "Upload funcionando!",

                filename:
                    req.file.filename,

                originalname:
                    req.file.originalname,

                size:
                    req.file.size,

                mimetype:
                    req.file.mimetype
            });

        } catch (error) {

            console.error(
                "TEST UPLOAD ERROR:",
                error
            );

            return res
                .status(500)
                .json({

                    ok: false,

                    error:
                        error.message
                });
        }
    }
);

// ======================================================
// DUBLAR VÍDEO
// ======================================================

app.post(
    "/api/dublar",
    uploadVideo.single("video"),

    function (req, res) {

        try {

            console.log("========================================");
            console.log("NOVO UPLOAD DE VÍDEO");
            console.log("========================================");

            if (!req.file) {

                return res
                    .status(400)
                    .json({

                        ok: false,

                        error:
                            "Nenhum vídeo foi enviado."
                    });
            }

            console.log(
                "Arquivo:",
                req.file.originalname
            );

            console.log(
                "Tamanho:",
                req.file.size
            );

            console.log(
                "Tipo:",
                req.file.mimetype
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
// STATUS DO JOB
// ======================================================

app.get(
    "/api/status/:jobId",

    function (req, res) {

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
// INICIAR LIVE
// ======================================================

app.post(
    "/api/live/start",

    function (req, res) {

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

        return res.json({

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

    function (req, res) {

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
                !liveSessions.has(sessionId)
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

            session.chunks += 1;

            session.lastAudio =
                Date.now();

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

            if (
                !fs.existsSync(livePipeline)
            ) {

                return res
                    .status(500)
                    .json({

                        ok:
                            false,

                        error:
                            "live_pipeline.py não foi encontrado."
                    });
            }

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
                "Executando live_pipeline.py"
            );

            const py =
                spawn(
                    getPythonCommand(),
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

            let stdoutBuffer = "";
            let stderrBuffer = "";

            py.stdout.on(
                "data",
                function (data) {

                    const text =
                        data.toString();

                    stdoutBuffer += text;

                    console.log(
                        "[LIVE PYTHON]",
                        text.trim()
                    );
                }
            );

            py.stderr.on(
                "data",
                function (data) {

                    const text =
                        data.toString();

                    stderrBuffer += text;

                    console.error(
                        "[LIVE PYTHON ERROR]",
                        text.trim()
                    );
                }
            );

            py.on(
                "error",
                function (error) {

                    console.error(
                        "Erro no live_pipeline:",
                        error
                    );
                }
            );

            py.on(
                "close",
                function (code) {

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

                    if (code !== 0) {

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

                    let text = "";
                    let translation = "";

                    const lines =
                        stdoutBuffer.split(/\r?\n/);

                    lines.forEach(
                        function (line) {

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

                    let audioUrl = null;

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
                "ERRO LIVE:",
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

    function (req, res) {

        const sessionId =
            req.body.sessionId;

        if (sessionId) {

            liveSessions.delete(
                sessionId
            );
        }

        return res.json({

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
    function (error, req, res, next) {

        if (
            error &&
            error.code === "LIMIT_FILE_SIZE"
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
                "ERRO DO SERVIDOR:",
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
// 404
// ======================================================

app.use(
    function (req, res) {

        return res
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
// LIMPEZA AUTOMÁTICA
// ======================================================

setInterval(
    function () {

        const now =
            Date.now();

        // Jobs com mais de 1 hora

        for (
            const [jobId, job]
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
                        "Erro na limpeza:",
                        error
                    );
                }

                jobs.delete(
                    jobId
                );
            }
        }

        // Sessões LIVE antigas

        for (
            const [sessionId, session]
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
    10 * 60 * 1000
);

// ======================================================
// SERVIDOR
// ======================================================

app.listen(
    PORT,
    "0.0.0.0",
    function () {

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
            "ElevenLabs: " +
            (
                process.env.ELEVENLABS_API_KEY
                    ? "CONFIGURADO"
                    : "NÃO CONFIGURADO"
            )
        );

        console.log(
            "========================================"
        );
    }
);
