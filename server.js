const express = require("express");
const cors = require("cors");
const multer = require("multer");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");
const crypto = require("crypto");

// ============================================================
// APP
// ============================================================

const app = express();

const PORT = process.env.PORT || 10000;

// ============================================================
// DIRETÓRIOS
// ============================================================

const BASE_DIR = process.cwd();

const UPLOAD_DIR = path.join(BASE_DIR, "uploads");
const OUTPUT_DIR = path.join(BASE_DIR, "outputs");

fs.mkdirSync(UPLOAD_DIR, { recursive: true });
fs.mkdirSync(OUTPUT_DIR, { recursive: true });

// ============================================================
// CORS
// ============================================================

app.use(
    cors({
        origin: true,
        methods: [
            "GET",
            "POST",
            "OPTIONS"
        ],
        allowedHeaders: [
            "Content-Type",
            "Authorization"
        ],
        credentials: false
    })
);

// ============================================================
// BODY
// ============================================================

app.use(
    express.json({
        limit: "10mb"
    })
);

app.use(
    express.urlencoded({
        extended: true,
        limit: "10mb"
    })
);

// ============================================================
// MULTER
// ============================================================

const storage = multer.diskStorage({

    destination: function (req, file, cb) {
        cb(null, UPLOAD_DIR);
    },

    filename: function (req, file, cb) {

        const id = crypto.randomUUID();

        const extension =
            path.extname(file.originalname || ".mp4")
                .toLowerCase() || ".mp4";

        cb(
            null,
            id + extension
        );
    }
});

const upload = multer({

    storage,

    limits: {
        fileSize: 500 * 1024 * 1024
    },

    fileFilter: function (req, file, cb) {

        const allowed = [
            ".mp4",
            ".mov",
            ".webm",
            ".mkv",
            ".avi",
            ".m4v"
        ];

        const extension =
            path.extname(file.originalname || "")
                .toLowerCase();

        if (!allowed.includes(extension)) {

            return cb(
                new Error(
                    "Formato de vídeo não suportado. Use MP4, MOV, WebM, MKV, AVI ou M4V."
                )
            );
        }

        cb(null, true);
    }
});

// ============================================================
// JOBS
// ============================================================
//
// O Node NÃO espera o pipeline terminar.
// O pipeline roda em um processo separado.
// Isso permite que /api/status/:jobId continue respondendo.
//

const jobs = new Map();

// ============================================================
// FUNÇÕES AUXILIARES
// ============================================================

function createJob(id, inputPath, outputPath, targetLang) {

    const job = {

        id,

        status: "queued",

        stage: "Aguardando processamento",

        progress: 0,

        targetLang,

        inputPath,

        outputPath,

        outputUrl: null,

        error: null,

        pid: null,

        startedAt: null,

        finishedAt: null,

        logs: []
    };

    jobs.set(id, job);

    return job;
}


function addJobLog(job, message) {

    if (!job) {
        return;
    }

    const text = String(message || "").trim();

    if (!text) {
        return;
    }

    job.logs.push(text);

    // Não deixar memória crescer indefinidamente
    if (job.logs.length > 200) {
        job.logs.shift();
    }

    console.log(
        `[JOB ${job.id}] ${text}`
    );
}


