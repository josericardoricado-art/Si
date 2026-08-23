const jwt=require('jsonwebtoken');
const SECRET=process.env.JWT_SECRET||'dev-only-change-this-secret';
function sign(user){return jwt.sign({sub:user.id,email:user.email,plan:user.plan},SECRET,{expiresIn:'7d'});}
function auth(req,res,next){const h=req.headers.authorization||'';const token=h.startsWith('Bearer ')?h.slice(7):null;if(!token)return res.status(401).json({error:'Faça login para continuar.'});try{req.user=jwt.verify(token,SECRET);next()}catch{return res.status(401).json({error:'Sessão expirada. Entre novamente.'})}}
module.exports={sign,auth};
