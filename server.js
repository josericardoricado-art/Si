const express = require("express");
const cors = require("cors");
const WebSocket = require("ws");
const crypto = require("crypto");

const app = express();

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

const GEMINI_API_KEY =
  process.env.GEMINI_API_KEY || "";

const GEMINI_MODEL =
  process.env.GEMINI_MODEL ||
  "gemini-3.5-live-translate-preview";

const GEMINI_WS_URL =
  "wss://generativelanguage.googleapis.com/ws/" +
  "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";

// ============================================================
// BUFFER DE SAÍDA
// ============================================================

const GEMINI_AUDIO_FLUSH_MS = 250;

const GEMINI_AUDIO_MAX_BUFFER_BYTES =
  24000;

// ============================================================
// SESSÕES
// ============================================================

const sessions = new Map();

// ============================================================
// FUNÇÕES AUXILIARES
// ============================================================

function novoJobId() {
  return crypto.randomUUID();
}

function agora() {
  return Date.now();
}

function registrarDiagnostico(
  session,
  stage,
  extra = {}
) {
  if (!session) {
    return;
  }

  if (!Array.isArray(session.diagnostics)) {
    session.diagnostics = [];
  }

  session.diagnostics.push({
    stage,
    time: Date.now(),
    ...extra
  });

  if (session.diagnostics.length > 300) {
    session.diagnostics =
      session.diagnostics.slice(-300);
  }
}

function criarBufferAudioSession(session) {

  if (!session.pendingOutputBuffers) {
    session.pendingOutputBuffers = [];
  }

  if (
    typeof session.pendingOutputBytes !==
    "number"
  ) {
    session.pendingOutputBytes = 0;
  }

  if (
    typeof session.outputFlushTimer ===
    "undefined"
  ) {
    session.outputFlushTimer = null;
  }

  if (
    typeof session.outputTurns !==
    "number"
  ) {
    session.outputTurns = 0;
  }

  if (!session.audioClients) {
    session.audioClients = new Set();
  }
}

// ============================================================
// ENVIAR ÁUDIO PELO WEBSOCKET
// ============================================================

function enviarAudioWebSocket(
  session,
  seq,
  base64
) {

  if (
    !session ||
    !session.audioClients
  ) {
    return;
  }

  const message =
    JSON.stringify({
      type: "audio",
      jobId: session.jobId,
      seq,
      audio: base64
    });

  for (
    const client
    of session.audioClients
  ) {

    try {

      if (
        client.readyState ===
        WebSocket.OPEN
      ) {

        client.send(message);

      } else {

        session.audioClients.delete(
          client
        );

      }

    } catch (error) {

      console.error(
        "[WS AUDIO] Erro enviando áudio:",
        error.message
      );

      try {
        session.audioClients.delete(
          client
        );
      } catch (_) {}
    }
  }
}

// ============================================================
// FLUSH DO ÁUDIO DO GEMINI
// ============================================================

function flushAudioGemini(
  session,
  reason = "manual"
) {

  if (!session) {
    return;
  }

  criarBufferAudioSession(
    session
  );

  if (
    !session.pendingOutputBuffers.length
  ) {
    return;
  }

  let audioBuffer;

  try {

    audioBuffer =
      Buffer.concat(
        session.pendingOutputBuffers
      );

  } catch (error) {

    console.error(
      "[GEMINI AUDIO BUFFER] Erro:",
      error
    );

    session.pendingOutputBuffers =
      [];

    session.pendingOutputBytes =
      0;

    return;
  }

  // PCM16 precisa ter quantidade par de bytes.
  if (
    audioBuffer.length % 2 !== 0
  ) {

    audioBuffer =
      audioBuffer.subarray(
        0,
        audioBuffer.length - 1
      );
  }

  if (
    audioBuffer.length === 0
  ) {

    session.pendingOutputBuffers =
      [];

    session.pendingOutputBytes =
      0;

    return;
  }

  const seq =
    (session.nextOutputSeq || 0) + 1;

  session.nextOutputSeq =
    seq;

  const base64 =
    audioBuffer.toString(
      "base64"
    );

  session.outputChunks.push({
    seq,
    audio: base64
  });

  session.outputBytes =
    (session.outputBytes || 0) +
    audioBuffer.length;

  session.outputTurns =
    (session.outputTurns || 0) +
    1;

  // Limite da fila.
  if (
    session.outputChunks.length >
    500
  ) {

    session.outputChunks =
      session.outputChunks.slice(
        -500
      );
  }

  registrarDiagnostico(
    session,
    "audio_output_flush",
    {
      reason,
      seq,
      bytes:
        audioBuffer.length,
      outputChunks:
        session.outputChunks.length
    }
  );

  console.log(
    `[GEMINI] Áudio agrupado: ` +
    `${audioBuffer.length} bytes | ` +
    `seq=${seq} | ` +
    `reason=${reason}`
  );

  // Enviar imediatamente para Android.
  enviarAudioWebSocket(
    session,
    seq,
    base64
  );

  session.pendingOutputBuffers =
    [];

  session.pendingOutputBytes =
    0;

  if (
    session.outputFlushTimer
  ) {

    clearTimeout(
      session.outputFlushTimer
    );

    session.outputFlushTimer =
      null;
  }
}

