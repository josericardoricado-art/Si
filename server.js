app.post(
  "/api/test-upload",
  uploadVideo.single("video"),
  (req, res) => {

    try {

      if (!req.file) {
        return res.status(400).json({
          ok: false,
          error: "Nenhum arquivo recebido."
        });
      }

      console.log("UPLOAD TESTE:");
      console.log("Nome:", req.file.originalname);
      console.log("Tamanho:", req.file.size);
      console.log("Arquivo:", req.file.path);

      res.json({
        ok: true,
        message: "Upload funcionando!",
        filename: req.file.filename,
        size: req.file.size
      });

    } catch (error) {

      console.error("TEST UPLOAD ERROR:", error);

      res.status(500).json({
        ok: false,
        error: error.message
      });

    }
  }
);
