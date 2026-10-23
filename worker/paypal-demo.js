// GET /demo : judge-facing web demo (no phone needed). Paste a call -> AI action cards -> confirm USD -> PayPal sandbox invoice -> paid status.
export const DEMO_HTML = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Sol: calls to PayPal invoices (sandbox demo)</title>
<style>
:root{--bg:#0d1117;--card:#161b22;--fg:#e6edf3;--mut:#8b949e;--acc:#ffb020;--pp:#0070e0;--ok:#2ea043}
*{box-sizing:border-box}body{margin:0;font:16px/1.5 system-ui,sans-serif;background:var(--bg);color:var(--fg)}
main{max-width:760px;margin:0 auto;padding:24px 16px 60px}h1{font-size:26px;margin:0 0 4px}h1 span{color:var(--acc)}
.sub{color:var(--mut);margin:0 0 18px}textarea{width:100%;min-height:120px;background:var(--card);color:var(--fg);border:1px solid #30363d;border-radius:12px;padding:12px;font:inherit}
button{font:inherit;border:0;border-radius:10px;padding:10px 16px;cursor:pointer;background:var(--acc);color:#111;font-weight:600}button:disabled{opacity:.5;cursor:wait}
.pp{background:var(--pp);color:#fff}.ghost{background:#30363d;color:var(--fg)}.row{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:10px 0}
.ex{background:#21262d;color:var(--fg);font-weight:400;font-size:14px;padding:6px 10px}
.card{background:var(--card);border:1px solid #30363d;border-radius:14px;padding:14px;margin:12px 0}.tag{display:inline-block;font-size:12px;font-weight:700;border-radius:6px;padding:2px 8px;margin-right:8px;text-transform:uppercase}
.owed{background:#1f6feb33;color:#79c0ff}.payment{background:#f8514933;color:#ff7b72}.callback{background:#2ea04333;color:#7ee787}.reminder{background:#d2992233;color:#e3b341}
.q{color:var(--mut);font-size:14px;font-style:italic}input{font:inherit;width:110px;padding:8px;border-radius:8px;border:1px solid #30363d;background:#0d1117;color:var(--fg)}
.st{font-weight:600}.paid{color:var(--ok)}.small{font-size:13px;color:var(--mut)}a{color:#79c0ff}
</style></head><body><main>
<h1>Sol <span>→</span> PayPal</h1>
<p class="sub">Sol is an AI phone secretary. It answers your calls, an AI reads each call and makes action cards, and an "owes you" card becomes a PayPal invoice with one tap. <b>Sandbox only: no real money.</b></p>
<label class="small" for="call">Paste what the caller said (or try an example):</label>
<div class="row"><button class="ex" data-t="Hi, it's Andrii. Thanks for fixing my website! I'll pay you the 150 dollars tomorrow. Also please call me back at 4 pm about the logo.">Andrii: $150 for a website</button>
<button class="ex" data-t="Привіт, це Олена. Я поверну тобі 40 доларів за квитки в п'ятницю. І нагадай мені про зустріч у понеділок.">Олена (Ukrainian): $40</button>
<button class="ex" data-t="Hey, Mark here. I owe you 25 euros for dinner, I'll send it tonight.">Mark: €25</button></div>
<textarea id="call" placeholder="Caller: ..."></textarea>
<div class="row"><input id="name" placeholder="Caller name" style="width:180px"><button id="go">Read the call with AI</button><span id="info" class="small"></span></div>
<div id="cards"></div>
<p class="small">How it works: the cards come from <code>POST /call/actions</code> (NVIDIA Nemotron on Nebius Token Factory, structured JSON). An invoice comes from <code>POST /paypal/invoice</code> (PayPal Invoicing v2: create + send, which returns the payer link). Paid status comes from <code>GET /paypal/invoice/status</code> (poll) and <code>POST /paypal/webhook</code> (INVOICING.INVOICE.PAID). To pay, open the link and log in with any PayPal <b>sandbox personal</b> account (see the README). Source: <a href="https://github.com/Solar-DePIN-Hub/solarchik-assistant">github.com/Solar-DePIN-Hub/solarchik-assistant</a></p>
</main><script>
const $=(s)=>document.querySelector(s);const esc=(s)=>String(s??"").replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
document.querySelectorAll(".ex").forEach(b=>b.onclick=()=>{$("#call").value=b.dataset.t;$("#name").value=b.textContent.split(":")[0].split(" ")[0]});
$("#go").onclick=async()=>{const t=$("#call").value.trim();if(!t)return;$("#go").disabled=true;$("#info").textContent="AI is reading the call…";$("#cards").innerHTML="";
const d=new Date();const today=d.toISOString().slice(0,10);const t0=Date.now();
try{const r=await fetch("/call/actions",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({fiat:true,language:/[а-яіїєґ]/i.test(t)?"uk":"en",today,calls:[{id:"demo|"+t0,name:$("#name").value||"Caller",note:t,date:today}]})});
const j=await r.json();if(!j.ok)throw new Error(j.error||r.status);$("#info").textContent=j.actions.length+" card(s) · "+j.model+" · "+((Date.now()-t0)/1000).toFixed(1)+" s";
if(!j.actions.length)$("#cards").innerHTML='<div class="card">Nothing to do in this call.</div>';j.actions.forEach(render)}catch(e){$("#info").textContent="Error: "+e.message}$("#go").disabled=false};
function render(a,i){const el=document.createElement("div");el.className="card";
let h='<span class="tag '+a.type+'">'+(a.type==="owed"?"owes you":a.type)+'</span><b>'+esc(a.text)+'</b>'+(a.quote?'<div class="q">“'+esc(a.quote)+'”</div>':"");
if(a.type==="owed"){const cur=a.token==="EUR"?"EUR":"USD";const sug=a.token==="EUR"?a.amount:(a.usdSuggested||"");
h+='<div class="row"><span class="small">Heard: '+esc(a.amount+" "+a.tokenWord)+'. Confirm the amount to request:</span></div><div class="row"><input type="number" min="0.01" step="0.01" value="'+sug+'"> <b>'+cur+'</b>'+
'<input type="email" placeholder="payer email (optional)" style="width:220px"><button class="pp">Request via PayPal</button></div><div class="row res"></div>'}
if(a.check&&(a.check.answer||a.check.links.length))h+='<div class="q">🔎 Web check (Tavily): '+esc(a.check.answer)+' '+a.check.links.map(l=>'<a target="_blank" rel="noopener" href="'+esc(l.url)+'">'+esc(l.title||"source")+'</a>').join(" · ")+'</div>';
el.innerHTML=h;$("#cards").appendChild(el);if(a.type!=="owed")return;
const [amt,mail]=el.querySelectorAll("input");const btn=el.querySelector("button");const res=el.querySelector(".res");const cur=a.token==="EUR"?"EUR":"USD";
btn.onclick=async()=>{const v=Number(amt.value);if(!(v>0)){res.textContent="Enter the amount first.";return}
if(!confirm("Create a PayPal (sandbox) invoice for "+v.toFixed(2)+" "+cur+"?"))return;btn.disabled=true;res.textContent="Creating invoice…";
try{const r=await fetch("/paypal/invoice",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({confirmed:true,amount:v,currency:cur,text:a.text,payerName:$("#name").value,payerEmail:mail.value,callId:a.callId})});
const j=await r.json();if(!j.ok)throw new Error(j.error);res.innerHTML='Invoice <code>'+esc(j.id)+'</code> · <a target="_blank" href="'+esc(j.payLink)+'">Open pay link</a> · <span class="st">sent</span> <button class="ghost">Copy link</button>';
res.querySelector(".ghost").onclick=()=>navigator.clipboard.writeText(j.payLink);poll(j.id,res.querySelector(".st"))}catch(e){res.textContent="Error: "+e.message;btn.disabled=false}}}
async function poll(id,st){for(let k=0;k<120;k++){await new Promise(r=>setTimeout(r,5000));try{const j=await(await fetch("/paypal/invoice/status?id="+id)).json();if(j.ok){st.textContent=j.state+(j.paid?" ("+j.paid+")":"");if(j.state==="paid"){st.className="st paid";st.textContent="✓ paid "+(j.paid||"");return}}}catch{}}}
</script></body></html>`;