// ============================================================
// ADICIONAR ÁUDIO AO BUFFER
// ============================================================

function adicionarAudioGeminiAoBuffer(
  session,
  audioBuffer
) {

  if (!session) {
    return;
  }

  if (!audioBuffer) {
    return;
  }

  criarBufferAudioSession(
    session
  );

  if (
    !Buffer.isBuffer(audioBuffer)
  ) {

    audioBuffer =
      Buffer.from(
        audioBuffer
      );
  }

  if (
    audioBuffer.length === 0
  ) {
    return;
  }

  session.pendingOutputBuffers.push(
    audioBuffer
  );

  session.pendingOutputBytes +=
    audioBuffer.length;

  if (
    session.pendingOutputBytes >=
    GEMINI_AUDIO_MAX_BUFFER_BYTES
  ) {

    flushAudioGemini(
      session,
      "max_buffer"
    );

    return;
  }

  if (
    session.outputFlushTimer
  ) {

    clearTimeout(
      session.outputFlushTimer
    );
  }

  session.outputFlushTimer =
    setTimeout(
      () => {

        try {

          flushAudioGemini(
            session,
            "timeout"
          );

        } catch (error) {

          console.error(
            "[GEMINI AUDIO TIMER]",
            error
          );
        }

      },
      GEMINI_AUDIO_FLUSH_MS
    );
}

// ============================================================
// HEALTH
// ============================================================

app.get(
  "/api/health",
  (req, res) => {

    return res.json({

      ok: true,

      service:
        "SI Tradutor Live",

      version:
        "12.0-Gemini-Live-WebSocket",

      message:
        "Backend do SI Tradutor Live funcionando",

      gemini:
        !!GEMINI_API_KEY,

      model:
        GEMINI_MODEL,

      authentication:
        "API key + Gemini Live WebSocket"
    });
  }
);

// ============================================================
// CRIAR SESSÃO
// ============================================================

app.post(
  "/api/audio/start",
  (req, res) => {

    try {

      const clientId =
        req.body?.clientId ||
        crypto.randomUUID();

      const targetLanguage =
        req.body?.targetLang ||
        req.body?.targetLanguage ||
        "pt-BR";

      const jobId =
        novoJobId();

      const session = {

        jobId,

        clientId,

        targetLanguage,

        status:
          "starting",

        stopped:
          false,

        geminiReady:
          false,

        geminiConnecting:
          false,

        ws:
          null,

        pendingInputChunks:
          [],

        outputChunks:
          [],

        nextOutputSeq:
          0,

        audioClients:
          new Set(),

        outputBytes:
          0,

        pendingOutputBuffers:
          [],

        pendingOutputBytes:
          0,

        outputFlushTimer:
          null,

        outputTurns:
          0,

        chunksReceived:
          0,

        bytesReceived:
          0,

        chunksSentToGemini:
          0,

        bytesSentToGemini:
          0,

        inputTranscript:
          "",

        outputTranscript:
          "",

        lastError:
          null,

        diagnostics:
          [],

        createdAt:
          agora(),

        lastActivity:
          agora(),

        reconnectTimer:
          null
      };

      sessions.set(
        jobId,
        session
      );

      registrarDiagnostico(
        session,
        "session_created"
      );

      console.log(
        `[SESSION] Criada: ${jobId} | ` +
        `target=${targetLanguage}`
      );

      connectGemini(
        session
      ).catch(
        error => {

          console.error(
            "[GEMINI CONNECT ERROR]",
            error
          );

        }
      );

      return res.json({

        ok: true,

        jobId,

        clientId,

        targetLanguage,

        status:
          session.status

      });

    } catch (error) {

      console.error(
        "[AUDIO START ERROR]",
        error
      );

      return res.status(500).json({

        ok: false,

        error:
          error.message

      });
    }
  }
);

// ============================================================
// CONEXÃO COM GEMINI LIVE
// ============================================================

