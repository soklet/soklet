import {isDeepStrictEqual} from 'node:util';
import {validAppsResult, MIME, UI, UI_URI, PROTOCOL} from '../apps/host-trace.mjs';

const META='io.modelcontextprotocol/';
const same=isDeepStrictEqual;
const fail=code=>{throw new Error(code);};
export const CONTROL_CASES=Object.freeze([
  Object.freeze({name:'helper-off-before',method:'tools/call',apps:false}),
  Object.freeze({name:'helper-on',method:'tools/call',apps:true}),
  Object.freeze({name:'helper-off-after',method:'tools/call',apps:false}),
  Object.freeze({name:'ordinary-resource-off',method:'resources/read',apps:false}),
]);
const requiredCapabilities={extensions:{[UI]:{mimeTypes:[MIME]}}};

export function requestForControl(profile) {
  if(!CONTROL_CASES.some(item=>same(item,profile)))fail('APPS_HOST_CONTROL_PROFILE');
  return {jsonrpc:'2.0',id:1,method:profile.method,params:{
    ...(profile.method==='tools/call'?{name:'refresh_catalog',arguments:{}}:{uri:UI_URI}),
    _meta:{[META+'protocolVersion']:PROTOCOL,[META+'clientCapabilities']:profile.apps
      ?{extensions:{[UI]:{mimeTypes:[MIME],elicitation:{}}}}:{}}}};
}

export function validControlResponse(profile,status,headers,response,shell) {
  if(!CONTROL_CASES.some(item=>same(item,profile))
      || !/^application\/json(?:;|$)/i.test(headers.get('content-type')??'')
      || headers.get('cache-control')!=='no-store' || headers.has('mcp-session-id')
      || headers.has('www-authenticate'))return false;
  const denied=profile.method==='tools/call'&&!profile.apps;
  if(denied)return status===400 && same(response,{jsonrpc:'2.0',id:1,error:{
    code:-32021,message:'Missing required client capability',data:{requiredCapabilities}}});
  return status===200 && response?.jsonrpc==='2.0' && response.id===1
    && same(Object.keys(response).sort(),['id','jsonrpc','result'])
    && validAppsResult(profile.method,response.result,shell);
}

export function adjudicateDirectControls(rows) {
  return Array.isArray(rows)&&rows.length===CONTROL_CASES.length&&rows.every((row,i)=>
    same(Object.keys(row??{}).sort(),['appsAdvertised','name','requestBytes','responseBytes','responseMatches','status'])
    &&row.name===CONTROL_CASES[i].name&&row.appsAdvertised===CONTROL_CASES[i].apps
    &&row.responseMatches===true&&row.status===(i===0||i===2?400:200)
    &&Number.isSafeInteger(row.requestBytes)&&row.requestBytes>0&&row.requestBytes<=65536
    &&Number.isSafeInteger(row.responseBytes)&&row.responseBytes>0&&row.responseBytes<=1024*1024)
    ?'PASSED':'FAILED';
}

// These four explicit direct controls are separate from the browser trace;
// they never count as proof of a DOM action or host capability advertisement.
export async function runDirectControls({port,token,shell,fetchImpl=fetch}) {
  if(!Number.isSafeInteger(port)||port<1||port>65535||typeof token!=='string'
      ||!/^[a-f0-9]{64}$/.test(token)||typeof shell!=='string'||!shell.length
      ||Buffer.byteLength(shell)>512*1024)fail('APPS_HOST_CONTROL_INPUT');
  const rows=[];
  for(const profile of CONTROL_CASES) {
    const request=requestForControl(profile),body=JSON.stringify(request);
    const response=await fetchImpl(`http://127.0.0.1:${port}/apps`,{method:'POST',
      headers:{'content-type':'application/json',accept:'application/json, text/event-stream',
        'mcp-protocol-version':PROTOCOL,'mcp-method':profile.method,
        'mcp-name':profile.method==='tools/call'?'refresh_catalog':UI_URI,Authorization:`Bearer ${token}`},
      body,signal:AbortSignal.timeout(5000),redirect:'error'});
    if(!response.body)fail('APPS_HOST_CONTROL_BODY');
    const reader=response.body.getReader(),chunks=[];
    let bytes=0;
    while(true) {
      const {value,done}=await reader.read();if(done)break;
      if((bytes+=value.byteLength)>1024*1024){await reader.cancel();fail('APPS_HOST_CONTROL_BOUND');}
      chunks.push(Buffer.from(value));
    }
    let result;
    try{result=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{fail('APPS_HOST_CONTROL_JSON');}
    const row={name:profile.name,appsAdvertised:profile.apps,status:response.status,
      responseMatches:validControlResponse(profile,response.status,response.headers,result,shell),
      requestBytes:Buffer.byteLength(body),responseBytes:bytes};
    rows.push(row);
    if(!row.responseMatches)fail('APPS_HOST_CONTROL_RESPONSE');
  }
  return rows;
}
