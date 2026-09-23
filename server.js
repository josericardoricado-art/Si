const express = require("express");
const cors = require("cors");
const http = require("http");
const WebSocket = require("ws");
const crypto = require("crypto");

const app = express();
const server = http.createServer(app);

app.use(
  cors({
    origin: "*",
    methods: ["GET", "POST", "OPTIONS"],
    allowedHeaders: ["Content-Type", "Authorization"]
  })
);

app.use(express.json({ limit: "25mb" }));
app.use(express.urlencoded({ extended: true, limit: "25mb" }));

const PORT = Number(process.env.PORT || 10000);

const DEEPL_API_KEY =
  process.env.DEEPL_API_KEY || "";

const DEEPGRAM_API_KEY =
  process.env.DEEPGRAM_API_KEY || "";

const DEEPL_API_URL =
  DEEPL_API_KEY.includes(":fx")
    ? "https://api-free.deepl.com/v2/translate"
    : "https://api.deepl.com/v2/translate";

const DEEPGRAM_URL =
  "wss://api.deepgram.com/v2/listen" +
  "?model=flux-general-multi" +
  "&encoding=linear16" +
  "&sample_rate=16000" +
  "&eot_timeout_ms=1200";

const sessions = new Map();

const LANGUAGES = {
  "pt-BR": "PT-BR",
  "en-US": "EN-US",
  "es-ES": "ES",
  "fr-FR": "FR",
  "de-DE": "DE",
  "it-IT": "IT",
  "ja-JP": "JA",
  "ko-KR": "KO",
  "zh-CN": "ZH",
  "ru-RU": "RU",
  "ar-SA": "AR",
  "hi-IN": "HI",
  "tr-TR": "TR",
  "nl-NL": "NL",
  "pl-PL": "PL",
  "uk-UA": "UK",
  "th-TH": "TH",
  "id-ID": "ID",
  "vi-VN": "VI"
};

function novoId() {
  return crypto.randomUUID();
}

function normalizarIdioma(lang) {
  if (!lang) {
    return "PT-BR";
  }

  if (LANGUAGES[lang]) {
    return LANGUAGES[lang];
  }

  const base =
    String(lang)
      .toLowerCase()
      .split("-")[0];

  const mapa = {
    pt: "PT-BR",
    en: "EN-US",
    es: "ES",
    fr: "FR",
    de: "DE",
    it: "IT",
    ja: "JA",
    ko: "KO",
    zh: "ZH",
    ru: "RU",
    ar: "AR",
    hi: "HI",
    tr: "TR",
    nl: "NL",
    pl: "PL",
    uk: "UK",
    th: "TH",
    id: "ID",
    vi: "VI"
  };

  return mapa[base] || "PT-BR";
}

function diagnostico(session, etapa, extra = {}) {
  if (!session) {
    return;
  }

  session.diagnostics.push({
    etapa,
    hora: new Date().toISOString(),
    ...extra
  });

  if (session.diagnostics.length > 100) {
    session.diagnostics =
      session.diagnostics.slice(-100);
  }
}

async function traduzirDeepL(texto, idioma) {
  if (!DEEPL_API_KEY) {
    throw new Error(
      "DEEPL_API_KEY não configurada no Render."
    );
  }

  if (!texto || !String(texto).trim()) {
    return "";
  }

  const resposta = await fetch(
    DEEPL_API_URL,
    {
      method: "POST",
      headers: {
        "Authorization":
          `DeepL-Auth-Key ${DEEPL_API_KEY}`,
        "Content-Type":
          "application/json"
      },
      body: JSON.stringify({
        text: [String(texto)],
        target_lang: idioma
      })
    }
  );

  const dados =
    await resposta.json();

  if (!resposta.ok) {
    throw new Error(
      dados?.message ||
      `DeepL HTTP ${resposta.status}`
    );
  }

  return (
    dados?.translations?.[0]?.text ||
    ""
  );
}

function enviarWS(session, objeto) {
  if (!session) {
    return;
  }

  const mensagem =
    JSON.stringify(objeto);

  for (
    const cliente of session.clients
  ) {
    try {
      if (
        cliente.readyState ===
        WebSocket.OPEN
      ) {
        cliente.send(mensagem);
      }
    } catch (_) {}
  }
}

