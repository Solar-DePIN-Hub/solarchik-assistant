/** The "Pay from any chain" page for one Sol request (served at /r/:id). Vanilla JS, no third-party requests. */
import qrcode from "./vendor/qrcode.mjs";

/** A QR code as an SVG string (server-side, so the page loads nothing from elsewhere). */
export function qrSvg(text, cell = 6, margin = 4) {
  const q = qrcode(0, "M");
  q.addData(String(text).slice(0, 600));
  q.make();
  const n = q.getModuleCount();
  const size = (n + margin * 2) * cell;
  let d = "";
  for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) if (q.isDark(r, c)) d += `M${(c + margin) * cell} ${(r + margin) * cell}h${cell}v${cell}h-${cell}z`;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${size} ${size}" width="${size}" height="${size}" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path d="${d}" fill="#111"/></svg>`;
}

/** What the QR carries: a standard payment URI where one is well defined, otherwise the bare address. */
export function qrPayload(chain, address, amountFormatted, memo) {
  if (chain === "btc") return `bitcoin:${address}?amount=${amountFormatted}`;
  if (chain === "ltc") return `litecoin:${address}?amount=${amountFormatted}`;
  if (chain === "doge") return `dogecoin:${address}?amount=${amountFormatted}`;
  if (memo) return address; // memo chains: the memo is shown in text, never hidden in a QR
  return address;
}

export const PAGE_CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

