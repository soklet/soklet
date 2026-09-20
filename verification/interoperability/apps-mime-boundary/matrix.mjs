import {isDeepStrictEqual as same} from 'node:util';
import {PROTOCOL,UI,MIME,UI_URI,validAppsResult} from '../apps/host-trace.mjs';
import {validAppsDisabledResult} from '../apps-disabled-host/trace.mjs';

const META='io.modelcontextprotocol/';
const MAX_REQUEST=65536,MAX_RESPONSE=1048576;
const fail=code=>{throw new Error(code);};
const object=value=>value!==null&&typeof value==='object'&&!Array.isArray(value);
function freeze(value) {
  if(value&&typeof value==='object') {Object.values(value).forEach(freeze);Object.freeze(value);}
  return value;
}
const setting=mimeTypes=>({extensions:{[UI]:{mimeTypes}}});
// These are fixed protocol inputs, not browser-advertisement observations.
// The ordering deliberately includes unsupported -> supported -> unsupported
// transitions under the same credential in each independently bounded batch.
export const MIME_CASES=freeze([
  {name:'wrong-html',supported:false,capabilities:setting(['text/html'])},
  {name:'exact',supported:true,capabilities:setting([MIME])},
  {name:'empty-list',supported:false,capabilities:setting([])},
  {name:'mixed-valid',supported:true,capabilities:setting(['text/html',MIME])},
  {name:'extension-empty',supported:false,capabilities:{extensions:{[UI]:{}}}},
  {name:'equivalent-spaced-quoted',supported:true,capabilities:setting([' TEXT / HTML ; PROFILE="mcp-app" '])},
  {name:'profile-value-case',supported:false,capabilities:setting(['text/html;profile=MCP-APP'])},
  {name:'extra-parameter',supported:false,capabilities:setting([MIME+';charset=UTF-8'])},
  {name:'exact-invalid-number-after',supported:false,capabilities:setting([MIME,5])},
  {name:'malformed-before-exact',supported:false,capabilities:setting(['text/html;profile="unterminated',MIME])},
  {name:'mixed-valid-recovery',supported:true,capabilities:setting([MIME,'text/html'])},
  {name:'wrong-html-recheck',supported:false,capabilities:setting(['text/html'])},
]);
export const BATCH_COUNT=3;
export const REQUEST_COUNT=54;
export const MIME_FAILURES=Object.freeze(['APPS_MIME_INPUT','APPS_MIME_BATCH','APPS_MIME_SPEC',
  'APPS_MIME_ABORTED','APPS_MIME_DEADLINE','APPS_MIME_REQUEST_BOUND','APPS_MIME_FETCH',
  'APPS_MIME_BODY','APPS_MIME_RESPONSE_BOUND','APPS_MIME_JSON','APPS_MIME_RESPONSE']);
const operations=Object.freeze(['tools-list','show','refresh','resource-read']);
const methods=Object.freeze({'discover':'server/discover','resource-list':'resources/list',
  'tools-list':'tools/list',show:'tools/call',refresh:'tools/call','resource-read':'resources/read'});
const catalogProfile=freeze({name:'catalog',supported:false,capabilities:{}});