function criarSessao(clientId, targetLang) {
  const jobId = novoId();

  const session = {
    jobId,
    clientId:
      clientId || "android",
    targetLang:
      normalizarIdioma(targetLang),

    status: "starting",

    deepgram: null,
    deepgramConnected: false,

    clients: new Set(),

    chunksRecebidos: 0,
    bytesRecebidos: 0,

    sourceText: "",
    translatedText: "",

    diagnostics: [],

    createdAt:
      new Date().toISOString(),

    updatedAt:
      new Date().toISOString(),

    stopped: false
  };

  sessions.set(
    jobId,
    session
  );

  diagnostico(
    session,
    "session_created"
  );

  return session;
}

function atualizar(
  session,
  dados
) {
  Object.assign(
    session,
    dados
  );

  session.updatedAt =
    new Date().toISOString();
}
function conectarDeepgram(session) {
  if (!DEEPGRAM_API_KEY) {
    console.error(
      "[DEEPGRAM] DEEPGRAM_API_KEY não configurada."
    );

    atualizar(session, {
      status: "deepgram_key_missing"
    });

    return;
  }

  if (session.stopped) {
    return;
  }

  console.log(
    `[DEEPGRAM] Conectando ${session.jobId}`
  );

  diagnostico(
    session,
    "deepgram_connecting"
  );

  const dg =
    new WebSocket(
      DEEPGRAM_URL,
      {
        headers: {
          Authorization:
            `Token ${DEEPGRAM_API_KEY}`
        }
      }
    );

  session.deepgram = dg;

  dg.on("open", () => {
    session.deepgramConnected =
      true;

    atualizar(session, {
      status:
        "deepgram_connected"
    });

    diagnostico(
      session,
      "deepgram_connected"
    );

    console.log(
      `[DEEPGRAM] Conectado ${session.jobId}`
    );

    enviarWS(session, {
      type:
        "deepgram_connected",
      jobId:
        session.jobId
    });
  });

  dg.on(
    "message",
    async mensagem => {
      try {
        const dados =
          JSON.parse(
            mensagem.toString()
          );

        if (
          dados.type !==
          "TurnInfo"
        ) {
          return;
        }

        const texto =
          String(
            dados.transcript || ""
          ).trim();

        if (!texto) {
          return;
        }

        console.log(
          `[DEEPGRAM] ${dados.event}: ${texto}`
        );

        if (
          dados.event ===
          "Update"
        ) {
          atualizar(session, {
            status:
              "transcribing",
            sourceText:
              texto
          });

          enviarWS(session, {
            type:
              "transcript",
            jobId:
              session.jobId,
            text:
              texto,
            final:
              false,
            languages:
              dados.languages ||
              []
          });

          return;
        }

        if (
          dados.event ===
          "EndOfTurn"
        ) {
          atualizar(session, {
            status:
              "transcript_final",
            sourceText:
              texto
          });

          enviarWS(session, {
            type:
              "transcript",
            jobId:
              session.jobId,
            text:
              texto,
            final:
              true,
            languages:
              dados.languages ||
              []
          });

          try {
            atualizar(session, {
              status:
                "translating"
            });

            const traducao =
              await traduzirDeepL(
                texto,
                session.targetLang
              );

            atualizar(session, {
              status:
                "translated",
              translatedText:
                traducao
            });

            console.log(
              `[DEEPL] ${traducao}`
            );

            enviarWS(session, {
              type:
                "translation",
              jobId:
                session.jobId,
              sourceText:
                texto,
              translatedText:
                traducao,
              targetLang:
                session.targetLang
            });

          } catch (erro) {
            console.error(
              "[DEEPL] Erro:",
              erro.message
            );

            atualizar(session, {
              status:
                "translation_error"
            });

            enviarWS(session, {
              type:
                "error",
              jobId:
                session.jobId,
              stage:
                "deepl",
              error:
                erro.message
            });
          }
        }

      } catch (erro) {
        console.error(
          "[DEEPGRAM] Erro:",
          erro.message
        );
      }
    }
  );

  dg.on(
    "close",
    (codigo, motivo) => {
      session.deepgramConnected =
        false;

      session.deepgram =
        null;

      console.log(
        `[DEEPGRAM] Fechado ${session.jobId} ` +
        `code=${codigo}`
      );

      atualizar(session, {
        status:
          "deepgram_disconnected"
      });

      diagnostico(
        session,
        "deepgram_closed",
        {
          codigo
        }
      );

      enviarWS(session, {
        type:
          "deepgram_disconnected",
        jobId:
          session.jobId,
        code:
          codigo
      });
    }
  );

  dg.on(
    "error",
    erro => {
      session.deepgramConnected =
        false;

      console.error(
        "[DEEPGRAM] WebSocket error:",
        erro.message
      );

      atualizar(session, {
        status:
          "deepgram_error"
      });

      diagnostico(
        session,
        "deepgram_error",
        {
          error:
            erro.message
        }
      );

      enviarWS(session, {
        type:
          "error",
        jobId:
          session.jobId,
        stage:
          "deepgram",
        error:
          erro.message
      });
    }
  );
}

