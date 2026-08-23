# Dublagem AI 2.0

Projeto evoluído a partir da primeira versão enviada: login/cadastro, painel do usuário, histórico, dublagem, clonagem de voz, planos e preparação para Mercado Pago/deploy.

## Arquitetura
- Frontend: HTML/CSS/JS servido pelo Node.
- Backend: Node.js + Express.
- Banco: SQLite (`better-sqlite3`).
- Auth: bcrypt + JWT.
- IA: Whisper + Argos Translate + Coqui XTTS v2 + FFmpeg.
- Pagamentos: Mercado Pago Assinaturas, opcional até configurar credenciais.

## Instalação local
1. Instale Node.js 18+, Python 3.10+ e FFmpeg.
2. `cd backend && npm install`
3. `python3 -m venv worker/venv && source worker/venv/bin/activate`
4. `pip install -r worker/requirements.txt`
5. Configure idiomas do Argos conforme os pares que pretende oferecer.
6. Copie `.env.example` para `.env` e altere `JWT_SECRET`.
7. `npm start`
8. Abra `http://localhost:3000`.

## Mercado Pago
A integração usa a API de assinaturas. Crie os planos recorrentes no painel do Mercado Pago e informe `MP_PLAN_BASIC_ID` e `MP_PLAN_PRO_ID`, além de `MP_ACCESS_TOKEN` e `APP_URL`.

O webhook é `/api/webhooks/mercadopago`. Em produção, use HTTPS e valide a assinatura/autenticidade do webhook conforme a configuração atual da sua conta.

## Clonagem de voz
O plano Creator/Pro aceita uma amostra de voz e passa `speaker_wav` ao XTTS v2. Use somente voz própria ou com autorização do titular. A qualidade depende da amostra e do modelo.

## Produção
Não use SQLite/armazenamento local como solução definitiva se houver múltiplas instâncias. Para produção, mova banco para PostgreSQL, arquivos para S3/R2, fila para Redis/BullMQ e workers para máquinas com GPU. Use HTTPS, rate limiting, validação MIME, antivírus/limites de upload e segredo JWT forte.