async function connectGemini(
  session
) {

  if (
    !session ||
    session.stopped
  ) {
    return;
  }

  if (
    !GEMINI_API_KEY
  ) {

    session.lastError =
      "GEMINI_API_KEY não configurada";

    session.status =
      "error";

    registrarDiagnostico(
      session,
      "gemini_api_key_missing"
    );

    return;
  }

  if (
    session.geminiConnecting
  ) {
    return;
  }

  session.geminiConnecting =
    true;

  session.lastActivity =
    Date.now();

  registrarDiagnostico(
    session,
    "gemini_connecting"
  );

  console.log(
    `[GEMINI] Conectando sessão ${session.jobId}`
  );

  try {

    const wsUrl =
      `${GEMINI_WS_URL}?key=${encodeURIComponent(
        GEMINI_API_KEY
      )}`;

    const ws =
      new WebSocket(
        wsUrl
      );

    session.ws =
      ws;

    ws.on(
      "open",
      () => {

        if (
          session.stopped
        ) {

          try {
            ws.close();
          } catch (_) {}

          return;
        }

        console.log(
          `[GEMINI] WebSocket aberto: ${session.jobId}`
        );

        session.status =
          "connecting";

        session.lastActivity =
          Date.now();

        const setupMessage = {

          setup: {

            model:
              `models/${GEMINI_MODEL}`,

            generationConfig: {

              responseModalities:
                ["AUDIO"],

              translationConfig: {

                targetLanguageCode:
                  session.targetLanguage,

                echoTargetLanguage:
                  false
              }
            },

            inputAudioTranscription:
              {},

            outputAudioTranscription:
              {}
          }
        };

        try {

          ws.send(
            JSON.stringify(
              setupMessage
            )
          );

          registrarDiagnostico(
            session,
            "gemini_setup_sent"
          );

        } catch (sendError) {

          session.lastError =
            sendError.message;

          console.error(
            "[GEMINI] Erro enviando setup:",
            sendError
          );
        }
      }
    );

    ws.on(
      "message",
      data => {

        try {

          session.lastActivity =
            Date.now();

          let text;

          if (
            typeof data ===
            "string"
          ) {

            text =
              data;

          } else if (
            Buffer.isBuffer(data)
          ) {

            text =
              data.toString(
                "utf8"
              );

          } else if (
            data instanceof ArrayBuffer
          ) {

            text =
              Buffer.from(
                data
              ).toString(
                "utf8"
              );

          } else if (
            ArrayBuffer.isView(data)
          ) {

            text =
              Buffer.from(
                data.buffer,
                data.byteOffset,
                data.byteLength
              ).toString(
                "utf8"
              );

          } else {

            text =
              String(data);
          }

          if (!text) {
            return;
          }

          let message;

          try {

            message =
              JSON.parse(
                text
              );

          } catch (jsonError) {

            console.error(
              "[GEMINI] JSON inválido:",
              jsonError.message
            );

            registrarDiagnostico(
              session,
              "gemini_invalid_json"
            );

            return;
          }

          handleGeminiMessage(
            session,
            message
          );

        } catch (error) {

          console.error(
            "[GEMINI MESSAGE ERROR]",
            error
          );

          session.lastError =
            error.message;
        }
      }
    );

    ws.on(
      "error",
      error => {

        console.error(
          `[GEMINI] WebSocket error ${session.jobId}:`,
          error.message
        );

        session.lastError =
          error.message;

        session.geminiReady =
          false;

        registrarDiagnostico(
          session,
          "gemini_websocket_error",
          {
            error:
              error.message
          }
        );
      }
    );

    ws.on(
      "close",
      (code, reason) => {

        const reasonText =
          reason
            ? reason.toString()
            : "";

        console.log(
          `[GEMINI] WebSocket fechado ${session.jobId} | ` +
          `code=${code} | ` +
          `reason=${reasonText}`
        );

        session.geminiReady =
          false;

        session.geminiConnecting =
          false;

        session.ws =
          null;

        session.lastActivity =
          Date.now();

        registrarDiagnostico(
          session,
          "gemini_closed",
          {
            code,
            reason:
              reasonText
          }
        );

        if (
          !session.stopped &&
          code !== 1000
        ) {

          scheduleGeminiReconnect(
            session
          );
        }
      }
    );

  } catch (error) {

    console.error(
      "[GEMINI CONNECT ERROR]",
      error
    );

    session.geminiConnecting =
      false;

    session.geminiReady =
      false;

    session.lastError =
      error.message;

    registrarDiagnostico(
      session,
      "gemini_connect_error",
      {
        error:
          error.message
      }
    );

    if (
      !session.stopped
    ) {

      scheduleGeminiReconnect(
        session
      );
    }
  }
}

// ============================================================
// RECONEXÃO
// ============================================================

function scheduleGeminiReconnect(
  session
) {

  if (
    !session ||
    session.stopped
  ) {
    return;
  }

  if (
    session.reconnectTimer
  ) {
    return;
  }

  session.reconnectTimer =
    setTimeout(
      () => {

        session.reconnectTimer =
          null;

        if (
          session.stopped
        ) {
          return;
        }

        connectGemini(
          session
        ).catch(
          error => {

            console.error(
              "[GEMINI RECONNECT ERROR]",
              error
            );

          }
        );

      },
      2000
    );
}

// ============================================================
// PROCESSAR MENSAGEM DO GEMINI
// ============================================================

