#!/usr/bin/env node
import {createHash, randomBytes} from 'node:crypto';
import {existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync} from 'node:fs';
import {createServer} from 'node:net';
import {dirname, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {createEnvironment, createSessionConfig, sanitizedSessionConfig} from '../inspector/config.mjs';
import {directoryIdentity} from '../inspector/run.mjs';
import {startProcess} from '../inspector/process.mjs';
import {connectCdp} from '../inspector/cdp.mjs';
import {browserRequestPolicy, browserVersion, chromeArguments, validWebConfig} from '../inspector/web-probe.mjs';
import {startFixture} from './fixture.mjs';
import {verifyPatchedDependencies} from './patch.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, '../../..');
const json = value => JSON.stringify(value, null, 2) + '\n';
const hash = path => createHash('sha256').update(readFileSync(path)).digest('hex');
const fail = code => {throw new Error(code);};
const pause = () => new Promise(done => setTimeout(done, 50));
const connectionSelector = '[aria-label=\'Connect or disconnect "soklet"\']';
export const VARIANTS = Object.freeze(['original','patched']);
export const CASES = Object.freeze([
  Object.freeze({name:'no-subscription', mode:'no-subscription'}),
  Object.freeze({name:'jsonrpc-200-control', mode:'jsonrpc-200-control'}),
  Object.freeze({name:'policy-403', mode:'policy-403'}),
  Object.freeze({name:'policy-403-throw', mode:'policy-403', onInsufficientScope:'throw'}),
  Object.freeze({name:'policy-403-no-refresh', mode:'policy-403', autoRefreshOnListChanged:false}),
  Object.freeze({name:'auth-401-invalid-token', mode:'auth-401-invalid-token'}),
  Object.freeze({name:'auth-403-insufficient-scope', mode:'auth-403-insufficient-scope'}),
]);

export function parseArguments(args) {
  const keys = ['--original-dependencies','--dependencies','--browser','--work-dir'];
  if (args.length !== keys.length * 2) fail('AUTH_ARGUMENTS');
  const result = {};
  for (let i=0;i<args.length;i+=2) {
    if (!keys.includes(args[i]) || Object.hasOwn(result,args[i]) || !args[i+1]) fail('AUTH_ARGUMENTS');
    result[args[i]] = args[i+1];
  }
  return result;
}

export function configuration(url, token, profile) {
  if (!CASES.some(row => JSON.stringify(row) === JSON.stringify(profile))) fail('AUTH_CASE');
  const config = createSessionConfig(url, token, false);
  if (profile.onInsufficientScope) config.mcpServers.soklet.oauth = {onInsufficientScope:profile.onInsufficientScope};
  if (profile.autoRefreshOnListChanged === false) config.mcpServers.soklet.autoRefreshOnListChanged = false;
  return config;
}

export function adjudicateCase(row) {
  const profile = CASES.find(profile => profile.name === row?.name);
  if (!profile || !VARIANTS.includes(row.variant) || row.mode !== profile.mode || row.fixtureFailure || row.failure
      || (row.cdpFailure && row.cdpFailure !== 'CDP_CLOSED')
      || row.browserShutdown !== 'CLEAN' || row.hostShutdown !== 'CLEAN'
      || !['readOnlyMemoryStore','hostAuthRequired','hostOriginRestricted','connectedViaDom',
        'acquisitionObserved','observationWindowCompleted','disconnectClicked','disconnectedViaDom',
        'configUnchanged','privateStateRemoved'].every(key => row[key] === true)
      || !['cdp','browser','host','fixture'].every(key => row.cleanup?.[key] === true)
      || row.network?.unexpected !== 0 || row.network?.blockedFont !== 1
      || !Number.isSafeInteger(row.network?.requests) || row.network.requests < 1 || row.network.requests > 256
      || row.browserExceptions !== 0
      || !Array.isArray(row.rows) || !Array.isArray(row.rejections)) return 'FAILED';
  const calls = row.rows;
  const required = ['authorized','requestEnvelopeValid','protocolMetadataMatches','protocolHeaderMatches',
    'methodHeaderMatches','perRequestCapabilitiesPresent','noSessionState','requestSelectionValid',
    'responseJson','responseNoStore','responseCorrelated','responseEnvelopeValid','resultMatchesFixture'];
  if (calls.length < 3 || calls.length > 32 || calls[0]?.method !== 'server/discover'
      || calls.filter(call => call.method === 'server/discover').length !== 1
      || calls.filter(call => call.method === 'tools/list').length !== 2
      || calls.some((call,i) => call?.sequence !== i+1 || call.surface !== 'inspector-auth-patch'
        || !Number.isSafeInteger(call.requestBytes) || call.requestBytes < 1 || call.requestBytes > 65536
        || !Number.isSafeInteger(call.responseBytes) || call.responseBytes < 1 || call.responseBytes > 65536
        || required.some(key => call[key] !== true)
        || !['server/discover','tools/list','subscriptions/listen'].includes(call.method)
        || call.responseStatus !== (call.method !== 'subscriptions/listen' ? 200
          : profile.mode === 'auth-401-invalid-token' ? 401
          : ['policy-403','auth-403-insufficient-scope'].includes(profile.mode) ? 403 : 200)
        || call.authChallenge !== (call.method !== 'subscriptions/listen' ? 'none'
          : profile.mode === 'auth-401-invalid-token' ? 'invalid_token'
          : profile.mode === 'auth-403-insufficient-scope' ? 'insufficient_scope' : 'none')
        || call.wwwAuthenticatePresent !== (call.method === 'subscriptions/listen' && profile.mode.startsWith('auth-'))
        || call.requiredScopeCatalogRead !== (call.method === 'subscriptions/listen' && profile.mode === 'auth-403-insufficient-scope')
        || call.subscriptionDenied !== (call.method === 'subscriptions/listen'))) return 'FAILED';
  const subscriptions = calls.filter(call => call.method === 'subscriptions/listen');
  const sequence = [...calls,...row.rejections].map(item=>item?.requestSequence).sort((a,b)=>a-b);
  if (sequence.length > 64 || sequence.some((value,i)=>!Number.isSafeInteger(value)||value!==i+1)) return 'FAILED';
  if (profile.mode === 'no-subscription')
    return subscriptions.length === 0 && row.rejections.length === 0 && row.browserExceptions === 0 ? 'CONTROL_PASSED' : 'FAILED';
  if (subscriptions.length === 0) return 'FAILED';
  if (profile.mode === 'jsonrpc-200-control')
    return row.rejections.length === 0 && row.browserExceptions === 0 ? 'CONTROL_PASSED' : 'FAILED';
  if (profile.mode === 'policy-403' && row.variant === 'patched')
    return row.rejections.length === 0 ? 'POLICY_DENIAL_PRESERVED' : 'FAILED';
  const paths = ['OAUTH_PROTECTED_RESOURCE_PATH','OAUTH_PROTECTED_RESOURCE_ROOT',
    'OAUTH_AUTHORIZATION_SERVER_ROOT','OPENID_CONFIGURATION_ROOT','OAUTH_REGISTRATION_ROOT'];
  if (row.rejections.length < 1 || row.rejections.length > 32
      || row.rejections.some((item,i) => item.sequence !== i+1 || !paths.includes(item.path)
        || item.requestSequence <= Math.min(...subscriptions.map(call=>call.requestSequence))
        || item.method !== (item.path === 'OAUTH_REGISTRATION_ROOT' ? 'POST' : 'GET')
        || item.code !== 'AUTH_OAUTH_REFUSED' || item.originAbsent !== true)
      || !row.rejections.some(item => item.path === 'OAUTH_REGISTRATION_ROOT')) return 'FAILED';
  return profile.mode === 'policy-403' ? 'BUG_REPRODUCED' : 'AUTH_RECOVERY_PRESERVED';
}

export function expectedStatus(profile,variant) {
  return profile.mode.startsWith('auth-') ? 'AUTH_RECOVERY_PRESERVED'
    : profile.mode === 'policy-403' ? (variant === 'original' ? 'BUG_REPRODUCED' : 'POLICY_DENIAL_PRESERVED')
    : 'CONTROL_PASSED';
}

export function adjudicateMatrix(receipt) {
  if (receipt.failure || receipt.inputsUnchanged !== true || receipt.patchIdentityVerified !== true
      || receipt.hostQualification !== false || receipt.candidateEvidence !== false
      || receipt.runs?.length !== CASES.length * VARIANTS.length) return 'FAILED';
  return receipt.runs.every((row,i) => {
    const profile=CASES[i%CASES.length],variant=VARIANTS[Math.floor(i/CASES.length)];
    return row.name === profile.name && row.variant === variant && adjudicateCase(row) === expectedStatus(profile,variant);
  }) ? 'LOCAL_PATCH_VALIDATED_WITH_CONTROLS' : 'FAILED';
}

export async function collectCases(runCase, onRow, interrupted) {
  for (const profile of CASES) {
    if (interrupted()) break;
    await onRow(await runCase(profile));
    if (interrupted()) break;
  }
}

export async function forwardPaused(cdp,session,event,allowed,isClosing) {
  if(isClosing())return;
  try {
    await cdp.send(allowed?'Fetch.continueRequest':'Fetch.failRequest',{requestId:event.requestId,
      ...(!allowed?{errorReason:'BlockedByClient'}:{})},session);
  } catch(error) {
    if(!isClosing()||error.message!=='CDP_CLOSED')throw error;
  }
}

function regular(path) {
  const absolute = resolve(path);
  if (!lstatSync(absolute).isFile() || lstatSync(absolute).isSymbolicLink()) fail('AUTH_INPUT');
  return realpathSync(absolute);
}
export function validateWorkDirectory(work,protectedRoots) {
  if(existsSync(work)||(work.startsWith(root+'/')&&!work.startsWith(resolve(root,'target')+'/')))
    fail('AUTH_WORK_DIRECTORY');
  for(let parent=dirname(work);parent!==dirname(parent);parent=dirname(parent))
    if(existsSync(parent)&&(!lstatSync(parent).isDirectory()||lstatSync(parent).isSymbolicLink()))fail('AUTH_WORK_ANCESTOR');
  for(const directory of protectedRoots) {
    const canonical=realpathSync(directory);
    if(work===canonical||work.startsWith(canonical+'/')||canonical.startsWith(work+'/'))
      fail('AUTH_WORK_INPUT_OVERLAP');
  }
}
function sourceInputs() {
  const rows = [];
  function visit(path) {
    for (const entry of readdirSync(path,{withFileTypes:true}).sort((a,b) => a.name.localeCompare(b.name))) {
      if (entry.isSymbolicLink()) fail('AUTH_SOURCE_SYMLINK');
      const file = resolve(path,entry.name);
      if (entry.isDirectory()) visit(file);
      else if (entry.isFile()) rows.push({path:file.slice(root.length+1),sha256:hash(file)});
    }
  }
  visit(here); visit(resolve(here,'../inspector')); visit(resolve(here,'../inspector-auth'));
  const control = resolve(here,'../run-against-public-fixture.mjs');
  rows.push({path:control.slice(root.length+1),sha256:hash(control)});
  return rows;
}
async function freePort() {
  const server = createServer();
  await new Promise((done,reject) => {server.once('error',reject);server.listen(0,'127.0.0.1',done);});
  const port = server.address().port;
  await new Promise(done => server.close(done));
  return port;
}
async function api(origin, token, authenticated=true, requestOrigin=origin) {
  const response = await fetch(origin+'/api/config',{headers:{Origin:requestOrigin,
    ...(authenticated ? {'x-mcp-remote-auth':`Bearer ${token}`} : {})},
  signal:AbortSignal.timeout(1500),redirect:'error'});
  if (response.status !== 200) {await response.body.cancel();return {status:response.status};}
  const reader = response.body.getReader(), chunks = [];
  let size = 0;
  while (true) {
    const {value,done} = await reader.read();
    if (done) break;
    if ((size+=value.byteLength)>65536) {await reader.cancel();fail('AUTH_API_BOUND');}
    chunks.push(Buffer.from(value));
  }
  return {status:response.status,config:JSON.parse(Buffer.concat(chunks).toString('utf8'))};
}
async function readyHost(origin,token,exited) {
  const deadline = Date.now()+10000;
  while (Date.now()<deadline) {
    if (exited()) fail('AUTH_HOST_EARLY_EXIT');
    try {const result=await api(origin,token);if(result.status===200)return result.config;} catch {/* bounded startup */}
    await pause();
  }
  fail('AUTH_HOST_READY_TIMEOUT');
}
async function devtools(profile,exited) {
  const deadline=Date.now()+10000, path=resolve(profile,'DevToolsActivePort');
  while(Date.now()<deadline) {
    if(exited())fail('AUTH_BROWSER_EARLY_EXIT');
    if(existsSync(path)) {
      const value=readFileSync(path,'utf8');
      const match=/^([1-9][0-9]{0,4})\n(\/devtools\/browser\/[a-f0-9-]+)\n?$/.exec(value);
      if(value.length>512||!match||Number(match[1])>65535)fail('AUTH_BROWSER_ENDPOINT');
      return `ws://127.0.0.1:${match[1]}${match[2]}`;
    }
    await pause();
  }
  fail('AUTH_BROWSER_READY_TIMEOUT');
}
async function boundedExit(handle) {
  let timer;
  try {return await Promise.race([handle.completion,new Promise((_,reject)=>{
    timer=setTimeout(()=>reject(new Error('AUTH_STOP_TIMEOUT')),3000);
  })]);} finally {clearTimeout(timer);}
}
async function evaluate(cdp,session,expression) {
  const result=await cdp.send('Runtime.evaluate',{expression,returnByValue:true,awaitPromise:false},session);
  if(result.exceptionDetails||result.result?.type!=='boolean')fail('AUTH_DOM_EVALUATION');
  return result.result.value===true;
}
function click(selector) {
  return `(() => {const nodes=[...document.querySelectorAll(${JSON.stringify(selector)})]
    .filter(node=>node.getClientRects().length&&!node.disabled);
    if(nodes.length!==1)return false;nodes[0].click();return true;})()`;
}

async function probe({profile,variant,work,browserPath,entry}) {
  const privateRoot=resolve(work,'private-'+variant+'-'+profile.name);
  mkdirSync(privateRoot,{mode:0o700});
  // No JVM is started: this shared helper uses the system Java path only to
  // build an isolated executable PATH, never to run or inspect Java.
  const env=createEnvironment(privateRoot,'/usr/bin/java');
  for(const key of ['HOME','XDG_CONFIG_HOME','XDG_CACHE_HOME','TMPDIR','MCP_STORAGE_DIR'])
    mkdirSync(env[key],{recursive:true,mode:0o700});
  const row={name:profile.name,mode:profile.mode,variant,status:'FAILED',stage:'SETUP',
    settings:{onInsufficientScope:profile.onInsufficientScope??'DEFAULT',
      autoRefreshOnListChanged:profile.autoRefreshOnListChanged??'DEFAULT'},
    rows:[],rejections:[],browserExceptions:0,network:{requests:0,blockedFont:0,unexpected:0},
    browserShutdown:'NOT_PROVEN',hostShutdown:'NOT_PROVEN'};
  const token=randomBytes(32).toString('hex'),hostToken=randomBytes(32).toString('hex');
  let fixture,host,browser,cdp,configPath,configBytes;
  let hostExited=false,browserExited=false,interrupted=false,closingBrowser=false;
  const active=new Set();
  function managed(command,args,extra={}) {
    if(interrupted)fail('AUTH_INTERRUPTED');
    const handle=startProcess(command,args,{cwd:privateRoot,env,timeoutMs:60000,maxOutputBytes:1024*1024,...extra});
    active.add(handle);
    void handle.completion.then(()=>active.delete(handle),()=>active.delete(handle));
    return handle;
  }
  const signal=()=>{interrupted=true;for(const handle of active)void handle.stop().catch(()=>{});};
  process.on('SIGINT',signal);process.on('SIGTERM',signal);
  try {
    fixture=await startFixture({token,mode:profile.mode});
    const config=configuration(`http://127.0.0.1:${fixture.port}/mcp`,token,profile);
    configPath=resolve(privateRoot,'session.json');configBytes=json(config);
    writeFileSync(configPath,configBytes,{flag:'wx',mode:0o600});
    row.config=sanitizedSessionConfig(config);
    const origin=`http://127.0.0.1:${await freePort()}`;
    host=managed(process.execPath,[entry,'--web','--config',configPath],{env:{...env,HOST:'127.0.0.1',
      CLIENT_PORT:new URL(origin).port,ALLOWED_ORIGINS:origin,MCP_SANDBOX_PORT:'0',MCP_APP_ORIGIN_PORT:'0',
      MCP_INSPECTOR_API_TOKEN:hostToken}});
    void host.completion.then(()=>{hostExited=true;},()=>{hostExited=true;});
    const initial=await readyHost(origin,hostToken,()=>hostExited);
    row.readOnlyMemoryStore=validWebConfig(initial,origin);
    row.hostAuthRequired=(await api(origin,hostToken,false)).status===401;
    row.hostOriginRestricted=(await api(origin,hostToken,true,'http://127.0.0.1:1')).status===403;
    if(!row.readOnlyMemoryStore||!row.hostAuthRequired||!row.hostOriginRestricted)fail('AUTH_ISOLATION');
    row.stage='BROWSER';
    const browserProfile=resolve(privateRoot,'chrome-profile');mkdirSync(browserProfile,{mode:0o700});
    browser=managed(browserPath,chromeArguments(browserProfile),{timeoutMs:45000});
    void browser.completion.then(()=>{browserExited=true;},()=>{browserExited=true;});
    cdp=await connectCdp(await devtools(browserProfile,()=>browserExited));
    row.browserVersion=browserVersion(await cdp.send('Browser.getVersion'));
    const {targetId}=await cdp.send('Target.createTarget',{url:'about:blank'});
    const {sessionId}=await cdp.send('Target.attachToTarget',{targetId,flatten:true});
    cdp.on('Runtime.exceptionThrown',()=>{row.browserExceptions+=1;});
    cdp.on('Fetch.requestPaused',async(event,session)=>{
      const policy=browserRequestPolicy(event.request,origin,event.resourceType);
      row.network.requests+=1;
      if(policy==='BLOCKED_PINNED_FONT')row.network.blockedFont+=1;
      if(policy==='BLOCKED_UNEXPECTED')row.network.unexpected+=1;
      const allowed=policy==='SAME_ORIGIN'&&row.network.requests<=256;
      // Once Browser.close is requested, late requests remain paused until
      // process exit. They are still classified; they are never forwarded.
      await forwardPaused(cdp,session,event,allowed,()=>closingBrowser);
    });
    await cdp.send('Page.enable',{},sessionId);await cdp.send('Runtime.enable',{},sessionId);
    await cdp.send('Fetch.enable',{patterns:[{urlPattern:'*',requestStage:'Request'}]},sessionId);
    await cdp.send('Page.navigate',{url:origin},sessionId);
    row.stage='CONNECT';
    const deadline=Date.now()+10000;
    while(Date.now()<deadline) {
      if(await evaluate(cdp,sessionId,click(connectionSelector))) {
        row.connectedViaDom=true;break;
      }
      await pause();
    }
    if(!row.connectedViaDom)fail('AUTH_CONNECT_TIMEOUT');
    const acquiredDeadline=Date.now()+10000;
    while(Date.now()<acquiredDeadline&&!fixture.failure()) {
      if(fixture.rows.filter(item=>item.method==='tools/list').length===2) {row.acquisitionObserved=true;break;}
      await pause();
    }
    if(!row.acquisitionObserved)fail('AUTH_ACQUISITION_TIMEOUT');
    row.stage='OBSERVE';
    // A fixed observation interval, not "stop as soon as a bug appears": both
    // negative controls get the same opportunity to produce recovery traffic.
    const observeUntil=Date.now()+6000;
    while(Date.now()<observeUntil) {
      if(interrupted||hostExited||browserExited||cdp.failure()||fixture.failure())fail('AUTH_OBSERVATION_INTERRUPTED');
      await pause();
    }
    row.observationWindowCompleted=true;
    row.stage='DISCONNECT';
    row.disconnectClicked=await evaluate(cdp,sessionId,click('[aria-label="Disconnect from server"]'));
    if(!row.disconnectClicked)fail('AUTH_DISCONNECT_CONTROL');
    const disconnectedDeadline=Date.now()+5000;
    while(Date.now()<disconnectedDeadline) {
      if(await evaluate(cdp,sessionId,`document.querySelector(${JSON.stringify(connectionSelector)})?.checked === false`)) {
        row.disconnectedViaDom=true;break;
      }
      await pause();
    }
    if(!row.disconnectedViaDom)fail('AUTH_DISCONNECT_TIMEOUT');
    row.stage='SHUTDOWN';
  } catch(error) {
    row.failure=/^AUTH_[A-Z_]+$/.test(error.message)?error.message:'AUTH_PROBE_FAILED';
  } finally {
    let cdpClosed=true;
    if(browser)try {
      if(cdp&&!cdp.failure()) {
        closingBrowser=true;
        await cdp.send('Browser.close');
        // Complete the requested protocol close before waiting for the browser
        // process, matching the established ordinary-tools harness lifecycle.
        await cdp.close();
      }
      const exit=await boundedExit(browser);
      if(exit.code===0&&exit.signal===null)row.browserShutdown='CLEAN';
    }catch{/* fallback below */}
    try{await cdp?.close();}catch{cdpClosed=false;}
    row.cdpFailure=cdp?.failure()??null;
    if(cdp?.failure()&&cdp.failure()!=='CDP_CLOSED')row.failure??='AUTH_CDP_FAILURE';
    if(host)try {
      host.child.kill('SIGTERM');const exit=await boundedExit(host);
      if(exit.code===0&&exit.signal===null)row.hostShutdown='CLEAN';
    }catch{/* fallback below */}
    const cleanup=await Promise.allSettled([browser?.stop(),host?.stop(),fixture?.close()]);
    row.cleanup={cdp:cdpClosed,browser:cleanup[0].status==='fulfilled',host:cleanup[1].status==='fulfilled',fixture:cleanup[2].status==='fulfilled'};
    process.off('SIGINT',signal);process.off('SIGTERM',signal);
    row.rows=fixture?.rows??[];row.rejections=fixture?.rejections??[];row.fixtureFailure=fixture?.failure()??null;
    try{row.configUnchanged=!!configPath&&readFileSync(configPath,'utf8')===configBytes;}catch{row.configUnchanged=false;}
    try{rmSync(privateRoot,{recursive:true,force:true});}catch{row.failure??='AUTH_PRIVATE_CLEANUP';}
    row.privateStateRemoved=!existsSync(privateRoot);
    row.status=adjudicateCase(row);
    if(row.status!=='FAILED')row.stage='COMPLETE';
  }
  return row;
}

export async function run(options) {
  if(process.platform!=='darwin'||process.versions.node!=='26.5.0')fail('AUTH_RUNTIME');
  const original=resolve(options['--original-dependencies']),dependencies=resolve(options['--dependencies']);
  const provenance=verifyPatchedDependencies(original,dependencies);
  const entries=Object.fromEntries(VARIANTS.map(variant=> {
    const inspector=resolve(variant==='original'?original:dependencies,'node_modules/@modelcontextprotocol/inspector');
    regular(resolve(inspector,'clients/web/dist/index.html'));
    return [variant,regular(resolve(inspector,'clients/launcher/build/index.js'))];
  }));
  const browserPath=regular(options['--browser']);
  if(!browserPath.endsWith('/Google Chrome.app/Contents/MacOS/Google Chrome'))fail('AUTH_BROWSER_DISTRIBUTION');
  const browserRoot=resolve(dirname(browserPath),'../..');
  const work=resolve(options['--work-dir']);
  validateWorkDirectory(work,[original,dependencies,browserRoot]);
  mkdirSync(work,{recursive:true,mode:0o700});
  const receipt={formatVersion:1,profile:'soklet.inspector.isolated-auth-patch.v1',
    status:'FAILED',hostQualification:false,candidateEvidence:false,usesSoklet:false,usesApps:false,
    executedAt:new Date().toISOString(),runtime:{node:process.version,platform:process.platform,architecture:process.arch},
    target:{name:'@modelcontextprotocol/inspector',version:'2.7.0',commit:'2e90a628e6296c62e4bef942afbb43d3faa4baf4'},
    patchIdentityVerified:true,provenance,browserDistributionIdentity:directoryIdentity(browserRoot),inputs:sourceInputs(),
    bounds:{hostMs:60000,browserMs:45000,startupMs:10000,observationMs:6000,childOutputBytes:1024*1024,
      gracefulExitMs:3000,termGraceMs:2000,killGraceMs:2000},
    limitations:['A minimal diagnostic fixture, not Soklet conformance or Apps host qualification.',
      'HTTP200 JSON-RPC error is only a causal control, not a proposed policy/status workaround.',
      'OAuth metadata/registration is always refused locally; no accounts or external authentication are used.',
      'Only one locally patched backend artifact is tested; the released host remains unqualified.',
      'Positive auth controls verify recovery initiation, not successful authentication or scope escalation.',
      'Subscription retries are still expected; no cancellation or retry behavior is patched.'],runs:[]};
  let interrupted=false;
  const signal=()=>{interrupted=true;receipt.failure='AUTH_INTERRUPTED';};
  process.on('SIGINT',signal);process.on('SIGTERM',signal);
  try {
    for(const variant of VARIANTS) {
      if(interrupted)break;
      await collectCases(profile=>probe({profile,variant,work,browserPath,entry:entries[variant]}), row=>{
        receipt.runs.push(row);
        writeFileSync(resolve(work,'receipt.json'),json(receipt),{mode:0o600});
        console.log(JSON.stringify({variant,name:row.name,status:row.status}));
      },()=>interrupted);
    }
  }catch(error){receipt.failure=/^AUTH_[A-Z_]+$/.test(error.message)?error.message:'AUTH_EXECUTION_FAILED';}
  finally {
    try {
      receipt.inputsUnchanged=JSON.stringify(sourceInputs())===JSON.stringify(receipt.inputs)
        &&JSON.stringify(verifyPatchedDependencies(original,dependencies))===JSON.stringify(provenance)
        &&JSON.stringify(directoryIdentity(browserRoot))===JSON.stringify(receipt.browserDistributionIdentity);
    }catch{receipt.inputsUnchanged=false;}
    receipt.status=receipt.failure?'FAILED':adjudicateMatrix(receipt);
    writeFileSync(resolve(work,'receipt.json'),json(receipt),{mode:0o600});
    process.off('SIGINT',signal);process.off('SIGTERM',signal);
  }
  return receipt;
}

if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
  try {
    const receipt=await run(parseArguments(process.argv.slice(2)));
    console.log(json({status:receipt.status,hostQualification:false,runs:receipt.runs.map(({variant,name,status,failure})=>({variant,name,status,failure}))}).trim());
    if(receipt.status!=='LOCAL_PATCH_VALIDATED_WITH_CONTROLS')process.exitCode=1;
  }catch{console.error('Inspector isolated patch experiment rejected inputs; no qualified receipt.');process.exitCode=1;}
}
