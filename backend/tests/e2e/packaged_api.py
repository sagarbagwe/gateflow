#!/usr/bin/env python3
import json,os,subprocess,time,uuid,socket,urllib.request,urllib.error,http.cookiejar
from pathlib import Path
root=Path(__file__).resolve().parents[3];os.chdir(root)
import argparse
parser=argparse.ArgumentParser(description='Disposable packaged API E2E; disrupts the selected dev Compose broker during outage checks')
parser.add_argument('--export-openapi',action='store_true');args=parser.parse_args()
def cmd(*args,input=None): return subprocess.run(args,input=input,text=True,capture_output=True,check=True).stdout
cfg=json.loads(cmd('docker','compose','config','--format','json'))['services'];pg=cfg['postgres'];rb=cfg['rabbitmq'];rd=cfg['redis'];sink=cfg['mailpit'];pe=pg['environment'];re=rb['environment'];mailbase='http://127.0.0.1:'+str(next(x['published'] for x in sink['ports'] if x['target']==8025))
db='gateflow_e2e_'+uuid.uuid4().hex[:12];vhost='gateflow_e2e_'+uuid.uuid4().hex[:12]
def sql(statement,database=None):
 return cmd('docker','compose','exec','-T','postgres','sh','-c','exec psql -U "$POSTGRES_USER" -d "$1" -v ON_ERROR_STOP=1 -At','sh',database or db,input=statement+';\n').strip()
def ctl(*args): return cmd('docker','compose','exec','-T','rabbitmq','rabbitmqctl',*args)
captured=[];count=0;process=None;created=False;madevhost=False;paused=False
logpath=root/'backend/target/e2e.log';logpath.parent.mkdir(parents=True,exist_ok=True);log=open(logpath,'w')
def check(ok,label):
 global count
 if not ok: raise AssertionError(label)
 count+=1;print('PASS:',label,flush=True)
def await_(f,timeout=25):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  try:
   result=f()
   if result:return result
  except (urllib.error.URLError,ConnectionError,subprocess.CalledProcessError):pass
  time.sleep(.2)
 raise AssertionError('Timed out waiting for packaged flow')
with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
base='http://127.0.0.1:'+str(port)
class Actor:
 def __init__(self):self.client=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
 def call(self,method,path,body=None,status=200,key=None):
  headers={}
  if method!='GET':
   csrf=self.call('GET','/api/v1/auth/csrf');headers.update({'Content-Type':'application/json',csrf['headerName']:csrf['token']})
  if key:headers['Idempotency-Key']=key
  request=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers,method=method)
  try:
   response=self.client.open(request,timeout=10);code=response.status;payload=response.read()
  except urllib.error.HTTPError as error:code=error.code;payload=error.read()
  check(code==status,method+' '+path.split('/api/v1/')[-1].split('/')[0]+' status '+str(status)+' (actual '+str(code)+')')
  return json.loads(payload) if payload else None
 def signup(self):
  u=self.call('POST','/api/v1/auth/signup',{'email':uuid.uuid4().hex+'@example.test','displayName':'Packaged fixture','password':'packaged-test-password-2026'},201);self.user=u['id'];self.email=u['email'];return self
