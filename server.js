const express = require("express");
const cors = require("cors");
const multer = require("multer");
const path = require("path");
const fs = require("fs");

const app = express();

const PORT =
  process.env.PORT || 10000;


/* =====================================
   CONFIGURAÇÕES
===================================== */

const ADMIN_PASSWORD =
  process.env.ADMIN_PASSWORD ||
  "troque-esta-senha";


/* =====================================
   CORS
===================================== */

app.use(
  cors({
    origin: "*",
    methods: [
      "GET",
      "POST",
      "PUT",
      "DELETE",
      "OPTIONS"
    ],
    allowedHeaders: [
      "Content-Type",
      "Authorization"
    ]
  })
);


app.use(
  express.json({
    limit: "10mb"
  })
);

app.use(
  express.urlencoded({
    extended: true
  })
);


/* =====================================
   UPLOAD
===================================== */

const uploadFolder =
  path.join(
    __dirname,
    "uploads"
  );


if(
  !fs.existsSync(uploadFolder)
){

  fs.mkdirSync(
    uploadFolder,
    {
      recursive:true
    }
  );

}


const storage =
  multer.diskStorage({

    destination:
      function(
        req,
        file,
        cb
      ){

        cb(
          null,
          uploadFolder
        );

      },

    filename:
      function(
        req,
        file,
        cb
      ){

        const extension =
          path.extname(
            file.originalname
          );

        const name =
          "video-" +
          Date.now() +
          "-" +
          Math.random()
            .toString(36)
            .substring(2,8) +
          extension;

        cb(
          null,
          name
        );

      }

  });


const upload =
  multer({

    storage:storage,

    limits:{
      fileSize:
        500 * 1024 * 1024
    },

    fileFilter:
      function(
        req,
        file,
        cb
      ){

        if(
          file.mimetype.startsWith(
            "video/"
          )
        ){

          cb(
            null,
            true
          );

        }else{

          cb(
            new Error(
              "Envie somente arquivos de vídeo."
            )
          );

        }

      }

  });


/* =====================================
   BANCO SIMPLES DE CHAT
===================================== */

const messages = [];


/*
  Exemplo:

  {
    id: "123",
    clientId: "abc",
    sender: "client",
    message: "Olá",
    date: "..."
  }

*/


/* =====================================
   ID DAS MENSAGENS
===================================== */

function generateId(){

  return Date.now() +
    "-" +
    Math.random()
      .toString(36)
      .substring(2,10);

}


/* =====================================
   HEALTH
===================================== */

app.get(
  "/api/health",
  function(req,res){

    res.json({

      ok:true,

      service:
        "LinguaLive",

      message:
        "Servidor online",

      time:
        new Date().toISOString()

    });

  }
);


/* =====================================
   UPLOAD DE VÍDEO
===================================== */

app.post(
  "/api/test-upload",

  upload.single("video"),

  function(req,res){

    try{

      if(!req.file){

        return res
          .status(400)
          .json({

            ok:false,

            error:
              "Nenhum vídeo foi enviado."

          });

      }


      const jobId =
        generateId();


      console.log(
        "Vídeo recebido:",
        req.file.filename
      );


      res.json({

        ok:true,

        jobId:jobId,

        message:
          "Vídeo recebido com sucesso.",

        filename:
          req.file.filename,

        targetLang:
          req.body.targetLang || "pt"

      });


    }catch(error){

      console.error(error);

      res
        .status(500)
        .json({

          ok:false,

          error:
            "Erro ao receber o vídeo."

        });

    }

  }
);


/* =====================================
   CHAT — CLIENTE ENVIA
===================================== */

app.post(
  "/api/chat/send",
  function(req,res){

    try{

      const clientId =
        String(
          req.body.clientId || ""
        ).trim();


      const message =
        String(
          req.body.message || ""
        ).trim();


      if(!clientId){

        return res
          .status(400)
          .json({

            error:
              "clientId é obrigatório."

          });

      }


      if(!message){

        return res
          .status(400)
          .json({

            error:
              "A mensagem está vazia."

          });

      }


      if(message.length > 2000){

        return res
          .status(400)
          .json({

            error:
              "Mensagem muito grande."

          });

      }


      const newMessage = {

        id:
          generateId(),

        clientId:
          clientId,

        sender:
          "client",

        message:
          message,

        date:
          new Date().toISOString()

      };


      messages.push(
        newMessage
      );


      console.log(
        "NOVA MENSAGEM DO CLIENTE:"
      );

      console.log(
        clientId +
        ": " +
        message
      );


      res.json({

        ok:true,

        message:
          newMessage

      });


    }catch(error){

      console.error(error);

      res
        .status(500)
        .json({

          error:
            "Erro ao enviar mensagem."

        });

    }

  }
);