function handleGeminiMessage(
  session,
  message
) {

  if (
    !session ||
    !message
  ) {
    return;
  }

  session.lastActivity =
    Date.now();

  // ----------------------------------------------------------
  // GEMINI PRONTO
  // ----------------------------------------------------------

  if (
    message.setupComplete
  ) {

    session.geminiReady =
      true;

    session.geminiConnecting =
      false;

    session.status =
      "ready";

    session.lastError =
      null;

    registrarDiagnostico(
      session,
      "gemini_setup_complete"
    );

    console.log(
      `[GEMINI] Pronto: ${session.jobId}`
    );

    if (
      session.pendingInputChunks &&
      session.pendingInputChunks.length
    ) {

      const pending =
        session.pendingInputChunks.splice(
          0,
          session.pendingInputChunks.length
        );

      for (
        const chunk
        of pending
      ) {

        sendAudioToGemini(
          session,
          chunk
        );
      }
    }

    return;
  }

  // ----------------------------------------------------------
  // ERRO
  // ----------------------------------------------------------

  if (
    message.error
  ) {

    const errorText =
      typeof message.error ===
      "string"
        ? message.error
        : JSON.stringify(
            message.error
          );

    session.lastError =
      errorText;

    session.geminiReady =
      false;

    registrarDiagnostico(
      session,
      "gemini_error",
      {
        error:
          errorText
      }
    );

    console.error(
      `[GEMINI] Erro da API: ${errorText}`
    );

    return;
  }

  const serverContent =
    message.serverContent;

  if (!serverContent) {
    return;
  }

  // ----------------------------------------------------------
  // TRANSCRIÇÃO DE ENTRADA
  // ----------------------------------------------------------

  if (
    serverContent.inputTranscription &&
    serverContent.inputTranscription.text
  ) {

    session.inputTranscript +=
      serverContent
        .inputTranscription
        .text;

    session.inputTranscript =
      session.inputTranscript.slice(
        -10000
      );
  }

  // ----------------------------------------------------------
  // TRANSCRIÇÃO DE SAÍDA
  // ----------------------------------------------------------

  if (
    serverContent.outputTranscription &&
    serverContent.outputTranscription.text
  ) {

    session.outputTranscript +=
      serverContent
        .outputTranscription
        .text;

    session.outputTranscript =
      session.outputTranscript.slice(
        -10000
      );
  }

  // ----------------------------------------------------------
  // ÁUDIO GERADO
  // ----------------------------------------------------------

  if (
    serverContent.modelTurn &&
    Array.isArray(
      serverContent.modelTurn.parts
    )
  ) {

    for (
      const part
      of serverContent.modelTurn.parts
    ) {

      if (
        !part.inlineData ||
        !part.inlineData.data
      ) {

        continue;
      }

      const mimeType =
        part.inlineData.mimeType ||
        "audio/pcm";

      const audioBuffer =
        Buffer.from(
          part.inlineData.data,
          "base64"
        );

      if (
        audioBuffer.length === 0
      ) {

        continue;
      }

      console.log(
        `[GEMINI] Áudio recebido: ` +
        `${audioBuffer.length} bytes | ` +
        `${mimeType}`
      );

      adicionarAudioGeminiAoBuffer(
        session,
        audioBuffer
      );
    }
  }

  // ----------------------------------------------------------
  // FIM DO TURNO
  // ----------------------------------------------------------

  if (
    serverContent.turnComplete ===
    true
  ) {

    flushAudioGemini(
      session,
      "turn_complete"
    );
  }
}

// ============================================================
// ENVIAR ÁUDIO PARA GEMINI
// ============================================================

function sendAudioToGemini(
  session,
  audioBuffer
) {

  if (
    !session ||
    !audioBuffer
  ) {

    return false;
  }

  if (
    !Buffer.isBuffer(
      audioBuffer
    )
  ) {

    audioBuffer =
      Buffer.from(
        audioBuffer
      );
  }

  if (
    audioBuffer.length === 0
  ) {

    return false;
  }

  if (
    !session.ws ||
    session.ws.readyState !==
      WebSocket.OPEN ||
    !session.geminiReady
  ) {

    return false;
  }

  try {

    const message = {

      realtimeInput: {

        audio: {

          mimeType:
            "audio/pcm;rate=16000",

          data:
            audioBuffer.toString(
              "base64"
            )
        }
      }
    };

    session.ws.send(
      JSON.stringify(
        message
      )
    );

    session.chunksSentToGemini +=
      1;

    session.bytesSentToGemini +=
      audioBuffer.length;

    session.lastActivity =
      Date.now();

    return true;

  } catch (error) {

    session.lastError =
      error.message;

    console.error(
      "[GEMINI SEND ERROR]",
      error
    );

    registrarDiagnostico(
      session,
      "gemini_send_error",
      {
        error:
          error.message
      }
    );

    return false;
  }
}

// ============================================================
// RECEBER CHUNK DE ÁUDIO DO ANDROID
// ============================================================

