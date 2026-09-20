import assert from 'node:assert/strict';
import {test} from 'node:test';
import {PROTOCOL,UI,MIME,UI_URI} from '../apps/host-trace.mjs';
import {BATCH_COUNT,MIME_CASES,REQUEST_COUNT,adjudicateMimeMatrix,batchRequests,
  expectedStatus,projectMimeExchange,requestForSpec,runMimeMatrix} from './matrix.mjs';

const token='c'.repeat(64),secret='PRIVATE_CANARY_NEVER_PERSIST';
const shell='<!doctype html><html><body>STATIC_SHELL_NEVER_PERSIST</body></html>';
const metadata={'io.modelcontextprotocol/serverInfo':{name:'soklet-apps-fixture',version:'fixture-v1'}};
const schema={type:'object',properties:{},additionalProperties:false};
const data={locale:'en-US',direction:'ltr',tenant:'alpha',title:'Catalog view',refreshLabel:'Refresh catalog',
  summary:'Catalog alpha: 1 item.',itemLabel:'Toy <img src=x onerror=alert(1)>',amount:1234.5,currency:'USD',
  updatedAt:'2026-09-19T12:00:00Z',timeZone:'UTC'};
const specs=()=>Array.from({length:BATCH_COUNT},(_,index)=>batchRequests(index)).flat();
const responseHeaders=()=>new Headers({'content-type':'application/json','cache-control':'no-store'});
function requestHeaders(spec) {
  const request=requestForSpec(spec),name=request.params.name??request.params.uri;
  return new Headers({'content-type':'application/json',accept:'application/json, text/event-stream',
    'mcp-protocol-version':PROTOCOL,'mcp-method':request.method,authorization:`Bearer ${token}`,
    ...(name?{'mcp-name':name}:{})});
}
function result(spec) {
  const catalog={resultType:'complete',_meta:metadata,ttlMs:0,cacheScope:'private'};
  if(spec.operation==='discover')return {...catalog,supportedVersions:[PROTOCOL],capabilities:{
    tools:{listChanged:true},resources:{},extensions:{[UI]:{mimeTypes:[MIME]}}}};
  if(spec.operation==='resource-list')return {...catalog,resources:[{uri:UI_URI,name:'catalog_view',title:'Catalog view',mimeType:MIME}]};
  if(spec.operation==='tools-list')return {...catalog,tools:[
    {name:'show_catalog',title:'Show catalog',inputSchema:schema,
      ...(spec.supported?{_meta:{ui:{resourceUri:UI_URI,visibility:['model','app']}}}:{})},
    ...(spec.supported?[{name:'refresh_catalog',title:'Refresh catalog',inputSchema:schema,
      _meta:{ui:{visibility:['app']}}}]:[])]};
  if(spec.operation==='resource-read')return {...catalog,contents:[{uri:UI_URI,mimeType:MIME,text:shell,
    _meta:{ui:{csp:{connectDomains:[],resourceDomains:[],frameDomains:[],baseUriDomains:[]},prefersBorder:true}}}]};
  return {resultType:'complete',_meta:{...metadata,'example/view':'catalog-v1'},
    content:[{type:'text',text:data.summary}],structuredContent:data};
}
function response(spec) {
  return {jsonrpc:'2.0',id:1,...(expectedStatus(spec)===400?{error:{code:-32021,
    message:'Missing required client capability',data:{requiredCapabilities:{extensions:{[UI]:{mimeTypes:[MIME]}}}}}}
    :{result:result(spec)})};
}
function projection(spec,changes={}) {
  return projectMimeExchange({spec,request:requestForSpec(spec),requestHeaders:requestHeaders(spec),
    response:response(spec),responseHeaders:responseHeaders(),status:expectedStatus(spec),
    requestBytes:300,responseBytes:500,shell,token,...changes});
}
const rows=()=>specs().map(spec=>projection(spec));
function sanitized(value) {
  const serialized=JSON.stringify(value);
  for(const privateValue of [secret,token,'Bearer ',shell,UI_URI,data.summary,data.itemLabel])
    assert.equal(serialized.includes(privateValue),false,privateValue);
}
function deeplyFrozen(value) {
  if(!value||typeof value!=='object')return true;
  return Object.isFrozen(value)&&Object.values(value).every(deeplyFrozen);
}
function mockResponse(spec,changes={}) {
  return new Response(JSON.stringify(response(spec)),{status:expectedStatus(spec),headers:responseHeaders(),...changes});
}
const options={batchIndex:0,port:12345,token,shell};