function enviarAudioDeepgram(
  session,
  buffer
) {
  if (
    !session ||
    !session.deepgram
  ) {
    return false;
  }

  if (
    session.deepgram.readyState !==
    WebSocket.OPEN
  ) {
    return false;
  }

  try {
    session.deepgram.send(
      buffer
    );

    return true;
  } catch (erro) {
    console.error(
      "[DEEPGRAM] Erro enviando áudio:",
      erro.message
    );

    return false;
  }
}

function fecharDeepgram(session) {
  if (
    !session ||
    !session.deepgram
  ) {
    return;
  }

  try {
    if (
      session.deepgram.readyState ===
      WebSocket.OPEN
    ) {
      session.deepgram.send(
        JSON.stringify({
          type:
            "CloseStream"
        })
      );
    }

    session.deepgram.close();

  } catch (_) {}

  session.deepgram =
    null;

  session.deepgramConnected =
    false;
}

app.get(
  "/api/health",
  (req, res) => {
    res.json({
      ok: true,
      service:
        "SI Tradutor Live",
      version:
        "14.0-Deepgram-DeepL",
      provider:
        "Deepgram + DeepL",
      deeplConfigured:
        Boolean(
          DEEPL_API_KEY
        ),
      deepgramConfigured:
        Boolean(
          DEEPGRAM_API_KEY
        ),
      sessions:
        sessions.size
    });
  }
);

app.get(
  "/api/deepl/test",
  async (req, res) => {
    try {
      const texto =
        req.query.text ||
        "Olá, este é um teste do SI Tradutor Live.";

      const target =
        normalizarIdioma(
          req.query.target ||
          "en-US"
        );

      const traducao =
        await traduzirDeepL(
          texto,
          target
        );

      res.json({
        ok: true,
        provider:
          "DeepL",
        status:
          200,
        source:
          texto,
        target,
        translation:
          traducao
      });

    } catch (erro) {
      res.status(500).json({
        ok: false,
        provider:
          "DeepL",
        error:
          erro.message
      });
    }
  }
);

