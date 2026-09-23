const express = require("express");
const cors = require("cors");
const http = require("http");
const WebSocket = require("ws");
const crypto = require("crypto");

const app = express();
const server = http.createServer(app);

/* =========================================================
   CORS
========================================================= */

app.use(
  cors({
    origin: "*",
    methods: ["GET", "POST", "PUT", "OPTIONS"],
    allowedHeaders: ["Content-Type", "Authorization"]
  })
);

app.use(express.json({ limit: "25mb" }));
app.use(express.urlencoded({ extended: true, limit: "25mb" }));


/* =========================================================
   CONFIGURAÇÃO
========================================================= */

const PORT = Number(process.env.PORT || 10000);

const DEEPL_API_KEY =
  process.env.DEEPL_API_KEY || "";

const DEEPL_VOICE_URL =
  "https://api.deepl.com/v3/voice/realtime";

const DEEPL_TRANSLATE_URL =
  DEEPL_API_KEY.endsWith(":fx")
    ? "https://api-free.deepl.com/v2/translate"
    : "https://api.deepl.com/v2/translate";


/* =========================================================
   SESSÕES
========================================================= */

const sessions = new Map();


/* =========================================================
   ID
========================================================= */

function novoId() {
  return crypto.randomUUID();
}


/* =========================================================
   ID DO YOUTUBE
========================================================= */

function obterYouTubeId(url) {

  if (!url) {
    return null;
  }

  try {

    const parsed =
      new URL(String(url).trim());

    const host =
      parsed.hostname.toLowerCase();

    /* youtu.be/ID */

    if (
      host.includes("youtu.be")
    ) {

      const id =
        parsed.pathname
          .replace(/^\/+/, "")
          .split("/")[0];

      if (id) {
        return id;
      }
    }


    /* youtube.com */

    if (
      host.includes("youtube.com")
    ) {

      const v =
        parsed.searchParams.get("v");

      if (v) {
        return v;
      }


      const partes =
        parsed.pathname
          .split("/")
          .filter(Boolean);


      if (
        partes.length >= 2 &&
        (
          partes[0] === "live" ||
          partes[0] === "embed" ||
          partes[0] === "shorts"
        )
      ) {

        return partes[1];

      }

    }

  } catch (erro) {

    console.error(
      "[YOUTUBE] URL inválida:",
      erro.message
    );

  }

  return null;
}


/* =========================================================
   IDIOMAS
========================================================= */

const LANGUAGES = {

  pt: "PT-BR",
  "pt-BR": "PT-BR",
  "pt-PT": "PT-PT",

  en: "EN-US",
  "en-US": "EN-US",
  "en-GB": "EN-GB",

  es: "ES",
  "es-ES": "ES",

  fr: "FR",
  "fr-FR": "FR",

  de: "DE",
  "de-DE": "DE",

  it: "IT",
  "it-IT": "IT",

  ja: "JA",
  "ja-JP": "JA",

  ko: "KO",
  "ko-KR": "KO",

  zh: "ZH",
  "zh-CN": "ZH",
  "zh-HANS": "ZH-HANS",

  ru: "RU",
  "ru-RU": "RU",

  ar: "AR",
  "ar-SA": "AR",

  nl: "NL",
  "nl-NL": "NL",

  pl: "PL",
  "pl-PL": "PL",

  uk: "UK",
  "uk-UA": "UK",

  tr: "TR",
  "tr-TR": "TR",

  sv: "SV",
  "sv-SE": "SV"
};


function normalizarIdioma(lang) {

  if (!lang) {
    return "PT-BR";
  }

  const valor =
    String(lang).trim();

  if (LANGUAGES[valor]) {
    return LANGUAGES[valor];
  }

  const base =
    valor
      .toLowerCase()
      .split("-")[0];

  return (
    LANGUAGES[base] ||
    "PT-BR"
  );
}


/* =========================================================
   TRADUÇÃO TEXTUAL DEEP L
   Mantida para compatibilidade/testes.
========================================================= */

async function traduzirDeepL(
  texto,
  idioma
) {

  if (!DEEPL_API_KEY) {

    throw new Error(
      "DEEPL_API_KEY não configurada no Render."
    );

  }

  if (
    !texto ||
    !String(texto).trim()
  ) {

    return "";

  }


  const resposta =
    await fetch(
      DEEPL_TRANSLATE_URL,
      {
        method: "POST",

        headers: {
          "Authorization":
            `DeepL-Auth-Key ${DEEPL_API_KEY}`,

          "Content-Type":
            "application/json"
        },

        body: JSON.stringify({

          text: [
            String(texto)
          ],

          target_lang:
            idioma

        })
      }
    );


  let dados = {};

  try {

    dados =
      await resposta.json();

  } catch (_) {}


  if (!resposta.ok) {

    throw new Error(
      dados?.message ||
      `DeepL HTTP ${resposta.status}`
    );

  }


  return (
    dados
      ?.translations
      ?.[0]
      ?.text ||
    ""
  );
}