export function batchRequests(batchIndex) {
  if(!Number.isSafeInteger(batchIndex)||batchIndex<0||batchIndex>=BATCH_COUNT)fail('APPS_MIME_BATCH');
  const specs=[];
  function add(profile,operation) {
    specs.push(freeze({sequence:batchIndex*18+specs.length+1,batch:batchIndex+1,
      caseName:profile.name,operation,supported:profile.supported,profile}));
  }
  add(catalogProfile,'discover');add(catalogProfile,'resource-list');
  for(const profile of MIME_CASES.slice(batchIndex*4,batchIndex*4+4))
    for(const operation of operations)add(profile,operation);
  return Object.freeze(specs);
}
function checkedSpec(spec) {
  if(!object(spec)||!Number.isSafeInteger(spec.batch)||spec.batch<1||spec.batch>BATCH_COUNT
      ||!batchRequests(spec.batch-1).some(expected=>same(expected,spec)))fail('APPS_MIME_SPEC');
  return spec;
}
export function requestForSpec(spec) {
  checkedSpec(spec);
  const name=spec.operation==='show'?'show_catalog':spec.operation==='refresh'?'refresh_catalog':undefined;
  return {jsonrpc:'2.0',id:1,method:methods[spec.operation],params:{
    ...(name?{name,arguments:{}}:{}),...(spec.operation==='resource-read'?{uri:UI_URI}:{}),
    _meta:{[META+'protocolVersion']:PROTOCOL,[META+'clientCapabilities']:structuredClone(spec.profile.capabilities)}}};
}
export function expectedStatus(spec) {
  checkedSpec(spec);return spec.operation==='refresh'&&!spec.supported?400:200;
}
const missingCapability={code:-32021,message:'Missing required client capability',
  data:{requiredCapabilities:{extensions:{[UI]:{mimeTypes:[MIME]}}}}};
const REQUIRED=Object.freeze(['requestMatches','headersMatch','authorizationSent','responseJson',
  'responseNoStore','responseEnvelopeMatches','resultMatches','noSessionState','noAuthChallenge']);
const FIELDS=Object.freeze(['surface','sequence','batch','caseName','operation','supported',
  'responseStatus','requestBytes','responseBytes',...REQUIRED]);

/** Only fixed labels, booleans, HTTP status and bounded counts leave memory. */
export function projectMimeExchange({spec,request,requestHeaders,response,responseHeaders,status,
  requestBytes,responseBytes,shell,token}) {
  const expected=requestForSpec(spec),denied=expectedStatus(spec)===400;
  const name=expected.params.name??expected.params.uri;
  return {surface:'candidate-direct-http',sequence:spec.sequence,batch:spec.batch,
    caseName:spec.caseName,operation:spec.operation,supported:spec.supported,
    responseStatus:status,requestBytes,responseBytes,
    requestMatches:same(request,expected),
    headersMatch:requestHeaders.get('content-type')==='application/json'
      &&requestHeaders.get('accept')==='application/json, text/event-stream'
      &&requestHeaders.get('mcp-protocol-version')===PROTOCOL
      &&requestHeaders.get('mcp-method')===expected.method
      &&requestHeaders.get('mcp-name')===(name??null),
    authorizationSent:typeof token==='string'&&/^[a-f0-9]{64}$/.test(token)
      &&requestHeaders.get('authorization')===`Bearer ${token}`,
    responseJson:/^application\/json(?:;|$)/i.test(responseHeaders.get('content-type')??''),
    responseNoStore:responseHeaders.get('cache-control')==='no-store',
    responseEnvelopeMatches:object(response)&&response.jsonrpc==='2.0'&&response.id===expected.id
      &&same(Object.keys(response).sort(),denied?['error','id','jsonrpc']:['id','jsonrpc','result']),
    resultMatches:denied?same(response?.error,missingCapability)
      :(spec.operation==='tools-list'&&!spec.supported?validAppsDisabledResult:validAppsResult)(expected.method,response?.result,shell),
    noSessionState:!requestHeaders.has('mcp-session-id')&&!responseHeaders.has('mcp-session-id'),
    noAuthChallenge:!responseHeaders.has('www-authenticate')};
}
function validRow(row,spec) {
  return object(row)&&same(Object.keys(row).sort(),[...FIELDS].sort())
    &&row.surface==='candidate-direct-http'&&row.sequence===spec.sequence&&row.batch===spec.batch
    &&row.caseName===spec.caseName&&row.operation===spec.operation&&row.supported===spec.supported
    &&row.responseStatus===expectedStatus(spec)&&REQUIRED.every(key=>row[key]===true)
    &&Number.isSafeInteger(row.requestBytes)&&row.requestBytes>0&&row.requestBytes<=MAX_REQUEST
    &&Number.isSafeInteger(row.responseBytes)&&row.responseBytes>0&&row.responseBytes<=MAX_RESPONSE;
}
export function adjudicateMimeMatrix(rows) {
  const expected=Array.from({length:BATCH_COUNT},(_,i)=>batchRequests(i)).flat();
  return Array.isArray(rows)&&rows.length===REQUEST_COUNT&&expected.every((spec,i)=>validRow(rows[i],spec))?'PASSED':'FAILED';
}