app.post(
  "/api/audio/start",
  (req, res) => {
    try {
      const session =
        criarSessao(
          req.body?.clientId,
          req.body?.targetLang
        );

      conectarDeepgram(
        session
      );

      res.json({
        ok: true,
        jobId:
          session.jobId,
        clientId:
          session.clientId,
        targetLang:
          session.targetLang,
        status:
          session.status
      });

    } catch (erro) {
      res.status(500).json({
        ok: false,
        error:
          erro.message
      });
    }
  }
);
app.post(
  "/api/audio/chunk",
  (req, res) => {
    try {
      const {
        jobId,
        audio,
        mimeType,
        sampleRate
      } = req.body || {};

      if (!jobId) {
        return res.status(400).json({
          ok: false,
          error:
            "jobId não informado."
        });
      }

      const session =
        sessions.get(jobId);

      if (!session) {
        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada."
        });
      }

      if (!audio) {
        return res.status(400).json({
          ok: false,
          error:
            "Áudio não informado."
        });
      }

      const buffer =
        Buffer.from(
          audio,
          "base64"
        );

      session.chunksRecebidos +=
        1;

      session.bytesRecebidos +=
        buffer.length;

      atualizar(session, {
        status:
          "receiving_audio",
        mimeType:
          mimeType ||
          "audio/pcm",
        sampleRate:
          sampleRate ||
          16000
      });

      const enviado =
        enviarAudioDeepgram(
          session,
          buffer
        );

      if (enviado) {
        diagnostico(
          session,
          "audio_sent_deepgram",
          {
            bytes:
              buffer.length
          }
        );
      } else {
        console.log(
          `[DEEPGRAM] Áudio aguardando conexão ` +
          `${jobId}`
        );
      }

      res.json({
        ok: true,
        jobId,
        received:
          true,
        sentToDeepgram:
          enviado,
        chunks:
          session.chunksRecebidos,
        bytes:
          session.bytesRecebidos
      });

    } catch (erro) {
      console.error(
        "[AUDIO CHUNK]",
        erro.message
      );

      res.status(500).json({
        ok: false,
        error:
          erro.message
      });
    }
  }
);

app.get(
  "/api/audio/status/:jobId",
  (req, res) => {
    const session =
      sessions.get(
        req.params.jobId
      );

    if (!session) {
      return res.status(404).json({
        ok: false,
        error:
          "Sessão não encontrada."
      });
    }

    res.json({
      ok: true,
      jobId:
        session.jobId,
      status:
        session.status,
      targetLang:
        session.targetLang,
      deepgramConnected:
        session.deepgramConnected,
      chunksRecebidos:
        session.chunksRecebidos,
      bytesRecebidos:
        session.bytesRecebidos,
      sourceText:
        session.sourceText,
      translatedText:
        session.translatedText,
      createdAt:
        session.createdAt,
      updatedAt:
        session.updatedAt
    });
  }
);

app.get(
  "/api/audio/diagnostic/:jobId",
  (req, res) => {
    const session =
      sessions.get(
        req.params.jobId
      );

    if (!session) {
      return res.status(404).json({
        ok: false,
        error:
          "Sessão não encontrada."
      });
    }

    res.json({
      ok: true,
      jobId:
        session.jobId,
      status:
        session.status,
      provider:
        "Deepgram + DeepL",
      deepgramConnected:
        session.deepgramConnected,
      deeplConfigured:
        Boolean(
          DEEPL_API_KEY
        ),
      deepgramConfigured:
        Boolean(
          DEEPGRAM_API_KEY
        ),
      chunksRecebidos:
        session.chunksRecebidos,
      bytesRecebidos:
        session.bytesRecebidos,
      sourceText:
        session.sourceText,
      translatedText:
        session.translatedText,
      diagnostics:
        session.diagnostics
    });
  }
);

app.get(
  "/api/audio/output/:jobId",
  (req, res) => {
    const session =
      sessions.get(
        req.params.jobId
      );

    if (!session) {
      return res.status(404).json({
        ok: false,
        error:
          "Sessão não encontrada."
      });
    }

    res.json({
      ok: true,
      jobId:
        session.jobId,
      status:
        session.status,
      sourceText:
        session.sourceText,
      translatedText:
        session.translatedText,
      audioUrl:
        null,
      message:
        "A saída de voz será adicionada na próxima etapa."
    });
  }
);

app.post(
  "/api/audio/stop",
  (req, res) => {
    try {
      const {
        jobId
      } = req.body || {};

      if (!jobId) {
        return res.status(400).json({
          ok: false,
          error:
            "jobId não informado."
        });
      }

      const session =
        sessions.get(jobId);

      if (!session) {
        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada."
        });
      }

      session.stopped =
        true;

      fecharDeepgram(
        session
      );

      atualizar(session, {
        status:
          "stopped"
      });

      enviarWS(session, {
        type:
          "session_stopped",
        jobId:
          session.jobId
      });

      res.json({
        ok: true,
        jobId,
        status:
          "stopped"
      });

    } catch (erro) {
      res.status(500).json({
        ok: false,
        error:
          erro.message
      });
    }
  }
);

