const PLANS={
  free:{name:'Grátis',price:0,minutes:10,voices:false},
  basic:{name:'Creator',price:Number(process.env.PLAN_BASIC_PRICE||100),minutes:300,voices:true},
  pro:{name:'Pro',price:Number(process.env.PLAN_PRO_PRICE||200),minutes:1200,voices:true}
};
async function mp(path,options={}){if(!process.env.MP_ACCESS_TOKEN)throw new Error('Mercado Pago ainda não configurado.');const r=await fetch('https://api.mercadopago.com'+path,{...options,headers:{'Content-Type':'application/json','Authorization':'Bearer '+process.env.MP_ACCESS_TOKEN,...(options.headers||{})}});const text=await r.text();let data;try{data=JSON.parse(text)}catch{data={raw:text}}if(!r.ok)throw new Error(data.message||'Erro no Mercado Pago');return data;}
async function createSubscription(plan,user){const planId=plan==='basic'?process.env.MP_PLAN_BASIC_ID:process.env.MP_PLAN_PRO_ID;if(!planId)throw new Error('ID do plano do Mercado Pago não configurado.');return mp('/preapproval',{method:'POST',body:JSON.stringify({preapproval_plan_id:planId,reason:`Dublagem AI - ${PLANS[plan].name}`,external_reference:user.id,payer_email:user.email,back_url:process.env.APP_URL||'http://localhost:3000'})});}
module.exports={PLANS,createSubscription};