app.post(
  "/api/audio/chunk",
  (req, res) => {

    try {

      const jobId =
        req.body?.jobId;

      const audioBase64 =
        req.body?.audio;

      if (!jobId) {

        return res.status(400).json({
          ok: false,
          error:
            "jobId obrigatório"
        });
      }

      if (!audioBase64) {

        return res.status(400).json({
          ok: false,
          error:
            "audio obrigatório"
        });
      }

      const session =
        sessions.get(jobId);

      if (!session) {

        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada",
          jobId
        });
      }

      if (
        session.stopped
      ) {

        return res.status(400).json({
          ok: false,
          error:
            "Sessão já encerrada",
          jobId
        });
      }

      const audioBuffer =
        Buffer.from(
          audioBase64,
          "base64"
        );

      if (
        audioBuffer.length === 0
      ) {

        return res.status(400).json({
          ok: false,
          error:
            "Áudio vazio"
        });
      }

      session.chunksReceived +=
        1;

      session.bytesReceived +=
        audioBuffer.length;

      session.lastActivity =
        Date.now();

      if (
        !session.geminiReady
      ) {

        if (
          session.pendingInputChunks.length <
          120
        ) {

          session.pendingInputChunks.push(
            audioBuffer
          );

          registrarDiagnostico(
            session,
            "input_queued",
            {
              size:
                audioBuffer.length,

              queueSize:
                session.pendingInputChunks.length
            }
          );

          return res.json({
            ok: true,
            queued: true,
            queueSize:
              session.pendingInputChunks.length
          });
        }

        return res.status(503).json({
          ok: false,
          error:
            "Gemini ainda não está pronto",
          queued: false
        });
      }

      const sent =
        sendAudioToGemini(
          session,
          audioBuffer
        );

      if (!sent) {

        if (
          session.pendingInputChunks.length <
          120
        ) {

          session.pendingInputChunks.push(
            audioBuffer
          );

          return res.json({
            ok: true,
            queued: true,
            queueSize:
              session.pendingInputChunks.length
          });
        }

        return res.status(503).json({
          ok: false,
          error:
            "Não foi possível enviar áudio ao Gemini"
        });
      }

      return res.json({
        ok: true,
        queued: false,
        sent: true,
        chunksReceived:
          session.chunksReceived,
        chunksSentToGemini:
          session.chunksSentToGemini
      });

    } catch (error) {

      console.error(
        "[AUDIO CHUNK ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error:
          error.message
      });
    }
  }
);

// ============================================================
// ÁUDIO DE SAÍDA - CURSOR
// ============================================================

app.get(
  "/api/audio/output/:jobId",
  (req, res) => {

    try {

      const jobId =
        req.params.jobId;

      const session =
        sessions.get(jobId);

      if (!session) {

        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada",
          jobId
        });
      }

      let after =
        Number(
          req.query.after || 0
        );

      if (
        !Number.isFinite(after) ||
        after < 0
      ) {

        after = 0;
      }

      let limit =
        Number(
          req.query.limit || 30
        );

      if (
        !Number.isFinite(limit) ||
        limit <= 0
      ) {

        limit = 30;
      }

      limit =
        Math.min(
          Math.floor(limit),
          50
        );

      const chunks =
        session.outputChunks
          .filter(
            chunk =>
              Number(chunk.seq) >
              after
          )
          .slice(
            0,
            limit
          );

      const nextSeq =
        chunks.length > 0
          ? Number(
              chunks[
                chunks.length - 1
              ].seq
            )
          : after;

      let bytes = 0;

      for (
        const chunk
        of chunks
      ) {

        try {

          bytes +=
            Buffer.from(
              chunk.audio,
              "base64"
            ).length;

        } catch (_) {}
      }

      return res.json({

        ok: true,

        jobId,

        after,

        nextSeq,

        chunks,

        count:
          chunks.length,

        bytes,

        available:
          session.outputChunks.length,

        nextOutputSeq:
          session.nextOutputSeq

      });

    } catch (error) {

      console.error(
        "[OUTPUT ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error:
          error.message
      });
    }
  }
);

// ============================================================
// STATUS
// ============================================================

app.get(
  "/api/audio/status/:jobId",
  (req, res) => {

    try {

      const jobId =
        req.params.jobId;

      const session =
        sessions.get(jobId);

      if (!session) {

        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada",
          jobId
        });
      }

      return res.json({

        ok: true,

        jobId,

        clientId:
          session.clientId,

        targetLanguage:
          session.targetLanguage,

        status:
          session.status,

        stopped:
          session.stopped,

        geminiReady:
          session.geminiReady,

        geminiConnecting:
          session.geminiConnecting,

        chunksReceived:
          session.chunksReceived,

        bytesReceived:
          session.bytesReceived,

        chunksSentToGemini:
          session.chunksSentToGemini,

        bytesSentToGemini:
          session.bytesSentToGemini,

        pendingInputChunks:
          session.pendingInputChunks.length,

        outputChunks:
          session.outputChunks.length,

        nextOutputSeq:
          session.nextOutputSeq,

        outputBytes:
          session.outputBytes,

        outputTurns:
          session.outputTurns,

        inputTranscript:
          session.inputTranscript,

        outputTranscript:
          session.outputTranscript,

        lastError:
          session.lastError,

        createdAt:
          session.createdAt,

        lastActivity:
          session.lastActivity,

        websocketClients:
          session.audioClients.size

      });

    } catch (error) {

      console.error(
        "[STATUS ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error:
          error.message
      });
    }
  }
);

// ============================================================
// DIAGNÓSTICO
// ============================================================