/* =========================================================
   HEALTH
========================================================= */

app.get(
  "/",
  (req, res) => {

    res.json({

      ok: true,

      service:
        "SI Tradutor Live",

      message:
        "Backend funcionando.",

      provider:
        "DeepL Voice",

      version:
        "15.0-DeepL-Voice"

    });

  }
);


app.get(
  "/api/health",
  (req, res) => {

    res.json({

      ok: true,

      service:
        "SI Tradutor Live",

      version:
        "15.0-DeepL-Voice",

      provider:
        "DeepL Voice",

      deeplConfigured:
        Boolean(DEEPL_API_KEY),

      sessions:
        sessions.size

    });

  }
);


/* =========================================================
   CRIAR LIVE
========================================================= */

app.post(
  "/api/youtube-live",
  (req, res) => {

    try {

      const body =
        req.body || {};


      const url =
        String(
          body.url ||
          body.youtubeUrl ||
          body.link ||
          ""
        ).trim();


      const targetLanguage =
        normalizarIdioma(
          body.targetLang ||
          body.targetLanguage ||
          "pt"
        );


      if (!url) {

        return res.status(400).json({

          ok: false,

          error:
            "Informe o link do YouTube."

        });

      }


      const youtubeId =
        obterYouTubeId(url);


      if (!youtubeId) {

        return res.status(400).json({

          ok: false,

          error:
            "Não consegui identificar o vídeo do YouTube."

        });

      }


      const liveId =
        novoId();


      const session = {

        liveId,

        url,

        youtubeId,

        targetLanguage,

        status:
          "created",

        translation:
          "",

        sourceText:
          "",

        translatedText:
          "",

        createdAt:
          new Date().toISOString(),

        updatedAt:
          new Date().toISOString(),

        stopped:
          false

      };


      sessions.set(
        liveId,
        session
      );


      console.log(
        `[LIVE] Criada ${liveId}`
      );


      res.json({

        ok: true,

        live_id:
          liveId,

        youtube_id:
          youtubeId,

        target_language:
          targetLanguage,

        status:
          "created"

      });

    } catch (erro) {

      console.error(
        "[LIVE] Erro:",
        erro
      );


      res.status(500).json({

        ok: false,

        error:
          erro.message

      });

    }

  }
);


/* =========================================================
   STATUS DA LIVE
========================================================= */

app.get(
  "/api/youtube-live/:liveId",
  (req, res) => {

    const session =
      sessions.get(
        req.params.liveId
      );


    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Live não encontrada."

      });

    }


    res.json({

      ok: true,

      live_id:
        session.liveId,

      youtube_id:
        session.youtubeId,

      target_language:
        session.targetLanguage,

      status:
        session.status,

      sourceText:
        session.sourceText,

      translatedText:
        session.translatedText,

      translation:
        session.translation,

      createdAt:
        session.createdAt,

      updatedAt:
        session.updatedAt

    });

  }
);


/* =========================================================
   DEEPL VOICE — CRIAR SESSÃO
========================================================= */

