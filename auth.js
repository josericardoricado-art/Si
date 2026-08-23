// auth.js
// Funções de autenticação: geração/verificação de token JWT e middleware
// que protege rotas exigindo login.

const jwt = require("jsonwebtoken");

// IMPORTANTE: em produção, defina isso como variável de ambiente
// (ex: process.env.JWT_SECRET) e nunca deixe hardcoded no código.
const JWT_SECRET = process.env.JWT_SECRET || "troque-isto-em-producao-por-um-segredo-forte";

function generateToken(user) {
  return jwt.sign(
    { id: user.id, email: user.email, name: user.name },
    JWT_SECRET,
    { expiresIn: "7d" }
  );
}

function requireAuth(req, res, next) {
  const header = req.headers.authorization;
  if (!header || !header.startsWith("Bearer ")) {
    return res.status(401).json({ error: "Não autenticado. Faça login novamente." });
  }

  const token = header.replace("Bearer ", "");
  try {
    const payload = jwt.verify(token, JWT_SECRET);
    req.user = payload; // { id, email, name }
    next();
  } catch (err) {
    return res.status(401).json({ error: "Sessão expirada ou inválida. Faça login novamente." });
  }
}

module.exports = { generateToken, requireAuth, JWT_SECRET };