app.get(
  "/api/audio/diagnostic/:jobId",
  (req, res) => {

    try {

      const jobId =
        req.params.jobId;

      const session =
        sessions.get(jobId);

      if (!session) {

        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada",
          jobId
        });
      }

      return res.json({

        ok: true,

        jobId,

        status:
          session.status,

        stopped:
          session.stopped,

        targetLanguage:
          session.targetLanguage,

        geminiReady:
          session.geminiReady,

        geminiConnecting:
          session.geminiConnecting,

        chunksReceived:
          session.chunksReceived,

        bytesReceived:
          session.bytesReceived,

        chunksSentToGemini:
          session.chunksSentToGemini,

        bytesSentToGemini:
          session.bytesSentToGemini,

        pendingInputChunks:
          session.pendingInputChunks.length,

        outputChunks:
          session.outputChunks.length,

        nextOutputSeq:
          session.nextOutputSeq,

        outputBytes:
          session.outputBytes,

        outputTurns:
          session.outputTurns,

        websocketClients:
          session.audioClients.size,

        inputTranscript:
          session.inputTranscript,

        outputTranscript:
          session.outputTranscript,

        lastError:
          session.lastError,

        createdAt:
          session.createdAt,

        lastActivity:
          session.lastActivity,

        diagnostics:
          session.diagnostics

      });

    } catch (error) {

      console.error(
        "[DIAGNOSTIC ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error:
          error.message
      });
    }
  }
);

// ============================================================
// LISTAR SESSÕES
// ============================================================

app.get(
  "/api/audio/sessions",
  (req, res) => {

    try {

      const lista = [];

      for (
        const [jobId, session]
        of sessions.entries()
      ) {

        lista.push({

          jobId,

          clientId:
            session.clientId,

          targetLanguage:
            session.targetLanguage,

          status:
            session.status,

          stopped:
            session.stopped,

          geminiReady:
            session.geminiReady,

          geminiConnecting:
            session.geminiConnecting,

          chunksReceived:
            session.chunksReceived,

          bytesReceived:
            session.bytesReceived,

          chunksSentToGemini:
            session.chunksSentToGemini,

          bytesSentToGemini:
            session.bytesSentToGemini,

          pendingInputChunks:
            session.pendingInputChunks.length,

          outputChunks:
            session.outputChunks.length,

          nextOutputSeq:
            session.nextOutputSeq,

          outputBytes:
            session.outputBytes,

          outputTurns:
            session.outputTurns,

          websocketClients:
            session.audioClients.size,

          createdAt:
            session.createdAt,

          lastActivity:
            session.lastActivity,

          lastError:
            session.lastError || null

        });
      }

      return res.json({

        ok: true,

        count:
          lista.length,

        sessions:
          lista

      });

    } catch (error) {

      console.error(
        "[SESSIONS ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error:
          error.message
      });
    }
  }
);

// ============================================================
// PARAR SESSÃO
// ============================================================

app.post(
  "/api/audio/stop",
  (req, res) => {

    try {

      const jobId =
        req.body?.jobId ||
        req.body?.jobID ||
        req.body?.id ||
        "";

      if (!jobId) {

        return res.status(400).json({
          ok: false,
          error:
            "jobId obrigatório"
        });
      }

      const session =
        sessions.get(jobId);

      if (!session) {

        return res.status(404).json({
          ok: false,
          error:
            "Sessão não encontrada",
          jobId
        });
      }

      if (
        session.stopped
      ) {

        return res.json({
          ok: true,
          alreadyStopped: true,
          jobId
        });
      }

      session.status =
        "stopping";

      session.stopped =
        true;

      session.lastActivity =
        Date.now();

      // Enviar áudio pendente.
      try {

        if (
          session.pendingInputChunks &&
          session.pendingInputChunks.length > 0 &&
          session.geminiReady
        ) {

          const pending =
            session.pendingInputChunks;

          session.pendingInputChunks =
            [];

          for (
            const chunk
            of pending
          ) {

            sendAudioToGemini(
              session,
              chunk
            );
          }
        }

      } catch (error) {

        console.error(
          "[STOP] Erro ao enviar áudio pendente:",
          error.message
        );
      }

      // Flush final.
      try {

        flushAudioGemini(
          session,
          "stop"
        );

      } catch (error) {

        console.error(
          "[STOP] Erro no flush:",
          error.message
        );
      }

      if (
        session.outputFlushTimer
      ) {

        clearTimeout(
          session.outputFlushTimer
        );

        session.outputFlushTimer =
          null;
      }

      if (
        session.reconnectTimer
      ) {

        clearTimeout(
          session.reconnectTimer
        );

        session.reconnectTimer =
          null;
      }

      try {

        if (
          session.ws
        ) {

          session.ws.close(
            1000,
            "Sessão encerrada pelo usuário"
          );
        }

      } catch (_) {}

      session.ws =
        null;

      session.geminiReady =
        false;

      session.geminiConnecting =
        false;

      session.status =
        "stopped";

      // Avisar o Android.
      for (
        const client
        of session.audioClients
      ) {

        try {

          if (
            client.readyState ===
            WebSocket.OPEN
          ) {

            client.send(
              JSON.stringify({
                type: "stopped",
                jobId,
                nextOutputSeq:
                  session.nextOutputSeq
              })
            );
          }

        } catch (_) {}
      }

      return res.json({

        ok: true,

        jobId,

        status:
          session.status,

        nextOutputSeq:
          session.nextOutputSeq

      });

    } catch (error) {

      console.error(
        "[STOP ERROR]",
        error
      );

      return res.status(500).json({
        ok: false,
        error:
          error.message
      });
    }
  }
);

