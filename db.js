// db.js
// Banco de dados em arquivo (SQLite) — não precisa instalar Postgres/MySQL
// separado. Guarda usuários e os jobs de dublagem de cada um.

const Database = require("better-sqlite3");
const path = require("path");

const db = new Database(path.join(__dirname, "dublagem.db"));

db.pragma("journal_mode = WAL");

db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );

  CREATE TABLE IF NOT EXISTS jobs (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    status TEXT NOT NULL,
    progress INTEGER DEFAULT 0,
    stage TEXT,
    input_path TEXT,
    output_path TEXT,
    target_lang TEXT,
    original_filename TEXT,
    error TEXT,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
  );
`);

module.exports = db;