/** One fresh fixture batch: 18 requests, leaving room for two admission controls
 * below its unchanged capacity-20 request bucket. There are no automatic retries.
 * A caller cancellation or failed row stops future requests immediately. */
export async function runMimeMatrix({batchIndex,port,token,shell,signal,onRow=()=>{},fetchImpl=fetch}) {
  const specs=batchRequests(batchIndex);
  if(!Number.isSafeInteger(port)||port<1||port>65535||typeof token!=='string'||!/^[a-f0-9]{64}$/.test(token)
      ||typeof shell!=='string'||!shell.length||Buffer.byteLength(shell)>524288
      ||(signal!==undefined&&!(signal instanceof AbortSignal))||typeof onRow!=='function'||typeof fetchImpl!=='function')
    fail('APPS_MIME_INPUT');
  const deadline=AbortSignal.timeout(60000),rows=[];
  const check=()=>{if(signal?.aborted)fail('APPS_MIME_ABORTED');if(deadline.aborted)fail('APPS_MIME_DEADLINE');};
  for(const spec of specs) {
    check();
    const request=requestForSpec(spec),body=JSON.stringify(request),name=request.params.name??request.params.uri;
    if(Buffer.byteLength(body)>MAX_REQUEST)fail('APPS_MIME_REQUEST_BOUND');
    const headers=new Headers({'content-type':'application/json',accept:'application/json, text/event-stream',
      'mcp-protocol-version':PROTOCOL,'mcp-method':request.method,authorization:`Bearer ${token}`,
      ...(name?{'mcp-name':name}:{})});
    const perRequest=AbortSignal.timeout(5000);
    const combined=AbortSignal.any([deadline,perRequest,...(signal?[signal]:[])]);
    let response,bytes=0,chunks=[];
    try {
      response=await fetchImpl(`http://127.0.0.1:${port}/apps`,{method:'POST',headers,body,signal:combined,redirect:'error'});
      if(!response.body)fail('APPS_MIME_BODY');
      const reader=response.body.getReader();
      try {
        while(true) {
          const {value,done}=await reader.read();if(done)break;
          if((bytes+=value.byteLength)>MAX_RESPONSE) {await reader.cancel();fail('APPS_MIME_RESPONSE_BOUND');}
          chunks.push(Buffer.from(value));
        }
      } finally {reader.releaseLock();}
    } catch(error) {
      check();
      if(combined.aborted)fail('APPS_MIME_DEADLINE');
      if(['APPS_MIME_BODY','APPS_MIME_RESPONSE_BOUND'].includes(error?.message))throw error;
      fail('APPS_MIME_FETCH');
    }
    check();
    if(combined.aborted)fail('APPS_MIME_DEADLINE');
    let result;
    try{result=JSON.parse(Buffer.concat(chunks).toString('utf8'));}catch{fail('APPS_MIME_JSON');}
    const row=projectMimeExchange({spec,request:JSON.parse(body),requestHeaders:headers,response:result,
      responseHeaders:response.headers,status:response.status,requestBytes:Buffer.byteLength(body),responseBytes:bytes,shell,token});
    rows.push(row);onRow(structuredClone(row));
    if(!validRow(row,spec))fail('APPS_MIME_RESPONSE');
  }
  check();return rows;
}