// ============================================================
// ROTA PRINCIPAL
// ============================================================

app.get(
  "/",
  (req, res) => {

    res.json({

      ok: true,

      service:
        "SI Tradutor Live",

      version:
        "12.0-Gemini-Live-WebSocket",

      message:
        "Backend do SI Tradutor Live funcionando",

      endpoints: {

        health:
          "/api/health",

        audioStart:
          "POST /api/audio/start",

        audioChunk:
          "POST /api/audio/chunk",

        audioOutput:
          "GET /api/audio/output/:jobId",

        audioStatus:
          "GET /api/audio/status/:jobId",

        diagnostic:
          "GET /api/audio/diagnostic/:jobId",

        audioStop:
          "POST /api/audio/stop",

        sessions:
          "GET /api/audio/sessions",

        websocket:
          "wss://si-u2ul.onrender.com/ws"
      }
    });
  }
);

// ============================================================
// TRATAMENTO DE ERROS
// ============================================================

app.use(
  (err, req, res, next) => {

    console.error(
      "[EXPRESS ERROR]",
      err
    );

    if (
      res.headersSent
    ) {

      return next(err);
    }

    return res.status(500).json({

      ok: false,

      error:
        err.message ||
        "Erro interno do servidor"

    });
  }
);

// ============================================================
// LIMPEZA AUTOMÁTICA
// ============================================================

setInterval(
  () => {

    try {

      const agoraMs =
        Date.now();

      for (
        const [jobId, session]
        of sessions.entries()
      ) {

        const ultimaAtividade =
          session.lastActivity ||
          session.createdAt ||
          agoraMs;

        const idade =
          agoraMs -
          ultimaAtividade;

        // Sessões paradas há mais de 10 minutos.
        if (
          session.stopped &&
          idade >
            10 * 60 * 1000
        ) {

          try {

            if (
              session.outputFlushTimer
            ) {

              clearTimeout(
                session.outputFlushTimer
              );

              session.outputFlushTimer =
                null;
            }

          } catch (_) {}

          try {

            if (
              session.reconnectTimer
            ) {

              clearTimeout(
                session.reconnectTimer
              );

              session.reconnectTimer =
                null;
            }

          } catch (_) {}

          try {

            if (
              session.ws
            ) {

              session.ws.close(
                1000,
                "Limpeza automática"
              );

              session.ws =
                null;
            }

          } catch (_) {}

          for (
            const client
            of session.audioClients
          ) {

            try {

              if (
                client.readyState ===
                WebSocket.OPEN
              ) {

                client.close(
                  1000,
                  "Sessão expirada"
                );
              }

            } catch (_) {}
          }

          session.audioClients.clear();

          sessions.delete(
            jobId
          );

          console.log(
            `[CLEANUP] Sessão removida: ${jobId}`
          );

          continue;
        }

        // Sessões ativas abandonadas por mais de 30 minutos.
        if (
          !session.stopped &&
          idade >
            30 * 60 * 1000
        ) {

          console.log(
            `[CLEANUP] Encerrando sessão abandonada: ${jobId}`
          );

          session.stopped =
            true;

          session.status =
            "stopped";

          try {

            if (
              session.outputFlushTimer
            ) {

              clearTimeout(
                session.outputFlushTimer
              );

              session.outputFlushTimer =
                null;
            }

          } catch (_) {}

          try {

            if (
              session.reconnectTimer
            ) {

              clearTimeout(
                session.reconnectTimer
              );

              session.reconnectTimer =
                null;
            }

          } catch (_) {}

          try {

            if (
              session.ws
            ) {

              session.ws.close(
                1000,
                "Sessão abandonada"
              );

              session.ws =
                null;
            }

          } catch (_) {}
        }
      }

    } catch (error) {

      console.error(
        "[CLEANUP ERROR]",
        error
      );
    }

  },
  60 * 1000
);

// ============================================================
// SERVIDOR HTTP
// ============================================================
// TESTE DE CONEXÃO COM DEEPL
// ============================================================

app.get(
  "/api/deepl/test",
  async (req, res) => {

    try {

      const apiKey =
        process.env.DEEPL_API_KEY;

      if (!apiKey) {

        return res.status(500).json({

          ok: false,

          provider:
            "DeepL",

          error:
            "DEEPL_API_KEY não está configurada no Render"

        });
      }

      // Chave DeepL Free normalmente termina em :fx.
      // Caso contrário, usamos o endpoint Pro.

      const host =
        apiKey.endsWith(":fx")
          ? "https://api-free.deepl.com"
          : "https://api.deepl.com";

      const response =
        await fetch(
          `${host}/v2/translate`,
          {
            method:
              "POST",

            headers: {

              "Authorization":
                `DeepL-Auth-Key ${apiKey}`,

              "Content-Type":
                "application/json"
            },

            body:
              JSON.stringify({

                text: [
                  "Olá, este é um teste do SI Tradutor Live."
                ],

                target_lang:
                  "EN-US"
              })
          }
        );

      const data =
        await response.json();

      if (!response.ok) {

        console.error(
          "[DEEPL TEST]",
          response.status,
          data
        );

        return res
          .status(response.status)
          .json({

            ok: false,

            provider:
              "DeepL",

            status:
              response.status,

            error:
              data.message ||
              "DeepL recusou a autenticação ou a requisição."

          });
      }

      const translation =
        data?.translations?.[0]?.text ||
        "";

      return res.json({

        ok: true,

        provider:
          "DeepL",

        status:
          response.status,

        translation

      });

    } catch (error) {

      console.error(
        "[DEEPL TEST] Erro:",
        error
      );

      return res.status(500).json({

        ok: false,

        provider:
          "DeepL",

        error:
          error.message ||
          "Erro ao conectar ao DeepL."

      });
    }
  }
);