app.post(
  "/api/deepl/voice-session",
  async (req, res) => {

    try {

      if (!DEEPL_API_KEY) {

        return res.status(500).json({

          ok: false,

          error:
            "DEEPL_API_KEY não está configurada no Render."

        });

      }


      const body =
        req.body || {};


      const targetLanguage =
        normalizarIdioma(
          body.targetLang ||
          body.targetLanguage ||
          "pt"
        );


      /*
       * O navegador usa MediaRecorder.
       * O formato usado pelo SI é WebM/Opus.
       */

      const sourceMediaContentType =
        "audio/webm;codecs=opus";


      /*
       * Sessão DeepL Voice.
       *
       * target_languages:
       * tradução em texto.
       *
       * target_media_languages:
       * voz traduzida.
       */

      const payload = {

        source_media_content_type:
          sourceMediaContentType,

        message_format:
          "json",

        source_language_mode:
          "auto",

        target_languages: [
          targetLanguage
        ],

        target_media_languages: [
          targetLanguage
        ],

        target_media_content_type:
          "audio/webm;codecs=opus"

      };


      console.log(
        "[DEEPL VOICE] Criando sessão:",
        JSON.stringify(payload)
      );


      const resposta =
        await fetch(
          DEEPL_VOICE_URL,
          {

            method: "POST",

            headers: {

              "Authorization":
                `DeepL-Auth-Key ${DEEPL_API_KEY}`,

              "Content-Type":
                "application/json"

            },

            body:
              JSON.stringify(payload)

          }
        );


      const textoResposta =
        await resposta.text();


      let dados = {};

      try {

        dados =
          JSON.parse(
            textoResposta
          );

      } catch (_) {

        dados = {
          raw:
            textoResposta
        };

      }


      console.log(
        `[DEEPL VOICE] HTTP ${resposta.status}`
      );


      if (!resposta.ok) {

        console.error(
          "[DEEPL VOICE] Erro:",
          dados
        );


        return res.status(
          resposta.status
        ).json({

          ok: false,

          error:
            dados?.message ||
            dados?.error ||
            dados?.raw ||
            `DeepL HTTP ${resposta.status}`,

          deepl_status:
            resposta.status,

          deepl_response:
            dados

        });

      }


      if (!dados.streaming_url) {

        return res.status(502).json({

          ok: false,

          error:
            "A DeepL não retornou streaming_url.",

          deepl_response:
            dados

        });

      }


      if (!dados.token) {

        return res.status(502).json({

          ok: false,

          error:
            "A DeepL não retornou o token da sessão.",

          deepl_response:
            dados

        });

      }


      res.json({

        ok: true,

        provider:
          "DeepL Voice",

        streaming_url:
          dados.streaming_url,

        token:
          dados.token,

        session_id:
          dados.session_id || null,

        target_language:
          targetLanguage,

        source_media_content_type:
          sourceMediaContentType,

        target_media_content_type:
          "audio/webm;codecs=opus"

      });

    } catch (erro) {

      console.error(
        "[DEEPL VOICE] Exceção:",
        erro
      );


      res.status(500).json({

        ok: false,

        error:
          `Erro ao criar sessão DeepL Voice: ${erro.message}`

      });

    }

  }
);


/* =========================================================
   TESTE DEEP L VOICE
========================================================= */

