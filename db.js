const fs=require('fs');const path=require('path');const Database=require('better-sqlite3');
const dir=path.join(__dirname,'data');fs.mkdirSync(dir,{recursive:true});
const db=new Database(path.join(dir,'dublagem.db'));db.pragma('journal_mode = WAL');
db.exec(`CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,name TEXT NOT NULL,email TEXT UNIQUE NOT NULL,password_hash TEXT NOT NULL,plan TEXT NOT NULL DEFAULT 'free',subscription_status TEXT NOT NULL DEFAULT 'inactive',subscription_id TEXT,created_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,original_name TEXT,filename TEXT,status TEXT,progress INTEGER DEFAULT 0,stage TEXT,target_lang TEXT,voice_mode TEXT DEFAULT 'standard',voice_path TEXT,output_path TEXT,error TEXT,created_at INTEGER NOT NULL,finished_at INTEGER);
CREATE TABLE IF NOT EXISTS payments(id TEXT PRIMARY KEY,user_id TEXT NOT NULL,plan TEXT,status TEXT,external_id TEXT,created_at INTEGER NOT NULL);`);
module.exports=db;