export function pageHtml(id) {
  const safeId = String(id).replace(/[^a-z0-9]/g, "");
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pay with Sol · any chain</title><meta name="robots" content="noindex">
<style>
:root{--bg:#0e1116;--card:#171b22;--line:#2a303b;--text:#f2f4f7;--muted:#9aa3b2;--sun:#f5c542;--ok:#3ddc84;--warn:#ffb020;--bad:#ff5d5d}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:16px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
main{max-width:520px;margin:0 auto;padding:20px 16px 48px}h1{font-size:26px;line-height:1.2;margin:6px 0 4px}
.k{color:var(--sun);font-weight:800;letter-spacing:.06em;font-size:13px;text-transform:uppercase}.m{color:var(--muted);font-size:14px}
.card{background:var(--card);border:1px solid var(--line);border-radius:18px;padding:16px;margin-top:14px}
button,.btn{display:block;width:100%;border:0;border-radius:14px;padding:14px;font-weight:800;font-size:16px;background:var(--sun);color:#111;cursor:pointer;text-align:center;text-decoration:none}
button.ghost{background:transparent;color:var(--text);border:1px solid var(--line)}button:disabled{opacity:.5}
select,input{width:100%;padding:12px;border-radius:12px;border:1px solid var(--line);background:#0b0e12;color:var(--text);font-size:16px;margin:6px 0 12px}
label{font-size:13px;color:var(--muted);font-weight:700}.row{display:flex;justify-content:space-between;gap:10px;padding:6px 0;border-bottom:1px solid var(--line)}
.row:last-child{border:0}.row b{text-align:right;word-break:break-all}.addr{font:600 14px ui-monospace,monospace;word-break:break-all;background:#0b0e12;padding:10px;border-radius:10px}
.qr{background:#fff;border-radius:14px;padding:8px;width:220px;margin:12px auto}.qr svg{width:100%;height:auto;display:block}
.req{border-left:3px solid var(--warn);padding:8px 12px;margin-top:12px;font-size:14px}.req p{margin:4px 0}
.st{font-weight:800;font-size:18px}.ok{color:var(--ok)}.warn{color:var(--warn)}.bad{color:var(--bad)}.wait{color:var(--sun)}
.hide{display:none}.err{color:var(--bad);font-size:14px;margin-top:6px}a{color:var(--sun)}
</style></head><body><main>
<div class="k">Sol · payment request</div>
<h1 id="title">Loading…</h1>
<div class="m" id="sub"></div>
<div class="card hide" id="paid"></div>
<div id="choose" class="hide">
 <div class="card"><b>Have a Solana wallet?</b><div class="m">Pay directly with Phantom, Solflare or any Solana Pay wallet.</div>
  <div style="margin-top:10px"><a class="btn" id="solpay" href="#">Open in Solana wallet</a></div></div>
 <div class="card"><b>Pay from another network</b>
  <div class="m">USDC on Base, USDT on Tron, ETH, BTC, NEAR and more. The amount is converted for you, and the requester receives exactly <span class="amt"></span> on Solana. Powered by Aurora Intents (NEAR Intents).</div>
  <div style="margin-top:12px"><label for="net">Network you pay from</label><select id="net"></select>
  <label for="asset" id="assetLabel">Asset</label><select id="asset"></select>
  <label for="refund">Your address on <span id="netName">that network</span> (refunds go here if anything goes wrong)</label>
  <input id="refund" autocomplete="off" spellcheck="false" placeholder="">
  <div class="m" id="inc" style="color:#f5c542"></div><button id="go">Get the exact amount</button><div class="err" id="err"></div></div></div>
</div>
<div id="pay" class="hide"></div>
<p class="m" style="margin-top:22px">Only pay people you know. Sol never asks for keys or seed phrases, and this page never touches your wallet.</p>
</main><script>
const ID=${JSON.stringify(safeId)};let REQ=null,SRC=[],timer=null;
const $=s=>document.querySelector(s);const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const short=a=>a&&a.length>12?a.slice(0,4)+"…"+a.slice(-4):a;
const PH={eth:"0x…",base:"0x…",arb:"0x…",op:"0x…",pol:"0x…",bsc:"0x…",avax:"0x…",gnosis:"0x…",btc:"bc1… or 1… / 3…",tron:"T…",ton:"UQ… or EQ…",near:"you.near",sol:"Solana address",sui:"0x…",aptos:"0x…",stellar:"G…",xrp:"r…",ltc:"ltc1… or L…",doge:"D…",zec:"t1…",bch:"bitcoincash:q…",cardano:"addr1…"};
async function api(p,o){const r=await fetch(p,o);const j=await r.json().catch(()=>({}));if(!r.ok)throw Object.assign(new Error(j.error||r.status),{j});return j}
function head(){const who=REQ.who&&REQ.who!=="Someone"?REQ.who:"you";$("#title").textContent=REQ.from?REQ.from+" asks "+who+" for "+REQ.amount+" "+REQ.token:(who==="you"?"Payment request: ":who+", a payment request: ")+REQ.amount+" "+REQ.token;
 $("#sub").textContent=(REQ.note?REQ.note+" · ":"")+"Paid to Solana address "+short(REQ.to);document.querySelectorAll(".amt").forEach(e=>e.textContent=REQ.amount+" "+REQ.token)}
function showPaid(){const p=REQ.paid||{};$("#choose").classList.add("hide");$("#pay").classList.add("hide");const t=(p.solanaTxs||[])[0];
 $("#paid").innerHTML='<div class="st ok">Paid ✓</div><div class="m">'+esc(p.amountOutFormatted||REQ.amount)+" "+esc(REQ.token)+" arrived on Solana"+(p.chain?" (paid from "+esc(p.symbol)+" on "+esc(p.chainName||p.chain)+")":"")+".</div>"+(t?'<p><a target="_blank" rel="noopener" href="https://solscan.io/tx/'+encodeURIComponent(t)+'">View the Solana transaction</a></p>':"");$("#paid").classList.remove("hide")}
function nets(){const by={};SRC.forEach(s=>{(by[s.chain]=by[s.chain]||{name:s.chainName,list:[]}).list.push(s)});return by}
function fillNets(){const by=nets();const keys=Object.keys(by).sort((a,b)=>{const p=["base","arb","eth","tron","bsc","pol","op","btc","near","ton","sol"];const ia=p.indexOf(a),ib=p.indexOf(b);return (ia<0?99:ia)-(ib<0?99:ib)||by[a].name.localeCompare(by[b].name)});
 $("#net").innerHTML=keys.map(k=>'<option value="'+k+'">'+esc(by[k].name)+"</option>").join("");fillAssets()}
function fillAssets(){const by=nets(),c=$("#net").value,l=(by[c]||{list:[]}).list.filter(s=>!(s.chain==="sol"&&s.symbol===REQ.token));
 $("#asset").innerHTML=l.map(s=>'<option value="'+esc(s.assetId)+'">'+esc(s.symbol)+"</option>").join("");$("#assetLabel").textContent="Asset ("+l.length+(l.length===1?" asset":" assets")+" supported on "+((by[c]||{}).name||"")+")";
 $("#netName").textContent=(by[c]||{}).name||"that network";$("#refund").placeholder=PH[c]||"address"}
function payPanel(d,reqs){const qr='<div class="qr"><img alt="QR code of the deposit address" style="width:100%" src="/anychain/qr?d='+encodeURIComponent(d.depositAddress)+"&c="+encodeURIComponent(d.chain)+"&a="+encodeURIComponent(d.amountInFormatted)+'"></div>';
 $("#pay").innerHTML='<div class="card"><div class="row"><span>Sending from network</span><b>'+esc(d.chainName)+'</b></div><div class="row"><span>Supported asset</span><b>'+esc(d.symbol)+' (1 asset)</b></div><div class="row"><span>Send exactly</span><b>'+esc(d.amountInFormatted)+" "+esc(d.symbol)+(d.amountInUsd?' <span class="m">≈ $'+Number(d.amountInUsd).toFixed(2)+"</span>":"")+'</b></div><div class="row"><span></span><span class="m">includes the conversion and network fees</span></div><div class="row"><span>They receive</span><b>'+esc(REQ.amount)+" "+esc(REQ.token)+' on Solana</b></div><div class="row"><span>Send before</span><b id="dl"></b></div>'+
 '<p><label>Deposit address</label></p><div class="addr" id="da">'+esc(d.depositAddress)+"</div>"+(d.depositMemo?'<p><label>Memo (required)</label></p><div class="addr">'+esc(d.depositMemo)+"</div>":"")+
 '<div style="margin-top:8px"><button class="ghost" id="copy">Copy address</button></div>'+qr+'<div class="req"><b>Transfer requirements</b>'+reqs.map(x=>"<p>"+esc(x)+"</p>").join("")+"</div>"+
 '<p class="st wait" id="st">Waiting for your transfer</p><div class="m" id="stb"></div>'+
 '<details style="margin-top:10px"><summary class="m">Already sent? Paste the transaction hash to speed it up</summary><input id="tx" placeholder="transaction hash" spellcheck="false"><button class="ghost" id="txgo">Submit hash</button></details>'+
 '<div style="margin-top:12px"><button class="ghost" id="again">Pay another way</button></div></div>';
 const dl=new Date(d.deadline);$("#dl").textContent=isNaN(dl)?d.deadline:dl.toLocaleString([], {hour:"2-digit",minute:"2-digit",day:"numeric",month:"short"});
 $("#copy").onclick=()=>navigator.clipboard&&navigator.clipboard.writeText(d.depositAddress).then(()=>$("#copy").textContent="Copied ✓");
 $("#again").onclick=()=>{clearInterval(timer);$("#pay").classList.add("hide");$("#choose").classList.remove("hide")};
 $("#txgo").onclick=async()=>{try{const j=await api("/anychain/submit",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({id:ID,depositAddress:d.depositAddress,txHash:$("#tx").value.trim()})});setInfo(j.info)}catch(e){$("#stb").textContent="That hash was not accepted: "+e.message}};
 $("#choose").classList.add("hide");$("#pay").classList.remove("hide");poll()}
function tooLow(d){const m=/at least (\\d+)/.exec(d||"");const dec=REQ.token==="SOL"?9:6;const min=m?Number(m[1])/10**dec:null;
 return "This network's bridge needs a bigger payment"+(min?" (at least "+(+min.toFixed(4))+" "+REQ.token+" for the requester)":"")+". Pick another network, e.g. Base, Arbitrum or Ethereum."}
function setInfo(i){if(!i)return;$("#st").className="st "+i.tone;$("#st").textContent=i.title;$("#stb").textContent=i.body}
function poll(){clearInterval(timer);const tick=async()=>{try{const j=await api("/anychain/status?id="+ID);REQ=j.request;if(REQ.status==="paid"){clearInterval(timer);showPaid();return}setInfo(j.info);
 const d=(REQ.deposits||[]).at(-1);if(d&&["REFUNDED","FAILED"].includes(d.status))clearInterval(timer);if(d&&d.status==="PENDING_DEPOSIT"&&new Date(d.deadline)<new Date()){clearInterval(timer);setInfo({tone:"warn",title:"Quote expired",body:"Do not send to this address any more. Tap Pay another way for a fresh quote."})}}catch(e){}};tick();timer=setInterval(tick,6000)}
$("#net").onchange=()=>{fillAssets();incidents()};
$("#go").onclick=async()=>{$("#err").textContent="";$("#go").disabled=true;$("#go").textContent="Getting a quote…";
 try{const j=await api("/anychain/quote",{method:"POST",headers:{"content-type":"application/json"},body:JSON.stringify({id:ID,origin:$("#asset").value,refundTo:$("#refund").value.trim()})});payPanel(j.deposit,j.requirements)}
 catch(e){const c=e.j&&e.j.error;$("#err").textContent=c==="BAD_REFUND_ADDRESS"?"That doesn't look like a "+(e.j.chainName||"")+" address. Paste your own address on the network you pay from.":c==="ALREADY_PAID"?"This request is already paid.":c==="TOO_MANY_QUOTES"?"Too many open quotes for this request. Use the one you already have, or try again in an hour.":c==="RATE_LIMITED"?"Too many tries. Please wait a few minutes.":c==="AURORA_ERROR"&&/too low/i.test(e.j.detail||"")?tooLow(e.j.detail):c==="AURORA_ERROR"?"No quote right now ("+(e.j.detail||"try again")+"). Try another asset or a bit later.":"Couldn't get a quote ("+(c||e.message)+")."}
 finally{$("#go").disabled=false;$("#go").textContent="Get the exact amount"}};
function resumeReqs(d){return["Send only "+d.symbol+" from the "+d.chainName+" network. 1 asset is supported for this address.","Send exactly "+d.amountInFormatted+" "+d.symbol+". Less is refunded; any extra above the quote is refunded to you.","Send before the time shown above. After that, do not use this address; get a new quote.","Sending another asset or using another network may result in loss of funds."]}
async function incidents(){const c=$("#net").value;$("#inc").textContent="";if(!c)return;try{const j=await api("/anychain/incidents?chain="+encodeURIComponent(c));if(c===$("#net").value&&j.incidents&&j.incidents.length)$("#inc").textContent="Heads-up for "+((nets()[c]||{}).name||c)+": "+j.incidents.map(i=>i.text).join(" · ")}catch(e){}}
(async()=>{try{REQ=await api("/anychain/request/"+ID)}catch(e){$("#title").textContent=e.message==="NOT_FOUND"?"This request doesn't exist or has expired":"Couldn't load this request";$("#sub").textContent="";return}
 head();if(REQ.status==="paid")return showPaid();
 if(REQ.solanaPay)$("#solpay").href=REQ.solanaPay;else $("#solpay").parentNode.parentNode.classList.add("hide");
 $("#choose").classList.remove("hide");
 const live=(REQ.deposits||[]).filter(d=>!["REFUNDED","FAILED"].includes(d.status)&&new Date(d.deadline)>new Date()).at(-1);if(live)payPanel(live,resumeReqs(live));
 try{SRC=(await api("/anychain/sources")).sources;fillNets();incidents()}catch(e){$("#go").disabled=true;$("#net").disabled=true;$("#asset").disabled=true;$("#refund").disabled=true;$("#err").textContent="Paying from other networks is unavailable right now"+(e.message==="AURORA_NOT_CONFIGURED"?" (this server has no Aurora Intents key yet)":"")+(REQ.solanaPay?". The Solana wallet option above still works.":".")}})();
</script></body></html>`;
}