app.get(
  "/api/deepl/voice-test",
  async (req, res) => {

    try {

      if (!DEEPL_API_KEY) {

        return res.status(500).json({

          ok: false,

          error:
            "DEEPL_API_KEY não configurada."

        });

      }


      const target =
        normalizarIdioma(
          req.query.target ||
          "pt"
        );


      const payload = {

        source_media_content_type:
          "audio/webm;codecs=opus",

        message_format:
          "json",

        source_language_mode:
          "auto",

        target_languages: [
          target
        ],

        target_media_languages: [
          target
        ],

        target_media_content_type:
          "audio/webm;codecs=opus"

      };


      const resposta =
        await fetch(
          DEEPL_VOICE_URL,
          {

            method: "POST",

            headers: {

              "Authorization":
                `DeepL-Auth-Key ${DEEPL_API_KEY}`,

              "Content-Type":
                "application/json"

            },

            body:
              JSON.stringify(payload)

          }
        );


      const texto =
        await resposta.text();


      let dados;

      try {

        dados =
          JSON.parse(texto);

      } catch (_) {

        dados = {
          raw:
            texto
        };

      }


      res.status(
        resposta.ok
          ? 200
          : resposta.status
      ).json({

        ok:
          resposta.ok,

        http_status:
          resposta.status,

        provider:
          "DeepL Voice",

        response:
          dados

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


/* =========================================================
   TRADUÇÃO MANUAL DE COMPATIBILIDADE
========================================================= */

app.post(
  "/api/youtube-live/:liveId/translation",
  async (req, res) => {

    const session =
      sessions.get(
        req.params.liveId
      );


    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Live não encontrada."

      });

    }


    try {

      const texto =
        String(
          req.body?.text ||
          req.body?.sourceText ||
          ""
        ).trim();


      if (!texto) {

        return res.json({

          ok: true,

          translatedText:
            ""

        });

      }


      const traducao =
        await traduzirDeepL(
          texto,
          session.targetLanguage
        );


      session.sourceText =
        texto;

      session.translatedText =
        traducao;

      session.translation =
        traducao;

      session.status =
        "translated";

      session.updatedAt =
        new Date().toISOString();


      res.json({

        ok: true,

        sourceText:
          texto,

        translatedText:
          traducao,

        targetLang:
          session.targetLanguage

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


/* =========================================================
   ÁUDIO — COMPATIBILIDADE
========================================================= */

app.post(
  "/api/youtube-live/:liveId/audio",
  (req, res) => {

    const session =
      sessions.get(
        req.params.liveId
      );


    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Live não encontrada."

      });

    }


    session.status =
      "receiving_audio";

    session.updatedAt =
      new Date().toISOString();


    res.json({

      ok: true,

      message:
        "Áudio recebido.",

      live_id:
        session.liveId

    });

  }
);


app.get(
  "/api/youtube-live/:liveId/audio",
  (req, res) => {

    const session =
      sessions.get(
        req.params.liveId
      );


    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Live não encontrada."

      });

    }


    res.json({

      ok: true,

      status:
        session.status

    });

  }
);


/* =========================================================
   PARAR LIVE
========================================================= */

app.post(
  "/api/youtube-live/:liveId/stop",
  (req, res) => {

    const session =
      sessions.get(
        req.params.liveId
      );


    if (!session) {

      return res.status(404).json({

        ok: false,

        error:
          "Live não encontrada."

      });

    }


    session.stopped =
      true;

    session.status =
      "stopped";

    session.updatedAt =
      new Date().toISOString();


    res.json({

      ok: true,

      status:
        "stopped",

      live_id:
        session.liveId

    });

  }
);


/* =========================================================
   SESSÕES
========================================================= */

app.get(
  "/api/sessions",
  (req, res) => {

    const lista =
      Array.from(
        sessions.values()
      ).map(
        session => ({

          live_id:
            session.liveId,

          youtube_id:
            session.youtubeId,

          target_language:
            session.targetLanguage,

          status:
            session.status,

          sourceText:
            session.sourceText,

          translatedText:
            session.translatedText,

          createdAt:
            session.createdAt,

          updatedAt:
            session.updatedAt

        })
      );


    res.json({

      ok: true,

      count:
        lista.length,

      sessions:
        lista

    });

  }
);


/* =========================================================
   WEBSOCKET DO SI
   Mantido para compatibilidade.
========================================================= */

const wss =
  new WebSocket.Server({

    server,

    path:
      "/ws"

  });


wss.on(
  "connection",
  ws => {

    console.log(
      "[WS] Cliente conectado."
    );


    ws.send(
      JSON.stringify({

        type:
          "connected",

        provider:
          "DeepL Voice",

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
                dados.jobId ||
                dados.liveId
              );


            if (!session) {

              ws.send(
                JSON.stringify({

                  type:
                    "error",

                  error:
                    "Sessão não encontrada."

                })
              );

              return;

            }


            ws.jobId =
              session.liveId;


            ws.send(
              JSON.stringify({

                type:
                  "subscribed",

                jobId:
                  session.liveId,

                targetLang:
                  session.targetLanguage,

                status:
                  session.status

              })
            );

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

        console.log(
          "[WS] Cliente desconectado."
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


/* =========================================================
   LIMPEZA
========================================================= */

setInterval(
  () => {

    const agora =
      Date.now();


    for (
      const [
        liveId,
        session
      ] of sessions
    ) {

      const criado =
        new Date(
          session.createdAt
        ).getTime();


      if (
        agora - criado >
        60 * 60 * 1000
      ) {

        sessions.delete(
          liveId
        );


        console.log(
          `[SESSION] Removida: ${liveId}`
        );

      }

    }

  },
  5 * 60 * 1000
);


/* =========================================================
   ROTA 404
========================================================= */

app.use(
  (req, res) => {

    console.log(
      `[404] ${req.method} ${req.originalUrl}`
    );


    res.status(404).json({

      ok: false,

      error:
        "Rota não encontrada.",

      path:
        req.originalUrl

    });

  }
);


/* =========================================================
   START SERVER
========================================================= */

server.listen(
  PORT,
  "0.0.0.0",
  () => {

    console.log(
      "========================================"
    );

    console.log(
      "SI TRADUTOR LIVE"
    );

    console.log(
      "DeepL Voice"
    );

    console.log(
      "========================================"
    );

    console.log(
      `Porta: ${PORT}`
    );

    console.log(
      `DeepL configurado: ${Boolean(
        DEEPL_API_KEY
      )}`
    );

    console.log(
      `WebSocket: ws://0.0.0.0:${PORT}/ws`
    );

    console.log(
      "========================================"
    );

  }
);