// ============================================================
// SERVIDOR HTTP
// ============================================================

const server =
  app.listen(
    PORT,
    "0.0.0.0",
    () => {

      console.log(
        "=================================================="
      );

      console.log(
        "SI Tradutor Live iniciado"
      );

      console.log(
        `Porta: ${PORT}`
      );

      console.log(
        `Gemini configurado: ${Boolean(
          GEMINI_API_KEY
        )}`
      );

      console.log(
        `Modelo: ${GEMINI_MODEL}`
      );

      console.log(
        "WebSocket: /ws"
      );

      console.log(
        "=================================================="
      );
    }
  );

// ============================================================
// WEBSOCKET DE ÁUDIO EM TEMPO REAL
// ============================================================

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
      "[WS] Cliente conectado"
    );

    ws.audioSession =
      null;

    try {

      ws.send(
        JSON.stringify({
          ok: true,
          type:
            "connected",
          message:
            "WebSocket do SI Tradutor Live conectado"
        })
      );

    } catch (_) {}

    ws.on(
      "message",
      data => {

        try {

          let text;

          if (
            typeof data ===
            "string"
          ) {

            text =
              data;

          } else if (
            Buffer.isBuffer(data)
          ) {

            text =
              data.toString(
                "utf8"
              );

          } else if (
            data instanceof ArrayBuffer
          ) {

            text =
              Buffer.from(
                data
              ).toString(
                "utf8"
              );

          } else if (
            ArrayBuffer.isView(data)
          ) {

            text =
              Buffer.from(
                data.buffer,
                data.byteOffset,
                data.byteLength
              ).toString(
                "utf8"
              );

          } else {

            text =
              String(data);
          }

          const message =
            JSON.parse(
              text
            );

          // ----------------------------------------------------
          // INSCRIÇÃO NA SESSÃO
          // ----------------------------------------------------

          if (
            message.type ===
            "subscribe"
          ) {

            const jobId =
              message.jobId;

            const session =
              sessions.get(
                jobId
              );

            if (
              !jobId ||
              !session
            ) {

              ws.send(
                JSON.stringify({
                  type:
                    "error",
                  ok: false,
                  error:
                    "Sessão não encontrada",
                  jobId:
                    jobId || null
                })
              );

              return;
            }

            // Remover inscrição anterior.
            if (
              ws.audioSession &&
              ws.audioSession !==
                session
            ) {

              try {

                ws.audioSession
                  .audioClients
                  .delete(ws);

              } catch (_) {}
            }

            criarBufferAudioSession(
              session
            );

            session.audioClients.add(
              ws
            );

            ws.audioSession =
              session;

            registrarDiagnostico(
              session,
              "websocket_subscribed",
              {
                clients:
                  session.audioClients.size
              }
            );

            ws.send(
              JSON.stringify({
                type:
                  "subscribed",
                ok: true,
                jobId,
                nextOutputSeq:
                  session.nextOutputSeq,
                status:
                  session.status
              })
            );

            console.log(
              `[WS] Cliente inscrito na sessão ${jobId} | ` +
              `clientes=${session.audioClients.size}`
            );

            return;
          }

          // ----------------------------------------------------
          // PING
          // ----------------------------------------------------

          if (
            message.type ===
            "ping"
          ) {

            ws.send(
              JSON.stringify({
                type:
                  "pong",
                time:
                  Date.now()
              })
            );
          }

        } catch (error) {

          console.error(
            "[WS MESSAGE ERROR]",
            error.message
          );

          try {

            ws.send(
              JSON.stringify({
                type:
                  "error",
                ok: false,
                error:
                  "Mensagem WebSocket inválida"
              })
            );

          } catch (_) {}
        }
      }
    );

    ws.on(
      "close",
      () => {

        try {

          if (
            ws.audioSession
          ) {

            ws.audioSession
              .audioClients
              .delete(ws);

            registrarDiagnostico(
              ws.audioSession,
              "websocket_disconnected",
              {
                clients:
                  ws.audioSession
                    .audioClients
                    .size
              }
            );
          }

        } catch (_) {}

        console.log(
          "[WS] Cliente desconectado"
        );
      }
    );

    ws.on(
      "error",
      error => {

        console.error(
          "[WS ERROR]",
          error.message
        );

        try {

          if (
            ws.audioSession
          ) {

            ws.audioSession
              .audioClients
              .delete(ws);
          }

        } catch (_) {}
      }
    );
  }
);