try:
 sql('CREATE DATABASE "'+db+'"',pe['POSTGRES_DB']);created=True
 ctl('add_vhost',vhost);madevhost=True
 cmd('docker','compose','exec','-T','rabbitmq','sh','-c','exec rabbitmqctl set_permissions -p "$1" "$RABBITMQ_DEFAULT_USER" ".*" ".*" ".*"','sh',vhost)
 env=dict(os.environ,API_DOCS_ENABLED='true',DATABASE_URL=f"jdbc:postgresql://127.0.0.1:{pg['ports'][0]['published']}/{db}",DATABASE_USER=pe['POSTGRES_USER'],DATABASE_PASSWORD=pe['POSTGRES_PASSWORD'],SERVER_ADDRESS='127.0.0.1',SERVER_PORT=str(port),AUTH_COOKIE_SECURE='false',RABBITMQ_HOST='127.0.0.1',RABBITMQ_PORT=str(rb['ports'][0]['published']),RABBITMQ_USERNAME=re['RABBITMQ_DEFAULT_USER'],RABBITMQ_PASSWORD=re['RABBITMQ_DEFAULT_PASS'],RABBITMQ_VHOST=vhost,ASYNC_ENABLED='true',REDIS_HOST='127.0.0.1',REDIS_PORT=str(rd['ports'][0]['published']),REDIS_PASSWORD=rd['environment']['REDIS_PASSWORD'],PUBLISHED_POLICY_CACHE_ENABLED='true',NOTIFICATIONS_ENABLED='true',EMAIL_ENABLED='true',SMTP_HOST='127.0.0.1',SMTP_PORT=str(next(x['published'] for x in sink['ports'] if x['target']==1025)),SMTP_AUTH='false',SMTP_STARTTLS='false',SMTP_STARTTLS_REQUIRED='false')
 process=subprocess.Popen([env['JAVA_HOME']+'/bin/java','-jar','backend/target/gateflow-backend-0.1.0-SNAPSHOT.jar'],env=env,stdout=log,stderr=log)
 await_(lambda:urllib.request.urlopen(base+'/api/v1/auth/csrf',timeout=2).status==200);check(True,'packaged JAR ready with automatic relay and listener')
 admin=Actor().signup();owner=Actor().signup();reviewer=Actor().signup();outsider=Actor().signup()
 specification=admin.call('GET','/v3/api-docs/gateflow')
 check(specification['openapi'].startswith('3.0.'),'actual packaged JAR generates OpenAPI')
 check(specification['servers']==[{'url':'/'}],'schema uses relative server only')
 if args.export_openapi:Path('docs/api/openapi.json').write_text(json.dumps(specification,indent=2,sort_keys=True)+'\n')
 Actor().call('GET','/v3/api-docs/gateflow',status=401)
 org=admin.call('POST','/api/v1/organizations',{'name':'Packaged async','slug':'async-'+uuid.uuid4().hex},201)['id'];p='/api/v1/organizations/'+org
 roles=admin.call('GET',p+'/roles')['items'];member=next(x['id'] for x in roles if x['code']=='MEMBER')
 role=admin.call('POST',p+'/roles',{'code':'REVIEWER','name':'Reviewer','permissions':['REQUEST_APPROVE','REQUEST_VIEW_ALL','WORKFLOW_VIEW']},201)['id']
 for actor,r in [(owner,member),(reviewer,role)]:admin.call('POST',p+'/memberships',{'userId':actor.user,'roleIds':[r]},201)
 definition=admin.call('POST',p+'/workflows',{'name':'Access review','description':'Packaged end to end'},201)['id']
 version=admin.call('POST',p+'/workflows/'+definition+'/versions',{'steps':[{'name':'Review','approverRoleId':role,'condition':{'type':'ALWAYS'}}]},201)
 version=admin.call('POST',p+'/workflows/'+definition+'/versions/'+version['id']+'/publish',{'expectedVersion':0})
 payload={'workflowVersionId':version['id'],'title':'Grant staging access','description':'Temporary engineering access','requestType':'SOFTWARE_ACCESS','details':{'softwareName':'staging'}}
 owner.call('PUT',p+'/notifications/preferences',{'inAppEnabled':True,'emailEnabled':True,'expectedVersion':0});
 key=str(uuid.uuid4());request=owner.call('POST',p+'/requests',payload,201,key);rid=request['id'];path=p+'/requests/'+rid
 replay=owner.call('POST',p+'/requests',payload,200,key);check(replay['id']==rid,'idempotent submit preserves request identity')
 check(sql("SELECT count(*) FROM outbox_events WHERE request_id='"+rid+"'")=='1','one durable event for idempotent submit')
 def activity(n,where=path):return await_(lambda:(lambda x:x if len(x['activity']['items'])==n else None)(owner.call('GET',where+'/activity')))
 page=activity(1);check(page['eventuallyConsistent'] and page['activity']['items'][0]['type']=='REQUEST_SUBMITTED','automatic worker projects minimal activity')
 outsider.call('GET',path+'/activity',status=404)
 owner.call('GET',path+'/activity?limit=101',status=400)

 notification=await_(lambda:(lambda x:x['items'][0] if x['items'] else None)(owner.call('GET',p+'/notifications')))
 check(notification['kind']=='STATUS_UPDATE','owner receives asynchronous in-app status notification')
 check(await_(lambda:(lambda x:x['items'][0] if x['items'] else None)(reviewer.call('GET',p+'/notifications')))['actionable'],'current reviewer receives actionable notification')
 check(admin.call('GET',p+'/notifications')['items']==[],'admin cannot browse another recipient inbox')
 outsider.call('GET',p+'/notifications',status=404)
 check(owner.call('GET',p+'/notifications/unread-count')['unreadCount']==1,'unread count matches visible recipient inbox')
 read=owner.call('PATCH',p+'/notifications/'+notification['id']+'/read');again=owner.call('PATCH',p+'/notifications/'+notification['id']+'/read')
 check(read['readAt']==again['readAt'],'read acknowledgment is idempotent')
 check(owner.call('GET',p+'/notifications?unreadOnly=true')['items']==[],'unread filter excludes acknowledged notification')
 def mail():
  data=json.load(urllib.request.urlopen(mailbase+'/api/v1/messages?limit=300',timeout=5))
  found=[m for m in data['messages'] if any(x['Address']==owner.email for x in m['To'])]
  for m in found:
   if m['ID'] not in captured:captured.append(m['ID'])
  return found
 first_mail=await_(lambda:mail() or None)
 check(len(first_mail)==1,'automatic email worker reaches local SMTP capture only once for submit/replay')
 raw=json.load(urllib.request.urlopen(mailbase+'/api/v1/message/'+first_mail[0]['ID'],timeout=5))
 check('new notification in GateFlow' in raw['Text'] and rid not in raw['Text'] and org not in raw['Text'],'email body contains no business details or tenant identifiers')
 check(sql("SELECT status FROM notification_email_deliveries LIMIT 1")=='ACCEPTED','SMTP acceptance durably recorded')
 owner.call('PUT',p+'/notifications/preferences',{'inAppEnabled':True,'emailEnabled':False,'expectedVersion':1})
 owner.call('PUT',p+'/notifications/preferences',{'inAppEnabled':False,'emailEnabled':False,'expectedVersion':1},status=409)
 check(sql("SELECT count(*) FROM audit_logs WHERE action='NOTIFICATION_PREFERENCES_CHANGED'")=='2','successful preference writes audit atomically; stale update does not')
 step=request['steps'][0]['id'];approved=reviewer.call('POST',path+'/steps/'+step+'/decisions',{'expectedVersion':0,'decision':'APPROVE','comment':'Verified'},200,str(uuid.uuid4()));check(approved['state']=='APPROVED','authoritative approval succeeds')
 page=activity(2);check([x['requestVersion'] for x in page['activity']['items']]==[1,0],'timeline orders by aggregate version')
 check(sql("SELECT count(*) FROM processed_events WHERE consumer_name='activity_projection_v1'")=='2','consumer receipts exactly match committed projections')
 audits=admin.call('GET',p+'/audit-logs?action=REQUEST_DECIDED&resourceId='+rid)
 check(len(audits['items'])==1 and 'newValue' not in audits['items'][0],'privileged audit feed is filtered and metadata-only')
 evidence=admin.call('GET',p+'/audit-logs/'+audits['items'][0]['id'])
 check(evidence['oldValue']['state']=='IN_REVIEW' and evidence['newValue']['state']=='APPROVED' and not evidence['snapshotRedacted'],'approval detail contains exact safe before/after evidence')
 check(evidence['entry']['requestId'] is not None,'audit contains request correlation ID')
 owner.call('GET',p+'/audit-logs',status=403)
 outsider.call('GET',p+'/audit-logs',status=404)
 admin.call('GET',p+'/audit-logs?limit=101',status=400)
 admin.call('DELETE',p+'/audit-logs/'+audits['items'][0]['id'],status=405)
 check(sql("SELECT count(*) FROM audit_logs WHERE action='REQUEST_SUBMITTED' AND resource_id='"+rid+"'")=='1','replayed command appends no duplicate audit')
 # Outage: commands commit while broker is unavailable; transport recovers automatically.
 container=cmd('docker','compose','ps','-q','rabbitmq').strip();cmd('docker','pause',container);paused=True
 second=owner.call('POST',p+'/requests',payload,201,str(uuid.uuid4()));sid=second['id'];check(sql("SELECT count(*) FROM outbox_events WHERE request_id='"+sid+"' AND published_at IS NULL")=='1','broker outage leaves durable pending event, not failed command')
 check(owner.call('GET',p+'/requests/'+sid+'/activity')['activity']['items']==[],'activity explicitly lags authoritative source during outage')
 cmd('docker','unpause',container);paused=False
 activity(1,p+'/requests/'+sid);check(True,'relay and listener recover without application restart')
 # Persisted broker topology survives broker app restart; listener reconnects for future work.
 ctl('stop_app');ctl('start_app')
 third=owner.call('POST',p+'/requests',payload,201,str(uuid.uuid4()));activity(1,p+'/requests/'+third['id']);check(True,'automatic listener reconnects after broker application restart')
 check(sql('SELECT count(*) FROM outbox_events WHERE published_at IS NULL')=='0','all smoke events marked broker accepted')
 check(sql('SELECT count(*) FROM request_activity')=='4','four source events yield four timeline rows')
 check(sql('SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success')=='11','packaged application applies eleven migrations');
 check(len(mail())==1,'opt-out prevents future email while in-app notifications continue')
 print('Packaged assertions:',count,flush=True)
finally:
 if paused:cmd('docker','unpause',container)
 if process:
  process.terminate()
  try:process.wait(timeout=20)
  except subprocess.TimeoutExpired:process.kill();process.wait()
 log.close()
 if captured:
  request=urllib.request.Request(mailbase+'/api/v1/messages',data=json.dumps({'IDs':captured}).encode(),headers={'Content-Type':'application/json'},method='DELETE')
  urllib.request.urlopen(request,timeout=5).close()
 if madevhost:ctl('delete_vhost',vhost)
 if created:sql('DROP DATABASE "'+db+'" WITH (FORCE)',pe['POSTGRES_DB'])
 print('Temporary application stopped; dedicated broker vhost and disposable database removed.',flush=True)