function updateProgressFromLine(job, line) {

    const progressMatch =
        String(line).match(
            /PROGRESS\s*:\s*(\d+)/i
        );

    if (progressMatch) {

        const value = Number(
            progressMatch[1]
        );

        if (Number.isFinite(value)) {

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


    const stageMatch =
        String(line).match(
            /STAGE\s*:\s*(.+)/i
        );

    if (stageMatch) {

        job.stage =
            stageMatch[1].trim();

        job.status = "processing";
    }


    // Mostra mensagens úteis do pipeline
    if (
        line.includes("[PIPELINE]") ||
        line.includes("[PIPELINE ERROR]")
    ) {

        addJobLog(
            job,
            line
        );
    }
}


function safeDelete(filePath) {

    try {

        if (
            filePath &&
            fs.existsSync(filePath)
        ) {

            fs.unlinkSync(filePath);
        }

    } catch (error) {

        console.error(
            "Erro ao apagar arquivo:",
            error.message
        );
    }
}


// ============================================================
// HEALTH
// ============================================================

app.get(
    "/api/health",
    (req, res) => {

        res.status(200).json({

            ok: true,

            status: "online",

            service: "SI - Tradutor Universal",

            time: new Date().toISOString(),

            whisper:
                process.env.WHISPER_MODEL || "tiny",

            openai:
                Boolean(
                    process.env.OPENAI_API_KEY
                ),

            elevenlabs:
                Boolean(
                    process.env.ELEVENLABS_API_KEY
                )
        });
    }
);


// ============================================================
// ROOT
// ============================================================

app.get(
    "/",
    (req, res) => {

        res.status(200).json({

            ok: true,

            message:
                "SI - Tradutor Universal API",

            status: "online",

            endpoints: {

                health:
                    "/api/health",

                upload:
                    "POST /api/test-upload",

                status:
                    "GET /api/status/:jobId",

                video:
                    "GET /api/video/:jobId"
            }
        });
    }
);


// ============================================================
// UPLOAD + INICIAR PROCESSAMENTO
// ============================================================

app.post(
    "/api/test-upload",
    upload.single("video"),
    async (req, res) => {

        try {

            // ------------------------------------------------
            // Verificar arquivo
            // ------------------------------------------------

            if (!req.file) {

                return res.status(400).json({

                    ok: false,

                    error:
                        "Nenhum vídeo foi enviado."
                });
            }


            // ------------------------------------------------
            // Idioma
            // ------------------------------------------------

            const targetLang =
                String(
                    req.body.targetLang ||
                    req.body.targetLanguage ||
                    "pt"
                )
                    .trim()
                    .toLowerCase();


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
                "sv",
                "da",
                "no",
                "fi",
                "cs",
                "el",
                "he",
                "id",
                "vi",
                "th"
            ];


            if (
                !allowedLanguages.includes(
                    targetLang
                )
            ) {

                safeDelete(
                    req.file.path
                );

                return res.status(400).json({

                    ok: false,

                    error:
                        `Idioma não suportado: ${targetLang}`
                });
            }


            // ------------------------------------------------
            // ID DO JOB
            // ------------------------------------------------

            const jobId =
                crypto.randomUUID();


            const inputExtension =
                path.extname(
                    req.file.originalname || ".mp4"
                ).toLowerCase();


            const inputPath =
                req.file.path;


            const outputPath =
                path.join(
                    OUTPUT_DIR,
                    `${jobId}.mp4`
                );


            // ------------------------------------------------
            // Criar JOB
            // ------------------------------------------------

            const job =
                createJob(
                    jobId,
                    inputPath,
                    outputPath,
                    targetLang
                );


            job.status = "processing";

            job.stage =
                "Iniciando processamento";

            job.progress = 1;

            job.startedAt =
                new Date().toISOString();


            addJobLog(
                job,
                `Vídeo recebido: ${req.file.originalname}`
            );

            addJobLog(
                job,
                `Tamanho: ${req.file.size} bytes`
            );

            addJobLog(
                job,
                `Idioma de destino: ${targetLang}`
            );


            // ------------------------------------------------
            // Pipeline
            // ------------------------------------------------

            const pipelinePath =
                path.join(
                    BASE_DIR,
                    "pipeline.py"
                );


            if (
                !fs.existsSync(
                    pipelinePath
                )
            ) {

                safeDelete(
                    inputPath
                );

                jobs.delete(
                    jobId
                );

                return res.status(500).json({

                    ok: false,

                    error:
                        "pipeline.py não foi encontrado no servidor."
                });
            }


            // ------------------------------------------------
            // Python
            // ------------------------------------------------

            const pythonCommand =
                process.env.PYTHON_COMMAND ||
                "python3";


            const args = [

                pipelinePath,

                "--input",
                inputPath,

                "--output",
                outputPath,

                "--target-lang",
                targetLang
            ];


            addJobLog(
                job,
                `Executando: ${pythonCommand} ${args.join(" ")}`
            );


            // =================================================
            // IMPORTANTE
            //
            // spawn NÃO bloqueia o servidor Node.
            //
            // Enquanto o Python trabalha, o Node continua
            // atendendo:
            //
            // GET /api/status/:jobId
            //
            // =================================================

            const child =
                spawn(
                    pythonCommand,
                    args,
                    {

                        cwd: BASE_DIR,

                        env: {
                            ...process.env,

                            // Render Free:
                            // reduz consumo de threads
                            OMP_NUM_THREADS:
                                process.env.OMP_NUM_THREADS ||
                                "1",

                            OPENBLAS_NUM_THREADS:
                                process.env.OPENBLAS_NUM_THREADS ||
                                "1",

                            MKL_NUM_THREADS:
                                process.env.MKL_NUM_THREADS ||
                                "1",

                            NUMEXPR_NUM_THREADS:
                                process.env.NUMEXPR_NUM_THREADS ||
                                "1",

                            // Whisper tiny
                            WHISPER_MODEL:
                                process.env.WHISPER_MODEL ||
                                "tiny"
                        },

                        stdio: [
                            "ignore",
                            "pipe",
                            "pipe"
                        ]
                    }
                );


            job.pid =
                child.pid;


            addJobLog(
                job,
                `Processo Python iniciado. PID: ${child.pid}`
            );


            // =================================================
            // STDOUT
            // =================================================

            let stdoutBuffer = "";


            child.stdout.on(
                "data",
                (data) => {

                    const text =
                        data.toString();

                    stdoutBuffer += text;

                    const lines =
                        stdoutBuffer.split(
                            /\r?\n/
                        );

                    stdoutBuffer =
                        lines.pop() || "";


                    for (
                        const line
                        of lines
                    ) {

                        const clean =
                            line.trim();

                        if (!clean) {
                            continue;
                        }

                        console.log(
                            `[PIPELINE ${jobId}] ${clean}`
                        );

                        updateProgressFromLine(
                            job,
                            clean
                        );
                    }
                }
            );


            // =================================================
            // STDERR
            // =================================================
            //
            // Atenção:
            // Whisper pode escrever barra de download/progresso
            // no stderr mesmo sem ser um erro fatal.
            //
            // Portanto NÃO marcamos o job como failed apenas
            // porque chegou algo no stderr.
            //

            let stderrBuffer = "";


            child.stderr.on(
                "data",
                (data) => {

                    const text =
                        data.toString();

                    stderrBuffer += text;

                    const lines =
                        stderrBuffer.split(
                            /\r?\n/
                        );

                    stderrBuffer =
                        lines.pop() || "";


                    for (
                        const line
                        of lines
                    ) {

                        const clean =
                            line.trim();

                        if (!clean) {
                            continue;
                        }

                        console.error(
                            `[PIPELINE STDERR ${jobId}] ${clean}`
                        );

                        updateProgressFromLine(
                            job,
                            clean
                        );

                        // Guardamos apenas mensagens
                        // realmente importantes.
                        if (
                            clean.includes(
                                "[PIPELINE ERROR]"
                            ) ||
                            clean.includes(
                                "Traceback"
                            ) ||
                            clean.includes(
                                "Error"
                            ) ||
                            clean.includes(
                                "Exception"
                            )
                        ) {

                            addJobLog(
                                job,
                                clean
                            );
                        }
                    }
                }
            );


            // =================================================
            // ERROR DO PROCESSO
            // =================================================

            child.on(
                "error",
                (error) => {

                    console.error(
                        `[JOB ${jobId}] Erro ao iniciar Python:`,
                        error
                    );


                    job.status =
                        "failed";

                    job.stage =
                        "Erro ao iniciar processamento";

                    job.error =
                        error.message;

                    job.finishedAt =
                        new Date().toISOString();


                    addJobLog(
                        job,
                        `Falha ao iniciar pipeline: ${error.message}`
                    );
                }
            );


            // =================================================
            // FINALIZAÇÃO
            // =================================================

            child.on(
                "close",
                (code, signal) => {

                    // Processar qualquer texto restante
                    if (
                        stdoutBuffer.trim()
                    ) {

                        updateProgressFromLine(
                            job,
                            stdoutBuffer.trim()
                        );
                    }


                    if (
                        stderrBuffer.trim()
                    ) {

                        updateProgressFromLine(
                            job,
                            stderrBuffer.trim()
                        );
                    }


                    job.finishedAt =
                        new Date().toISOString();


                    // ------------------------------------------------
                    // SUCESSO
                    // ------------------------------------------------

                    if (
                        code === 0 &&
                        fs.existsSync(
                            outputPath
                        )
                    ) {

                        const outputSize =
                            fs.statSync(
                                outputPath
                            ).size;


                        if (
                            outputSize <= 0
                        ) {

                            job.status =
                                "failed";

                            job.stage =
                                "Vídeo final vazio";

                            job.error =
                                "O pipeline terminou, mas o vídeo final está vazio.";

                            addJobLog(
                                job,
                                job.error
                            );

                        } else {

                            job.status =
                                "completed";

                            job.stage =
                                "Concluído";

                            job.progress =
                                100;

                            job.outputUrl =
                                `/api/video/${jobId}`;

                            addJobLog(
                                job,
                                `Processamento concluído. Saída: ${outputSize} bytes`
                            );
                        }

                    }

                    // ------------------------------------------------
                    // FALHA
                    // ------------------------------------------------

                    else {

                        job.status =
                            "failed";

                        job.stage =
                            "Processamento falhou";

                        let reason =
                            `Pipeline finalizou com código ${code}`;

                        if (signal) {

                            reason +=
                                ` e sinal ${signal}`;
                        }

                        job.error =
                            reason;


                        addJobLog(
                            job,
                            reason
                        );


                        // ------------------------------------------------
                        // Diagnóstico específico do Render
                        // ------------------------------------------------

                        if (
                            code === 137
                        ) {

                            job.error =
                                "O processo foi encerrado pelo sistema (código 137). No Render Free isso geralmente indica falta de memória durante o processamento.";

                            addJobLog(
                                job,
                                job.error
                            );
                        }


                        if (
                            code === 143
                        ) {

                            job.error =
                                "O processo foi encerrado pelo sistema (código 143). O serviço pode ter sido reiniciado ou encerrado.";

                            addJobLog(
                                job,
                                job.error
                            );
                        }
                    }


                    // ------------------------------------------------
                    // Remover vídeo original depois que terminou
                    // ------------------------------------------------

                    setTimeout(
                        () => {

                            safeDelete(
                                inputPath
                            );

                        },
                        60 * 1000
                    );
                }
            );


            // =================================================
            // RESPONDER IMEDIATAMENTE AO NAVEGADOR
            // =================================================

            return res.status(202).json({

                ok: true,

                jobId,

                status:
                    job.status,

                stage:
                    job.stage,

                progress:
                    job.progress,

                targetLang,

                message:
                    "Vídeo recebido e processamento iniciado.",

                statusUrl:
                    `/api/status/${jobId}`,

                outputUrl:
                    null
            });


        } catch (error) {

            console.error(
                "Erro no upload:",
                error
            );


            if (
                req.file &&
                req.file.path
            ) {

                safeDelete(
                    req.file.path
                );
            }


            return res.status(500).json({

                ok: false,

                error:
                    error.message ||
                    "Erro interno no servidor."
            });
        }
    }
);


// ============================================================
// STATUS DO JOB
// ============================================================

app.get(
    "/api/status/:jobId",
    (req, res) => {

        const jobId =
            req.params.jobId;


        const job =
            jobs.get(jobId);


        if (!job) {

            return res.status(404).json({

                ok: false,

                error:
                    "Job não encontrado ou o servidor foi reiniciado.",

                jobId
            });
        }


        // ----------------------------------------------------
        // Não enviar caminhos internos do servidor
        // ----------------------------------------------------

        return res.status(200).json({

            ok: true,

            jobId:
                job.id,

            status:
                job.status,

            stage:
                job.stage,

            progress:
                job.progress,

            targetLang:
                job.targetLang,

            outputUrl:
                job.outputUrl,

            error:
                job.error,

            startedAt:
                job.startedAt,

            finishedAt:
                job.finishedAt,

            pid:
                job.pid,

            logs:
                job.logs.slice(-30)
        });
    }
);


// ============================================================
// VÍDEO FINAL
// ============================================================

app.get(
    "/api/video/:jobId",
    (req, res) => {

        const jobId =
            req.params.jobId;


        const job =
            jobs.get(jobId);


        if (!job) {

            return res.status(404).json({

                ok: false,

                error:
                    "Job não encontrado."
            });
        }


        if (
            job.status !== "completed"
        ) {

            return res.status(409).json({

                ok: false,

                error:
                    "O vídeo ainda não está pronto.",

                status:
                    job.status,

                progress:
                    job.progress
            });
        }


        if (
            !job.outputPath ||
            !fs.existsSync(
                job.outputPath
            )
        ) {

            return res.status(404).json({

                ok: false,

                error:
                    "O vídeo final não foi encontrado no servidor."
            });
        }


        const stat =
            fs.statSync(
                job.outputPath
            );


        // =====================================================
        // SUPORTE A RANGE
        // Permite reprodução/download no celular.
        // =====================================================

        const range =
            req.headers.range;


        if (!range) {

            res.setHeader(
                "Content-Type",
                "video/mp4"
            );

            res.setHeader(
                "Content-Length",
                stat.size
            );

            res.setHeader(
                "Accept-Ranges",
                "bytes"
            );

            res.setHeader(
                "Cache-Control",
                "no-cache"
            );

            return fs
                .createReadStream(
                    job.outputPath
                )
                .pipe(res);
        }


        const parts =
            range
                .replace(
                    /bytes=/,
                    ""
                )
                .split("-");


        const start =
            parseInt(
                parts[0],
                10
            );


        const end =
            parts[1]
                ? parseInt(
                    parts[1],
                    10
                )
                : stat.size - 1;


        if (
            Number.isNaN(start) ||
            start >= stat.size ||
            end >= stat.size
        ) {

            res.status(416);

            res.setHeader(
                "Content-Range",
                `bytes */${stat.size}`
            );

            return res.end();
        }


        const chunkSize =
            end - start + 1;


        res.status(206);

        res.setHeader(
            "Content-Range",
            `bytes ${start}-${end}/${stat.size}`
        );

        res.setHeader(
            "Accept-Ranges",
            "bytes"
        );

        res.setHeader(
            "Content-Length",
            chunkSize
        );

        res.setHeader(
            "Content-Type",
            "video/mp4"
        );

        res.setHeader(
            "Cache-Control",
            "no-cache"
        );


        fs
            .createReadStream(
                job.outputPath,
                {
                    start,
                    end
                }
            )
            .pipe(res);
    }
);


// ============================================================
// DOWNLOAD
// ============================================================

app.get(
    "/api/download/:jobId",
    (req, res) => {

        const job =
            jobs.get(
                req.params.jobId
            );


        if (!job) {

            return res.status(404).json({

                ok: false,

                error:
                    "Job não encontrado."
            });
        }


        if (
            job.status !== "completed"
        ) {

            return res.status(409).json({

                ok: false,

                error:
                    "O vídeo ainda está sendo processado."
            });
        }


        if (
            !fs.existsSync(
                job.outputPath
            )
        ) {

            return res.status(404).json({

                ok: false,

                error:
                    "Arquivo final não encontrado."
            });
        }


        res.download(
            job.outputPath,
            `SI-dublado-${job.id}.mp4`
        );
    }
);


// ============================================================
// ERRO DO MULTER
// ============================================================

app.use(
    (error, req, res, next) => {

        if (
            error instanceof multer.MulterError
        ) {

            if (
                error.code ===
                "LIMIT_FILE_SIZE"
            ) {

                return res.status(413).json({

                    ok: false,

                    error:
                        "O vídeo é muito grande. O limite é 500 MB."
                });
            }


            return res.status(400).json({

                ok: false,

                error:
                    error.message
            });
        }


        if (error) {

            console.error(
                "Erro geral:",
                error
            );


            return res.status(500).json({

                ok: false,

                error:
                    error.message ||
                    "Erro interno do servidor."
            });
        }


        next();
    }
);


// ============================================================
// 404
// ============================================================

app.use(
    (req, res) => {

        res.status(404).json({

            ok: false,

            error:
                "Endpoint não encontrado.",

            path:
                req.originalUrl
        });
    }
);


// ============================================================
// SERVIDOR
// ============================================================

const server =
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
                `Modo vídeo: ATIVO`
            );

            console.log(
                `Polling de status: ATIVO`
            );

            console.log(
                `Whisper: ${
                    process.env.WHISPER_MODEL ||
                    "tiny"
                }`
            );

            console.log(
                `OpenAI: ${
                    process.env.OPENAI_API_KEY
                        ? "CONFIGURADO"
                        : "NÃO CONFIGURADO"
                }`
            );

            console.log(
                `ElevenLabs: ${
                    process.env.ELEVENLABS_API_KEY
                        ? "CONFIGURADO"
                        : "NÃO CONFIGURADO"
                }`
            );

            console.log(
                "========================================"
            );
        }
    );