test('twelve deeply frozen profiles encode the exact wrong/equivalent/mixed MIME boundaries',()=>{
  const setting=mimeTypes=>({extensions:{[UI]:{mimeTypes}}});
  assert.deepEqual(MIME_CASES,[
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
  assert.equal(deeplyFrozen(MIME_CASES),true);
  assert.throws(()=>MIME_CASES[1].capabilities.extensions[UI].mimeTypes.push('private'),TypeError);
  assert.equal(BATCH_COUNT,3);assert.equal(REQUEST_COUNT,54);
});

test('three ordered bounded batches contain catalog controls then four operations for each MIME profile',()=>{
  const all=specs();assert.equal(all.length,54);
  assert.deepEqual(all.map(spec=>spec.sequence),Array.from({length:54},(_,index)=>index+1));
  for(let batchIndex=0;batchIndex<3;batchIndex++) {
    const batch=batchRequests(batchIndex);
    assert.equal(batch.length,18);assert.equal(deeplyFrozen(batch),true);
    assert.ok(batch.every(spec=>spec.batch===batchIndex+1));
    assert.deepEqual(batch.slice(0,2).map(spec=>spec.operation),['discover','resource-list']);
    assert.ok(batch.slice(0,2).every(spec=>spec.caseName==='catalog'&&spec.supported===false));
    for(let index=0;index<4;index++) {
      const group=batch.slice(index*4+2,index*4+6),profile=MIME_CASES[batchIndex*4+index];
      assert.deepEqual(group.map(spec=>spec.operation),['tools-list','show','refresh','resource-read']);
      assert.ok(group.every(spec=>spec.caseName===profile.name&&spec.supported===profile.supported&&spec.profile===profile));
    }
  }
  assert.equal(all.filter(spec=>expectedStatus(spec)===400).length,8);
  assert.equal(all.filter(spec=>expectedStatus(spec)===200).length,46);
  for(const index of [-1,3,0.5,NaN,Infinity,null,undefined,'0'])
    assert.throws(()=>batchRequests(index),{message:'APPS_MIME_BATCH'});
});

test('every request has exact modern per-request capabilities, operation and no session envelope',()=>{
  for(const spec of specs()) {
    const request=requestForSpec(spec);
    const method={discover:'server/discover','resource-list':'resources/list','tools-list':'tools/list',
      show:'tools/call',refresh:'tools/call','resource-read':'resources/read'}[spec.operation];
    const name=spec.operation==='show'?'show_catalog':spec.operation==='refresh'?'refresh_catalog':undefined;
    assert.deepEqual(request,{jsonrpc:'2.0',id:1,method,params:{
      ...(name?{name,arguments:{}}:{}),...(spec.operation==='resource-read'?{uri:UI_URI}:{}),
      _meta:{'io.modelcontextprotocol/protocolVersion':PROTOCOL,
        'io.modelcontextprotocol/clientCapabilities':spec.profile.capabilities}}});
    request.params._meta['io.modelcontextprotocol/clientCapabilities'].private=secret;
    assert.equal(Object.hasOwn(spec.profile.capabilities,'private'),false);
    assert.deepEqual(requestForSpec(structuredClone(spec)),requestForSpec(spec));
  }
  for(const spec of [null,{}, {...batchRequests(0)[2],sequence:999},
    {...batchRequests(0)[2],supported:true},{...batchRequests(0)[2],operation:'private'},
    {...batchRequests(0)[2],private:secret},{...batchRequests(0)[2],profile:MIME_CASES[1]}])
    for(const fn of [requestForSpec,expectedStatus])assert.throws(()=>fn(spec),{message:'APPS_MIME_SPEC'});
});

test('complete matrix requires all fifty-four exact rows in sequence and remains nonmutating',()=>{
  const valid=rows(),before=structuredClone(valid);
  assert.equal(adjudicateMimeMatrix(valid),'PASSED');assert.deepEqual(valid,before);sanitized(valid);
  for(const value of [null,{},[],valid.slice(1),valid.toReversed(),[...valid,valid[0]],Array(54).fill(null),Array(54)])
    assert.equal(adjudicateMimeMatrix(value),'FAILED');
  for(let index=0;index<54;index++) {
    const changed=structuredClone(valid);changed[index]=structuredClone(valid[(index+1)%54]);
    assert.equal(adjudicateMimeMatrix(changed),'FAILED');
    const hole=structuredClone(valid);delete hole[index];
    assert.equal(adjudicateMimeMatrix(hole),'FAILED');
  }
});

test('every required structural flag, fixed field and integer byte bound is mandatory for every row',()=>{
  const valid=rows();
  const flags=['requestMatches','headersMatch','authorizationSent','responseJson','responseNoStore',
    'responseEnvelopeMatches','resultMatches','noSessionState','noAuthChallenge'];
  for(let index=0;index<54;index++) {
    for(const key of flags)for(const value of [false,undefined,1,'true']) {
      const changed=structuredClone(valid);changed[index][key]=value;
      assert.equal(adjudicateMimeMatrix(changed),'FAILED',`${index}:${key}:${String(value)}`);
    }
    for(const change of [{surface:'browser'},{sequence:0},{sequence:1.5},{batch:4},{caseName:'private'},
      {operation:'resources/read'},{supported:!valid[index].supported},{responseStatus:401},
      {responseStatus:valid[index].responseStatus===200?400:200},{requestBytes:0},{requestBytes:65537},
      {requestBytes:1.5},{requestBytes:'300'},{responseBytes:0},{responseBytes:1048577},
      {responseBytes:Infinity},{responseBytes:0.5},{private:secret}]) {
      const changed=structuredClone(valid);Object.assign(changed[index],change);
      assert.equal(adjudicateMimeMatrix(changed),'FAILED');
    }
    for(const key of Object.keys(valid[index])) {
      const changed=structuredClone(valid);delete changed[index][key];
      assert.equal(adjudicateMimeMatrix(changed),'FAILED');
    }
  }
});

test('request integrity rejects envelope, capabilities, selection and modern protocol drift',()=>{
  for(const spec of specs()) {
    for(const mutate of [request=>{request.id=secret;},request=>{request.jsonrpc='1.0';},
      request=>{request.method='private';},request=>{request.extra=secret;},
      request=>{request.params.private=secret;},request=>{request.params._meta['io.modelcontextprotocol/protocolVersion']='2025-11-25';},
      request=>{request.params._meta['io.modelcontextprotocol/clientCapabilities']={private:secret};}]) {
      const request=requestForSpec(spec);mutate(request);
      const row=projection(spec,{request});assert.equal(row.requestMatches,false);sanitized(row);
    }
  }
});

test('headers enforce exact protocol routing, credential, no-store, no-auth and no-session facts',()=>{
  for(const spec of batchRequests(0)) {
    for(const [header,value,flag] of [['content-type','text/plain','headersMatch'],
      ['accept','application/json','headersMatch'],['mcp-protocol-version','2025-11-25','headersMatch'],
      ['mcp-method','private','headersMatch'],['mcp-name','private','headersMatch'],
      ['authorization',`Bearer ${secret}`,'authorizationSent'],['mcp-session-id',secret,'noSessionState']]) {
      const headers=requestHeaders(spec);headers.set(header,value);
      const row=projection(spec,{requestHeaders:headers});assert.equal(row[flag],false);sanitized(row);
    }
    for(const [header,value,flag] of [['content-type','text/event-stream','responseJson'],
      ['cache-control','public','responseNoStore'],['mcp-session-id',secret,'noSessionState'],
      ['www-authenticate','Bearer','noAuthChallenge']]) {
      const headers=responseHeaders();headers.set(header,value);
      const row=projection(spec,{responseHeaders:headers});assert.equal(row[flag],false);sanitized(row);
    }
    assert.equal(projection(spec,{token:'bad'}).authorizationSent,false);
  }
});

test('response correlation and fixture data integrity reject extra, missing and hostile content',()=>{
  for(const spec of specs()) {
    for(const mutate of [value=>{value.id=secret;},value=>{value.jsonrpc='1.0';},
      value=>{value.extra=secret;},value=>{delete value.id;}]) {
      const body=structuredClone(response(spec));mutate(body);
      const row=projection(spec,{response:body});assert.equal(row.responseEnvelopeMatches,false);sanitized(row);
    }
    const body=structuredClone(response(spec));
    if(body.error)body.error.data.requiredCapabilities.extensions[UI].mimeTypes=['text/html'];
    else if(spec.operation==='discover')delete body.result.capabilities.extensions;
    else if(spec.operation==='resource-list')body.result.resources=[];
    else if(spec.operation==='tools-list')body.result.tools[0].title=secret;
    else if(spec.operation==='resource-read')body.result.contents[0].text=secret;
    else body.result.content[0].text=secret;
    const row=projection(spec,{response:body});assert.equal(row.resultMatches,false);sanitized(row);
  }
  for(const spec of specs().filter(spec=>spec.operation==='refresh'&&!spec.supported)) {
    for(const change of [{code:-32603},{message:'private'},{data:{}},{extra:secret}]) {
      const body=structuredClone(response(spec));Object.assign(body.error,change);
      assert.equal(projection(spec,{response:body}).resultMatches,false);
    }
  }
});

test('unsupported MIME strips tool UI/helper but preserves ordinary show results and authorized resource bodies',()=>{
  for(const spec of specs().filter(spec=>spec.operation==='tools-list')) {
    const body=structuredClone(response(spec));
    if(spec.supported) {delete body.result.tools[0]._meta;body.result.tools.pop();}
    else body.result.tools[0]._meta={ui:{resourceUri:UI_URI,visibility:['model','app']}};
    assert.equal(projection(spec,{response:body}).resultMatches,false);
  }
  for(const operation of ['show','resource-read']) {
    const selected=specs().filter(spec=>spec.operation===operation);
    assert.equal(selected.length,12);
    assert.ok(selected.every(spec=>expectedStatus(spec)===200&&projection(spec).resultMatches));
    assert.ok(selected.every(spec=>assert.deepEqual(response(spec),response(selected[0]))===undefined));
  }
  sanitized(rows());
});

test('mock transport sends only exact eighteen-request batches and retains independent safe copies',async()=>{
  const all=[];
  for(let batchIndex=0;batchIndex<3;batchIndex++) {
    const calls=[],retained=[],batch=batchRequests(batchIndex);
    const actual=await runMimeMatrix({...options,batchIndex,onRow:row=>retained.push(row),fetchImpl:async(url,request)=>{
      const spec=batch[calls.length];calls.push(spec);
      assert.equal(url,'http://127.0.0.1:12345/apps');assert.equal(request.method,'POST');
      assert.equal(request.redirect,'error');assert.ok(request.signal instanceof AbortSignal);
      assert.deepEqual(request.headers,requestHeaders(spec));assert.deepEqual(JSON.parse(request.body),requestForSpec(spec));
      return mockResponse(spec);
    }});
    assert.equal(calls.length,18);assert.deepEqual(actual,retained);
    assert.ok(actual.every((row,index)=>row!==retained[index]));sanitized(actual);all.push(...actual);
  }
  assert.equal(adjudicateMimeMatrix(all),'PASSED');
});

test('failed response row is retained before throwing and prevents any later request',async()=>{
  for(const failedIndex of [0,8,17]) {
    const retained=[],batch=batchRequests(0);let calls=0;
    await assert.rejects(runMimeMatrix({...options,onRow:row=>retained.push(row),fetchImpl:async()=>{
      const spec=batch[calls++];return calls-1===failedIndex?new Response('{}',{status:200,headers:responseHeaders()}):mockResponse(spec);
    }}),{message:'APPS_MIME_RESPONSE'});
    assert.equal(calls,failedIndex+1);assert.equal(retained.length,failedIndex+1);
    assert.equal(retained.at(-1).responseEnvelopeMatches,false);assert.equal(retained.at(-1).resultMatches,false);
    sanitized(retained);
  }
});

test('bad input is rejected before fetch and invalid batches retain their exact fixed failure',async()=>{
  for(const changes of [{port:0},{port:65536},{port:1.5},{token:'bad'},{token:'C'.repeat(64)},
    {shell:''},{shell:'x'.repeat(524289)},{shell:'🙂'.repeat(131073)},{signal:{}},{onRow:null},{fetchImpl:3}]) {
    let calls=0;
    await assert.rejects(runMimeMatrix({...options,fetchImpl:async()=>{calls++;},...changes}),{message:'APPS_MIME_INPUT'});
    assert.equal(calls,0);
  }
  for(const batchIndex of [-1,3,'0',undefined])
    await assert.rejects(runMimeMatrix({...options,batchIndex}),{message:'APPS_MIME_BATCH'});
});

test('invalid JSON, missing body and streaming overflow fail without retaining raw content or scheduling more work',async()=>{
  for(const [responseFactory,code] of [
    [()=>new Response(secret,{status:200,headers:responseHeaders()}),'APPS_MIME_JSON'],
    [()=>new Response(null,{status:200,headers:responseHeaders()}),'APPS_MIME_BODY'],
    [()=>new Response('x'.repeat(1048577),{status:200,headers:responseHeaders()}),'APPS_MIME_RESPONSE_BOUND'],
  ]) {
    let calls=0;const retained=[];
    await assert.rejects(runMimeMatrix({...options,onRow:row=>retained.push(row),fetchImpl:async()=>{calls++;return responseFactory();}}),{message:code});
    assert.equal(calls,1);assert.deepEqual(retained,[]);
  }
  let cancelled=false,calls=0;
  const stream=new ReadableStream({start(controller){controller.enqueue(new Uint8Array(1048576));controller.enqueue(new Uint8Array(1));},
    cancel(){cancelled=true;}});
  await assert.rejects(runMimeMatrix({...options,fetchImpl:async()=>{calls++;return new Response(stream);}}),{message:'APPS_MIME_RESPONSE_BOUND'});
  assert.equal(calls,1);assert.equal(cancelled,true);
});

test('unknown fetch and stream exceptions become fixed redacted failures',async()=>{
  for(const fetchImpl of [async()=>{throw new Error(secret);},async()=>new Response(new ReadableStream({start(controller){controller.error(new Error(secret));}}))]) {
    const retained=[];let calls=0;
    await assert.rejects(runMimeMatrix({...options,onRow:row=>retained.push(row),fetchImpl:async(...args)=>{calls++;return fetchImpl(...args);}}),
      error=>error.message==='APPS_MIME_FETCH'&&!error.message.includes(secret));
    assert.equal(calls,1);assert.deepEqual(retained,[]);
  }
});

test('cancellation before start or between rows stops immediately without future fetches',async()=>{
  const aborted=new AbortController();aborted.abort(new Error(secret));let calls=0;
  await assert.rejects(runMimeMatrix({...options,signal:aborted.signal,fetchImpl:async()=>{calls++;}}),{message:'APPS_MIME_ABORTED'});
  assert.equal(calls,0);
  for(const stopAt of [1,9,18]) {
    const controller=new AbortController(),retained=[],batch=batchRequests(1);calls=0;
    await assert.rejects(runMimeMatrix({...options,batchIndex:1,signal:controller.signal,onRow:row=>{
      retained.push(row);if(retained.length===stopAt)controller.abort(new Error(secret));
    },fetchImpl:async()=>mockResponse(batch[calls++])}),{message:'APPS_MIME_ABORTED'});
    assert.equal(calls,stopAt);assert.equal(retained.length,stopAt);sanitized(retained);
  }
});

test('in-flight cancellation preserves earlier rows but cannot accept the cancelled exchange',async()=>{
  const controller=new AbortController(),retained=[],batch=batchRequests(0);let calls=0;
  await assert.rejects(runMimeMatrix({...options,signal:controller.signal,onRow:row=>retained.push(row),fetchImpl:async()=>{
    const spec=batch[calls++];if(calls===7)controller.abort(new Error(secret));return mockResponse(spec);
  }}),{message:'APPS_MIME_ABORTED'});
  assert.equal(calls,7);assert.equal(retained.length,6);sanitized(retained);
});