/* =====================================
   CHAT — CLIENTE RECEBE
===================================== */

app.get(
  "/api/chat/messages/:clientId",

  function(req,res){

    const clientId =
      req.params.clientId;


    const clientMessages =
      messages.filter(
        function(item){

          return item.clientId ===
            clientId;

        }
      );


    res.json({

      ok:true,

      messages:
        clientMessages

    });

  }
);


/* =====================================
   ADMIN — VER MENSAGENS
===================================== */

app.get(
  "/api/admin/messages",

  function(req,res){

    const password =
      req.headers[
        "x-admin-password"
      ];


    if(
      password !==
      ADMIN_PASSWORD
    ){

      return res
        .status(401)
        .json({

          ok:false,

          error:
            "Senha de administrador inválida."

        });

    }


    res.json({

      ok:true,

      messages:
        messages

    });

  }
);


/* =====================================
   ADMIN — RESPONDER
===================================== */

app.post(
  "/api/admin/reply",

  function(req,res){

    const password =
      req.headers[
        "x-admin-password"
      ];


    if(
      password !==
      ADMIN_PASSWORD
    ){

      return res
        .status(401)
        .json({

          ok:false,

          error:
            "Senha de administrador inválida."

        });

    }


    const clientId =
      String(
        req.body.clientId || ""
      ).trim();


    const message =
      String(
        req.body.message || ""
      ).trim();


    if(
      !clientId ||
      !message
    ){

      return res
        .status(400)
        .json({

          ok:false,

          error:
            "clientId e message são obrigatórios."

        });

    }


    const newMessage = {

      id:
        generateId(),

      clientId:
        clientId,

      sender:
        "admin",

      message:
        message,

      date:
        new Date().toISOString()

    };


    messages.push(
      newMessage
    );


    console.log(
      "RESPOSTA ENVIADA PARA:",
      clientId
    );


    res.json({

      ok:true,

      message:
        newMessage

    });

  }
);


/* =====================================
   ADMIN — LISTAR CLIENTES
===================================== */

app.get(
  "/api/admin/clients",

  function(req,res){

    const password =
      req.headers[
        "x-admin-password"
      ];


    if(
      password !==
      ADMIN_PASSWORD
    ){

      return res
        .status(401)
        .json({

          ok:false,

          error:
            "Senha inválida."

        });

    }


    const clientIds =
      [
        ...new Set(
          messages.map(
            item =>
              item.clientId
          )
        )
      ];


    const clients =
      clientIds.map(
        function(clientId){

          const clientMessages =
            messages.filter(
              item =>
                item.clientId ===
                clientId
            );


          return {

            clientId:

              clientId,

            messages:

              clientMessages,

            lastMessage:

              clientMessages[
                clientMessages.length - 1
              ]

          };

        }
      );


    res.json({

      ok:true,

      clients:

        clients

    });

  }
);


/* =====================================
   ARQUIVOS DE UPLOAD
===================================== */

app.use(
  "/uploads",
  express.static(
    uploadFolder
  )
);


/* =====================================
   ERROS DO MULTER
===================================== */

app.use(
  function(
    error,
    req,
    res,
    next
  ){

    console.error(
      error
    );


    if(
      error instanceof multer.MulterError
    ){

      return res
        .status(400)
        .json({

          ok:false,

          error:
            "Erro no upload: " +
            error.message

        });

    }


    if(error){

      return res
        .status(400)
        .json({

          ok:false,

          error:
            error.message ||
            "Erro no servidor."

        });

    }


    next();

  }
);


/* =====================================
   404
===================================== */

app.use(
  function(req,res){

    res
      .status(404)
      .json({

        ok:false,

        error:
          "Endpoint não encontrado."

      });

  }
);


/* =====================================
   INICIAR SERVIDOR
===================================== */

app.listen(
  PORT,
  "0.0.0.0",
  function(){

    console.log(
      "================================"
    );

    console.log(
      "LinguaLive Backend ONLINE"
    );

    console.log(
      "Porta:",
      PORT
    );

    console.log(
      "================================"
    );

  }
);