app.get(
  "/api/audio/sessions",
  (req, res) => {
    const lista =
      Array.from(
        sessions.values()
      ).map(session => ({
        jobId:
          session.jobId,
        clientId:
          session.clientId,
        targetLang:
          session.targetLang,
        status:
          session.status,
        deepgramConnected:
          session.deepgramConnected,
        chunksRecebidos:
          session.chunksRecebidos,
        bytesRecebidos:
          session.bytesRecebidos,
        sourceText:
          session.sourceText,
        translatedText:
          session.translatedText,
        createdAt:
          session.createdAt,
        updatedAt:
          session.updatedAt
      }));

    res.json({
      ok: true,
      count:
        lista.length,
      sessions:
        lista
    });
  }
);
const wss =
  new WebSocket.Server({
    server,
    path: "/ws"
  });

wss.on(
  "connection",
  ws => {
    console.log(
      "[WS] Android conectado."
    );

    ws.send(
      JSON.stringify({
        type:
          "connected",
        provider:
          "Deepgram + DeepL",
        message:
          "WebSocket do SI conectado."
      })
    );

    ws.on(
      "message",
      mensagem => {
        try {
          const dados =
            JSON.parse(
              mensagem.toString()
            );

          if (
            dados.type ===
            "subscribe"
          ) {
            const session =
              sessions.get(
                dados.jobId
              );

            if (!session) {
              ws.send(
                JSON.stringify({
                  type:
                    "error",
                  error:
                    "Sessão não encontrada.",
                  jobId:
                    dados.jobId
                })
              );

              return;
            }

            session.clients.add(
              ws
            );

            ws.jobId =
              dados.jobId;

            ws.send(
              JSON.stringify({
                type:
                  "subscribed",
                jobId:
                  session.jobId,
                targetLang:
                  session.targetLang,
                status:
                  session.status
              })
            );

            return;
          }

          if (
            dados.type ===
            "unsubscribe"
          ) {
            if (ws.jobId) {
              const session =
                sessions.get(
                  ws.jobId
                );

              if (session) {
                session.clients.delete(
                  ws
                );
              }
            }

            ws.jobId =
              null;

            return;
          }

        } catch (erro) {
          console.error(
            "[WS] Mensagem inválida:",
            erro.message
          );
        }
      }
    );

    ws.on(
      "close",
      () => {
        if (ws.jobId) {
          const session =
            sessions.get(
              ws.jobId
            );

          if (session) {
            session.clients.delete(
              ws
            );
          }
        }

        console.log(
          "[WS] Android desconectado."
        );
      }
    );

    ws.on(
      "error",
      erro => {
        console.error(
          "[WS] Erro:",
          erro.message
        );
      }
    );
  }
);

setInterval(
  () => {
    const agora =
      Date.now();

    for (
      const [
        jobId,
        session
      ] of sessions
    ) {
      const ultima =
        new Date(
          session.updatedAt
        ).getTime();

      if (
        agora - ultima >
        30 * 60 * 1000
      ) {
        try {
          fecharDeepgram(
            session
          );
        } catch (_) {}

        for (
          const cliente
          of session.clients
        ) {
          try {
            cliente.close();
          } catch (_) {}
        }

        sessions.delete(
          jobId
        );

        console.log(
          `[SESSION] Removida: ${jobId}`
        );
      }
    }
  },
  60 * 1000
);

app.use(
  (req, res) => {
    res.status(404).json({
      ok: false,
      error:
        "Rota não encontrada.",
      path:
        req.originalUrl
    });
  }
);

server.listen(
  PORT,
  "0.0.0.0",
  () => {
    console.log(
      `SI Tradutor Live rodando na porta ${PORT}`
    );

    console.log(
      `DeepL configurado: ${
        Boolean(DEEPL_API_KEY)
      }`
    );

    console.log(
      `Deepgram configurado: ${
        Boolean(DEEPGRAM_API_KEY)
      }`
    );

    console.log(
      `WebSocket: ws://0.0.0.0:${PORT}/ws`
    );
  }
);