// ============================================================
// TIMEOUTS
// ============================================================
//
// Não deixar o Node matar uploads/processamentos HTTP
// por timeout curto.
//

server.timeout =
    30 * 60 * 1000;

server.requestTimeout =
    30 * 60 * 1000;

server.headersTimeout =
    31 * 60 * 1000;

server.keepAliveTimeout =
    65 * 1000;


// ============================================================
// LIMPEZA AUTOMÁTICA DE JOBS
// ============================================================
//
// Mantém memória do Render Free sob controle.
// Jobs antigos são removidos depois de 2 horas.
//

setInterval(
    () => {

        const now =
            Date.now();

        const MAX_AGE =
            2 * 60 * 60 * 1000;


        for (
            const [
                jobId,
                job
            ] of jobs.entries()
        ) {

            const referenceTime =
                job.finishedAt ||
                job.startedAt;


            if (!referenceTime) {
                continue;
            }


            const age =
                now -
                new Date(
                    referenceTime
                ).getTime();


            if (
                age > MAX_AGE
            ) {

                console.log(
                    `[CLEANUP] Removendo job ${jobId}`
                );


                safeDelete(
                    job.inputPath
                );

                safeDelete(
                    job.outputPath
                );


                jobs.delete(
                    jobId
                );
            }
        }

    },
    10 * 60 * 1000
);


// ============================================================
// ENCERRAMENTO SEGURO
// ============================================================

function gracefulShutdown(
    signal
) {

    console.log(
        `Recebido ${signal}. Encerrando servidor...`
    );


    server.close(
        () => {

            console.log(
                "Servidor encerrado."
            );

            process.exit(0);
        }
    );


    setTimeout(
        () => {

            process.exit(1);

        },
        10000
    );
}


process.on(
    "SIGTERM",
    () => gracefulShutdown("SIGTERM")
);

process.on(
    "SIGINT",
    () => gracefulShutdown("SIGINT")
);
