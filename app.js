const API=location.origin;const token=()=>localStorage.getItem('dai_token');const user=()=>JSON.parse(localStorage.getItem('dai_user')||'null');
async function api(path,opt={}){opt.headers={...(opt.headers||{}),...(token()?{Authorization:'Bearer '+token()}: {})};const r=await fetch(API+path,opt);const d=await r.json().catch(()=>({}));if(!r.ok)throw Error(d.error||'Erro');return d}
function logout(){localStorage.clear();location.href='login.html'}
async function loadMe(){const d=await api('/api/me');localStorage.setItem('dai_user',JSON.stringify(d.user));document.querySelectorAll('[data-user]').forEach(x=>x.textContent=d.user.name);document.querySelectorAll('[data-plan]').forEach(x=>x.textContent=d.user.plan)}
